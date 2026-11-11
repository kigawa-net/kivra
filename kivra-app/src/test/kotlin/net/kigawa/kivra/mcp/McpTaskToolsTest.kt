package net.kigawa.kivra.mcp

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.GuardLog
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.legacyPermissionGuard
import net.kigawa.kivra.selfdev.NewTask

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

    private fun env(goalRead: Boolean, goalText: Boolean): Map<String, String> = mapOf(
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
    )

    /** The production wiring as Application builds it. [tasks] is null while the autopilot is off. */
    private fun deps(
        db: TestDb,
        ops: SelfDevOperatorRepository?,
        goalRead: Boolean = true,
        goalText: Boolean = false,
        guard: PermissionGuard? = null,
    ): McpRouteDeps {
        val e = env(goalRead, goalText)
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

    private fun assigneeId(db: TestDb, name: String): String =
        assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(db.database).create(name, AssigneeRole.OPERATOR, "root")).value.assigneeId
}

private const val TITLE_UNSAFE = "[unsafe title omitted]"
