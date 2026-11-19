package net.kigawa.kivra.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.PlanCommit
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectMemberStore
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.GuardLog
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.legacyPermissionGuard
import net.kigawa.kivra.selfdev.NewTask
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * `kivra_task_list` (ADR 0019 addendum: the cycle tree) on `/mcp`, through the production wiring on H2 with the real repositories and the real
 * JWT verifier. The human operator is `sub-bob`; the AI principal is the owner key of `sub-alice`; `sub-member` belongs to Alpha only.
 */
class McpTaskToolsTest {
    private val clock: Clock = Clock.systemUTC()
    private val repo = RepoRef("kigawa-net", "kivra")
    private val bobOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-bob")!!
    private val aliceOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-alice")!!
    private val memberOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-member")!!

    private val readScope = McpTestTokens.READ_SCOPE
    private val humanToken get() = McpTestTokens.token(subject = "sub-bob", scope = readScope)
    private val aiToken get() = McpTestTokens.token(subject = "sub-alice", scope = readScope)
    private val memberToken get() = McpTestTokens.token(subject = "sub-member", scope = readScope)

    private val toolName = "kivra_task_list"
    private val noSuchTask = "0".repeat(25) + "1"
    private val rootMarker = "ROOT-TITLE-MARKER"
    private val childOneTitle = "First child title"
    private val childTwoTitle = "Second child ‮evil"
    private val bodyMarker = "BODY-MARKER-TASK"
    private val pathMarker = "docs/path-marker.md"

    private class NoLog : GuardLog {
        override fun info(message: String) {}
        override fun warn(message: String) {}
    }

    /** Two projects, the rows: a root in Alpha and one in Beta, both created by the operator with planned children. */
    private inner class Fixture(val db: TestDb) {
        val ops = SelfDevOperatorRepository(db.database)
        val projects = ProjectRepository(db.database, clock)
        val alpha: String = projects.create("Alpha", null).projectId
        val beta: String = projects.create("Beta", null).projectId
        val root: String = plannedRoot("root-alpha", alpha, rootMarker)
        val rootBeta: String = plannedRoot("root-beta", beta, "Beta root")

        /** A submitted task, planned into two children: the second depends on the first and needs an approval. */
        private fun plannedRoot(key: String, projectId: String, title: String): String {
            val task = (ops.submitTask(bobOwner, key, title, bodyMarker, "kigawa-net/kivra", projectId) as SelfDevSubmitResult.Created).task
            val fence = assertNotNull(ops.claimForPlanning(task.taskId, "token-$key", Duration.ofMinutes(10))).fence
            val committed = ops.commitPlan(
                fence,
                listOf(
                    NewTask(
                        title = childOneTitle, body = "CHILD-BODY", targetPaths = listOf(pathMarker), sizeEstimate = "S",
                        dependsOn = emptyList(), requiresApproval = false, initialState = TaskState.QUEUED,
                    ),
                    NewTask(
                        title = childTwoTitle, body = null, targetPaths = listOf("src/other.kt"), sizeEstimate = "M",
                        dependsOn = listOf(1), requiresApproval = true, initialState = TaskState.PENDING_APPROVAL,
                    ),
                ),
            )
            assertIs<PlanCommit.Committed>(committed)
            return task.taskId
        }
    }

    private fun env(goalRead: Boolean, goalText: Boolean, writeEnabled: Boolean = false): Map<String, String> = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
        McpSettings.ENV_ENABLED to "true",
        McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
        McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        McpSettings.ENV_ALLOWED_ORIGINS to McpTestTokens.ORIGIN,
        McpSettings.ENV_GOAL_READ_ENABLED to goalRead.toString(),
        McpSettings.ENV_GOAL_TEXT_ENABLED to goalText.toString(),
        McpSettings.ENV_WRITE_ENABLED to writeEnabled.toString(),
        // The write rate (default 5 a minute) is raised here: one test makes more writes than that.
        McpSettings.ENV_WRITE_RATE_PER_MINUTE to "1000",
    )

    /** The production wiring as Application builds it. [tasks] is null while the autopilot is off. */
    private fun deps(
        db: TestDb,
        ops: SelfDevOperatorRepository?,
        goalRead: Boolean = true,
        goalText: Boolean = false,
        guard: PermissionGuard? = null,
        writeEnabled: Boolean = false,
    ): McpRouteDeps {
        val e = env(goalRead, goalText, writeEnabled)
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, repo,
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 1,
            tasks = ops, guard = guard, projects = ProjectRepository(db.database, clock),
        )!!
    }

    private fun ApplicationTestBuilder.setUp(deps: McpRouteDeps) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureMcpRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.rpc(body: String, token: String): HttpResponse =
        client.post("/mcp") {
            header(HttpHeaders.Host, McpTestTokens.HOST)
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun call(name: String, args: String, id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""

    private suspend fun ApplicationTestBuilder.callTool(name: String, args: String, token: String): JsonObject =
        rpc(call(name, args), token).json()

    private suspend fun ApplicationTestBuilder.listed(token: String): List<String> =
        rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token).json()["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun JsonObject.result(): JsonObject = this["result"]!!.jsonObject
    private fun JsonObject.structured(): JsonObject = result()["structuredContent"]!!.jsonObject
    private fun JsonObject.isError(): Boolean = result()["isError"]!!.jsonPrimitive.content.toBoolean()
    /** The refusal code of a not-found answer, as `kivra_order_get` gives it: `reason_code` in the structured result. */
    private fun JsonObject.errorCode(): String = structured()["reason_code"]!!.jsonPrimitive.content
    private fun JsonObject.rpcCode(): Int = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.rpcMessage(): String = this["error"]!!.jsonObject["message"]!!.jsonPrimitive.content

    private fun withTestDb(block: (TestDb) -> Unit) = TestDb().use(block)

    private fun JsonObject.children(): List<JsonObject> = structured()["children"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.childWithNoDeps(): JsonObject = children().single { it["depends_on"]!!.jsonArray.isEmpty() }
    private fun JsonObject.childWithDeps(): JsonObject = children().single { it["depends_on"]!!.jsonArray.isNotEmpty() }

    @Test
    fun `kivra_task_list is listed only with the goal read flag and the autopilot`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.ops, goalRead = true))
            assertTrue(toolName in listed(humanToken), "listed with the flag and the autopilot")
        }
        testApplication {
            setUp(deps(db, fx.ops, goalRead = false))
            assertFalse(toolName in listed(humanToken), "not listed without the goal read flag")
        }
        testApplication {
            setUp(deps(db, null, goalRead = true))
            assertFalse(toolName in listed(humanToken), "not listed while the autopilot is off")
        }
    }

    @Test
    fun `the root and its children carry only the listed fields, and no body, policy or path`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.ops))
            val body = rpc(call(toolName, """{"task_id":"${fx.root}"}"""), humanToken).json()
            assertFalse(body.isError(), "a visible root is not an error: $body")
            val s = body.structured()
            val task = s["task"]!!.jsonObject
            assertEquals(fx.root, task["task_id"]!!.jsonPrimitive.content)
            assertEquals("PENDING_APPROVAL", task["state"]!!.jsonPrimitive.content)
            assertEquals(fx.alpha, task["project_id"]!!.jsonPrimitive.content)
            assertEquals(setOf("task_id", "state", "state_reason", "version", "project_id"), task.keys)

            assertEquals(2, body.children().size)
            assertEquals(false, s["children_truncated"]!!.jsonPrimitive.content.toBoolean())
            val first = body.childWithNoDeps()
            val second = body.childWithDeps()
            assertEquals(setOf("task_id", "state", "state_reason", "requires_approval", "depends_on", "version", "created_at"), first.keys)
            assertEquals("QUEUED", first["state"]!!.jsonPrimitive.content)
            assertEquals(false, first["requires_approval"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("PENDING_APPROVAL", second["state"]!!.jsonPrimitive.content)
            assertEquals(true, second["requires_approval"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(listOf(first["task_id"]!!.jsonPrimitive.content), second["depends_on"]!!.jsonArray.map { it.jsonPrimitive.content })

            // Titles are off by default (ADR 0019 amendment), and nothing of a body, a policy or a target path is returned.
            assertFalse(s.containsKey("untrusted"))
            val text = body.toString()
            for (secret in listOf(rootMarker, childOneTitle, bodyMarker, "CHILD-BODY", pathMarker, "src/other.kt", fx.beta)) {
                assertFalse(secret in text, "the response names no $secret")
            }
        }
    }

    @Test
    fun `titles appear only with the text flag, to the operator, and an unsafe one is replaced`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.ops, goalText = true))
            val body = rpc(call(toolName, """{"task_id":"${fx.root}"}"""), humanToken).json()
            val s = body.structured()
            assertEquals(rootMarker, s["task"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(childOneTitle, body.childWithNoDeps()["title"]!!.jsonPrimitive.content)
            assertEquals(TITLE_UNSAFE, body.childWithDeps()["title"]!!.jsonPrimitive.content)
            assertEquals(true, s["untrusted"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(s["note"]!!.jsonPrimitive.content.contains("not instructions"))
            assertFalse(childTwoTitle in body.toString(), "the unsafe title itself is not returned")
        }
    }

    @Test
    fun `the AI principal gets no titles even with the text flag on`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.ops, goalText = true, guard = legacyPermissionGuard(aliceOwner)))
            val body = rpc(call(toolName, """{"task_id":"${fx.root}"}"""), aiToken).json()
            assertFalse(body.isError(), "the AI may read the tree: $body")
            assertFalse(body.structured()["task"]!!.jsonObject.containsKey("title"))
            assertTrue(body.children().none { it.containsKey("title") })
            assertFalse(body.structured().containsKey("untrusted"))
            assertFalse(rootMarker in body.toString(), "no title reaches the AI")
        }
    }

    @Test
    fun `an unknown argument and a bad or missing task id are invalid params`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.ops))
            val extra = rpc(call(toolName, """{"task_id":"${fx.root}","extra":1}"""), humanToken).json()
            assertEquals(-32602, extra.rpcCode())
            assertEquals("unknown argument", extra.rpcMessage())

            val bad = rpc(call(toolName, """{"task_id":"not-a-ulid"}"""), humanToken).json()
            assertEquals(-32602, bad.rpcCode())
            assertEquals("task_id has an invalid format", bad.rpcMessage())

            val missing = rpc(call(toolName, "{}"), humanToken).json()
            assertEquals(-32602, missing.rpcCode())
            assertEquals("task_id is required", missing.rpcMessage())
        }
    }

    @Test
    fun `a missing task is NOT_FOUND`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.ops))
            val body = rpc(call(toolName, """{"task_id":"$noSuchTask"}"""), humanToken).json()
            assertTrue(body.isError())
            assertEquals("NOT_FOUND", body.errorCode())
        }
    }

    @Test
    fun `a root of a project the caller may not read is the same not-found as a missing one`() = withTestDb { db ->
        val fx = Fixture(db)
        val memberId = assigneeId(db, "member")
        ProjectMemberRepository(db.database, clock).put(fx.alpha, memberId, "OPERATOR")
        val owners = object : AssigneeLookup {
            override fun accessOf(ownerKey: String): AssigneeAccess? =
                if (ownerKey == memberOwner) AssigneeAccess(Role.OPERATOR, true, memberId) else null
        }
        val guard = PermissionGuard(
            PermissionService(
                PermissionMode.ENFORCE, RolesDefault.NONE, null, null, owners, PermissionMode.ENFORCE, ProjectMemberStore(db.database),
            ),
            NoLog(),
        )
        testApplication {
            setUp(deps(db, fx.ops, guard = guard))
            val visible = rpc(call(toolName, """{"task_id":"${fx.root}"}"""), memberToken).json()
            assertFalse(visible.isError(), "a root of the member's project is visible")

            val hidden = rpc(call(toolName, """{"task_id":"${fx.rootBeta}"}"""), memberToken).json()
            val missing = rpc(call(toolName, """{"task_id":"$noSuchTask"}"""), memberToken).json()
            assertEquals("NOT_FOUND", hidden.errorCode())
            assertEquals(missing.result(), hidden.result(), "a hidden root answers byte for byte as a missing one")
        }
    }

    @Test
    fun `children beyond the limit are cut and flagged`() = withTestDb { db ->
        val fx = Fixture(db)
        val task = (fx.ops.submitTask(bobOwner, "many", "Many", null, "kigawa-net/kivra", fx.alpha) as SelfDevSubmitResult.Created).task
        val fence = assertNotNull(fx.ops.claimForPlanning(task.taskId, "token-many", Duration.ofMinutes(10))).fence
        val plan = (1..51).map {
            NewTask(
                title = "Child $it", body = null, targetPaths = listOf("a/$it.md"), sizeEstimate = "S",
                dependsOn = emptyList(), requiresApproval = true, initialState = TaskState.PENDING_APPROVAL,
            )
        }
        assertIs<PlanCommit.Committed>(fx.ops.commitPlan(fence, plan))
        testApplication {
            setUp(deps(db, fx.ops))
            val body = rpc(call(toolName, """{"task_id":"${task.taskId}"}"""), humanToken).json()
            assertEquals(50, body.children().size)
            assertEquals(true, body.structured()["children_truncated"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `a title is cut to 200 code points, and a control or bidi character replaces it`() {
        assertEquals(TITLE_UNSAFE, safeTaskTitle("a‮b"))
        assertEquals(TITLE_UNSAFE, safeTaskTitle("a\nb"))
        assertEquals(200, safeTaskTitle("x".repeat(250)).length)
        val emoji = "😀"
        assertEquals("a".repeat(199) + emoji, safeTaskTitle("a".repeat(199) + emoji + "z"))
    }

    // ---- the approve and the skip (ADR 0015 amendment 2026-11-19) ---------------------------------------------------

    private val decideScope = SCOPE_SELFDEV_TASK_DECIDE
    private val decideTools = listOf("kivra_task_approve", "kivra_task_skip", "kivra_task_replan")
    /** The approve and the skip only: a plan root is refused by GUARD_FAILED for these two, while the replan answers NO_EDGE (see below). */
    private val rootRefusedTools = listOf("kivra_task_approve", "kivra_task_skip")
    /** An operator (bob) with the decide scope. */
    private val decideBob get() = McpTestTokens.token(subject = "sub-bob", scope = "$readScope $decideScope")
    /** The AI principal (alice) with the same scope: the scope does not make it an operator. */
    private val decideAi get() = McpTestTokens.token(subject = "sub-alice", scope = "$readScope $decideScope")

    /** The production wiring with the write switch on, and the AI principal of the guard (alice), as the REST routes see it. */
    private fun decisionDeps(db: TestDb, ops: SelfDevOperatorRepository?): McpRouteDeps =
        deps(db, ops, writeEnabled = true, guard = legacyPermissionGuard(aliceOwner))

    private fun waitingTask(fx: Fixture): SelfDevTask =
        fx.ops.childrenOf(fx.root, 10).single { it.state == TaskState.PENDING_APPROVAL }

    private fun queuedTask(fx: Fixture): SelfDevTask =
        fx.ops.childrenOf(fx.root, 10).single { it.state == TaskState.QUEUED }

    /** The refusal of a tool result: `structuredContent.error.code` (the dispatcher's and the decision tools' shape). */
    private fun JsonObject.refusalCode(): String = structured()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `approve and skip are listed only with the write switch, the autopilot, and their own scope`() = withTestDb { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            assertTrue(listed(decideBob).containsAll(decideTools), "listed with the write switch, the autopilot and the scope")
            assertFalse(listed(humanToken).any { it in decideTools }, "the read scope lists neither")
        }
        testApplication {
            setUp(deps(db, fx.ops, writeEnabled = false))
            assertFalse(listed(decideBob).any { it in decideTools }, "not listed without the write switch")
            for (name in decideTools) {
                assertEquals(-32602, callTool(name, """{"task_id":"$noSuchTask","version":0}""", decideBob).rpcCode(), name)
            }
        }
        testApplication {
            setUp(decisionDeps(db, null))
            assertFalse(listed(decideBob).any { it in decideTools }, "not listed while the autopilot is off")
        }
    }

    @Test
    fun `approve and skip need their own scope - without it 403 insufficient_scope, and nothing is written`() = withTestDb { db ->
        val fx = Fixture(db)
        val target = waitingTask(fx)
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            for (name in decideTools) {
                val response = rpc(call(name, """{"task_id":"${target.taskId}","version":${target.version}}"""), McpTestTokens.token(subject = "sub-bob", scope = readScope))
                assertEquals(HttpStatusCode.Forbidden, response.status, name)
                assertTrue(response.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$decideScope\""), name)
            }
        }
        assertEquals(TaskState.PENDING_APPROVAL, fx.ops.task(target.taskId)!!.state)
    }

    @Test
    fun `approve moves a waiting task to QUEUED once per version, also one stopped by the scope check, and skip moves it to SKIPPED`() =
        withTestDb { db ->
            val fx = Fixture(db)
            val target = waitingTask(fx)
            // The scope check stopped it (AUTO_SCOPE_EXCEEDED). The approve does not check the scope again, so the reason is only a reason here.
            transaction(db.database) {
                execUpdate("UPDATE selfdev_tasks SET state_reason = 'AUTO_SCOPE_EXCEEDED' WHERE task_id = ?", target.taskId)
            }
            testApplication {
                setUp(decisionDeps(db, fx.ops))
                val ok = callTool("kivra_task_approve", """{"task_id":"${target.taskId}","version":${target.version}}""", decideBob)
                assertFalse(ok.isError(), ok.toString())
                assertEquals("QUEUED", ok.structured()["state"]!!.jsonPrimitive.content)
                assertEquals("OPERATOR_APPROVED", ok.structured()["state_reason"]!!.jsonPrimitive.content)
                assertFalse(ok.toString().contains(childTwoTitle), "the title is not returned")
                assertEquals(
                    "STALE_VERSION",
                    callTool("kivra_task_approve", """{"task_id":"${target.taskId}","version":${target.version}}""", decideBob).refusalCode(),
                )
            }
            val approved = fx.ops.task(target.taskId)!!
            assertEquals(TaskState.QUEUED, approved.state)
            assertEquals(bobOwner, approved.approvedBy)

            // A second waiting task, skipped: the same version rule, and the state is SKIPPED with OPERATOR_SKIPPED.
            val other = (fx.ops.childrenOf(fx.root, 10).single { it.state == TaskState.QUEUED && it.taskId != target.taskId })
            testApplication {
                setUp(decisionDeps(db, fx.ops))
                val skipped = callTool("kivra_task_skip", """{"task_id":"${other.taskId}","version":${other.version}}""", decideBob)
                assertFalse(skipped.isError(), skipped.toString())
                assertEquals("SKIPPED", skipped.structured()["state"]!!.jsonPrimitive.content)
                assertEquals("OPERATOR_SKIPPED", skipped.structured()["state_reason"]!!.jsonPrimitive.content)
                assertEquals(
                    "STALE_VERSION",
                    callTool("kivra_task_skip", """{"task_id":"${other.taskId}","version":${other.version}}""", decideBob).refusalCode(),
                )
            }
            assertEquals(TaskState.SKIPPED, fx.ops.task(other.taskId)!!.state)
        }

    @Test
    fun `skip also moves a waiting task to SKIPPED, and the outcome names no title`() = withTestDb { db ->
        val fx = Fixture(db)
        val target = waitingTask(fx)
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            val out = callTool("kivra_task_skip", """{"task_id":"${target.taskId}","version":${target.version}}""", decideBob)
            assertFalse(out.isError(), out.toString())
            assertEquals("SKIPPED", out.structured()["state"]!!.jsonPrimitive.content)
            assertFalse(out.toString().contains(childTwoTitle))
        }
        assertEquals(TaskState.SKIPPED, fx.ops.task(target.taskId)!!.state)
    }

    @Test
    fun `the AI principal is refused approve and skip by the permission, whatever its scopes, and nothing is written`() = withTestDb { db ->
        val fx = Fixture(db)
        val target = waitingTask(fx)
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            for (name in decideTools) {
                val out = callTool(name, """{"task_id":"${target.taskId}","version":${target.version}}""", decideAi)
                assertTrue(out.isError(), name)
                assertEquals("forbidden", out.refusalCode(), name)
            }
        }
        val row = fx.ops.task(target.taskId)!!
        assertEquals(TaskState.PENDING_APPROVAL, row.state)
        assertEquals(target.version, row.version)
        assertEquals(null, row.approvedBy)
    }

    @Test
    fun `approve and skip refuse bad input before the repository - unknown fields, bad id, missing, string, fraction or negative version`() =
        withTestDb { db ->
            val fx = Fixture(db)
            val target = waitingTask(fx)
            val bad = listOf(
                """{"task_id":"${target.taskId}","version":${target.version},"reason":"ok"}""",
                """{"task_id":"${target.taskId}","version":${target.version},"title":"x"}""",
                """{"task_id":"not-an-id","version":${target.version}}""",
                """{"task_id":"${target.taskId}"}""",
                """{"task_id":"${target.taskId}","version":"${target.version}"}""",
                """{"task_id":"${target.taskId}","version":1.5}""",
                """{"task_id":"${target.taskId}","version":-1}""",
            )
            testApplication {
                setUp(decisionDeps(db, fx.ops))
                for (name in decideTools) {
                    for (args in bad) assertEquals(-32602, callTool(name, args, decideBob).rpcCode(), "$name refused: $args")
                }
            }
            val row = fx.ops.task(target.taskId)!!
            assertEquals(TaskState.PENDING_APPROVAL, row.state)
            assertEquals(target.version, row.version)
        }

    @Test
    fun `approve and skip answer a missing task as TASK_NOT_FOUND, and a plan root and a wrong state as a refusal with no write`() = withTestDb { db ->
        val fx = Fixture(db)
        val queued = queuedTask(fx)
        val rootVersion = fx.ops.task(fx.root)!!.version
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            for (name in decideTools) {
                assertEquals("TASK_NOT_FOUND", callTool(name, """{"task_id":"$noSuchTask","version":0}""", decideBob).refusalCode(), name)
            }
            // A plan root is not run itself, so neither decision applies to it (the repository refuses it).
            for (name in rootRefusedTools) {
                val refused = callTool(name, """{"task_id":"${fx.root}","version":$rootVersion}""", decideBob)
                assertTrue(refused.isError(), name)
                assertEquals("GUARD_FAILED", refused.refusalCode(), name)
            }
            // A queued task is not waiting for approval: the approve is refused, and it stays QUEUED.
            val refusedApprove = callTool("kivra_task_approve", """{"task_id":"${queued.taskId}","version":${queued.version}}""", decideBob)
            assertTrue(refusedApprove.isError())
            assertEquals(TaskState.QUEUED, fx.ops.task(queued.taskId)!!.state)
        }
        assertEquals(rootVersion, fx.ops.task(fx.root)!!.version)
    }

    // ---- the replan (ADR 0015 amendment 2026-11-19 §10) -------------------------------------------------------------

    /** Puts the root into BLOCKED with a planning reason, as the planner's block does (the test sets the row; the planner is not run). */
    private fun blockRoot(db: TestDb, taskId: String, reason: String) {
        transaction(db.database) {
            execUpdate("UPDATE selfdev_tasks SET state = 'BLOCKED', state_reason = ? WHERE task_id = ?", reason, taskId)
        }
    }

    @Test
    fun `replan sends a BLOCKED plan back to TASK_SUBMITTED once per version, and names no title or body`() = withTestDb { db ->
        val fx = Fixture(db)
        blockRoot(db, fx.root, "PLAN_CALL_FAILED")
        val blocked = fx.ops.task(fx.root)!!
        assertEquals(TaskState.BLOCKED, blocked.state)
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            val out = callTool("kivra_task_replan", """{"task_id":"${fx.root}","version":${blocked.version}}""", decideBob)
            assertFalse(out.isError(), out.toString())
            assertEquals("TASK_SUBMITTED", out.structured()["state"]!!.jsonPrimitive.content)
            assertEquals("REPLAN_REQUESTED", out.structured()["state_reason"]!!.jsonPrimitive.content)
            assertEquals(blocked.version + 1, out.structured()["version"]!!.jsonPrimitive.content.toLong())
            assertFalse(out.toString().contains(rootMarker), "the title is not returned")
            assertFalse(out.toString().contains(bodyMarker), "the body is not returned")
            assertEquals(
                "STALE_VERSION",
                callTool("kivra_task_replan", """{"task_id":"${fx.root}","version":${blocked.version}}""", decideBob).refusalCode(),
            )
        }
        val row = fx.ops.task(fx.root)!!
        assertEquals(TaskState.TASK_SUBMITTED, row.state)
        assertEquals(blocked.version + 1, row.version)
    }

    @Test
    fun `replan refuses a task that is not BLOCKED with NO_EDGE, and a missing one as TASK_NOT_FOUND, with no write`() = withTestDb { db ->
        val fx = Fixture(db)
        val rootVersion = fx.ops.task(fx.root)!!.version
        assertEquals(TaskState.PENDING_APPROVAL, fx.ops.task(fx.root)!!.state)
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            val refused = callTool("kivra_task_replan", """{"task_id":"${fx.root}","version":$rootVersion}""", decideBob)
            assertTrue(refused.isError())
            assertEquals("NO_EDGE", refused.refusalCode())
            assertEquals("TASK_NOT_FOUND", callTool("kivra_task_replan", """{"task_id":"$noSuchTask","version":0}""", decideBob).refusalCode())
        }
        val row = fx.ops.task(fx.root)!!
        assertEquals(TaskState.PENDING_APPROVAL, row.state)
        assertEquals(rootVersion, row.version)
    }

    @Test
    fun `replan of a BLOCKED plan with a budget hold is accepted the same way`() = withTestDb { db ->
        val fx = Fixture(db)
        blockRoot(db, fx.root, "PLAN_BUDGET_HOLD")
        val blocked = fx.ops.task(fx.root)!!
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            val out = callTool("kivra_task_replan", """{"task_id":"${fx.root}","version":${blocked.version}}""", decideBob)
            assertFalse(out.isError(), out.toString())
        }
        assertEquals(TaskState.TASK_SUBMITTED, fx.ops.task(fx.root)!!.state)
    }

    @Test
    fun `replan needs its scope, the write switch and the autopilot, and the AI principal is refused`() = withTestDb { db ->
        val fx = Fixture(db)
        blockRoot(db, fx.root, "PLAN_INVALID")
        val blocked = fx.ops.task(fx.root)!!
        val args = """{"task_id":"${fx.root}","version":${blocked.version}}"""
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            val noScope = rpc(call("kivra_task_replan", args), McpTestTokens.token(subject = "sub-bob", scope = readScope))
            assertEquals(HttpStatusCode.Forbidden, noScope.status)
            assertTrue(noScope.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$decideScope\""))
            val ai = callTool("kivra_task_replan", args, decideAi)
            assertTrue(ai.isError())
            assertEquals("forbidden", ai.refusalCode())
        }
        testApplication {
            setUp(deps(db, fx.ops, writeEnabled = false))
            assertFalse(listed(decideBob).contains("kivra_task_replan"), "not listed without the write switch")
        }
        testApplication {
            setUp(decisionDeps(db, null))
            assertFalse(listed(decideBob).contains("kivra_task_replan"), "not listed while the autopilot is off")
        }
        assertEquals(TaskState.BLOCKED, fx.ops.task(fx.root)!!.state)
    }

    @Test
    fun `replan refuses bad input before the repository`() = withTestDb { db ->
        val fx = Fixture(db)
        blockRoot(db, fx.root, "PLAN_CALL_FAILED")
        val blocked = fx.ops.task(fx.root)!!
        testApplication {
            setUp(decisionDeps(db, fx.ops))
            for (args in listOf(
                """{"task_id":"${fx.root}","version":${blocked.version},"reason":"retry"}""",
                """{"task_id":"not-an-id","version":${blocked.version}}""",
                """{"task_id":"${fx.root}"}""",
                """{"task_id":"${fx.root}","version":1.5}""",
            )) {
                assertEquals(-32602, callTool("kivra_task_replan", args, decideBob).rpcCode(), args)
            }
        }
        assertEquals(TaskState.BLOCKED, fx.ops.task(fx.root)!!.state)
    }

    @Test
    fun `the replan writes its operator line with via=mcp and the ids and codes only`() {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        var rootId = ""
        try {
            withTestDb { db ->
                val fx = Fixture(db)
                rootId = fx.root
                blockRoot(db, fx.root, "PLAN_CALL_FAILED")
                testApplication {
                    setUp(decisionDeps(db, fx.ops))
                    callTool("kivra_task_replan", """{"task_id":"${fx.root}","version":${fx.ops.task(fx.root)!!.version}}""", decideBob)
                }
            }
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { it.contains("action=REPLAN") && it.contains("via=mcp") && it.contains("taskId=$rootId") })
        val everything = lines.joinToString("\n")
        for (marker in listOf(rootMarker, bodyMarker, "sub-bob", "sub-alice", bobOwner, aliceOwner)) {
            assertFalse(everything.contains(marker), "a log line leaked a value: $marker")
        }
    }

    @Test
    fun `the decision logs its operator line with via=mcp and the ids and codes only`() {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        var approvedId = ""
        var skippedId = ""
        try {
            withTestDb { db ->
                val fx = Fixture(db)
                val waiting = waitingTask(fx)
                approvedId = waiting.taskId
                val queued = queuedTask(fx)
                skippedId = queued.taskId
                testApplication {
                    setUp(decisionDeps(db, fx.ops))
                    callTool("kivra_task_approve", """{"task_id":"$approvedId","version":${waiting.version}}""", decideBob)
                    callTool("kivra_task_skip", """{"task_id":"$skippedId","version":${queued.version}}""", decideBob)
                }
            }
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { it.contains("action=TASK_APPROVED") && it.contains("via=mcp") && it.contains("taskId=$approvedId") })
        assertTrue(lines.any { it.contains("action=TASK_REJECTED") && it.contains("via=mcp") && it.contains("taskId=$skippedId") })
        val everything = lines.joinToString("\n")
        for (marker in listOf(childTwoTitle, bodyMarker, "sub-bob", "sub-alice", bobOwner, aliceOwner)) {
            assertFalse(everything.contains(marker), "a log line leaked a value: $marker")
        }
    }

    private fun assigneeId(db: TestDb, name: String): String =
        assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(db.database).create(name, AssigneeRole.OPERATOR, "root")).value.assigneeId
}

private const val TITLE_UNSAFE = "[unsafe title omitted]"
