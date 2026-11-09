package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import java.time.Clock
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.SelfDevSettings

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val AGENT_HEADER = "X-Test-Agent"
private const val OPERATOR = "1:i:oper"
private const val AI = "1:i:ai"
private const val TARGET = "owner/repo"

/**
 * The autopilot's operator API on the V40 Task model (SelfDevGoalRoutes.kt), on H2 with the real repository. The autopilot off answers
 * 503 `AUTOPILOT_DISABLED` and changes nothing; the operator's token is required; the AI principal may read but not write; a task is
 * accepted once per idempotency key; and the approval and the reject are compare-and-set on the version.
 */
class SelfDevGoalRoutesTest {
    private val openDbs = mutableListOf<TestDb>()

    private fun newDb(): TestDb = TestDb().also { openDbs += it }

    @AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth = when {
            headers[AGENT_HEADER] != null -> OperatorAuth.AgentRefused
            headers[PRINCIPAL_HEADER]?.isNotBlank() == true -> OperatorAuth.Authenticated(headers[PRINCIPAL_HEADER]!!)
            else -> OperatorAuth.Unauthenticated
        }
    }

    private fun enabledAutopilot(): AutopilotSettings = AutopilotSettings.fromEnv(
        mapOf(
            AutopilotSettings.ENV_ENABLED to "true",
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        ),
        SelfDevSettings.fromEnv(
            mapOf(
                "KIVRA_SELFDEV_ENABLED" to "true",
                "KIVRA_AUTH_MODE" to "oidc-jwt",
                "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
                "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
                "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
                "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
                "KIVRA_FX_USD_JPY" to "150",
                "KIVRA_FX_AS_OF" to "2026-10-18",
                "KIVRA_SELFDEV_RUNTIME" to "cli",
                "KIVRA_AGENT_API_ENABLED" to "true",
            ),
        ),
    )

    /** The project every goal in a test is submitted under; set by [setUp]. */
    private var projectId = ""

    private fun ApplicationTestBuilder.setUp(db: TestDb, autopilot: AutopilotSettings): SelfDevOperatorRepository {
        val operations = SelfDevOperatorRepository(db.database)
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        projectId = projects.create("Goal project", null).projectId
        val deps = SelfDevGoalRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            targetRepo = TARGET,
            operations = operations,
            projects = projects,
            control = SelfDevAutopilotControlRepository(db.database),
            aiPrincipal = AI,
        )
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevGoalRoutes(deps) }
        }
        return operations
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = "{}",
        principal: String? = OPERATOR,
        agent: Boolean = false,
    ): HttpResponse = client.request(path) {
        this.method = method
        principal?.let { header(PRINCIPAL_HEADER, it) }
        if (agent) header(AGENT_HEADER, "1")
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // The submission is on the project's alias; the old POST route is retired (ADR 0022 PR-G), see ProjectScopedRoutesTest.
    private suspend fun ApplicationTestBuilder.submit(key: String, title: String = "first task"): JsonObject =
        send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"$title","idempotencyKey":"$key","projectId":"$projectId"}""").obj()

    @Test
    fun `with the autopilot off every endpoint answers 503 AUTOPILOT_DISABLED, and nothing is written`() = testApplication {
        val db = newDb()
        val operations = setUp(db, AutopilotSettings.disabled())
        val status = send(HttpMethod.Get, "/api/selfdev/autopilot", body = null)
        assertEquals(HttpStatusCode.ServiceUnavailable, status.status)
        assertEquals("AUTOPILOT_DISABLED", status.obj().getValue("error").jsonPrimitive.content)
        val submit = send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"t","idempotencyKey":"k","projectId":"$projectId"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, submit.status)
        assertEquals("AUTOPILOT_DISABLED", submit.obj().getValue("error").jsonPrimitive.content)
        assertEquals(HttpStatusCode.ServiceUnavailable, send(HttpMethod.Post, "/api/selfdev/autopilot/pause").status)
        assertTrue(operations.listTasks(10).isEmpty())
    }

    @Test
    fun `the token is required before the disabled check, and an agent token is refused`() = testApplication {
        val db = newDb()
        setUp(db, AutopilotSettings.disabled())
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/goals", body = null, principal = null).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/goals", body = null, agent = true).status)
    }

    @Test
    fun `enabled, the status says enabled and the readout has its four conditions`() = testApplication {
        val db = newDb()
        setUp(db, enabledAutopilot())
        val status = send(HttpMethod.Get, "/api/selfdev/autopilot", body = null).obj()
        assertEquals(true, status.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(TARGET, status.getValue("targetRepo").jsonPrimitive.content)
        assertEquals(false, status.getValue("paused").jsonPrimitive.boolean)
        val readout = send(HttpMethod.Get, "/api/selfdev/autopilot/promotion", body = null).obj()
        assertEquals(4, readout.getValue("conditions").jsonArray.size)
    }

    @Test
    fun `a task is accepted once per key, a replay is 200, and another content under the key is 409`() = testApplication {
        val db = newDb()
        val operations = setUp(db, enabledAutopilot())
        val created = send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"first task","body":"b","idempotencyKey":"k1","projectId":"$projectId"}""")
        assertEquals(HttpStatusCode.Accepted, created.status)
        val taskId = created.obj().getValue("taskId").jsonPrimitive.content
        assertEquals("TASK_SUBMITTED", created.obj().getValue("state").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"first task","body":"b","idempotencyKey":"k1","projectId":"$projectId"}""").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"other","idempotencyKey":"k1","projectId":"$projectId"}""").status)
        assertEquals(1, operations.listTasks(10).size)
        val detail = send(HttpMethod.Get, "/api/selfdev/goals/$taskId", body = null).obj()
        assertEquals("b", detail.getValue("body").jsonPrimitive.content)
        assertEquals(1, Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/goals", body = null).bodyAsText()).jsonArray.size)
    }

    @Test
    fun `the AI principal may read but a write is refused before the body is read`() = testApplication {
        val db = newDb()
        val operations = setUp(db, enabledAutopilot())
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"t","idempotencyKey":"k","projectId":"$projectId"}""", principal = AI).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/autopilot/pause", principal = AI).status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/goals", body = null, principal = AI).status)
        assertTrue(operations.listTasks(10).isEmpty())
    }

    @Test
    fun `approve and reject refuse a task that is not yet PENDING_APPROVAL, an unknown id is 404, and a negative version is 400`() = testApplication {
        val db = newDb()
        setUp(db, enabledAutopilot())
        val approved = submit("ka").getValue("taskId").jsonPrimitive.content
        val rejected = submit("kr", "second").getValue("taskId").jsonPrimitive.content
        // The planner (which moves a task to PENDING_APPROVAL) is not part of this change, so a submitted task has no approval edge yet.
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/selfdev/tasks/$approved/approve", """{"version":0}""").status)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/selfdev/tasks/$rejected/reject", """{"version":0}""").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/selfdev/tasks/01AAAAAAAAAAAAAAAAAAAAAAAA/approve", """{"version":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/tasks/$approved/approve", """{"version":-1}""").status)
    }

    @Test
    fun `the operator switches the pause on and off and the state is read back`() = testApplication {
        val db = newDb()
        setUp(db, enabledAutopilot())
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/autopilot/pause").status)
        assertEquals(true, send(HttpMethod.Get, "/api/selfdev/autopilot", body = null).obj().getValue("paused").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/autopilot/resume").status)
        assertEquals(false, send(HttpMethod.Get, "/api/selfdev/autopilot", body = null).obj().getValue("paused").jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/autopilot/reset-breaker").status)
        assertEquals(0, send(HttpMethod.Get, "/api/selfdev/autopilot", body = null).obj().getValue("consecutiveFailures").jsonPrimitive.int)
    }

    @Test
    fun `a goal needs an existing project in its path - malformed and unknown are 404, a body project that differs is 400, and nothing is written`() = testApplication {
        val db = newDb()
        val operations = setUp(db, enabledAutopilot())
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/projects/not-a-ulid/tasks", """{"title":"t","idempotencyKey":"k1"}""").status)
        assertEquals(
            HttpStatusCode.NotFound,
            send(HttpMethod.Post, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA/tasks", """{"title":"t","idempotencyKey":"k2"}""").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"t","idempotencyKey":"k3","projectId":"01AAAAAAAAAAAAAAAAAAAAAAAA"}""").status,
        )
        assertTrue(operations.listTasks(10).isEmpty())
        // The body's projectId is optional on the alias: the path's project is the goal's project.
        assertEquals(HttpStatusCode.Accepted, send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"t","idempotencyKey":"k4"}""").status)
        assertEquals(projectId, operations.listTasks(10).single().projectId)
    }

    @Test
    fun `a goal keeps the project it was submitted under, and a replay under another project is 409`() = testApplication {
        val db = newDb()
        val operations = setUp(db, enabledAutopilot())
        val taskId = submit("kp").getValue("taskId").jsonPrimitive.content
        assertEquals(projectId, operations.task(taskId)?.projectId)
        val other = ProjectRepository(db.database, Clock.systemUTC()).create("Other", null).projectId
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/projects/$other/tasks", """{"title":"first task","idempotencyKey":"kp"}""").status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/projects/$projectId/tasks", """{"title":"first task","idempotencyKey":"kp","projectId":"$projectId"}""").status)
    }
}
