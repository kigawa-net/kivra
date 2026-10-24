package net.kigawa.kivra.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.AssigneeStoreLookup
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.GuardLog
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.slf4j.LoggerFactory

private const val AI_PRINCIPAL = "1:i:ai"
private const val ROOT_APPROVER = "1:i:kigawa"
private const val OTHER_OPERATOR = "1:i:other"
private const val SEED = "9:seed:seed"
private const val NO_SUCH = "00000000000000000000000001"
private const val GOAL_LOG = "net.kigawa.kivra.selfdev.autopilot.operator"

/**
 * The permissions of the `/mcp` tools behind [PermissionGuard] (ADR 0020, PR 3d), at the dispatcher, through the production wiring
 * ([buildMcpRouteDeps]) on H2 with the real repositories: the roles off (today's rules, against an oracle of the old rules), shadow, and
 * enforce. A refusal by the permission check is the tool result `forbidden` (outcome `forbidden`); anything else reached the handler.
 */
class McpRolesTest {
    private class Tool(val name: String, val permission: Permission, val args: String, val action: String? = null)

    private val reason = StopRequestReason.REQUESTABLE.first().name
    private val tools = listOf(
        Tool("kivra_run_list", Permission.READ, "{}"),
        Tool("kivra_run_get", Permission.READ, """{"run_id":"$NO_SUCH"}"""),
        Tool("kivra_order_list", Permission.READ, "{}"),
        Tool("kivra_order_get", Permission.READ, """{"order_id":"$NO_SUCH"}"""),
        Tool("kivra_goal_list", Permission.READ, "{}"),
        Tool("kivra_goal_get", Permission.READ, """{"goal_id":"$NO_SUCH"}"""),
        Tool("kivra_goal_get_titles", Permission.READ_TEXT, """{"goal_id":"$NO_SUCH"}""", action = "GOAL_TITLES"),
        Tool("kivra_selfdev_start", Permission.SPEND_START, """{"title":"a title","idempotency_key":"k-start"}"""),
        Tool("kivra_order_request_stop", Permission.STOP_REQUEST, """{"order_id":"$NO_SUCH","version":0,"reason":"$reason"}"""),
        Tool("kivra_goal_stop", Permission.GOAL_STOP, """{"goal_id":"$NO_SUCH","version":0,"reason":"$reason"}""", action = "STOP"),
        Tool("kivra_goal_submit", Permission.GOAL_WRITE, """{"title":"a title","idempotency_key":"k-submit"}""", action = "SUBMIT"),
    )

    /** The three tools an AI has always been refused (the old rules, in the handlers). */
    private val aiRefused = setOf("kivra_goal_get_titles", "kivra_goal_stop", "kivra_goal_submit")

    private val allScopes = setOf(SCOPE_RUNS_READ, SCOPE_SELFDEV_START, SCOPE_SELFDEV_STOP)

    private fun env(): Map<String, String> = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
        McpSettings.ENV_ENABLED to "true",
        McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
        McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        McpSettings.ENV_ALLOWED_ORIGINS to McpTestTokens.ORIGIN,
        McpSettings.ENV_WRITE_ENABLED to "true",
        McpSettings.ENV_WRITE_RATE_PER_MINUTE to "1000",
        McpSettings.ENV_GOAL_READ_ENABLED to "true",
        McpSettings.ENV_GOAL_STOP_ENABLED to "true",
        McpSettings.ENV_GOAL_SUBMIT_ENABLED to "true",
        McpSettings.ENV_GOAL_TEXT_ENABLED to "true",
    )

    /** The production wiring as Application builds it. [guard] null is the path of every old test: the deps' AI principal only. */
    private fun deps(db: TestDb, guard: PermissionGuard? = null, writeRate: Int = 1000): McpRouteDeps {
        val e = env() + (McpSettings.ENV_WRITE_RATE_PER_MINUTE to writeRate.toString())
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, RepoRef("kigawa-net", "kivra"),
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 5,
            orders = McpOrderDeps(SelfDevOrderRepository(db.database), AI_PRINCIPAL),
            goals = McpGoalDeps(
                SelfDevOperatorRepository(db.database), SelfDevPlanRepository(db.database), db.runs, AI_PRINCIPAL, SelfDevMemoryRecorder.OFF,
                submit = McpGoalSubmitDeps(SelfDevGoalRepository(db.database), "kigawa-net/kivra", 100),
            ),
            guard = guard,
        )!!
    }

    private fun message(tool: Tool, id: Int = 1): JsonObject = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"${tool.name}","arguments":${tool.args}}}""",
    ).jsonObject

    private fun call(d: McpRouteDeps, principal: String, tool: Tool, scopes: Set<String> = allScopes): McpDispatch =
        runBlocking { d.dispatcher.dispatch(message(tool), McpCaller(principal, scopes)) }

    private fun denied(d: McpDispatch) = d.outcome == "forbidden"

    private fun resultOf(d: McpDispatch) = d.response!!["result"]!!.jsonObject

    private class FakeLog : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    private fun service(mode: PermissionMode, lookup: AssigneeLookup?, default: RolesDefault = RolesDefault.OPERATOR) =
        PermissionService(mode, default, AI_PRINCIPAL, ROOT_APPROVER, lookup)

    private fun guard(mode: PermissionMode, lookup: AssigneeLookup?, default: RolesDefault = RolesDefault.OPERATOR, log: GuardLog = FakeLog()) =
        PermissionGuard(service(mode, lookup, default), log)

    private fun storeGuard(db: TestDb, mode: PermissionMode = PermissionMode.ENFORCE, default: RolesDefault = RolesDefault.OPERATOR, log: GuardLog = FakeLog()) =
        guard(mode, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database)), default, log)

    private fun bind(db: TestDb, role: AssigneeRole, key: String, state: AssigneeState = AssigneeState.ACTIVE) {
        val repo = SelfDevAssigneeRepository(db.database)
        val made = assertIs<AssigneeResult.Moved<*>>(repo.create("n-$key", role, SEED)).value as net.kigawa.kivra.db.Assignee
        assertIs<AssigneeResult.Moved<*>>(repo.bindMember(made.assigneeId, key, SEED))
        if (state == AssigneeState.DISABLED) assertIs<AssigneeResult.Moved<*>>(repo.setState(made.assigneeId, state, made.version, SEED))
    }

    /** The messages of the log lines written while [block] runs, by logger name (null: every logger). */
    private fun capture(name: String? = null, block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(name ?: org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun withDb(block: (TestDb) -> Unit) = TestDb().use(block)

    // ---- (a) off: the old rules ----------------------------------------------------------------------------------------

    @Test
    fun `off - all 11 tools answer as the old rules for the AI, the root approver and another operator`() = withDb { db ->
        val d = deps(db)
        for (principal in listOf(AI_PRINCIPAL, ROOT_APPROVER, OTHER_OPERATOR)) {
            for (tool in tools) {
                val expectDenied = principal == AI_PRINCIPAL && tool.name in aiRefused
                val result = call(d, principal, tool)
                assertEquals(expectDenied, denied(result), "$principal ${tool.name}")
                if (expectDenied) {
                    val r = resultOf(result)
                    assertEquals(true, r["isError"]!!.jsonPrimitive.content.toBoolean())
                    assertEquals("forbidden", r["structuredContent"]!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                    assertEquals("forbidden", r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
                    assertEquals(null, result.requiredScope)
                }
            }
        }
    }

    @Test
    fun `off - the AI refusal is the same log line as before, and nothing is logged for an allowed call`() = withDb { db ->
        val d = deps(db)
        for (tool in tools.filter { it.name in aiRefused }) {
            val lines = capture(GOAL_LOG) { call(d, AI_PRINCIPAL, tool) }
            assertEquals(listOf("autopilot operator refused: action=${tool.action} via=mcp actor=AI result=forbidden"), lines, tool.name)
        }
        val others = capture(GOAL_LOG) {
            for (tool in tools.filter { it.name !in aiRefused && it.name.startsWith("kivra_goal") }) call(d, AI_PRINCIPAL, tool)
        }
        assertTrue(others.none { it.contains("refused") }, others.toString())
    }

    @Test
    fun `off - the AI is refused before the input is read, another operator reaches the input check`() = withDb { db ->
        val d = deps(db)
        for (name in aiRefused) {
            val bad = Tool(name, Permission.READ, "{}")
            assertTrue(denied(call(d, AI_PRINCIPAL, bad)), name)
            val bypass = Tool(name, Permission.READ, """{"unknown":1}""")
            assertTrue(denied(call(d, AI_PRINCIPAL, bypass)), name)
            // The same input from an operator is an invalid params error (-32602), as it has always been.
            val r = call(d, OTHER_OPERATOR, bad)
            assertEquals("invalid_params", r.outcome, name)
        }
    }

    @Test
    fun `off - the check comes after the scope, the arguments shape and the write rate limit, as the handlers' refusal did`() = withDb { db ->
        val d = deps(db, writeRate = 1)
        val stop = tools.first { it.name == "kivra_goal_stop" }
        // The scope first: insufficient_scope, with the scope that is missing, never the refusal.
        val noScope = call(d, AI_PRINCIPAL, stop, scopes = setOf(SCOPE_RUNS_READ))
        assertEquals("insufficient_scope", noScope.outcome)
        assertEquals(SCOPE_SELFDEV_STOP, noScope.requiredScope)
        // The first write call of the AI is the refusal; the second is limited, because the refusal used the allowance (as before).
        assertTrue(denied(call(d, AI_PRINCIPAL, stop)))
        val limited = call(d, AI_PRINCIPAL, stop)
        assertEquals("rate_limited", limited.outcome)
        assertTrue(limited.rateLimited)
    }

    @Test
    fun `off - tools list is cut by the scope only`() = withDb { db ->
        val d = deps(db)
        fun names(principal: String, scopes: Set<String>): Set<String> {
            val m = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").jsonObject
            val r = runBlocking { d.dispatcher.dispatch(m, McpCaller(principal, scopes)) }
            return resultOf(r)["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        }
        assertEquals(tools.map { it.name }.toSet(), names(AI_PRINCIPAL, allScopes))
        assertEquals(names(OTHER_OPERATOR, allScopes), names(AI_PRINCIPAL, allScopes))
        assertTrue(names(OTHER_OPERATOR, setOf(SCOPE_RUNS_READ)).none { it in setOf("kivra_goal_stop", "kivra_goal_submit", "kivra_selfdev_start") })
    }

    @Test
    fun `off - the store is never read`() = withDb { db ->
        val boom = object : AssigneeLookup {
            override fun accessOf(ownerKey: String): AssigneeAccess? = throw AssertionError("the store must not be read while the roles are off")
        }
        val d = deps(db, guard(PermissionMode.OFF, boom))
        for (principal in listOf(AI_PRINCIPAL, OTHER_OPERATOR)) for (tool in tools) call(d, principal, tool)
    }

    @Test
    fun `off - the order stop request is an AI request for the AI principal and a human one for everyone else`() = withDb { db ->
        val d = deps(db)
        val order = createRoot(db)
        val stop = Tool("kivra_order_request_stop", Permission.STOP_REQUEST, """{"order_id":"${order.orderId}","version":${order.version},"reason":"$reason"}""")
        val r = call(d, AI_PRINCIPAL, stop)
        assertEquals("STOP_REQUESTED", r.outcome)
        assertEquals("AI", requesterKind(r))
    }

    private fun createRoot(db: TestDb): SelfDevOrder =
        (SelfDevOrderRepository(db.database).createRoot(OTHER_OPERATOR, "t", "p", "kigawa-net/kivra") as CreateRootResult.Created).order

    private fun requesterKind(d: McpDispatch): String =
        resultOf(d)["structuredContent"]!!.jsonObject["requester_kind"]!!.jsonPrimitive.content

    private fun stopOf(order: SelfDevOrder) =
        Tool("kivra_order_request_stop", Permission.STOP_REQUEST, """{"order_id":"${order.orderId}","version":${order.version},"reason":"$reason"}""")

    @Test
    fun `off - a human operator's order stop request is a human request`() = withDb { db ->
        val order = createRoot(db)
        val r = call(deps(db), OTHER_OPERATOR, stopOf(order))
        assertEquals("STOP_REQUESTED", r.outcome)
        assertEquals("HUMAN", requesterKind(r))
    }

    // ---- (b) shadow ----------------------------------------------------------------------------------------------------

    @Test
    fun `shadow - acts on the old rules and records only fixed fields, with via mcp`() = withDb { db ->
        bind(db, AssigneeRole.VIEWER, OTHER_OPERATOR)
        val log = FakeLog()
        val d = deps(db, storeGuard(db, PermissionMode.SHADOW, log = log))
        // The old rules allow another operator everything: the stop is not refused, and the log says enforce would have.
        val stop = tools.first { it.name == "kivra_goal_stop" }
        assertFalse(denied(call(d, OTHER_OPERATOR, stop)))
        assertEquals(1, log.lines.size, log.lines.toString())
        val line = log.lines.single()
        assertEquals(
            "permission shadow: wouldDeny permission=GOAL_STOP code=PERMISSION_DENIED source=ASSIGNEE role=VIEWER route=kivra_goal_stop via=mcp suppressed=0",
            line,
        )
        for (secret in listOf(OTHER_OPERATOR, "other", "n-$OTHER_OPERATOR", NO_SUCH, "sub-")) assertFalse(line.contains(secret), secret)
        // The AI is refused by the old rules, as it was, and that is no would-deny line.
        val before = log.lines.size
        assertTrue(denied(call(d, AI_PRINCIPAL, stop)))
        assertEquals(before, log.lines.size)
    }

    @Test
    fun `shadow - a store failure is an anomaly line with via mcp, and the old rules still decide`() = withDb { db ->
        val log = FakeLog()
        val boom = object : AssigneeLookup {
            override fun accessOf(ownerKey: String): AssigneeAccess? = throw IllegalStateException("down")
        }
        val d = deps(db, guard(PermissionMode.SHADOW, boom, log = log))
        val titles = tools.first { it.name == "kivra_goal_get_titles" }
        assertFalse(denied(call(d, OTHER_OPERATOR, titles)))
        assertTrue(log.lines.any { it.startsWith("permission anomaly: source=LOOKUP_FAILED") && it.contains("via=mcp") }, log.lines.toString())
    }

    // ---- (c) enforce: the five roles -----------------------------------------------------------------------------------

    private val reads = setOf("kivra_run_list", "kivra_run_get", "kivra_order_list", "kivra_order_get", "kivra_goal_list", "kivra_goal_get")

    private val expectedAllowed: Map<Role, Set<String>> = mapOf(
        Role.ADMIN to tools.map { it.name }.toSet(),
        Role.OPERATOR to tools.map { it.name }.toSet(),
        Role.APPROVER to reads + "kivra_order_request_stop",
        Role.AI to reads + setOf("kivra_order_request_stop", "kivra_selfdev_start"),
        Role.VIEWER to reads,
    )

    @Test
    fun `enforce - 5 roles x 11 tools`() {
        for (role in Role.entries) {
            withDb { db ->
                val principal = if (role == Role.AI) AI_PRINCIPAL else "1:i:r-${role.name}"
                if (role != Role.AI) bind(db, AssigneeRole.valueOf(role.name), principal)
                val d = deps(db, storeGuard(db))
                for (tool in tools) {
                    val allowed = expectedAllowed.getValue(role)
                    assertEquals(tool.name !in allowed, denied(call(d, principal, tool)), "$role ${tool.name}")
                }
            }
        }
    }

    @Test
    fun `enforce - the scope comes first, then the role`() = withDb { db ->
        bind(db, AssigneeRole.VIEWER, OTHER_OPERATOR)
        val d = deps(db, storeGuard(db))
        val stop = tools.first { it.name == "kivra_goal_stop" }
        // A viewer without the scope: insufficient_scope (the JSON-RPC error), not the tool's refusal.
        val noScope = call(d, OTHER_OPERATOR, stop, scopes = setOf(SCOPE_RUNS_READ))
        assertEquals("insufficient_scope", noScope.outcome)
        assertEquals(SCOPE_SELFDEV_STOP, noScope.requiredScope)
        assertNotEquals(null, noScope.response!!["error"])
        // The same viewer with the scope: the tool's refusal.
        val withScope = call(d, OTHER_OPERATOR, stop)
        assertTrue(denied(withScope))
        assertEquals(null, withScope.requiredScope)
        assertEquals(null, withScope.response!!["error"])
    }

    @Test
    fun `enforce - tools list is not cut by the role`() = withDb { db ->
        bind(db, AssigneeRole.VIEWER, OTHER_OPERATOR)
        val d = deps(db, storeGuard(db))
        val m = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").jsonObject
        val r = runBlocking { d.dispatcher.dispatch(m, McpCaller(OTHER_OPERATOR, allScopes)) }
        assertEquals(tools.size, resultOf(r)["tools"]!!.jsonArray.size)
    }

    @Test
    fun `enforce - the refusal log names the action and a fixed code, never a person`() = withDb { db ->
        bind(db, AssigneeRole.VIEWER, OTHER_OPERATOR)
        val d = deps(db, storeGuard(db))
        val lines = capture(GOAL_LOG) { for (t in tools.filter { it.action != null }) call(d, OTHER_OPERATOR, t) }
        assertEquals(
            listOf("GOAL_TITLES", "SUBMIT", "STOP").sorted(),
            lines.map { Regex("action=(\\w+)").find(it)!!.groupValues[1] }.sorted(),
        )
        for (l in lines) {
            assertTrue(l.contains("via=mcp actor=OPERATOR result=PERMISSION_DENIED"), l)
            assertFalse(l.contains(OTHER_OPERATOR) || l.contains("other"), l)
        }
    }

    @Test
    fun `enforce - the order stop request kind follows the effective role, for the env AI and for a DB AI`() = withDb { db ->
        bind(db, AssigneeRole.AI, "1:i:dbai")
        bind(db, AssigneeRole.OPERATOR, OTHER_OPERATOR)
        val d = deps(db, storeGuard(db))
        val order = createRoot(db)
        // One pending request per tree, so each of the other callers asks on a fresh database below.
        val r = call(d, "1:i:dbai", stopOf(order))
        assertEquals("STOP_REQUESTED", r.outcome)
        assertEquals("AI", requesterKind(r))
        withDb { db2 ->
            bind(db2, AssigneeRole.OPERATOR, OTHER_OPERATOR)
            val d2 = deps(db2, storeGuard(db2))
            val r2 = call(d2, OTHER_OPERATOR, stopOf(createRoot(db2)))
            assertEquals("HUMAN", requesterKind(r2))
        }
        withDb { db3 ->
            val d3 = deps(db3, storeGuard(db3))
            val r3 = call(d3, AI_PRINCIPAL, stopOf(createRoot(db3)))
            assertEquals("AI", requesterKind(r3))
        }
    }

    // ---- (d) invalid assignee, env AI, store failure -------------------------------------------------------------------

    @Test
    fun `enforce - a disabled assignee, and an unbound one under default NONE, are refused every tool`() = withDb { db ->
        bind(db, AssigneeRole.OPERATOR, OTHER_OPERATOR, AssigneeState.DISABLED)
        val d = deps(db, storeGuard(db))
        for (tool in tools) assertTrue(denied(call(d, OTHER_OPERATOR, tool)), tool.name)
        val none = deps(db, storeGuard(db, default = RolesDefault.NONE))
        for (tool in tools) assertTrue(denied(call(none, "1:i:unbound", tool)), tool.name)
        // With the default OPERATOR the unbound operator keeps everything.
        for (tool in tools) assertFalse(denied(call(d, "1:i:unbound", tool)), tool.name)
    }

    @Test
    fun `enforce - the env AI wins over the database`() = withDb { db ->
        bind(db, AssigneeRole.OPERATOR, AI_PRINCIPAL)
        val d = deps(db, storeGuard(db))
        for (tool in tools) assertEquals(tool.name in aiRefused, denied(call(d, AI_PRINCIPAL, tool)), tool.name)
    }

    @Test
    fun `enforce - a failing store refuses every human, and the env AI keeps only its own permissions`() = withDb { db ->
        val boom = object : AssigneeLookup {
            override fun accessOf(ownerKey: String): AssigneeAccess? = throw IllegalStateException("down")
        }
        val d = deps(db, guard(PermissionMode.ENFORCE, boom))
        for (tool in tools) assertTrue(denied(call(d, OTHER_OPERATOR, tool)), tool.name)
        for (tool in tools) assertEquals(tool.name in aiRefused, denied(call(d, AI_PRINCIPAL, tool)), tool.name)
    }
}
