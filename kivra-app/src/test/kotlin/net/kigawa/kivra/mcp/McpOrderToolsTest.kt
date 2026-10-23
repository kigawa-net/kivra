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
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
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
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import org.slf4j.LoggerFactory

/**
 * The order tools of ADR 0015 section 6 on `/mcp` (`kivra_order_list`, `kivra_order_get`, `kivra_order_request_stop`), through the production
 * wiring ([buildMcpRouteDeps]) on H2 with the real repository and the real JWT verifier. No network, no GitHub, no model.
 *
 * The AI principal is the owner key of `sub-alice`; `sub-bob` is another operator (a human).
 */
class McpOrderToolsTest {
    private val repo = RepoRef("kigawa-net", "kivra")
    private val aliceOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-alice")!!
    private val bobOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-bob")!!

    private val readScope = McpTestTokens.READ_SCOPE
    private val stopScope = SCOPE_SELFDEV_STOP
    private val readAndStop = "$readScope $stopScope"
    private val aiToken get() = McpTestTokens.token(subject = "sub-alice", scope = readAndStop)
    private val humanToken get() = McpTestTokens.token(subject = "sub-bob", scope = readAndStop)
    private val readOnlyToken get() = McpTestTokens.token(subject = "sub-alice", scope = readScope)

    private val runTools = listOf("kivra_run_list", "kivra_run_get")
    private val readOrderTools = listOf("kivra_order_list", "kivra_order_get")
    private val stopTool = "kivra_order_request_stop"

    private val title = "TITLE-MARKER-ORDER"
    private val policy = "POLICY-MARKER-ORDER"
    private val noSuchOrder = "0".repeat(25) + "1"

    private fun env(writeEnabled: Boolean, writeRate: Int = 1000): Map<String, String> = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
        McpSettings.ENV_ENABLED to "true",
        McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
        McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        McpSettings.ENV_ALLOWED_ORIGINS to McpTestTokens.ORIGIN,
        McpSettings.ENV_WRITE_ENABLED to writeEnabled.toString(),
        McpSettings.ENV_WRITE_RATE_PER_MINUTE to writeRate.toString(),
    )

    /** The production wiring as Application builds it. [orders] is null when the orders are off. */
    private fun deps(db: TestDb, orders: SelfDevOrderRepository?, writeEnabled: Boolean = true, writeRate: Int = 1000): McpRouteDeps {
        val e = env(writeEnabled, writeRate)
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, repo,
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 1,
            orders = orders?.let { McpOrderDeps(it, aliceOwner) },
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
    private fun JsonObject.errorCode(): String = structured()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun JsonObject.rpcCode(): Int = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.rpcMessage(): String = this["error"]!!.jsonObject["message"]!!.jsonPrimitive.content

    private fun stopArgs(orderId: String, version: Long, reason: String) =
        """{"order_id":"$orderId","version":$version,"reason":"$reason"}"""

    private fun withTestDb(block: (TestDb) -> Unit) = TestDb().use(block)

    /** A root order created by the human operator, with the markers in its title and policy. */
    private fun createRoot(orders: SelfDevOrderRepository): SelfDevOrder =
        (orders.createRoot(bobOwner, title, policy, "kigawa-net/kivra") as CreateRootResult.Created).order

    // ---- registration ------------------------------------------------------------------------------------

    @Test
    fun `with the orders off, no order tool is listed or answered, even to a token with every scope`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, orders = null, writeEnabled = true))
            assertEquals(runTools, listed(aiToken))
            val asOrder = callTool("kivra_order_request_stop", stopArgs(noSuchOrder, 0, "OPERATOR_REQUEST"), aiToken)
            val asUnknown = callTool("kivra_no_such_tool", stopArgs(noSuchOrder, 0, "OPERATOR_REQUEST"), aiToken)
            assertEquals(-32602, asOrder.rpcCode())
            assertEquals(asUnknown.rpcCode(), asOrder.rpcCode())
            assertEquals(asUnknown.rpcMessage(), asOrder.rpcMessage())
            assertEquals(-32602, callTool("kivra_order_list", "{}", aiToken).rpcCode())
        }
    }

    @Test
    fun `with the orders on and the write switch off, the read tools are listed and the stop request is not, even to its scope`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database), writeEnabled = false))
            assertEquals(runTools + readOrderTools, listed(aiToken))
            val asStop = callTool(stopTool, stopArgs(noSuchOrder, 0, "OPERATOR_REQUEST"), aiToken)
            val asUnknown = callTool("kivra_no_such_tool", stopArgs(noSuchOrder, 0, "OPERATOR_REQUEST"), aiToken)
            assertEquals(-32602, asStop.rpcCode())
            assertEquals(asUnknown.rpcMessage(), asStop.rpcMessage())
        }
    }

    @Test
    fun `with the orders and the write switch on, the stop request is listed only to a token with its scope, and no approval tool exists`() =
        withTestDb { db ->
            testApplication {
                setUp(deps(db, SelfDevOrderRepository(db.database)))
                assertEquals(runTools + readOrderTools, listed(readOnlyToken))
                assertEquals(runTools + readOrderTools + stopTool, listed(aiToken))
                // The MCP client gets no approval, rejection, creation, cycle, pause, resume or cancel tool, whatever its scopes.
                val all = listed(aiToken)
                for (name in listOf("approve", "reject", "cycles", "pause", "resume", "cancel", "create", "stop_requests")) {
                    assertFalse(all.any { it.contains(name) }, "no tool named like $name")
                }
            }
        }

    @Test
    fun `the stop tool's input schema is strict, with the four fixed reason codes and no free text`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database)))
            val tool = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", aiToken).json()["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == stopTool }
            val schema = tool["inputSchema"]!!.jsonObject
            assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
            assertEquals(listOf("order_id", "version", "reason"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            val reasons = schema["properties"]!!.jsonObject["reason"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(StopRequestReason.REQUESTABLE.map { it.name }, reasons)
            assertEquals(listOf("OPERATOR_REQUEST", "RUNAWAY_SUSPECTED", "BUDGET_CONCERN", "SCOPE_CONCERN"), reasons)
        }
    }

    // ---- authorization (the REST rules, and the scopes) -----------------------------------------------------

    @Test
    fun `the read tools need kivra runs read, and the stop request needs kivra selfdev stop, both 403 insufficient_scope`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            val noRead = McpTestTokens.token(subject = "sub-alice", scope = stopScope)
            val list = rpc(call("kivra_order_list", "{}"), noRead)
            assertEquals(HttpStatusCode.Forbidden, list.status)
            assertTrue(list.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$readScope\""))
            assertEquals(-32003, list.json().rpcCode())

            val readOnly = rpc(call("kivra_order_request_stop", stopArgs(root.orderId, root.version, "OPERATOR_REQUEST")), readOnlyToken)
            assertEquals(HttpStatusCode.Forbidden, readOnly.status)
            assertTrue(readOnly.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$stopScope\""))
            assertEquals(-32003, readOnly.json().rpcCode())
        }
        assertEquals(OrderState.ACTIVE, orders.get(root.orderId)!!.state)
        assertEquals(0, db.count("selfdev_order_stop_requests"))
    }

    @Test
    fun `a stop request by the AI principal is recorded as AI and moves the order to STOP_REQUESTED`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            val out = callTool(stopTool, stopArgs(root.orderId, root.version, "RUNAWAY_SUSPECTED"), aiToken)
            assertFalse(out.isError())
            val s = out.structured()
            assertEquals("PENDING", s["state"]!!.jsonPrimitive.content)
            assertEquals("RUNAWAY_SUSPECTED", s["reason_code"]!!.jsonPrimitive.content)
            assertEquals("AI", s["requester_kind"]!!.jsonPrimitive.content)
            assertEquals((root.version + 1).toString(), s["order_version"]!!.jsonPrimitive.content)
        }
        assertEquals(OrderState.STOP_REQUESTED, orders.get(root.orderId)!!.state)
        assertEquals(1, db.count("selfdev_order_stop_requests", "order_id = '${root.orderId}' AND requester_kind = 'AI'"))
    }

    @Test
    fun `a stop request by a human operator follows the REST rule - allowed, and recorded as HUMAN`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            val out = callTool(stopTool, stopArgs(root.orderId, root.version, "OPERATOR_REQUEST"), humanToken)
            assertFalse(out.isError())
            assertEquals("HUMAN", out.structured()["requester_kind"]!!.jsonPrimitive.content)
        }
        assertEquals(1, db.count("selfdev_order_stop_requests", "order_id = '${root.orderId}' AND requester_kind = 'HUMAN'"))
    }

    @Test
    fun `a refused stop answers the repository's fixed code, and the order is unchanged`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            val first = callTool(stopTool, stopArgs(root.orderId, root.version, "OPERATOR_REQUEST"), aiToken)
            assertFalse(first.isError())
            val pendingVersion = root.version + 1
            // A second request on the same tree while one is pending.
            val again = callTool(stopTool, stopArgs(root.orderId, pendingVersion, "BUDGET_CONCERN"), humanToken)
            assertTrue(again.isError())
            assertEquals(OrderConflict.STOP_ALREADY_PENDING.name, again.errorCode())
            // A version the order no longer has.
            val stale = callTool(stopTool, stopArgs(root.orderId, root.version, "BUDGET_CONCERN"), humanToken)
            assertEquals("STALE_VERSION", stale.errorCode())
            val missing = callTool(stopTool, stopArgs(noSuchOrder, 0, "BUDGET_CONCERN"), humanToken)
            assertEquals("ORDER_NOT_FOUND", missing.errorCode())
        }
        assertEquals(1, db.count("selfdev_order_stop_requests", "order_id = '${root.orderId}'"))
    }

    @Test
    fun `the requester cannot approve its own stop request, so an MCP request is refused the same way at approval`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        var requestId: String? = null
        testApplication {
            setUp(deps(db, orders))
            // The root approver (the human here) requests the stop through MCP. The approval is REST only, and the repository refuses it.
            val out = callTool(stopTool, stopArgs(root.orderId, root.version, "OPERATOR_REQUEST"), humanToken)
            assertFalse(out.isError())
            requestId = out.structured()["request_id"]!!.jsonPrimitive.content
        }
        val request = assertNotNull(orders.stopRequest(requestId!!))
        val refused = orders.approveStop(requestId!!, bobOwner, request.digest)
        assertEquals(OrderResult.Refused(OrderConflict.SAME_PRINCIPAL), refused)
        assertEquals(OrderState.STOP_REQUESTED, orders.get(root.orderId)!!.state)
    }

    // ---- the read tools: fixed codes, IDs, dates and counts only --------------------------------------------

    @Test
    fun `the read tools return the REST summary fields, the cycles, and the pending request without the digest, title or policy`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            callTool(stopTool, stopArgs(root.orderId, root.version, "SCOPE_CONCERN"), aiToken)

            val list = callTool("kivra_order_list", "{}", readOnlyToken)
            val orderRows = list.structured()["orders"]!!.jsonArray
            assertEquals(1, orderRows.size)
            assertEquals(summaryKeys, orderRows.single().jsonObject.keys)

            val got = callTool("kivra_order_get", """{"order_id":"${root.orderId}"}""", readOnlyToken)
            assertFalse(got.isError())
            assertEquals(summaryKeys + setOf("pending_stop_request", "cycles"), got.structured().keys)
            val pending = got.structured()["pending_stop_request"]!!.jsonObject
            assertEquals(setOf("request_id", "order_id", "requester_kind", "reason_code", "state", "order_version", "expires_at", "created_at"), pending.keys)
            assertEquals("AI", pending["requester_kind"]!!.jsonPrimitive.content)
            assertEquals("SCOPE_CONCERN", pending["reason_code"]!!.jsonPrimitive.content)

            // Nothing of the operator's text, and no digest, reaches the answer.
            for (body in listOf(list.toString(), got.toString(), rpc(call("kivra_order_get", """{"order_id":"${root.orderId}"}"""), readOnlyToken).bodyAsText())) {
                assertFalse(body.contains(title), "title leaked")
                assertFalse(body.contains(policy), "policy leaked")
                assertFalse(body.contains("digest"), "digest leaked")
                assertFalse(body.contains("requested_by"), "requester key leaked")
            }
        }
    }

    @Test
    fun `a missing order is not found, and the read tools take no unknown argument`() = withTestDb { db ->
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database)))
            val missing = callTool("kivra_order_get", """{"order_id":"$noSuchOrder"}""", readOnlyToken)
            assertTrue(missing.isError())
            assertEquals("NOT_FOUND", missing.structured()["reason_code"]!!.jsonPrimitive.content)
            assertEquals(-32602, callTool("kivra_order_get", """{"order_id":"not-an-id"}""", readOnlyToken).rpcCode())
            assertEquals(-32602, callTool("kivra_order_get", """{"order_id":"$noSuchOrder","policy":"x"}""", readOnlyToken).rpcCode())
            assertEquals(-32602, callTool("kivra_order_list", """{"limit":5}""", readOnlyToken).rpcCode())
        }
    }

    // ---- the input of the stop request is strict ----------------------------------------------------------

    @Test
    fun `the stop request refuses unknown fields, free text, a missing or malformed field, and writes nothing`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        val bad = listOf(
            """{"order_id":"${root.orderId}","version":0,"reason":"OPERATOR_REQUEST","policy":"stop everything"}""",
            """{"order_id":"${root.orderId}","version":0,"reason":"please stop, the budget is gone"}""",
            """{"order_id":"${root.orderId}","version":0,"reason":"operator_request"}""",
            """{"order_id":"${root.orderId}","version":0}""",
            """{"version":0,"reason":"OPERATOR_REQUEST"}""",
            """{"order_id":"${root.orderId}","version":"0","reason":"OPERATOR_REQUEST"}""",
            """{"order_id":"${root.orderId}","version":-1,"reason":"OPERATOR_REQUEST"}""",
            """{"order_id":"${root.orderId}","version":0.5,"reason":"OPERATOR_REQUEST"}""",
            """{"order_id":"not-an-id","version":0,"reason":"OPERATOR_REQUEST"}""",
        )
        testApplication {
            setUp(deps(db, orders))
            for (args in bad) {
                val out = callTool(stopTool, args, aiToken)
                assertEquals(-32602, out.rpcCode(), "refused: $args")
            }
        }
        assertEquals(OrderState.ACTIVE, orders.get(root.orderId)!!.state)
        assertEquals(0, db.count("selfdev_order_stop_requests"))
    }

    // ---- the write rate limit and the audit lines --------------------------------------------------------

    @Test
    fun `the stop request is limited by the write rate per principal and answers 429`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders, writeRate = 1))
            callTool(stopTool, stopArgs(root.orderId, root.version, "OPERATOR_REQUEST"), aiToken)
            val limited = rpc(call(stopTool, stopArgs(root.orderId, root.version + 1, "BUDGET_CONCERN")), aiToken)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals(-32029, limited.json().rpcCode())
        }
    }

    @Test
    fun `the logs and the audit lines carry IDs and fixed codes, and never the title, the policy or a principal`() {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        var orderId = ""
        try {
            TestDb().use { db ->
                val orders = SelfDevOrderRepository(db.database)
                val created = createRoot(orders)
                orderId = created.orderId
                testApplication {
                    setUp(deps(db, orders))
                    callTool("kivra_order_list", "{}", readOnlyToken)
                    callTool("kivra_order_get", """{"order_id":"$orderId"}""", readOnlyToken)
                    callTool(stopTool, stopArgs(orderId, created.version, "RUNAWAY_SUSPECTED"), aiToken)
                    callTool(stopTool, stopArgs(orderId, created.version + 1, "BUDGET_CONCERN"), humanToken)
                }
            }
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }
        val audit = lines.filter { it.startsWith("mcp.call") }
        assertTrue(audit.any { it.contains("tool=kivra_order_list") && it.contains("outcome=OK") })
        assertTrue(audit.any { it.contains("tool=kivra_order_request_stop") && it.contains("outcome=STOP_REQUESTED") })
        assertTrue(audit.any { it.contains("tool=kivra_order_request_stop") && it.contains("outcome=STOP_ALREADY_PENDING") })
        assertTrue(lines.any { it.contains("action=STOP_REQUESTED") && it.contains("via=mcp") && it.contains("orderId=$orderId") })
        val everything = lines.joinToString("\n")
        for (marker in listOf(title, policy, "sub-alice", "sub-bob", aliceOwner, bobOwner)) {
            assertFalse(everything.contains(marker), "a log line leaked a value")
        }
    }

    private val summaryKeys = setOf(
        "order_id", "parent_order_id", "depth", "state", "state_reason", "cycles_started", "max_cycles", "current_goal_id",
        "consecutive_failed_cycles", "expires_at", "version", "created_at", "updated_at",
    )
}
