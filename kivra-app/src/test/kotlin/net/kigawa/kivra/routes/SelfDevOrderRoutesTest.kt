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
import io.ktor.http.isSuccess
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
import kotlin.test.AfterTest
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
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.jetbrains.exposed.sql.transactions.transaction
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

    /**
     * Every database of a test is closed after it. A MariaDB TestDb holds a Hikari pool (10 connections by default); a pool that is not
     * closed keeps them, and the server's max_connections (151 in CI) runs out.
     */
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

    /** A clock the test moves (the 24-hour expiry of a stop request). */
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(START)

    private fun ApplicationTestBuilder.setUp(db: TestDb, enabled: Boolean = true, extraOrderEnv: Map<String, String> = emptyMap()) {
        val autopilot = if (enabled) AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev) else AutopilotSettings.disabled()
        val orderSettings = if (enabled) OrderSettings.fromEnv(orderEnv() + extraOrderEnv, autopilot, AI_PRINCIPAL) else OrderSettings.disabled()
        val deps = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = orderSettings,
            aiPrincipal = AI_PRINCIPAL,
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
        val db = newDb()
        setUp(db, enabled = false)
        assertEquals(HttpStatusCode.ServiceUnavailable, send(HttpMethod.Get, "/api/selfdev/orders", null).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, createRoot().status)
    }

    @Test
    fun `no token is 401 and an agent token is 403`() = testApplication {
        val db = newDb()
        setUp(db)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/orders", null, principal = null).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/orders", null, principal = null, headers = mapOf(AGENT_HEADER to "1")).status)
    }

    // ---- creation, and the root -------------------------------------------------------------------------------------------

    @Test
    fun `the root is created once and a second one is refused`() = testApplication {
        val db = newDb()
        setUp(db)
        assertEquals(HttpStatusCode.Created, createRoot().status)
        assertEquals(HttpStatusCode.Conflict, createRoot().status)
    }

    @Test
    fun `a blank title, a control character in the policy and an unknown body field are 400`() = testApplication {
        val db = newDb()
        setUp(db)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"  ","policy":"p"}""").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", "{\"title\":\"t\",\"policy\":\"a\u0007b\"}").status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","x":1}""").status)
    }

    @Test
    fun `maxCycles defaults to 1, is accepted up to the cap, and is fixed after creation`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = mapOf(OrderSettings.ENV_MAX_CYCLES_CAP to "3"))
        val created = send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","maxCycles":3}""")
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(3, created.json()["maxCycles"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, SelfDevOrderRepository(db.database, clock).list(10).single().maxCycles)
    }

    @Test
    fun `an omitted maxCycles is 1`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = mapOf(OrderSettings.ENV_MAX_CYCLES_CAP to "3"))
        assertEquals(HttpStatusCode.Created, createRoot().status)
        assertEquals(1, SelfDevOrderRepository(db.database, clock).list(10).single().maxCycles)
    }

    @Test
    fun `a maxCycles out of range or above the cap is 400 with a fixed code, and nothing is created`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = mapOf(OrderSettings.ENV_MAX_CYCLES_CAP to "3"))
        for (bad in listOf("0", "4", "-1", "11", "999999999999")) {
            val r = send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","maxCycles":$bad}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, bad)
        }
        val r = send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","maxCycles":4}""")
        assertEquals("MAX_CYCLES_INVALID", r.json()["code"]!!.jsonPrimitive.content)
        for (bad in listOf("2.5", "true", "[]")) {
            assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","maxCycles":$bad}""").status, bad)
        }
        assertTrue(SelfDevOrderRepository(db.database, clock).list(10).isEmpty())
    }

    @Test
    fun `with the default cap of 1 a maxCycles of 2 is 400`() = testApplication {
        val db = newDb()
        setUp(db)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","maxCycles":2}""").status)
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p","maxCycles":1}""").status)
    }

    // ---- the AI principal -----------------------------------------------------------------------------------------------

    @Test
    fun `the AI principal can read and request a stop, and cannot create, start, pause, resume, approve, reject or cancel`() = testApplication {
        val db = newDb()
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
        val db = newDb()
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
        val db = newDb()
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
        val db = newDb()
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
        val db = newDb()
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
        val db = newDb()
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
        val db = newDb()
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
            val db = newDb()
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

    // ---- child orders (ADR 0015 section 2.7, stage 2 PR 3a) ----------------------------------------------------------------

    private val depth2 = mapOf(OrderSettings.ENV_MAX_DEPTH to "2")
    private val missingId = "01" + "Z".repeat(24)

    private suspend fun ApplicationTestBuilder.createChild(
        parent: String,
        budget: Long = 1_000,
        key: String = "k-1",
        principal: String = ROOT_APPROVER,
        title: String = TITLE_MARKER,
    ): HttpResponse = send(
        HttpMethod.Post,
        "/api/selfdev/orders/$parent/children",
        """{"title":"$title","policy":"$POLICY_MARKER","orderBudgetJpy":$budget,"idempotencyKey":"$key"}""",
        principal,
    )

    @Test
    fun `children are disabled by default, and the create answers 409 CHILDREN_DISABLED`() = testApplication {
        val db = newDb()
        setUp(db)
        createRoot()
        val response = createChild(rootId())
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("CHILDREN_DISABLED", response.json().getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `the operator creates a child, a replay answers 200 with the same child, and another content under the key is 409`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        val created = createChild(id)
        assertEquals(HttpStatusCode.Created, created.status)
        val childId = created.json().getValue("orderId").jsonPrimitive.content
        assertEquals(id, created.json().getValue("parentOrderId").jsonPrimitive.content)
        assertEquals("1", created.json().getValue("depth").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, createChild(id).status)
        assertEquals(childId, createChild(id).json().getValue("orderId").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, createChild(id, title = "another title").status)
        val parent = send(HttpMethod.Get, "/api/selfdev/orders/$id", null).json().getValue("order").jsonObject
        assertEquals("1000", parent.getValue("allocatedJpy").jsonPrimitive.content)
        assertEquals("1", parent.getValue("childCount").jsonPrimitive.content)
        // The list carries the counts and never the title.
        val list = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/orders", null).bodyAsText()).jsonArray
        assertFalse(list.first().jsonObject.containsKey("title"))
    }

    @Test
    fun `a child's stop is requested by the AI principal and decided only by the root approver, who stops the child and its grandchild`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        val childId = createChild(id).json().getValue("orderId").jsonPrimitive.content
        val grandId = createChild(childId, key = "k-grand").json().getValue("orderId").jsonPrimitive.content
        val requested = send(
            HttpMethod.Post,
            "/api/selfdev/orders/$childId/stop-requests",
            """{"version":${orderVersion(childId)},"reasonCode":"RUNAWAY_SUSPECTED"}""",
            AI_PRINCIPAL,
        )
        assertEquals(HttpStatusCode.Created, requested.status)
        val requestId = requested.json().getValue("requestId").jsonPrimitive.content
        assertEquals("false", requested.json().getValue("targetIsRoot").jsonPrimitive.content)
        // The AI principal and another operator cannot decide it: 403, before the body is read.
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"${"a".repeat(64)}"}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"${"a".repeat(64)}"}""", OTHER_OPERATOR).status)
        val digest = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null).bodyAsText())
            .jsonArray.single().jsonObject.getValue("digest").jsonPrimitive.content
        val approved = send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"$digest"}""")
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals("STOPPED", approved.json().getValue("state").jsonPrimitive.content)
        assertEquals("STOPPED", send(HttpMethod.Get, "/api/selfdev/orders/$grandId", null).json().getValue("order").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("ACTIVE", send(HttpMethod.Get, "/api/selfdev/orders/$id", null).json().getValue("order").jsonObject.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun `the root approver may request a child's stop and decide it, since the parent's decision approves it`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        val childId = createChild(id).json().getValue("orderId").jsonPrimitive.content
        val requestId = send(
            HttpMethod.Post,
            "/api/selfdev/orders/$childId/stop-requests",
            """{"version":${orderVersion(childId)},"reasonCode":"OPERATOR_REQUEST"}""",
        ).json().getValue("requestId").jsonPrimitive.content
        val digest = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null).bodyAsText())
            .jsonArray.single().jsonObject.getValue("digest").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/stop-requests/$requestId/approve", """{"digest":"$digest"}""").status)
        assertEquals("STOPPED", send(HttpMethod.Get, "/api/selfdev/orders/$childId", null).json().getValue("order").jsonObject.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun `the AI principal is refused with 403 before the body is read, and a parent that is not active is 409`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        val byAi = send(HttpMethod.Post, "/api/selfdev/orders/$id/children", "not json at all", AI_PRINCIPAL)
        assertEquals(HttpStatusCode.Forbidden, byAi.status)
        assertEquals("forbidden", byAi.json().getValue("error").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/orders/$id/pause", """{"version":${orderVersion(id)}}""").status)
        val paused = createChild(id)
        assertEquals(HttpStatusCode.Conflict, paused.status)
        assertEquals("PARENT_NOT_ACTIVE", paused.json().getValue("code").jsonPrimitive.content)
        assertEquals("PARENT_NOT_ACTIVE", createChild(missingId).json().getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `a child's title, policy, key and budget are checked, and a bidi control in the policy is refused for the root too`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        assertEquals(HttpStatusCode.BadRequest, createChild(id, budget = 0).status)
        assertEquals(HttpStatusCode.BadRequest, createChild(id, budget = 1_000_000_000_000).status)
        assertEquals(HttpStatusCode.BadRequest, createChild(id, title = "  ").status)
        assertEquals(HttpStatusCode.BadRequest, createChild(id, key = " ").status)
        val bidi = "{\"title\":\"t\",\"policy\":\"a\u202Eb\",\"orderBudgetJpy\":1000,\"idempotencyKey\":\"k\"}"
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders/$id/children", bidi).status)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Post, "/api/selfdev/orders", "{\"title\":\"t\",\"policy\":\"a\u202Eb\"}").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            send(HttpMethod.Post, "/api/selfdev/orders/$id/children", """{"title":"t","policy":"p","orderBudgetJpy":1000}""").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            send(HttpMethod.Post, "/api/selfdev/orders/$id/children", """{"title":"t","policy":"p","orderBudgetJpy":1000,"idempotencyKey":"k","x":1}""").status,
        )
    }

    @Test
    fun `no route changes a child's parent`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        val childId = createChild(id).json().getValue("orderId").jsonPrimitive.content
        val body = """{"parentOrderId":"$childId"}"""
        for (response in listOf(
            send(HttpMethod.Post, "/api/selfdev/orders/$childId/parent", body),
            send(HttpMethod.Patch, "/api/selfdev/orders/$childId", body),
            send(HttpMethod.Put, "/api/selfdev/orders/$childId", body),
        )) {
            assertFalse(response.status.isSuccess(), "a parent change must not be routed: ${response.status}")
        }
        assertEquals(id, send(HttpMethod.Get, "/api/selfdev/orders/$childId", null).json().getValue("order").jsonObject
            .getValue("parentOrderId").jsonPrimitive.content)
    }

    private val cliSelfDev: SelfDevSettings by lazy { SelfDevSettings.fromEnv(selfDevEnv()) }

    @Test
    fun `a cycle the budget does not cover is 409 ORDER_BUDGET with the order's ID, a child under 1,000 yen is 400, and the reads carry the budget`() = testApplication {
        val db = newDb()
        setUp(db, extraOrderEnv = depth2)
        createRoot()
        val id = rootId()
        val childId = createChild(id, budget = 3_000).json().getValue("orderId").jsonPrimitive.content
        transaction(db.database) { execUpdate("UPDATE selfdev_orders SET spent_jpy = ? WHERE order_id = ?", 2_001L, childId) }
        val refused = send(HttpMethod.Post, "/api/selfdev/orders/$childId/cycles", """{"version":0}""")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val body = refused.json()
        assertEquals("ORDER_BUDGET", body.getValue("code").jsonPrimitive.content)
        assertEquals(childId, body.getValue("orderId").jsonPrimitive.content)
        assertFalse(body.getValue("error").jsonPrimitive.content.contains(TITLE_MARKER))
        // A child below the default cycle budget (1,000 yen) could never start a cycle, so it is refused at creation.
        assertEquals(HttpStatusCode.BadRequest, createChild(id, budget = 999, key = "small").status)
        val detail = send(HttpMethod.Get, "/api/selfdev/orders/$childId", null).json().getValue("order").jsonObject
        assertEquals("2001", detail.getValue("spentJpy").jsonPrimitive.content)
        assertEquals("1000", detail.getValue("cycleBudgetJpy").jsonPrimitive.content)
        assertEquals("999", detail.getValue("remainingJpy").jsonPrimitive.content)
        val list = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/orders", null).bodyAsText()).jsonArray
        assertFalse(list.first().jsonObject.containsKey("title"))
    }

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
