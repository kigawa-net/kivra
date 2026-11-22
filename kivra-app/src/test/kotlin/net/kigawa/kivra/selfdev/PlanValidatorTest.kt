package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.PatchPolicyDefaults
import net.kigawa.kivra.db.PlanningOrder

/** The Planner's output rules (ADR 0011 section 3) and the approval rules (sections 4 and 6.1). Pure: no model, no database. */
class PlanValidatorTest {

    private val required = AutopilotConfig(AutopilotApproval.REQUIRED, 3, 3, 5, 50)
    private val auto = AutopilotConfig(AutopilotApproval.AUTO, 3, 3, 5, 50)
    private val full = AutopilotConfig(AutopilotApproval.FULL, 3, 3, 5, 50)

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
    fun `a refused path is carried in the check, so the refusal can be traced to the path that caused it`() {
        val check = assertIs<PlanCheck.Invalid>(PlanValidator.validate(plan(task(paths = """["src/x.kt"]""")), required))
        assertEquals(PlanRejectReason.PATH_NOT_ALLOWED, check.reason)
        assertEquals("src/x.kt", check.path)
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
    fun `dependencies outside the range of earlier tasks are refused - a negative, a number past the plan, or a self reference late in the plan`() {
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(deps = "[-1]"))), "negative")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(deps = "[3]"))), "past the plan")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(deps = "[999999999]"))), "large")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(), task(deps = "[2,3]"))), "self among earlier")
        assertEquals(PlanRejectReason.DEPENDENCY, invalid(plan(task(), task(deps = "[0,1]"))), "zero among valid")
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
    fun `under full a task outside the stage 2 scope is queued - any size, any allowed path, up to three paths`() {
        val queued = valid(
            plan(
                task(paths = """["kivra-app/src/main/kotlin/A.kt"]""", size = "\"L\""),
                task(paths = """["kivra-app/src/main/kotlin/A.kt","kivra-app/src/main/kotlin/B.kt","docs/a.md"]""", size = "\"M\""),
                task(paths = """["docs/a.md"]""", size = "\"S\""),
            ),
            full,
        ).tasks
        assertEquals(listOf(TaskState.QUEUED, TaskState.QUEUED, TaskState.QUEUED), queued.map { it.initialState })
        assertTrue(queued.none { it.requiresApproval })
    }

    @Test
    fun `under full a denylisted task and a task over three paths still wait for the operator`() {
        val waiting = valid(
            plan(
                task(paths = """["kivra-app/src/main/kotlin/net/kigawa/kivra/auth/Login.kt"]""", size = "\"S\""),
                task(paths = """[".github/workflows/ci.yml"]""", size = "\"S\""),
                task(paths = """["kivra-app/src/main/kotlin/A.kt","kivra-app/src/main/kotlin/B.kt","kivra-app/src/main/kotlin/C.kt","kivra-app/src/main/kotlin/D.kt"]""", size = "\"S\""),
            ),
            full,
        ).tasks
        assertEquals(List(3) { TaskState.PENDING_APPROVAL }, waiting.map { it.initialState })
        assertTrue(waiting.all { it.requiresApproval })
        // The flag itself: a denylisted path always needs approval under full, and nothing else does.
        assertTrue(full.requiresApproval(denylisted = true))
        assertFalse(full.requiresApproval(denylisted = false))
    }

    @Test
    fun `under full the allowlist still applies - a path outside it is refused, not queued`() {
        assertEquals(PlanRejectReason.PATH_NOT_ALLOWED, invalid(plan(task(paths = """["src/x.kt"]""", size = "\"S\"")), full))
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

    private val limit = PatchPolicyDefaults.MAX_FILE_BYTES

    private fun refusedBySize(text: String, sizes: Map<String, Long>?): PlanCheck.Invalid =
        assertIs<PlanCheck.Invalid>(PlanValidator.validate(text, required, sizes))

    @Test
    fun `the size limit is the PatchPolicy limit, 200 KiB, and is defined once`() {
        assertEquals(200L * 1024, limit)
        assertEquals(limit, PatchPolicyConfig().maxFileBytes)
    }

    @Test
    fun `an existing target file over the limit refuses the plan, and the refusal carries that path`() {
        val check = refusedBySize(plan(task(paths = """["docs/big.md"]""")), mapOf("docs/big.md" to limit + 1))
        assertEquals(PlanRejectReason.FILE_TOO_LARGE, check.reason)
        assertEquals("docs/big.md", check.path)
    }

    @Test
    fun `one large file among the target paths refuses the task, and a denylisted large file is refused too`() {
        val paths = """["docs/a.md","kivra-app/src/main/kotlin/A.kt"]"""
        assertEquals(
            PlanRejectReason.FILE_TOO_LARGE,
            refusedBySize(plan(task(paths = paths)), mapOf("kivra-app/src/main/kotlin/A.kt" to limit + 1)).reason,
        )
        assertEquals(
            PlanRejectReason.FILE_TOO_LARGE,
            refusedBySize(plan(task(paths = """["docs/adr/0001.md"]""")), mapOf("docs/adr/0001.md" to limit + 1)).reason,
        )
    }

    @Test
    fun `an existing file of exactly the limit is accepted, and one byte more is refused`() {
        assertIs<PlanCheck.Valid>(PlanValidator.validate(plan(task(paths = """["docs/a.md"]""")), required, mapOf("docs/a.md" to limit)))
        assertEquals(
            PlanRejectReason.FILE_TOO_LARGE,
            refusedBySize(plan(task(paths = """["docs/a.md"]""")), mapOf("docs/a.md" to limit + 1)).reason,
        )
    }

    @Test
    fun `a new file is never too large, and neither is a file whose size is not known`() {
        // A path not in the base tree is a new file: it is not in the map.
        assertIs<PlanCheck.Valid>(PlanValidator.validate(plan(task(paths = """["docs/new.md"]""")), required, mapOf("docs/a.md" to limit + 1)))
        // Without a size map (the tree could not be read) the check is not made; PatchPolicy still checks at write time.
        assertIs<PlanCheck.Valid>(PlanValidator.validate(plan(task(paths = """["docs/a.md"]""")), required, null))
        assertFalse(PlanValidator.isTooLargeToChange("docs/a.md", null))
        assertFalse(PlanValidator.isTooLargeToChange("docs/new.md", mapOf("docs/a.md" to limit + 1)))
    }

    // ---- child orders (ADR 0015 addendum 2026-11-22) -------------------------------------------------------------------------------

    private val rules = ChildOrderRules(maxCyclesCap = 3)

    private fun child(
        title: String = "Child",
        instruction: String = "Do the workstream",
        maxCycles: String = "1",
    ) = """{"title":"$title","instruction":"$instruction","maxCycles":$maxCycles}"""

    private fun withChildren(vararg children: String, tasks: String = task()): String =
        """{"summary":"summary","tasks":[$tasks],"childOrders":[${children.joinToString(",")}]}"""

    private fun refusedWithChildren(text: String, childRules: ChildOrderRules? = rules): PlanRejectReason =
        assertIs<PlanCheck.Invalid>(PlanValidator.validate(text, required, null, childRules)).reason

    @Test
    fun `a plan with no childOrders key is the same plan as before, with or without the rules`() {
        assertEquals(emptyList(), valid(plan(task())).childOrders)
        assertEquals(emptyList(), valid(plan(task()), required).childOrders)
        assertEquals(emptyList(), assertIs<PlanCheck.Valid>(PlanValidator.validate(plan(task()), required, null, rules)).childOrders)
        // An empty list is accepted even where children are not allowed: it proposes nothing.
        assertEquals(emptyList(), assertIs<PlanCheck.Valid>(PlanValidator.validate(withChildren(tasks = task()), required, null, null)).childOrders)
    }

    @Test
    fun `valid child orders are kept in order, with their ordinals, and tasks are still stored as before`() {
        val result = assertIs<PlanCheck.Valid>(
            PlanValidator.validate(withChildren(child("First", maxCycles = "2"), child("Second"), tasks = task()), required, null, rules),
        )
        assertEquals(1, result.tasks.size)
        assertEquals(
            listOf(NewChildOrder(1, "First", "Do the workstream", 2), NewChildOrder(2, "Second", "Do the workstream", 1)),
            result.childOrders,
        )
    }

    @Test
    fun `child orders are refused when the order may not propose them`() {
        assertEquals(PlanRejectReason.CHILD_ORDERS_NOT_ALLOWED, refusedWithChildren(withChildren(child()), null))
        assertEquals(PlanRejectReason.CHILD_ORDERS_NOT_ALLOWED, refusedWithChildren(withChildren(child(), child("B")), null))
    }

    @Test
    fun `a plan needs at least one task, also when it has child orders`() {
        assertEquals(PlanRejectReason.TASK_COUNT, refusedWithChildren("""{"summary":"s","tasks":[],"childOrders":[${child()}]}"""))
        assertEquals(PlanRejectReason.TASK_COUNT, refusedWithChildren("""{"summary":"s","tasks":[]}"""))
    }

    @Test
    fun `at most three child orders per plan`() {
        assertEquals(PlanRejectReason.CHILD_ORDER_COUNT, refusedWithChildren(withChildren(*Array(4) { child("c$it") })))
        assertEquals(3, assertIs<PlanCheck.Valid>(PlanValidator.validate(withChildren(*Array(3) { child("c$it") }), required, null, rules)).childOrders.size)
    }

    @Test
    fun `the child order keys are closed, and its types are strict`() {
        // An unknown key, a missing key, a quoted maxCycles, a fraction and a null are all schema errors.
        val extra = """{"title":"T","instruction":"I","maxCycles":1,"targetPaths":["docs/a.md"]}"""
        assertEquals(PlanRejectReason.SCHEMA, refusedWithChildren(withChildren(extra)))
        assertEquals(PlanRejectReason.SCHEMA, refusedWithChildren(withChildren("""{"title":"T","instruction":"I"}""")))
        assertEquals(PlanRejectReason.SCHEMA, refusedWithChildren(withChildren(child(maxCycles = "\"1\""))))
        assertEquals(PlanRejectReason.SCHEMA, refusedWithChildren(withChildren(child(maxCycles = "1.0"))))
        assertEquals(PlanRejectReason.SCHEMA, refusedWithChildren(withChildren(child(maxCycles = "null"))))
        assertEquals(PlanRejectReason.SCHEMA, refusedWithChildren("""{"summary":"s","tasks":[${task()}],"childOrders":null}"""))
    }

    @Test
    fun `a child order title is one to 200 characters, with no control characters, and unique within the plan`() {
        assertEquals(PlanRejectReason.CHILD_ORDER_TITLE, refusedWithChildren(withChildren(child(title = ""))))
        assertEquals(PlanRejectReason.CHILD_ORDER_TITLE, refusedWithChildren(withChildren(child(title = "   "))))
        assertEquals(PlanRejectReason.CHILD_ORDER_TITLE, refusedWithChildren(withChildren(child(title = "t".repeat(201)))))
        assertEquals(PlanRejectReason.CHILD_ORDER_TITLE, refusedWithChildren(withChildren(child(title = "ti\\u0007tle"))))
        assertEquals(PlanRejectReason.CHILD_ORDER_DUPLICATE_TITLE, refusedWithChildren(withChildren(child("Same"), child("Same"))))
        assertIs<PlanCheck.Valid>(PlanValidator.validate(withChildren(child(title = "t".repeat(200))), required, null, rules))
    }

    @Test
    fun `a child order instruction is one to 8000 characters, and keeps line breaks`() {
        assertEquals(PlanRejectReason.CHILD_ORDER_INSTRUCTION, refusedWithChildren(withChildren(child(instruction = ""))))
        assertEquals(PlanRejectReason.CHILD_ORDER_INSTRUCTION, refusedWithChildren(withChildren(child(instruction = "i".repeat(8_001)))))
        assertIs<PlanCheck.Valid>(PlanValidator.validate(withChildren(child(instruction = "i".repeat(8_000))), required, null, rules))
        val kept = assertIs<PlanCheck.Valid>(PlanValidator.validate(withChildren(child(instruction = "a\\nb")), required, null, rules))
        assertEquals("a\nb", kept.childOrders.single().instruction)
    }

    @Test
    fun `maxCycles is between one and the cap of the rules`() {
        assertEquals(PlanRejectReason.CHILD_ORDER_MAX_CYCLES, refusedWithChildren(withChildren(child(maxCycles = "0"))))
        assertEquals(PlanRejectReason.CHILD_ORDER_MAX_CYCLES, refusedWithChildren(withChildren(child(maxCycles = "4"))))
        assertEquals(PlanRejectReason.CHILD_ORDER_MAX_CYCLES, refusedWithChildren(withChildren(child(maxCycles = "-1"))))
        assertIs<PlanCheck.Valid>(PlanValidator.validate(withChildren(child(maxCycles = "3")), required, null, rules))
        // The cap 1 (the default, KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP unset): only 1 is allowed.
        assertEquals(PlanRejectReason.CHILD_ORDER_MAX_CYCLES, refusedWithChildren(withChildren(child(maxCycles = "2")), ChildOrderRules(1)))
    }

    @Test
    fun `the child order rules are on for a root order with room under the depth limit only`() {
        val root = PlanningOrder("R", null, 0)
        val childOrder = PlanningOrder("C", "R", 1)
        // Children disabled (depth limit 0): no rules, for any order.
        assertNull(childOrderRulesFor(root, maxDepth = 0, maxCyclesCap = 1))
        // A root with room for a child.
        assertEquals(ChildOrderRules(2), childOrderRulesFor(root, maxDepth = 1, maxCyclesCap = 2))
        assertEquals(ChildOrderRules(2), childOrderRulesFor(root, maxDepth = 2, maxCyclesCap = 2))
        // A child never proposes grandchildren, even under depth 2 (the recursion stops at the first level).
        assertNull(childOrderRulesFor(childOrder, maxDepth = 2, maxCyclesCap = 2))
        // A task the operator submitted has no order.
        assertNull(childOrderRulesFor(null, maxDepth = 2, maxCyclesCap = 2))
    }

    @Test
    fun `a refused child order carries a reason code and no path`() {
        val check = assertIs<PlanCheck.Invalid>(PlanValidator.validate(withChildren(child(title = "x".repeat(300))), required, null, rules))
        assertEquals(PlanRejectReason.CHILD_ORDER_TITLE, check.reason)
        assertNull(check.path)
    }
}
