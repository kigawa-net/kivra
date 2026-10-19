package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.slf4j.LoggerFactory

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val AGENT_HEADER = "X-Test-Agent"
private const val ROOT_APPROVER = "1:i:kigawa"
private const val AI_PRINCIPAL = "1:i:ai"
private const val OTHER_OPERATOR = "1:i:other"
private const val TITLE_MARKER = "TITLE-MARKER-ROUTE"
private const val POLICY_MARKER = "POLICY-MARKER-ROUTE"
private val START: Instant = Instant.parse("2026-10-19T10:00:00Z")

/**
 * The order API (ADR 0015 stage 1) on H2 with the real repositories. The authenticator reads the principal from a header, so the
 * owner-key rules are exercised here: the AI principal may only request a stop and read, and only the root approver approves.
 */
class SelfDevOrderRoutesTest {

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: io.ktor.http.Headers): OperatorAuth = when {
            headers[AGENT_HEADER] != null -> OperatorAuth.AgentRefused
            headers[PRINCIPAL_HEADER]?.isNotBlank() == true -> OperatorAuth.Authenticated(headers[PRINCIPAL_HEADER]!!)
            else -> OperatorAuth.Unauthenticated
        }
    }

    /** A clock the test moves (the 24-hour expiry of a stop request). */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(START)

    private fun ApplicationTestBuilder.setUp(db: TestDb, enabled: Boolean = true) {
        val autopilot = if (enabled) AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev) else AutopilotSettings.disabled()
        val orderSettings = if (enabled) OrderSettings.fromEnv(orderEnv(), autopilot) else OrderSettings.disabled()
        val deps = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = orderSettings,
            targetRepo = "kigawa-net/kivra",
            orders = SelfDevOrderRepository(db.database, clock),
            goals = SelfDevGoalRepository(db.database, clock),
            operations = SelfDevOperatorRepository(db.database),
        )
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevOrderRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = "{}",
        principal: String? = ROOT_APPROVER,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = client.request(path) {
        this.method = method
        principal?.let { header(PRINCIPAL_HEADER, it) }
        headers.forEach { (k, v) -> header(k, v) }
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.createRoot(principal: String = ROOT_APPROVER): HttpResponse =
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"$TITLE_MARKER","policy":"$POLICY_MARKER"}""", principal)

    private suspend fun ApplicationTestBuilder.rootId(): String {
        val list = send(HttpMethod.Get, "/api/selfdev/orders", null).let { Json.parseToJsonElement(it.bodyAsText()).jsonArray }
        return list.single().jsonObject.getValue("orderId").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.orderVersion(id: String): String =
        send(HttpMethod.Get, "/api/selfdev/orders/$id", null).json().getValue("order").jsonObject.getValue("version").jsonPrimitive.content

    // ---- availability and authentication -----------------------------------------------------------------------------------

    @Test
    fun `while the orders are off every endpoint answers 503 and nothing is read`() = testApplication {
        val db = TestDb()
        setUp(db, enabled = false)
        assertEquals(HttpStatusCode.ServiceUnavailable, send(HttpMethod.Get, "/api/selfdev/orders", null).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, createRoot().status)
    }

    @Test
    fun `no token is 401 and an agent token is 403`() = testApplication {
        val db = TestDb()
        setUp(db)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/orders", null, principal = null).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/orders", null, principal = null, headers = mapOf(AGENT_HEADER to "1")).status)
    }

    // ---- creation, and the root -------------------------------------------------------------------------------------------

    @Test
    fun `the root is created once and a second one is refused`() = testApplication {
        val db = TestDb()
        setUp(db)
        assertEquals(HttpStatusCode.Created, createRoot().status)
        assertEquals(HttpStatusCode.Conflict, createRoot().status)
    }

    @Test
    fun `a blank title, a control character in the policy and an unknown body field are 400`() = testApplication {
        val db = TestDb()
        setUp(db)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"  ","policy":"p"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", "{\"title\":\"t\",\"policy\":\"a\u0007b\"}").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","x":1}""").status)
    }

    // ---- the AI principal -----------------------------------------------------------------------------------------------

    @Test
    fun `the AI principal can read and request a stop, and cannot create, start, pause, resume, approve, reject or cancel`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/orders", null, AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/orders/$id", null, AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, createRoot(AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/orders/$id/cycles", """{"version":0}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/orders/$id/pause", """{"version":0}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/orders/$id/resume", """{"version":0}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/01AAAAAAAAAAAAAAAAAAAAAAAA/approve", """{"digest":"${"a".repeat(64)}"}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/01AAAAAAAAAAAAAAAAAAAAAAAA/reject", "{}", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/01AAAAAAAAAAAAAAAAAAAAAAAA/cancel", "{}", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null, AI_PRINCIPAL).status)

        val version = orderVersion(id)
        val request = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"RUNAWAY_SUSPECTED"}""", AI_PRINCIPAL)
        assertEquals(HttpStatusCode.Created, request.status)
        assertEquals("AI", request.json().getValue("requesterKind").jsonPrimitive.content)
        assertTrue(request.json().getValue("digest") is JsonNull, "the requester does not see the digest")
    }

    @Test
    fun `an operator who is not the root approver starts a cycle but cannot approve`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/orders/$id/cycles", """{"version":0}""", OTHER_OPERATOR).status)
        val version = orderVersion(id)
        val request = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"OPERATOR_REQUEST"}""", AI_PRINCIPAL)
        val requestId = request.json().getValue("requestId").jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"${"a".repeat(64)}"}""", OTHER_OPERATOR).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null, OTHER_OPERATOR).status)
    }

    // ---- approval ------------------------------------------------------------------------------------------------------------

    @Test
    fun `the root approver approves an AI request with the digest it was shown, and the order stops`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        val version = orderVersion(id)
        val requestId = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"BUDGET_CONCERN"}""", AI_PRINCIPAL)
            .json().getValue("requestId").jsonPrimitive.content

        val list = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null).bodyAsText()).jsonArray
        assertEquals(1, list.size)
        val digest = list.single().jsonObject.getValue("digest").jsonPrimitive.content

        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"${"0".repeat(64)}"}""").status)
        val approved = send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"$digest"}""")
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals("STOPPED", approved.json().getValue("state").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"$digest"}""").status)
    }

    @Test
    fun `the requester cannot approve its own request, even as the root approver`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        val version = orderVersion(id)
        val requestId = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"OPERATOR_REQUEST"}""")
            .json().getValue("requestId").jsonPrimitive.content
        val digest = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null).bodyAsText())
            .jsonArray.single().jsonObject.getValue("digest").jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"$digest"}""").status)
    }

    @Test
    fun `an approval after 24 hours is refused, and the order is paused and not stopped`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        val version = orderVersion(id)
        val requestId = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"OPERATOR_REQUEST"}""", AI_PRINCIPAL)
            .json().getValue("requestId").jsonPrimitive.content
        val digest = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null).bodyAsText())
            .jsonArray.single().jsonObject.getValue("digest").jsonPrimitive.content
        clock.now = START.plus(Duration.ofHours(25))
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"$digest"}""").status)
        assertEquals("PAUSED", orderState(id))
    }

    @Test
    fun `a rejection pauses the order, and a bad reason code is 400`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        val version = orderVersion(id)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"free text here"}""", AI_PRINCIPAL).status)
        val requestId = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"SCOPE_CONCERN"}""", AI_PRINCIPAL)
            .json().getValue("requestId").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/reject", "{}").status)
        assertEquals("PAUSED", orderState(id))
    }

    // ---- pause and resume ---------------------------------------------------------------------------------------------

    @Test
    fun `an operator pauses and resumes with the version check`() = testApplication {
        val db = TestDb()
        setUp(db)
        createRoot()
        val id = rootId()
        assertEquals(HttpStatusCode.Conflict, send(HttpMethod.Post, "/api/selfdev/orders/$id/pause", """{"version":99}""").status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/orders/$id/pause", """{"version":0}""").status)
        assertEquals("PAUSED", orderState(id))
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/orders/$id/resume", """{"version":1}""").status)
        assertEquals("ACTIVE", orderState(id))
    }

    // ---- logs carry no text -----------------------------------------------------------------------------------------------

    @Test
    fun `logs never carry the title or the policy`() = testApplication {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.order.operator") as Logger
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val db = TestDb()
            setUp(db)
            createRoot()
            val id = rootId()
            send(HttpMethod.Post, "/api/selfdev/orders/$id/cycles", """{"version":0}""", OTHER_OPERATOR)
            val version = orderVersion(id)
            send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"OPERATOR_REQUEST"}""", AI_PRINCIPAL)
            val messages = appender.list.map { it.formattedMessage }
            assertTrue(messages.isNotEmpty())
            for (message in messages) {
                assertFalse(message.contains(TITLE_MARKER), message)
                assertFalse(message.contains(POLICY_MARKER), message)
                assertFalse(message.contains(ROOT_APPROVER), message)
                assertFalse(message.contains(AI_PRINCIPAL), message)
            }
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.orderState(id: String): String =
        send(HttpMethod.Get, "/api/selfdev/orders/$id", null).json().getValue("order").jsonObject.getValue("state").jsonPrimitive.content

    private val cliSelfDev: SelfDevSettings by lazy { SelfDevSettings.fromEnv(selfDevEnv()) }

    private fun selfDevEnv(): Map<String, String> = mapOf(
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
    )

    private fun autopilotEnv(): Map<String, String> = mapOf(
        AutopilotSettings.ENV_ENABLED to "true",
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
    )

    private fun orderEnv(): Map<String, String> = mapOf(
        OrderSettings.ENV_ENABLED to "true",
        OrderSettings.ENV_ROOT_APPROVER to ROOT_APPROVER,
        OrderSettings.ENV_AI_PRINCIPAL to AI_PRINCIPAL,
    )
}
