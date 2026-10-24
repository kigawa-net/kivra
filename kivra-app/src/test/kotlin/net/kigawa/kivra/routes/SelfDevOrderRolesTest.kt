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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.ROLE_PERMISSIONS
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.AssigneeStoreLookup
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val AGENT_HEADER = "X-Test-Agent"
private const val ROOT_APPROVER = "1:i:kigawa"
private const val AI_PRINCIPAL = "1:i:ai"
private const val OTHER_OPERATOR = "1:i:other"
private const val SEED = "9:seed:seed"
private const val REQUEST_ID = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The order routes behind [PermissionGuard] (ADR 0020, PR 3a): the roles off (today's rules, checked against an oracle of the old
 * rules), shadow, and enforce, on H2 with the real repositories.
 */
class SelfDevOrderRolesTest {
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

    private class FakeLog : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    private class MutableTime(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun service(mode: PermissionMode, lookup: AssigneeLookup?, default: RolesDefault = RolesDefault.OPERATOR) =
        PermissionService(mode, default, AI_PRINCIPAL, ROOT_APPROVER, lookup)

    private fun ApplicationTestBuilder.setUp(db: TestDb, guard: PermissionGuard? = null) {
        val autopilot = AutopilotSettings.fromEnv(autopilotEnv(), SelfDevSettings.fromEnv(selfDevEnv()))
        val orderSettings = OrderSettings.fromEnv(orderEnv(), autopilot, AI_PRINCIPAL)
        val orders = SelfDevOrderRepository(db.database)
        val base = SelfDevOrderRouteDeps(
            operators = StubAuth,
            autopilot = autopilot,
            orderSettings = orderSettings,
            aiPrincipal = AI_PRINCIPAL,
            targetRepo = "kigawa-net/kivra",
            orders = orders,
            goals = SelfDevGoalRepository(db.database),
            operations = SelfDevOperatorRepository(db.database),
        )
        val deps = if (guard == null) base else SelfDevOrderRouteDeps(
            base.operators, base.autopilot, base.orderSettings, base.aiPrincipal, base.targetRepo, base.orders, base.goals, base.operations, guard,
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

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.rootId(by: String = ROOT_APPROVER): String =
        Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/orders", null, by).bodyAsText()).jsonArray.single()
            .jsonObject.getValue("orderId").jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.version(id: String, by: String = ROOT_APPROVER): String =
        send(HttpMethod.Get, "/api/selfdev/orders/$id", null, by).json().getValue("order").jsonObject.getValue("version").jsonPrimitive.content

    /** The thirteen routes of the order API with the permission each needs (ADR 0020), for an order [id]. */
    private class Route(val name: String, val method: HttpMethod, val path: String, val body: String?, val permission: Permission, val humanOnly: Boolean = false)

    private fun routes(id: String, version: String): List<Route> = listOf(
        Route("list", HttpMethod.Get, "/api/selfdev/orders", null, Permission.READ),
        Route("create", HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", Permission.ORDER_MANAGE),
        Route("detail", HttpMethod.Get, "/api/selfdev/orders/$id", null, Permission.READ),
        Route("cycles-read", HttpMethod.Get, "/api/selfdev/orders/$id/cycles", null, Permission.READ),
        Route("cycle-start", HttpMethod.Post, "/api/selfdev/orders/$id/cycles", """{"version":$version}""", Permission.ORDER_MANAGE),
        Route("child", HttpMethod.Post, "/api/selfdev/orders/$id/children", """{"title":"c","policy":"p","orderBudgetJpy":1000,"idempotencyKey":"k"}""", Permission.ORDER_MANAGE),
        Route("pause", HttpMethod.Post, "/api/selfdev/orders/$id/pause", """{"version":$version}""", Permission.ORDER_MANAGE),
        Route("resume", HttpMethod.Post, "/api/selfdev/orders/$id/resume", """{"version":$version}""", Permission.ORDER_MANAGE),
        Route("stop-request", HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":$version,"reasonCode":"OPERATOR_REQUEST"}""", Permission.STOP_REQUEST),
        Route("stop-list", HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null, Permission.STOP_DECIDE),
        Route("approve", HttpMethod.Post, "/api/selfdev/stop-requests/$REQUEST_ID/approve", """{"digest":"${"a".repeat(64)}"}""", Permission.STOP_DECIDE),
        Route("reject", HttpMethod.Post, "/api/selfdev/stop-requests/$REQUEST_ID/reject", "{}", Permission.STOP_DECIDE),
        Route("cancel", HttpMethod.Post, "/api/selfdev/stop-requests/$REQUEST_ID/cancel", "{}", Permission.STOP_REQUEST, humanOnly = true),
    )

    /** The old rules, as they were before the guard: the AI reads and requests, the root approver decides, an operator does the rest. */
    private fun oldRuleAllows(principal: String, route: Route): Boolean = when (route.permission) {
        Permission.READ -> true
        Permission.STOP_REQUEST -> !(route.humanOnly && principal == AI_PRINCIPAL)
        Permission.STOP_DECIDE -> principal == ROOT_APPROVER
        else -> principal != AI_PRINCIPAL
    }

    // ---- (a) off: today's rules ---------------------------------------------------------------------------------------------

    @Test
    fun `off - all 13 routes answer as the old rules for the AI, the root approver and another operator`() = testApplication {
        val db = newDb()
        setUp(db)
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""")
        val id = rootId()
        for (principal in listOf(AI_PRINCIPAL, ROOT_APPROVER, OTHER_OPERATOR)) {
            for (route in routes(id, version(id))) {
                val status = send(route.method, route.path, route.body, principal).status
                assertEquals(!oldRuleAllows(principal, route), status == HttpStatusCode.Forbidden, "${route.name} $principal -> $status")
            }
        }
    }

    @Test
    fun `off - no token is 401 and an agent token is 403 on all 13 routes, before any permission check`() = testApplication {
        val db = newDb()
        setUp(db)
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""")
        val id = rootId()
        for (route in routes(id, "0")) {
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
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""")
        val id = rootId()
        for (route in routes(id, version(id))) send(route.method, route.path, route.body, OTHER_OPERATOR)
    }

    @Test
    fun `off - the digest is shown to the root approver only, as before`() = testApplication {
        val db = newDb()
        setUp(db)
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""")
        val id = rootId()
        val requested = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":${version(id)},"reasonCode":"OPERATOR_REQUEST"}""", OTHER_OPERATOR)
        assertEquals(JsonNull, requested.json().getValue("digest"))
        val detailForOther = send(HttpMethod.Get, "/api/selfdev/orders/$id", null, OTHER_OPERATOR).json()
        assertEquals(JsonNull, detailForOther.getValue("pendingStopRequest").jsonObject.getValue("digest"))
        val detailForRoot = send(HttpMethod.Get, "/api/selfdev/orders/$id", null, ROOT_APPROVER).json()
        assertEquals(64, detailForRoot.getValue("pendingStopRequest").jsonObject.getValue("digest").jsonPrimitive.content.length)
    }

    // ---- (c) enforce: the role table ---------------------------------------------------------------------------------------

    private fun bound(db: TestDb, role: AssigneeRole, key: String, state: AssigneeState = AssigneeState.ACTIVE): SelfDevAssigneeRepository {
        val repo = SelfDevAssigneeRepository(db.database)
        val made = assertIs<AssigneeResult.Moved<*>>(repo.create("n-$key", role, SEED)).value as net.kigawa.kivra.db.Assignee
        assertIs<AssigneeResult.Moved<*>>(repo.bindMember(made.assigneeId, key, SEED))
        if (state == AssigneeState.DISABLED) assertIs<AssigneeResult.Moved<*>>(repo.setState(made.assigneeId, state, made.version, SEED))
        return repo
    }

    private fun roleOf(r: AssigneeRole) = Role.valueOf(r.name)

    private fun enforceGuard(db: TestDb, default: RolesDefault = RolesDefault.OPERATOR, log: GuardLog = FakeLog()) =
        PermissionGuard(service(PermissionMode.ENFORCE, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database)), default), log)

    @Test
    fun `enforce - each role passes exactly the routes its permissions allow`() = testApplication {
        val db = newDb()
        AssigneeRole.entries.forEach { bound(db, it, "1:i:role-${it.name}") }
        setUp(db, enforceGuard(db))
        // the root exists (made by an ADMIN) so that every route has a real order
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", "1:i:role-ADMIN").status)
        val id = rootId()
        for (r in AssigneeRole.entries) {
            for (route in routes(id, version(id))) {
                val allowed = route.permission in ROLE_PERMISSIONS.getValue(roleOf(r)) && !(route.humanOnly && r == AssigneeRole.AI)
                val status = send(route.method, route.path, route.body, "1:i:role-${r.name}").status
                assertEquals(!allowed, status == HttpStatusCode.Forbidden, "$r ${route.name} -> $status")
            }
        }
    }

    @Test
    fun `enforce - the table's headline cases`() = testApplication {
        val db = newDb()
        AssigneeRole.entries.forEach { bound(db, it, "1:i:role-${it.name}") }
        setUp(db, enforceGuard(db))
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", "1:i:role-ADMIN")
        val id = rootId()
        suspend fun status(r: String, name: String) =
            routes(id, version(id)).single { it.name == name }.let { rt -> send(rt.method, rt.path, rt.body, "1:i:role-$r").status }
        // ADMIN decides a stop too; APPROVER only decides; AI reads and requests only; VIEWER reads only
        assertTrue(status("ADMIN", "stop-list") != HttpStatusCode.Forbidden)
        assertTrue(status("APPROVER", "stop-list") != HttpStatusCode.Forbidden)
        assertEquals(HttpStatusCode.Forbidden, status("APPROVER", "pause"))
        assertEquals(HttpStatusCode.Forbidden, status("OPERATOR", "stop-list"))
        assertEquals(HttpStatusCode.Forbidden, status("AI", "create"))
        assertTrue(status("AI", "list") != HttpStatusCode.Forbidden)
        assertTrue(status("AI", "stop-request") != HttpStatusCode.Forbidden)
        assertTrue(status("VIEWER", "detail") != HttpStatusCode.Forbidden)
        assertEquals(HttpStatusCode.Forbidden, status("VIEWER", "stop-request"))
    }

    @Test
    fun `enforce - an unbound operator gets the default, NONE refuses everything`() = testApplication {
        val db = newDb()
        setUp(db, enforceGuard(db))
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", OTHER_OPERATOR).status)
        val id = rootId()
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/api/selfdev/stop-requests?state=PENDING", null, OTHER_OPERATOR).status)
        assertTrue(send(HttpMethod.Get, "/api/selfdev/orders/$id", null, OTHER_OPERATOR).status.value < 400)
    }

    @Test
    fun `enforce - default NONE and a disabled assignee are refused on all 13 routes`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.ADMIN, "1:i:was-admin", AssigneeState.ACTIVE)
        bound(db, AssigneeRole.OPERATOR, "1:i:off-op", AssigneeState.DISABLED)
        setUp(db, enforceGuard(db, RolesDefault.NONE))
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", "1:i:was-admin")
        val id = rootId("1:i:was-admin")
        val v = version(id, "1:i:was-admin")
        for (key in listOf("1:i:never-bound", "1:i:off-op")) {
            for (route in routes(id, v)) {
                assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, key).status, "$key ${route.name}")
            }
        }
    }

    @Test
    fun `enforce - the env AI wins over the store, and the actor kind follows the role`() = testApplication {
        val db = newDb()
        // the env AI is an ADMIN in the store; another key is an AI in the store; a third is an OPERATOR
        bound(db, AssigneeRole.ADMIN, AI_PRINCIPAL)
        bound(db, AssigneeRole.AI, "1:i:db-ai")
        bound(db, AssigneeRole.OPERATOR, "1:i:op")
        bound(db, AssigneeRole.ADMIN, "1:i:adm")
        setUp(db, enforceGuard(db))
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", "1:i:adm")
        val id = rootId()
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/orders/$id/pause", """{"version":${version(id)}}""", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/stop-requests/$REQUEST_ID/approve", """{"digest":"${"a".repeat(64)}"}""", AI_PRINCIPAL).status)
        // the stop request needs a fresh state each time: an ADMIN cancels what the last call made
        suspend fun kind(key: String): String {
            val r = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":${version(id)},"reasonCode":"OPERATOR_REQUEST"}""", key)
            assertEquals(HttpStatusCode.Created, r.status, key)
            val body = r.json()
            send(HttpMethod.Post, "/api/selfdev/stop-requests/${body.getValue("requestId").jsonPrimitive.content}/cancel", "{}", "1:i:adm")
            if (key != "1:i:adm") assertEquals(JsonNull, body.getValue("digest"), "$key sees no digest")
            return body.getValue("requesterKind").jsonPrimitive.content
        }
        assertEquals("AI", kind(AI_PRINCIPAL))
        assertEquals("AI", kind("1:i:db-ai"))
        assertEquals("HUMAN", kind("1:i:op"))
        assertEquals("HUMAN", kind("1:i:adm"))
    }

    // ---- (d) cancel: the requester's condition ------------------------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.pendingRequestBy(requester: String): String {
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", "1:i:adm")
        val id = rootId()
        val r = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":${version(id)},"reasonCode":"OPERATOR_REQUEST"}""", requester)
        assertEquals(HttpStatusCode.Created, r.status)
        return r.json().getValue("requestId").jsonPrimitive.content
    }

    private fun cancelDb(): TestDb = newDb().also {
        bound(it, AssigneeRole.ADMIN, "1:i:adm")
        bound(it, AssigneeRole.OPERATOR, "1:i:op-a")
        bound(it, AssigneeRole.OPERATOR, "1:i:op-b")
        bound(it, AssigneeRole.APPROVER, "1:i:appr")
        bound(it, AssigneeRole.VIEWER, "1:i:view")
        bound(it, AssigneeRole.AI, "1:i:db-ai")
    }

    @Test
    fun `enforce cancel - the requester may, another operator, a viewer and the AI may not`() = testApplication {
        val db = cancelDb()
        setUp(db, enforceGuard(db))
        val request = pendingRequestBy("1:i:op-a")
        val path = "/api/selfdev/stop-requests/$request/cancel"
        for (other in listOf("1:i:op-b", "1:i:view", "1:i:db-ai", AI_PRINCIPAL)) {
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, path, "{}", other).status, other)
        }
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, path, "{}", "1:i:op-a").status)
    }

    private suspend fun ApplicationTestBuilder.deciderMayCancel(decider: String) {
        val request = pendingRequestBy("1:i:op-a")
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, "/api/selfdev/stop-requests/$request/cancel", "{}", decider).status, decider)
    }

    @Test
    fun `enforce cancel - an approver may cancel another's request`() = testApplication {
        val db = cancelDb()
        setUp(db, enforceGuard(db))
        deciderMayCancel("1:i:appr")
    }

    @Test
    fun `enforce cancel - an admin may cancel another's request`() = testApplication {
        val db = cancelDb()
        setUp(db, enforceGuard(db))
        deciderMayCancel("1:i:adm")
    }

    @Test
    fun `off cancel - the requester or the root approver may, another operator and the AI may not`() = testApplication {
        val db = newDb()
        setUp(db)
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", OTHER_OPERATOR)
        val id = rootId()
        val request = send(HttpMethod.Post, "/api/selfdev/orders/$id/stop-requests", """{"version":${version(id)},"reasonCode":"OPERATOR_REQUEST"}""", OTHER_OPERATOR)
            .json().getValue("requestId").jsonPrimitive.content
        val path = "/api/selfdev/stop-requests/$request/cancel"
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, path, "{}", "1:i:third").status)
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, path, "{}", AI_PRINCIPAL).status)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Post, path, "{}", ROOT_APPROVER).status)
    }

    // ---- (e) the store fails -------------------------------------------------------------------------------------------------

    @Test
    fun `enforce - a failing store refuses every human on all 13 routes, and the env AI keeps only its own permissions`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.ADMIN, "1:i:adm")
        val failing = object : AssigneeLookup {
            var down = false
            val real = AssigneeStoreLookup(SelfDevAssigneeRepository(db.database))
            override fun accessOf(ownerKey: String) = if (down) throw IllegalStateException("db down") else real.accessOf(ownerKey)
        }
        setUp(db, PermissionGuard(service(PermissionMode.ENFORCE, failing), FakeLog()))
        send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", "1:i:adm")
        val id = rootId()
        val v = version(id)
        failing.down = true
        for (route in routes(id, v)) {
            assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, "1:i:adm").status, "adm ${route.name}")
            assertEquals(HttpStatusCode.Forbidden, send(route.method, route.path, route.body, ROOT_APPROVER).status, "root ${route.name}")
            val aiAllowed = route.permission in ROLE_PERMISSIONS.getValue(Role.AI) && !route.humanOnly
            assertEquals(!aiAllowed, send(route.method, route.path, route.body, AI_PRINCIPAL).status == HttpStatusCode.Forbidden, "ai ${route.name}")
        }
    }

    // ---- (b) shadow ----------------------------------------------------------------------------------------------------------

    @Test
    fun `shadow - acts on the old rules, and logs would-deny with fixed fields and no owner key`() = testApplication {
        val db = newDb()
        // an OPERATOR by the old rules is a VIEWER in the store: enforce would refuse every write
        bound(db, AssigneeRole.VIEWER, OTHER_OPERATOR)
        val log = FakeLog()
        val time = MutableTime()
        setUp(db, PermissionGuard(service(PermissionMode.SHADOW, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database))), log, time))
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", OTHER_OPERATOR).status)
        val id = rootId()
        for (route in routes(id, version(id))) {
            for (principal in listOf(AI_PRINCIPAL, ROOT_APPROVER, OTHER_OPERATOR)) {
                val status = send(route.method, route.path, route.body, principal).status
                assertEquals(!oldRuleAllows(principal, route), status == HttpStatusCode.Forbidden, "${route.name} $principal -> $status")
            }
        }
        val denies = log.lines.filter { it.startsWith("permission shadow: wouldDeny") }
        assertTrue(denies.any { it.contains("permission=ORDER_MANAGE code=PERMISSION_DENIED source=ASSIGNEE role=VIEWER route=order.create via=rest") }, denies.toString())
        assertTrue(denies.any { it.contains("permission=STOP_REQUEST") && it.contains("route=order.stop.request") })
        val everything = log.lines.joinToString("\n")
        for (secret in listOf(OTHER_OPERATOR, ROOT_APPROVER, AI_PRINCIPAL, "n-$OTHER_OPERATOR")) assertFalse(everything.contains(secret), secret)
        // the same (permission, code, source, route) is logged once in the window
        assertEquals(denies.size, denies.toSet().size)
    }

    @Test
    fun `shadow - a repeat inside the window is counted and reported at the next log`() = testApplication {
        val db = newDb()
        bound(db, AssigneeRole.VIEWER, OTHER_OPERATOR)
        val log = FakeLog()
        val time = MutableTime()
        setUp(db, PermissionGuard(service(PermissionMode.SHADOW, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database))), log, time))
        suspend fun create() = send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", OTHER_OPERATOR)
        create(); create(); create()
        fun count() = log.lines.count { it.contains("permission=ORDER_MANAGE") }
        assertEquals(1, count())
        time.now += 61_000
        create()
        assertEquals(2, count())
        assertTrue(log.lines.last { it.contains("permission=ORDER_MANAGE") }.endsWith("suppressed=2"))
    }

    @Test
    fun `shadow - a failing store is a warn and a would-deny, and the request goes on by the old rules`() = testApplication {
        val db = newDb()
        val log = FakeLog()
        val failing = object : AssigneeLookup {
            override fun accessOf(ownerKey: String) = throw IllegalStateException("db down: $ownerKey")
        }
        setUp(db, PermissionGuard(service(PermissionMode.SHADOW, failing), log, MutableTime()))
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/orders", """{"title":"t","policy":"p"}""", OTHER_OPERATOR).status)
        val text = log.lines.joinToString("\n")
        assertTrue(text.contains("permission anomaly: source=LOOKUP_FAILED mode=SHADOW via=rest"))
        assertTrue(text.contains("code=NO_ROLE source=LOOKUP_FAILED"))
        assertFalse(text.contains(OTHER_OPERATOR))
    }

    // ---- the settings the routes are built from -------------------------------------------------------------------------------

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
