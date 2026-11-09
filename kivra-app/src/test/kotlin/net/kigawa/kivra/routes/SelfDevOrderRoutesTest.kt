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
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val OPERATOR = "1:i:oper"
private const val TARGET = "owner/repo"
private const val ROOT_APPROVER = "1:i:root"
private const val NO_PROJECT = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The order API's create route (ADR 0022, PR-C) on H2 with the real repository: the root's projectId is required, must be well formed, and must
 * name an existing project (400, 400, 404), and an accepted root keeps it.
 */
class SelfDevOrderRoutesTest {
    private val openDbs = mutableListOf<TestDb>()

    @kotlin.test.AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth =
            headers[PRINCIPAL_HEADER]?.takeIf { it.isNotBlank() }?.let { OperatorAuth.Authenticated(it) } ?: OperatorAuth.Unauthenticated
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

    private fun ApplicationTestBuilder.setUp(db: TestDb): SelfDevOrderRepository {
        val autopilot = enabledAutopilot()
        val orders = SelfDevOrderRepository(db.database)
        val deps = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = OrderSettings.fromEnv(
                mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to ROOT_APPROVER),
                autopilot,
                aiPrincipal = null,
            ),
            aiPrincipal = null,
            targetRepo = TARGET,
            orders = orders,
            operations = SelfDevOperatorRepository(db.database),
            projects = ProjectRepository(db.database, Clock.systemUTC()),
        )
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevOrderRoutes(deps) }
        }
        return orders
    }

    private suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, body: String?): HttpResponse =
        client.request(path) {
            this.method = method
            header(PRINCIPAL_HEADER, OPERATOR)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `an order needs an existing project in its path - malformed and unknown are 404, a body project that differs is 400, and nothing is written`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val project = ProjectRepository(db.database, Clock.systemUTC()).create("Orders check", null).projectId
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/projects/not-a-ulid/orders", """{"title":"t","policy":"Improve the docs"}""").status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/projects/$NO_PROJECT/orders", """{"title":"t","policy":"Improve the docs"}""").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            send(HttpMethod.Post, "/api/projects/$project/orders", """{"title":"t","policy":"Improve the docs","projectId":"$NO_PROJECT"}""").status,
        )
        assertTrue(orders.list(10).isEmpty())
    }

    @Test
    fun `an accepted root keeps the project it was created under`() = testApplication {
        val db = TestDb().also { openDbs += it }
        val orders = setUp(db)
        val project = ProjectRepository(db.database, Clock.systemUTC()).create("Orders", null).projectId
        val created = send(
            HttpMethod.Post,
            "/api/projects/$project/orders",
            """{"title":"t","policy":"Improve the docs","projectId":"$project"}""",
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val orderId = assertNotNull(created.obj()["orderId"]).jsonPrimitive.content
        assertEquals(project, created.obj()["projectId"]!!.jsonPrimitive.content)
        assertEquals(project, orders.get(orderId)?.projectId)
    }
}
