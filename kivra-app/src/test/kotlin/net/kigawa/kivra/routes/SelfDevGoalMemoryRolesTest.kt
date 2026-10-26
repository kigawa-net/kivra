package net.kigawa.kivra.routes

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.ROLE_PERMISSIONS
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.AssigneeStoreLookup
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import org.slf4j.LoggerFactory

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val AGENT_HEADER = "X-Test-Agent"
private const val ROOT_APPROVER = "1:i:kigawa"
private const val AI_PRINCIPAL = "1:i:ai"
private const val OTHER_OPERATOR = "1:i:other"
private const val SEED = "9:seed:seed"
private const val ULID = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The Goal and memory routes behind [PermissionGuard] (ADR 0020, PR 3b): the roles off (today's rules, checked against an oracle of the old
 * rules), shadow, and enforce, on H2 with the real repositories. A status other than 403 means the request passed the guard (the route then
 * answers 400, 404 or 200 for the made-up ids and bodies used here).
 */
class SelfDevGoalMemoryRolesTest {
    private val openDbs = mutableListOf<TestDb>()

    private fun newDb(): TestDb = TestDb().also { openDbs += it }

    @AfterTest
    fun closeDbs() {
        openDbs.forEach { it.close() }
        openDbs.clear()
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: Headers): OperatorAuth = when {
            headers[AGENT_HEADER] != null -> OperatorAuth.AgentRefused
            headers[PRINCIPAL_HEADER]?.isNotBlank() == true -> OperatorAuth.Authenticated(headers[PRINCIPAL_HEADER]!!)
            else -> OperatorAuth.Unauthenticated
        }
    }

    private class FakeLog : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    private fun service(mode: PermissionMode, lookup: AssigneeLookup?, default: RolesDefault = RolesDefault.OPERATOR) =
        PermissionService(mode, default, AI_PRINCIPAL, ROOT_APPROVER, lookup)

    /** The default guard of the deps is the roles off, so [guard] null is the path every old test takes. */
    private fun ApplicationTestBuilder.setUp(db: TestDb, guard: PermissionGuard? = null, memoryOn: Boolean = true) {
        val autopilot = AutopilotSettings.fromEnv(autopilotEnv(), SelfDevSettings.fromEnv(selfDevEnv()))
        val memory = if (memoryOn) SelfDevMemoryRepository(db.database, enabled = true) else null
        val goalDeps = if (guard == null) {
            goalDeps(db, autopilot)
        } else {
            goalDeps(db, autopilot, guard)
        }
        val memoryDeps = if (guard == null) {
            SelfDevMemoryRouteDeps(operators = StubAuth, memory = memory, aiPrincipal = AI_PRINCIPAL)
        } else {
            SelfDevMemoryRouteDeps(operators = StubAuth, memory = memory, aiPrincipal = AI_PRINCIPAL, guard = guard)
        }
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureSelfDevGoalRoutes(goalDeps)
                configureSelfDevMemoryRoutes(memoryDeps)
            }
        }
    }

    private fun goalDeps(db: TestDb, autopilot: AutopilotSettings, guard: PermissionGuard? = null): SelfDevGoalRouteDeps {
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        val operations = SelfDevOperatorRepository(db.database)
        val control = SelfDevAutopilotControlRepository(db.database)
        return if (guard == null) {
            SelfDevGoalRouteDeps(StubAuth, autopilot, "kigawa-net/kivra", goals, plans, operations, control, aiPrincipal = AI_PRINCIPAL)
        } else {
            SelfDevGoalRouteDeps(StubAuth, autopilot, "kigawa-net/kivra", goals, plans, operations, control, aiPrincipal = AI_PRINCIPAL, guard = guard)
        }
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String?,
        principal: String? = ROOT_APPROVER,
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

    private class Route(val name: String, val method: HttpMethod, val path: String, val body: String?, val permission: Permission)

    private val routes: List<Route> = listOf(
        Route("autopilot", HttpMethod.Get, "/api/selfdev/autopilot", null, Permission.READ),
        Route("promotion", HttpMethod.Get, "/api/selfdev/autopilot/promotion", null, Permission.READ),
        Route("pause", HttpMethod.Post, "/api/selfdev/autopilot/pause", "{}", Permission.ORDER_MANAGE),
        Route("resume", HttpMethod.Post, "/api/selfdev/autopilot/resume", "{}", Permission.ORDER_MANAGE),
        Route("merge-pause", HttpMethod.Post, "/api/selfdev/autopilot/auto-merge/pause", "{}", Permission.ORDER_MANAGE),
        Route("merge-resume", HttpMethod.Post, "/api/selfdev/autopilot/auto-merge/resume", "{}", Permission.ORDER_MANAGE),
        Route("reset-breaker", HttpMethod.Post, "/api/selfdev/autopilot/reset-breaker", "{}", Permission.ORDER_MANAGE),
        Route("goal-submit", HttpMethod.Post, "/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}""", Permission.GOAL_WRITE),
        Route("goal-list", HttpMethod.Get, "/api/selfdev/goals", null, Permission.READ),
        Route("goal-detail", HttpMethod.Get, "/api/selfdev/goals/$ULID", null, Permission.READ),
        Route("approve-plan", HttpMethod.Post, "/api/selfdev/goals/$ULID/approve-plan", """{"version":0}""", Permission.GOAL_WRITE),
        Route("replan", HttpMethod.Post, "/api/selfdev/goals/$ULID/replan", """{"version":0}""", Permission.GOAL_WRITE),
        Route("goal-stop", HttpMethod.Post, "/api/selfdev/goals/$ULID/stop", """{"version":0,"reason":"r"}""", Permission.GOAL_STOP),
        Route("task-approve", HttpMethod.Post, "/api/selfdev/tasks/$ULID-1/approve", """{"version":0}""", Permission.GOAL_WRITE),
        Route("task-reject", HttpMethod.Post, "/api/selfdev/tasks/$ULID-1/reject", """{"version":0}""", Permission.GOAL_WRITE),
        Route("memory-list", HttpMethod.Get, "/api/selfdev/memory", null, Permission.READ_TEXT),
        Route("memory-delete", HttpMethod.Post, "/api/selfdev/memory/$ULID/delete", """{"reason":"r"}""", Permission.GOAL_WRITE),
        Route("memory-condition", HttpMethod.Post, "/api/selfdev/memory/delete-by-condition", """{"dryRun":true}""", Permission.GOAL_WRITE),
    )

    /** The old rules: every read is open to the AI except the memory's list; every write is refused to the AI; an operator does anything. */
    private fun oldRuleAllows(principal: String, route: Route): Boolean =
        if (principal != AI_PRINCIPAL) true else route.permission == Permission.READ

    /** The Goal routes answer exactly `{"error":"forbidden"}`; the memory routes' error type has always carried a null `matched` too. */
    private fun assertForbiddenBody(body: String, route: String) {
        val expected = if (route.startsWith("memory")) """{"error":"forbidden","matched":null}""" else """{"error":"forbidden"}"""
        assertEquals(expected, body, route)
    }

    private fun roleOf(r: AssigneeRole) = Role.valueOf(r.name)

    private fun bound(db: TestDb, role: AssigneeRole, key: String, state: AssigneeState = AssigneeState.ACTIVE): SelfDevAssigneeRepository {
        val repo = SelfDevAssigneeRepository(db.database)
        val made = assertIs<AssigneeResult.Moved<*>>(repo.create("n-$key", role, SEED)).value as Assignee
        if (role == AssigneeRole.AI_APPROVER) {
            net.kigawa.kivra.db.bindToAiApproverForTest(db, made.assigneeId, key)
        } else {
            assertIs<AssigneeResult.Moved<*>>(repo.bindMember(made.assigneeId, key, SEED))
        }
        if (state == AssigneeState.DISABLED) assertIs<AssigneeResult.Moved<*>>(repo.setState(made.assigneeId, state, made.version, SEED))
        return repo
    }

    private fun enforceGuard(db: TestDb, default: RolesDefault = RolesDefault.OPERATOR, log: GuardLog = FakeLog()) =
        PermissionGuard(service(PermissionMode.ENFORCE, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database)), default), log)

    private class Captured(val logger: Logger, val appender: AppenderBase<ILoggingEvent>, val lines: MutableList<String>, val oldLevel: Level?)

    private fun capture(name: String): Captured {
        val logger = LoggerFactory.getLogger(name) as Logger
        val lines = CopyOnWriteArrayList<String>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(e: ILoggingEvent) { lines += e.formattedMessage }
        }.also { it.context = logger.loggerContext; it.start() }
        val old = logger.level
        logger.level = Level.INFO
        logger.addAppender(appender)
        return Captured(logger, appender, lines, old)
    }

    private fun Captured.close() {
        logger.detachAppender(appender)
        logger.level = oldLevel
    }

    // ---- (a) off: today's rules ---------------------------------------------------------------------------------------------

    @Test
    fun `off - every route answers as the old rules for the AI, the root approver and another operator`() = testApplication {
        val db = newDb()
        setUp(db)
        for (principal in listOf(AI_PRINCIPAL, ROOT_APPROVER, OTHER_OPERATOR)) {
            for (route in routes) {
                val response = send(route.method, route.path, route.body, principal)
                assertEquals(!oldRuleAllows(principal, route), response.status == HttpStatusCode.Forbidden, "${route.name} $principal -> ${response.status}")
                if (response.status == HttpStatusCode.Forbidden) assertForbiddenBody(response.bodyAsText(), route.name)
            }
        }
    }

    @Test
    fun `off - no token is 401 and an agent token is 403 on every route, before any permission check`() = testApplication {
        val db = newDb()
        setUp(db)
        for (route in routes) {
            assertEquals(HttpStatusCode.Unauthorized, send(route.method, route.path, route.body, principal = null).status, route.name)
            assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, principal = null, agent = true).status, route.name)
        }
    }

    @Test
    fun `off - the store is never read`() = testApplication {
        val db = newDb()
        val boom = object : AssigneeLookup {
            override fun accessOf(ownerKey: String) = throw AssertionError("the store must not be read while the roles are off")
        }
        setUp(db, PermissionGuard(service(PermissionMode.OFF, boom)))
        for (route in routes) send(route.method, route.path, route.body, OTHER_OPERATOR)
    }

    @Test
    fun `off - the AI refusal keeps its log line, and a refused request is logged with fixed fields only`() = testApplication {
        val db = newDb()
        setUp(db)
        val goal = capture("net.kigawa.kivra.selfdev.autopilot.operator")
        val memory = capture("net.kigawa.kivra.selfdev.memory.operator")
        try {
            send(HttpMethod.Post, "/api/selfdev/autopilot/pause", "{}", AI_PRINCIPAL)
            send(HttpMethod.Post, "/api/selfdev/goals/$ULID/stop", """{"version":0,"reason":"r"}""", AI_PRINCIPAL)
            send(HttpMethod.Get, "/api/selfdev/memory", null, AI_PRINCIPAL)
            assertEquals(
                listOf(
                    "autopilot operator refused: action=AUTOPILOT_PAUSE actor=AI result=forbidden",
                    "autopilot operator refused: action=GOAL_STOP actor=AI result=forbidden",
                ),
                goal.lines.toList(),
            )
            assertEquals(listOf("selfdev memory operator refused: action=MEMORY_LIST actor=AI result=forbidden"), memory.lines.toList())
        } finally {
            goal.close()
            memory.close()
        }
    }

    // ---- (b) shadow ----------------------------------------------------------------------------------------------------------

    @Test
    fun `shadow - acts on the old rules, and logs would-deny with fixed fields and no owner key`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.VIEWER, "1:i:viewer")
        val log = FakeLog()
        setUp(db, PermissionGuard(service(PermissionMode.SHADOW, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database))), log, { 1_000_000L }))
        // a viewer is refused by the table (not by today's rules): the request goes on
        for (route in routes) {
            val status = send(route.method, route.path, route.body, "1:i:viewer").status
            assertFalse(status == HttpStatusCode.Forbidden, "${route.name} acts on the old rules, not the table")
        }
        // the AI is still refused by today's rules
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/memory", null, AI_PRINCIPAL).status)
        val denies = log.lines.filter { it.startsWith("permission shadow: wouldDeny") }
        for (p in listOf("ORDER_MANAGE", "GOAL_WRITE", "GOAL_STOP", "READ_TEXT")) {
            assertTrue(denies.any { it.contains("permission=$p code=PERMISSION_DENIED") }, "wouldDeny $p")
        }
        assertTrue(denies.none { it.contains("permission=READ ") })
        assertTrue(denies.any { it.contains("route=goal.goal_stop") })
        val everything = log.lines.joinToString("\n")
        for (secret in listOf("1:i:viewer", AI_PRINCIPAL, ROOT_APPROVER, "n-1:i:viewer", ULID)) assertFalse(everything.contains(secret), secret)
    }

    // ---- (c) enforce: the role table ---------------------------------------------------------------------------------------

    @Test
    fun `enforce - each of the six roles passes exactly the routes its permissions allow, on all routes`() = testApplication {
        val db = newDb()
        AssigneeRole.entries.forEach { bound(db, it, "1:i:role-${it.name}") }
        setUp(db, enforceGuard(db))
        for (r in AssigneeRole.entries) {
            for (route in routes) {
                val allowed = route.permission in ROLE_PERMISSIONS.getValue(roleOf(r))
                val response = send(route.method, route.path, route.body, "1:i:role-${r.name}")
                assertEquals(!allowed, response.status == HttpStatusCode.Forbidden, "$r ${route.name} -> ${response.status}")
                if (!allowed) assertForbiddenBody(response.bodyAsText(), route.name)
            }
        }
    }

    @Test
    fun `enforce - the table's headline cases`() = testApplication {
        val db = newDb()
        AssigneeRole.entries.forEach { bound(db, it, "1:i:role-${it.name}") }
        setUp(db, enforceGuard(db))
        suspend fun status(role: String, name: String) =
            routes.single { it.name == name }.let { send(it.method, it.path, it.body, "1:i:role-$role").status }
        for (name in listOf("goal-submit", "goal-stop", "pause", "memory-list", "memory-delete")) {
            assertTrue(status("ADMIN", name) != HttpStatusCode.Forbidden, "ADMIN $name")
            assertTrue(status("OPERATOR", name) != HttpStatusCode.Forbidden, "OPERATOR $name")
            for (denied in listOf("APPROVER", "AI", "VIEWER")) assertEquals(HttpStatusCode.Forbidden, status(denied, name), "$denied $name")
        }
        for (denied in listOf("APPROVER", "AI", "VIEWER")) assertTrue(status(denied, "goal-list") != HttpStatusCode.Forbidden, "$denied reads")
    }

    @Test
    fun `enforce - an unbound operator gets the default, NONE and a disabled assignee are refused on every route`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.OPERATOR, "1:i:off-op", AssigneeState.DISABLED)
        setUp(db, enforceGuard(db, RolesDefault.OPERATOR))
        assertTrue(send(HttpMethod.Get, "/api/selfdev/memory", null, "1:i:never-bound").status != HttpStatusCode.Forbidden)
        for (route in routes) assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, "1:i:off-op").status, "disabled ${route.name}")
    }

    @Test
    fun `enforce - default NONE refuses an unbound operator on every route`() = testApplication {
        val db = newDb()
        setUp(db, enforceGuard(db, RolesDefault.NONE))
        for (route in routes) assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, "1:i:never-bound").status, route.name)
    }

    @Test
    fun `enforce - the env AI wins over the store, and another key that is an AI in the store is an AI too`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.ADMIN, AI_PRINCIPAL)
        bound(db, AssigneeRole.AI, "1:i:db-ai")
        setUp(db, enforceGuard(db))
        for (key in listOf(AI_PRINCIPAL, "1:i:db-ai")) {
            for (route in routes) {
                val allowed = route.permission in ROLE_PERMISSIONS.getValue(Role.AI)
                assertEquals(!allowed, send(route.method, route.path, route.body, key).status == HttpStatusCode.Forbidden, "$key ${route.name}")
            }
        }
    }

    @Test
    fun `enforce - a failing store refuses every human, and the env AI keeps only its own permissions`() = testApplication {
        val db = newDb()
        val failing = object : AssigneeLookup {
            override fun accessOf(ownerKey: String) = throw IllegalStateException("db down")
        }
        setUp(db, PermissionGuard(service(PermissionMode.ENFORCE, failing), FakeLog()))
        for (route in routes) {
            assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, OTHER_OPERATOR).status, "other ${route.name}")
            assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, ROOT_APPROVER).status, "root ${route.name}")
            val aiAllowed = route.permission in ROLE_PERMISSIONS.getValue(Role.AI)
            assertEquals(!aiAllowed, send(route.method, route.path, route.body, AI_PRINCIPAL).status == HttpStatusCode.Forbidden, "ai ${route.name}")
        }
    }

    @Test
    fun `enforce - the order of the checks is the old one, 401 then 503 then the permission`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.VIEWER, "1:i:viewer")
        setUp(db, enforceGuard(db), memoryOn = false)
        // no memory repository: a viewer refused by the table still sees the 503 first, as an AI saw it before
        assertEquals(HttpStatusCode.ServiceUnavailable, send(HttpMethod.Get, "/api/selfdev/memory", null, "1:i:viewer").status)
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/memory", null, null).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/goals", """{"title":"t","idempotencyKey":"k"}""", "1:i:viewer").status)
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

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
}
