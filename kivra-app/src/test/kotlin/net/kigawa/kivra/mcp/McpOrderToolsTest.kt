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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.EXTEND_DAYS_LIMIT
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.routes.RootOrderCheck
import net.kigawa.kivra.routes.RootOrderField
import net.kigawa.kivra.routes.MAX_POLICY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TITLE_CODE_POINTS
import net.kigawa.kivra.routes.checkRootOrderFields
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.github.RepoRef
import org.jetbrains.exposed.sql.transactions.transaction
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
    private val manageScope = SCOPE_SELFDEV_ORDER_MANAGE
    private val requeueScope = SCOPE_SELFDEV_REQUEUE
    private val manageTools = listOf("kivra_order_extend", "kivra_order_resume")
    private val requeueTool = "kivra_task_requeue"
    /** An operator (a human, bob) with the decision scopes. */
    private val operatorDecisions get() = McpTestTokens.token(subject = "sub-bob", scope = "$readScope $manageScope $requeueScope")
    /** The AI principal (alice) with the same scopes: the scopes do not make it an operator. */
    private val aiDecisions get() = McpTestTokens.token(subject = "sub-alice", scope = "$readScope $manageScope $requeueScope")

    private val title = "TITLE-MARKER-ORDER"
    private val policy = "POLICY-MARKER-ORDER"
    private val noSuchOrder = "0".repeat(25) + "1"
    private val CHILD_LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)
    /** The cap of the cycles an extension may reach (`KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP` in the wiring). */
    private val CYCLE_CAP = 3

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
    private fun deps(
        db: TestDb,
        orders: SelfDevOrderRepository?,
        writeEnabled: Boolean = true,
        writeRate: Int = 1000,
        tasks: SelfDevOperatorRepository? = null,
        targetRepo: String? = "kigawa-net/kivra",
    ): McpRouteDeps {
        val e = env(writeEnabled, writeRate)
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, repo,
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 1,
            orders = orders?.let {
                McpOrderDeps(it, aliceOwner, maxCyclesCap = CYCLE_CAP, targetRepo = targetRepo, projects = ProjectRepository(db.database, java.time.Clock.systemUTC()))
            },
            tasks = tasks,
            projects = net.kigawa.kivra.db.ProjectRepository(db.database, java.time.Clock.systemUTC()),
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
                // The MCP client gets no approval, rejection, creation, cycle, pause or cancel tool, whatever its scopes. Resume and extend
                // are listed only to the decision scope (see the tests of the decisions below).
                val all = listed(aiToken)
                for (name in listOf("approve", "reject", "cycles", "pause", "cancel", "create", "stop_requests")) {
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
    fun `the read tools return the tree links, the child count and the budget of a child order, as IDs and JPY numbers`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        val child = (orders.createChild(root.orderId, title, policy, 700, "mcp-child-1", bobOwner, CHILD_LIMITS) as ChildCreateResult.Created).order
        testApplication {
            setUp(deps(db, orders))
            val rows = callTool("kivra_order_list", "{}", readOnlyToken).structured()["orders"]!!.jsonArray.map { it.jsonObject }
            val rootRow = rows.single { it["order_id"]!!.jsonPrimitive.content == root.orderId }
            val childRow = rows.single { it["order_id"]!!.jsonPrimitive.content == child.orderId }
            assertTrue(rootRow["parent_order_id"] is JsonNull, "a root has no parent")
            assertEquals("1", rootRow["child_count"]!!.jsonPrimitive.content)
            assertEquals(root.orderId, childRow["parent_order_id"]!!.jsonPrimitive.content)
            assertEquals("1", childRow["depth"]!!.jsonPrimitive.content)
            assertEquals("0", childRow["child_count"]!!.jsonPrimitive.content)
            assertEquals("700", childRow["order_budget_jpy"]!!.jsonPrimitive.content)
            assertEquals("0", childRow["spent_jpy"]!!.jsonPrimitive.content)

            val got = callTool("kivra_order_get", """{"order_id":"${child.orderId}"}""", readOnlyToken).structured()
            assertEquals(root.orderId, got["parent_order_id"]!!.jsonPrimitive.content)
            assertEquals("700", got["order_budget_jpy"]!!.jsonPrimitive.content)
            assertEquals("0", got["child_count"]!!.jsonPrimitive.content)
        }
    }

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

    // ---- the operator's decisions (ADR 0015 amendment 2026-11-18) -----------------

    @Test
    fun `the decision tools are listed only with the write switch and to their own scope, never to the read or stop scope`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        testApplication {
            setUp(deps(db, orders, tasks = SelfDevOperatorRepository(db.database)))
            assertEquals(runTools + readOrderTools + stopTool, listed(aiToken))
            val withManage = listed(operatorDecisions)
            assertTrue(withManage.containsAll(manageTools), "the decision scope lists extend and resume")
            assertTrue(withManage.contains(requeueTool), "the requeue scope lists the requeue")
        }
        testApplication {
            setUp(deps(db, orders, writeEnabled = false))
            assertEquals(runTools + readOrderTools, listed(operatorDecisions))
            for (name in manageTools + requeueTool) {
                assertEquals(-32602, callTool(name, """{"order_id":"$noSuchOrder","version":0}""", operatorDecisions).rpcCode(), name)
            }
        }
    }

    @Test
    fun `extend adds cycles and days through the repository, keeps the state, and answers the REST summary`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            val out = callTool("kivra_order_extend", """{"order_id":"${root.orderId}","version":${root.version},"add_cycles":2,"extend_days":5}""", operatorDecisions)
            assertFalse(out.isError(), out.toString())
            val s = out.structured()
            assertEquals(summaryKeys, s.keys)
            assertEquals("ACTIVE", s["state"]!!.jsonPrimitive.content)
            assertEquals("3", s["max_cycles"]!!.jsonPrimitive.content)
            assertEquals((root.version + 1).toString(), s["version"]!!.jsonPrimitive.content)
        }
        val after = orders.get(root.orderId)!!
        assertEquals(3, after.maxCycles)
        assertEquals(OrderState.ACTIVE, after.state)
        assertEquals(1, db.count("selfdev_orders", "order_id = '${root.orderId}' AND max_cycles = 3"))
    }

    @Test
    fun `extend and resume need their own scope - without it the call is 403 insufficient_scope and nothing is written`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders, tasks = SelfDevOperatorRepository(db.database)))
            for (name in manageTools) {
                val args = if (name == "kivra_order_extend") """{"order_id":"${root.orderId}","version":${root.version},"add_cycles":1}""" else
                    """{"order_id":"${root.orderId}","version":${root.version}}"""
                val response = rpc(call(name, args), readAndStopToken())
                assertEquals(HttpStatusCode.Forbidden, response.status, name)
                assertTrue(response.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$manageScope\""), name)
            }
            val requeue = rpc(call(requeueTool, """{"task_id":"$noSuchOrder","version":0}"""), readAndStopToken())
            assertEquals(HttpStatusCode.Forbidden, requeue.status)
            assertTrue(requeue.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$requeueScope\""))
        }
        assertEquals(1, orders.get(root.orderId)!!.maxCycles)
    }

    @Test
    fun `the AI principal is refused extend and resume by the permission, whatever its scopes, and nothing is written`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        testApplication {
            setUp(deps(db, orders))
            val extend = callTool("kivra_order_extend", """{"order_id":"${root.orderId}","version":${root.version},"add_cycles":1}""", aiDecisions)
            assertTrue(extend.isError())
            assertEquals("forbidden", extend.structured()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            val resume = callTool("kivra_order_resume", """{"order_id":"${root.orderId}","version":${root.version}}""", aiDecisions)
            assertTrue(resume.isError())
        }
        assertEquals(1, orders.get(root.orderId)!!.maxCycles)
    }

    @Test
    fun `extend refuses bad input before the repository - unknown fields, neither field, out of range, strings and fractions`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        val v = root.version
        val bad = listOf(
            """{"order_id":"${root.orderId}","version":$v}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":0}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":${CYCLE_CAP + 1}}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":"1"}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":1.5}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":99999999999}""",
            """{"order_id":"${root.orderId}","version":$v,"extend_days":0}""",
            """{"order_id":"${root.orderId}","version":$v,"extend_days":${EXTEND_DAYS_LIMIT + 1}}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":1,"policy":"extend everything"}""",
            """{"order_id":"${root.orderId}","version":$v,"add_cycles":1,"reason":"please, the budget is fine"}""",
            """{"order_id":"not-an-id","version":$v,"add_cycles":1}""",
            """{"order_id":"${root.orderId}","version":-1,"add_cycles":1}""",
            """{"order_id":"${root.orderId}","version":"$v","add_cycles":1}""",
        )
        testApplication {
            setUp(deps(db, orders))
            for (args in bad) {
                assertEquals(-32602, callTool("kivra_order_extend", args, operatorDecisions).rpcCode(), "refused: $args")
            }
        }
        assertEquals(1, orders.get(root.orderId)!!.maxCycles)
        assertEquals(v, orders.get(root.orderId)!!.version)
    }

    @Test
    fun `extend answers a stale version and an unknown order as fixed codes, and a child order as a refusal with no write`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        val child = (orders.createChild(root.orderId, title, policy, 700, "mcp-decide-child", bobOwner, CHILD_LIMITS) as ChildCreateResult.Created).order
        testApplication {
            setUp(deps(db, orders))
            val stale = callTool("kivra_order_extend", """{"order_id":"${root.orderId}","version":${root.version + 9},"add_cycles":1}""", operatorDecisions)
            assertEquals("STALE_VERSION", stale.errorCode())
            val missing = callTool("kivra_order_extend", """{"order_id":"$noSuchOrder","version":0,"add_cycles":1}""", operatorDecisions)
            assertEquals("ORDER_NOT_FOUND", missing.errorCode())
            val notRoot = callTool("kivra_order_extend", """{"order_id":"${child.orderId}","version":${child.version},"add_cycles":1}""", operatorDecisions)
            assertTrue(notRoot.isError())
            assertEquals(OrderConflict.NOT_ROOT.name, notRoot.errorCode())
        }
        assertEquals(1, orders.get(root.orderId)!!.maxCycles)
    }

    @Test
    fun `resume moves a PAUSED order back to ACTIVE once per version, by an operator only`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val root = createRoot(orders)
        val paused = (orders.pauseByOperator(root.orderId, root.version, bobOwner) as OrderResult.Moved).value
        testApplication {
            setUp(deps(db, orders))
            val refusedForAi = callTool("kivra_order_resume", """{"order_id":"${root.orderId}","version":${paused.version}}""", aiDecisions)
            assertTrue(refusedForAi.isError())
            assertEquals(OrderState.PAUSED, orders.get(root.orderId)!!.state)

            val out = callTool("kivra_order_resume", """{"order_id":"${root.orderId}","version":${paused.version}}""", operatorDecisions)
            assertFalse(out.isError(), out.toString())
            assertEquals("ACTIVE", out.structured()["state"]!!.jsonPrimitive.content)
            // The same version again is stale: one resume, one version step.
            assertEquals("STALE_VERSION", callTool("kivra_order_resume", """{"order_id":"${root.orderId}","version":${paused.version}}""", operatorDecisions).errorCode())
            assertEquals(-32602, callTool("kivra_order_resume", """{"order_id":"${root.orderId}","version":${paused.version},"reason":"x"}""", operatorDecisions).rpcCode())
        }
        assertEquals(OrderState.ACTIVE, orders.get(root.orderId)!!.state)
    }

    // ---- the requeue of a task of the cycle tree (ADR 0015 amendment 2026-11-18) ---------------------------------

    /** A FAILED task of a project, with a real run (the task refers to it), as the REST requeue test sets it up. */
    private fun failedTask(db: TestDb, operations: SelfDevOperatorRepository): String {
        val projectId = ProjectRepository(db.database, java.time.Clock.systemUTC()).create("mcp project", null).projectId
        val taskId = (operations.submitTask(bobOwner, "mcp-requeue-key", "first task", null, "kigawa-net/kivra", projectId) as SelfDevSubmitResult.Created)
            .task.taskId
        val run = db.runs.submitTask(bobOwner, "run-of-$taskId", "first task", selfDev = true, maxActiveSelfDev = null).run.runId.value
        transaction(db.database) {
            execUpdate("UPDATE selfdev_tasks SET state = 'FAILED', state_reason = 'RUN_FAILED', attempts = 1, run_id = ? WHERE task_id = ?", run, taskId)
        }
        return taskId
    }

    @Test
    fun `requeue moves a FAILED task to QUEUED once per version, and the AI, a missing scope and a bad input change nothing`() = withTestDb { db ->
        val operations = SelfDevOperatorRepository(db.database)
        val taskId = failedTask(db, operations)
        val version = operations.task(taskId)!!.version
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database), tasks = operations))
            assertTrue(listed(operatorDecisions).contains(requeueTool))
            assertFalse(listed(aiToken).contains(requeueTool))

            val refusedForAi = callTool(requeueTool, """{"task_id":"$taskId","version":$version}""", aiDecisions)
            assertTrue(refusedForAi.isError())
            assertEquals("FAILED", operations.task(taskId)!!.state.name)

            val noScope = rpc(call(requeueTool, """{"task_id":"$taskId","version":$version}"""), readAndStopToken())
            assertEquals(HttpStatusCode.Forbidden, noScope.status)
            assertEquals(-32602, callTool(requeueTool, """{"task_id":"$taskId","version":$version,"title":"x"}""", operatorDecisions).rpcCode())
            assertEquals(-32602, callTool(requeueTool, """{"task_id":"not-an-id","version":$version}""", operatorDecisions).rpcCode())

            val ok = callTool(requeueTool, """{"task_id":"$taskId","version":$version}""", operatorDecisions)
            assertFalse(ok.isError(), ok.toString())
            assertEquals("QUEUED", ok.structured()["state"]!!.jsonPrimitive.content)
            assertEquals("OPERATOR_REQUEUED", ok.structured()["state_reason"]!!.jsonPrimitive.content)
            assertFalse(ok.toString().contains("first task"), "the title is not returned")

            assertEquals("STALE_VERSION", callTool(requeueTool, """{"task_id":"$taskId","version":$version}""", operatorDecisions).errorCode())
            assertEquals("TASK_NOT_FOUND", callTool(requeueTool, """{"task_id":"$noSuchOrder","version":0}""", operatorDecisions).errorCode())
        }
        val row = operations.task(taskId)!!
        assertEquals("QUEUED", row.state.name)
        assertEquals("ap:$taskId:a1", row.idempotencyKey)
        assertEquals(null, row.runId)
    }

    /** The read and stop scopes only: no decision scope. */
    private fun readAndStopToken() = McpTestTokens.token(subject = "sub-bob", scope = readAndStop)

    // ---- kivra_order_create (ADR 0015 amendment 2026-11-19, section 9) ---------------------------------------

    private val createScope = SCOPE_SELFDEV_ORDER_CREATE
    private val createTool = "kivra_order_create"
    /** An operator (bob, a human) with the create scope. */
    private val operatorCreate get() = McpTestTokens.token(subject = "sub-bob", scope = "$readScope $createScope")
    /** The AI principal (alice) with the create scope: the scope does not make it an operator. */
    private val aiCreate get() = McpTestTokens.token(subject = "sub-alice", scope = "$readScope $createScope")
    private val createTitle = "CREATE-TITLE-MARKER"
    private val createPolicy = "CREATE-POLICY-MARKER"

    private fun newProject(db: TestDb): String =
        ProjectRepository(db.database, java.time.Clock.systemUTC()).create("Order create", null).projectId

    /** The arguments of a creation, as JSON text. [extra] is appended as more members (for example `"max_cycles":2`). */
    private fun createArgs(projectId: String, title: String = createTitle, policy: String = createPolicy, extra: String = ""): String =
        """{"title":"$title","policy":"$policy","project_id":"$projectId"$extra}"""

    private fun countOrders(db: TestDb): Int = db.count("selfdev_orders")

    @Test
    fun `kivra_order_create is listed only with the write switch and to its own scope`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        testApplication {
            setUp(deps(db, orders))
            assertFalse(listed(operatorDecisions).contains(createTool), "the decision scope does not list the creation")
            assertFalse(listed(aiToken).contains(createTool))
            assertTrue(listed(operatorCreate).contains(createTool))
            assertTrue(listed(aiCreate).contains(createTool), "listed to the AI token too; the permission refuses the call")
        }
        testApplication {
            setUp(deps(db, orders, writeEnabled = false))
            assertFalse(listed(operatorCreate).contains(createTool))
            assertEquals(-32602, callTool(createTool, createArgs(newProject(db)), operatorCreate).rpcCode())
        }
    }

    @Test
    fun `an operator creates a root order as the REST route does, returns the summary and no title or policy`() = withTestDb { db ->
        val orders = SelfDevOrderRepository(db.database)
        val project = newProject(db)
        testApplication {
            setUp(deps(db, orders))
            val out = callTool(createTool, createArgs(project, extra = ""), operatorCreate)
            assertFalse(out.isError(), out.toString())
            val s = out.structured()
            assertEquals(summaryKeys, s.keys)
            assertEquals("ACTIVE", s["state"]!!.jsonPrimitive.content)
            assertEquals("1", s["max_cycles"]!!.jsonPrimitive.content)
            assertEquals(project, s["project_id"]!!.jsonPrimitive.content)
            assertFalse(out.toString().contains(createTitle), "the title is not returned")
            assertFalse(out.toString().contains(createPolicy), "the policy is not returned")
            val orderId = s["order_id"]!!.jsonPrimitive.content
            val row = orders.get(orderId)!!
            assertEquals(createTitle, row.title)
            assertEquals(createPolicy, row.policy)
            assertEquals("kigawa-net/kivra", row.targetRepo)
            assertEquals(bobOwner, row.createdBy)
            assertEquals(project, row.projectId)
            assertEquals(null, row.parentOrderId)
        }
        assertEquals(1, countOrders(db))
    }

    @Test
    fun `the creation needs its own scope - without it the call is 403 insufficient_scope and nothing is written`() = withTestDb { db ->
        val project = newProject(db)
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database)))
            val response = rpc(call(createTool, createArgs(project)), operatorDecisions)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("scope=\"$createScope\""))
            assertEquals(-32003, response.json().rpcCode())
        }
        assertEquals(0, countOrders(db))
    }

    @Test
    fun `the AI principal is refused the creation by the permission, whatever its scopes, and nothing is written`() = withTestDb { db ->
        val project = newProject(db)
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database)))
            val out = callTool(createTool, createArgs(project), aiCreate)
            assertTrue(out.isError())
            assertEquals("forbidden", out.structured()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
        assertEquals(0, countOrders(db))
    }

    @Test
    fun `the creation refuses bad input before the repository - unknown, missing, typed, out of range and unsafe text`() = withTestDb { db ->
        val project = newProject(db)
        val longTitle = "a".repeat(MAX_TITLE_CODE_POINTS + 1)
        val longPolicy = "a".repeat(MAX_POLICY_CODE_POINTS + 1)
        val cases = listOf(
            """{"title":"t","policy":"p","project_id":"$project","budget_jpy":1000}""",
            """{"title":"t","project_id":"$project"}""",
            """{"policy":"p","project_id":"$project"}""",
            """{"title":"t","policy":"p"}""",
            """{"title":"t","policy":"p","project_id":"not-a-project-id"}""",
            """{"title":7,"policy":"p","project_id":"$project"}""",
            """{"title":"","policy":"p","project_id":"$project"}""",
            """{"title":"$longTitle","policy":"p","project_id":"$project"}""",
            """{"title":"t\u0007x","policy":"p","project_id":"$project"}""",
            """{"title":"t","policy":"$longPolicy","project_id":"$project"}""",
            """{"title":"t","policy":"p\u202Eq","project_id":"$project"}""",
            """{"title":"t","policy":"p","project_id":"$project","max_cycles":0}""",
            """{"title":"t","policy":"p","project_id":"$project","max_cycles":4}""",
            """{"title":"t","policy":"p","project_id":"$project","max_cycles":"2"}""",
            """{"title":"t","policy":"p","project_id":"$project","max_cycles":1.5}""",
        )
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database)))
            for (args in cases) {
                assertEquals(-32602, callTool(createTool, args, operatorCreate).rpcCode(), args.take(80))
            }
        }
        assertEquals(0, countOrders(db))
    }

    @Test
    fun `the creation answers a missing project, a missing target and a cap of the cycles as fixed refusals, and writes nothing`() = withTestDb { db ->
        val project = newProject(db)
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database)))
            val noProject = callTool(createTool, createArgs(noSuchOrder), operatorCreate)
            assertTrue(noProject.isError())
            assertEquals("PROJECT_NOT_FOUND", noProject.errorCode())
            val atCap = callTool(createTool, createArgs(project, extra = ",\"max_cycles\":$CYCLE_CAP"), operatorCreate)
            assertFalse(atCap.isError(), "the cap itself is allowed")
            assertEquals("$CYCLE_CAP", atCap.structured()["max_cycles"]!!.jsonPrimitive.content)
        }
        testApplication {
            setUp(deps(db, SelfDevOrderRepository(db.database), targetRepo = null))
            val noTarget = callTool(createTool, createArgs(project), operatorCreate)
            assertEquals("ORDERS_DISABLED", noTarget.errorCode())
        }
        assertEquals(1, countOrders(db))
    }

    @Test
    fun `the creation is audited with the ID and fixed codes only, never the title or the policy`() {
        val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        var orderId = ""
        try {
            TestDb().use { db ->
                val project = newProject(db)
                testApplication {
                    setUp(deps(db, SelfDevOrderRepository(db.database)))
                    val out = callTool(createTool, createArgs(project), operatorCreate)
                    orderId = out.structured()["order_id"]!!.jsonPrimitive.content
                }
            }
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { it.formattedMessage }
        assertTrue(lines.any { it.contains("action=ORDER_CREATED") && it.contains("via=mcp") && it.contains("orderId=$orderId") })
        assertTrue(lines.any { it.startsWith("mcp.call") && it.contains("tool=kivra_order_create") && it.contains("outcome=CREATED") })
        val everything = lines.joinToString("\n")
        for (marker in listOf(createTitle, createPolicy, "sub-bob", bobOwner)) {
            assertFalse(everything.contains(marker), "a log line leaked a value")
        }
    }

    @Test
    fun `the field checks are one set of rules for the REST route and the tool, at the limits`() {
        // The REST route and kivra_order_create both call checkRootOrderFields, so the same boundary answers come from both.
        val ok = checkRootOrderFields("a".repeat(MAX_TITLE_CODE_POINTS), "p".repeat(MAX_POLICY_CODE_POINTS), 1, CYCLE_CAP)
        assertIs<RootOrderCheck.Valid>(ok)
        assertEquals(
            RootOrderField.TITLE,
            assertIs<RootOrderCheck.Invalid>(checkRootOrderFields("a".repeat(MAX_TITLE_CODE_POINTS + 1), "p", 1, CYCLE_CAP)).field,
        )
        assertEquals(
            RootOrderField.POLICY,
            assertIs<RootOrderCheck.Invalid>(checkRootOrderFields("t", "p".repeat(MAX_POLICY_CODE_POINTS + 1), 1, CYCLE_CAP)).field,
        )
        assertEquals(
            RootOrderField.MAX_CYCLES,
            assertIs<RootOrderCheck.Invalid>(checkRootOrderFields("t", "p", CYCLE_CAP + 1, CYCLE_CAP)).field,
        )
        assertEquals(
            RootOrderField.MAX_CYCLES,
            assertIs<RootOrderCheck.Invalid>(checkRootOrderFields("t", "p", 0, CYCLE_CAP)).field,
        )
        assertEquals(RootOrderField.TITLE, assertIs<RootOrderCheck.Invalid>(checkRootOrderFields("  ", "p", 1, CYCLE_CAP)).field)
    }

    private val summaryKeys = setOf(
        "order_id", "parent_order_id", "depth", "state", "state_reason", "cycles_started", "max_cycles", "current_goal_id",
        "consecutive_failed_cycles", "child_count", "order_budget_jpy", "spent_jpy", "expires_at", "version", "created_at", "updated_at", "project_id",
    )
}
