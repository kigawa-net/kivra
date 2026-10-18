package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.TaskState

/** The Planner's output rules (ADR 0011 section 3) and the approval rules (sections 4 and 6.1). Pure: no model, no database. */
class PlanValidatorTest {

    private val required = AutopilotConfig(AutopilotApproval.REQUIRED, 3, 3, 5, 50)
    private val auto = AutopilotConfig(AutopilotApproval.AUTO, 3, 3, 5, 50)

    private fun task(
        title: String = "Title",
        body: String = "Do it",
        paths: String = """["docs/a.md"]""",
        size: String = "\"S\"",
        deps: String = "[]",
    ) = """{"title":"$title","body":"$body","targetPaths":$paths,"sizeEstimate":$size,"dependsOn":$deps}"""

    private fun plan(vararg tasks: String, summary: String = "summary") =
        """{"summary":"$summary","tasks":[${tasks.joinToString(",")}]}"""

    private fun invalid(text: String, config: AutopilotConfig = required): PlanRejectReason =
        assertIs<PlanCheck.Invalid>(PlanValidator.validate(text, config)).reason

    private fun valid(text: String, config: AutopilotConfig = required): PlanCheck.Valid =
        assertIs<PlanCheck.Valid>(PlanValidator.validate(text, config))

    @Test
    fun `a plan in the schema is accepted, and every task waits for approval under required`() {
        val result = valid(plan(task(paths = """["docs/a.md","kivra-app/src/main/kotlin/A.kt"]"""), task(deps = "[1]")))
        assertEquals("summary", result.summary)
        assertEquals(2, result.tasks.size)
        assertEquals(listOf(TaskState.PENDING_APPROVAL, TaskState.PENDING_APPROVAL), result.tasks.map { it.initialState })
        assertTrue(result.tasks.all { it.requiresApproval })
        assertEquals(listOf(1), result.tasks[1].dependsOn)
    }

    @Test
    fun `the schema is closed - unknown fields, wrong types and missing fields are refused`() {
        assertEquals(PlanRejectReason.SCHEMA, invalid("""{"summary":"s","tasks":[${task()}],"extra":1}"""))
        // The model cannot send requiresApproval: the flag is computed here. With the strict decoder it is an unknown field.
        assertEquals(PlanRejectReason.SCHEMA, invalid(plan(task().dropLast(1) + ""","requiresApproval":false}""")))
        assertEquals(PlanRejectReason.SCHEMA, invalid(plan(task(paths = "\"docs/a.md\""))))
        assertEquals(PlanRejectReason.SCHEMA, invalid(plan(task(size = "1"))))
        assertEquals(PlanRejectReason.SCHEMA, invalid(plan(task(deps = "[\"1\"]"))))
        assertEquals(PlanRejectReason.SCHEMA, invalid(plan(task(deps = "[1.0]"))))
        assertEquals(PlanRejectReason.SCHEMA, invalid("""{"summary":"s"}"""))
        assertEquals(PlanRejectReason.SCHEMA, invalid("""{"summary":null,"tasks":[${task()}]}"""))
    }

    @Test
    fun `text that is not one JSON object is refused - a fence, prose around it, or trailing text`() {
        assertEquals(PlanRejectReason.SCHEMA, invalid("```json\n${plan(task())}\n```"))
        assertEquals(PlanRejectReason.SCHEMA, invalid("Here is the plan: ${plan(task())}"))
        assertEquals(PlanRejectReason.SCHEMA, invalid(plan(task()) + " trailing"))
        assertEquals(PlanRejectReason.SCHEMA, invalid(""))
    }

    @Test
    fun `the task count is between one and eight`() {
        assertEquals(PlanRejectReason.TASK_COUNT, invalid("""{"summary":"s","tasks":[]}"""))
        assertEquals(PlanRejectReason.TASK_COUNT, invalid(plan(*Array(9) { task() })))
        valid(plan(*Array(8) { task() }))
    }

    @Test
    fun `the title is one to 200 characters, and a control character becomes a space, not a line break`() {
        assertEquals(PlanRejectReason.TITLE, invalid(plan(task(title = ""))))
        assertEquals(PlanRejectReason.TITLE, invalid(plan(task(title = "t".repeat(201)))))
        valid(plan(task(title = "t".repeat(200))))
        val title = valid(plan(task(title = "ti\\u0007tle\\nline"))).tasks.single().title
        assertEquals("ti tle line", title)
    }

    @Test
    fun `the body is up to 8000 characters, counted in characters, and keeps line breaks but not other control characters`() {
        assertEquals(PlanRejectReason.BODY, invalid(plan(task(body = "b".repeat(8_001)))))
        assertEquals(PlanRejectReason.BODY, invalid(plan(task(body = ""))))
        valid(plan(task(body = "あ".repeat(8_000))))
        val body = valid(plan(task(body = "line1\\nline2\\tx\\u0007y\\r"))).tasks.single().body
        assertEquals("line1\nline2\txy", body)
    }

    @Test
    fun `the summary is at most 500 characters and may be empty`() {
        assertEquals(PlanRejectReason.SUMMARY_TOO_LONG, invalid(plan(task(), summary = "s".repeat(501))))
        assertEquals(null, valid(plan(task(), summary = "  ")).summary)
    }

    @Test
    fun `the paths per task are one to ten`() {
        assertEquals(PlanRejectReason.PATH_COUNT, invalid(plan(task(paths = "[]"))))
        assertEquals(PlanRejectReason.PATH_COUNT, invalid(plan(task(paths = "[" + (1..11).joinToString(",") { "\"docs/$it.md\"" } + "]"))))
        valid(plan(task(paths = "[" + (1..10).joinToString(",") { "\"docs/$it.md\"" } + "]")))
    }

    @Test
    fun `a path with an absolute form, a parent segment, a backslash, a NUL or a control character is refused by shape`() {
        for (bad in listOf("/etc/passwd", "../x.md", "docs/../x.md", "docs\\\\x.md", "docs/a\\u0000.md", "docs/a\\u0007.md", "")) {
            assertEquals(PlanRejectReason.PATH_SHAPE, invalid(plan(task(paths = "[\"$bad\"]"))), "path $bad")
        }
    }

    @Test
    fun `a path outside the allowlist is refused, and so is a path the allowlist does not name`() {
        assertEquals(PlanRejectReason.PATH_NOT_ALLOWED, invalid(plan(task(paths = """["src/x.kt"]"""))))
        assertEquals(PlanRejectReason.PATH_NOT_ALLOWED, invalid(plan(task(paths = """["kivra-app/build.txt"]"""))))
    }

    @Test
    fun `the size estimate is S, M or L`() {
        assertEquals(PlanRejectReason.SIZE_ESTIMATE, invalid(plan(task(size = "\"XL\""))))
        assertEquals(PlanRejectReason.SIZE_ESTIMATE, invalid(plan(task(size = "\"s\""))))
        valid(plan(task(size = "\"M\"")))
    }

    @Test
    fun `dependencies name only earlier tasks - a self reference, a forward reference, a cycle and a duplicate are refused`() {
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(deps = "[1]"))), "self")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(deps = "[2]"), task())), "forward")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(deps = "[2]"), task(deps = "[1]"))), "cycle")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(deps = "[1,1]"))), "duplicate")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(deps = "[0]"))), "zero")
        valid(plan(task(), task(), task(deps = "[1,2]")))
    }

    @Test
    fun `a denylisted path makes the task need approval under auto too, and the model cannot say otherwise`() {
        // docs/adr is in the docs allowlist and on the denylist: the denylist wins. The task is never auto-queued.
        val result = valid(plan(task(paths = """["docs/adr/0099.md"]""", size = "\"S\"")), auto).tasks.single()
        assertTrue(result.requiresApproval)
        assertEquals(TaskState.PENDING_APPROVAL, result.initialState)
    }

    @Test
    fun `a denylisted path outside the allowlist is accepted for approval, not refused`() {
        for (path in listOf(".github/workflows/ci.yml", "kivra-app/src/main/resources/db/migration/V99__x.sql", "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt", ".kivra/config.yml")) {
            val result = valid(plan(task(paths = "[\"$path\"]")), auto).tasks.single()
            assertTrue(result.requiresApproval, path)
            assertEquals(TaskState.PENDING_APPROVAL, result.initialState, path)
        }
    }

    @Test
    fun `under auto only a small docs, test or README task is queued, and anything else waits`() {
        val queued = valid(plan(task(paths = """["docs/a.md"]""", size = "\"S\""), task(paths = """["README.md"]""", size = "\"S\""), task(paths = """["kivra-app/src/test/kotlin/A.kt"]""", size = "\"S\"")), auto).tasks
        assertEquals(listOf(TaskState.QUEUED, TaskState.QUEUED, TaskState.QUEUED), queued.map { it.initialState })
        assertTrue(queued.none { it.requiresApproval })

        val waiting = valid(
            plan(
                task(paths = """["kivra-app/src/main/kotlin/A.kt"]""", size = "\"S\""),
                task(paths = """["docs/a.md"]""", size = "\"M\""),
                task(paths = """["docs/a.md","docs/b.md","docs/c.md","docs/d.md"]""", size = "\"S\""),
            ),
            auto,
        ).tasks
        assertEquals(listOf(TaskState.PENDING_APPROVAL, TaskState.PENDING_APPROVAL, TaskState.PENDING_APPROVAL), waiting.map { it.initialState })
        assertTrue(waiting.all { it.requiresApproval })
    }

    @Test
    fun `an injected instruction copied into a plan changes nothing - the rules are the code's`() {
        // The model follows the goal and claims a denylisted path is allowed, with requiresApproval false. The key is refused.
        val injected = plan(task(paths = """["kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt"]""", size = "\"S\"").dropLast(1) + ""","requiresApproval":false}""")
        assertEquals(PlanRejectReason.SCHEMA, invalid(injected, auto))
        // Without the key, the same path is still held for approval.
        val result = valid(plan(task(paths = """["kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt"]""", size = "\"S\"")), auto).tasks.single()
        assertTrue(result.requiresApproval)
        assertEquals(TaskState.PENDING_APPROVAL, result.initialState)
    }

    @Test
    fun `summary and titles never carry a line break`() {
        assertEquals("a b", PlanValidator.singleLine("a\nb"))
        // A CRLF is two control characters, so two spaces: nothing is joined into one word.
        assertEquals("a  b", PlanValidator.singleLine("a\r\nb"))
    }
}
