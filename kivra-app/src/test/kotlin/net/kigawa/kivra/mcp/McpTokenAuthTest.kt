package net.kigawa.kivra.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeState
import net.kigawa.kivra.db.AssigneeStoreLookup
import net.kigawa.kivra.db.IssuedToken
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevAssigneeTokenRepository
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.TokenResult
import net.kigawa.kivra.db.TokenVerification
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.PermissionGuard
import net.kigawa.kivra.routes.configureRunViewRoutes
import net.kigawa.kivra.routes.configureTaskApiRoutes
import net.kigawa.kivra.selfdev.SelfDevSettings
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private const val AI_PRINCIPAL = "1:i:ai"
private const val ROOT_APPROVER = "1:i:kigawa"
private const val ADMIN_KEY = "5:oidc:alice"
private const val NO_SUCH = "00000000000000000000000001"
private const val UNAUTHORIZED_BODY = """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"invalid or missing token"}}"""

/**
 * The MCP entrance for an assignee's API token (ADR 0020 addendum, PR T2), over HTTP on H2 with the real repositories and the production
 * wiring ([buildMcpRouteDeps]): off (parity), enforce, shadow, the invalid cases, the failure budget and the log.
 */
class McpTokenAuthTest {
    private class Tool(val name: String, val args: String)

    private val reason = StopRequestReason.REQUESTABLE.first().name
    private val tools = listOf(
        Tool("kivra_run_list", "{}"),
        Tool("kivra_run_get", """{"run_id":"$NO_SUCH"}"""),
        Tool("kivra_order_list", "{}"),
        Tool("kivra_order_get", """{"order_id":"$NO_SUCH"}"""),
        Tool("kivra_goal_list", "{}"),
        Tool("kivra_goal_get", """{"goal_id":"$NO_SUCH"}"""),
        Tool("kivra_goal_get_titles", """{"goal_id":"$NO_SUCH"}"""),
        Tool("kivra_selfdev_start", """{"title":"a title","idempotency_key":"k-start"}"""),
        Tool("kivra_order_request_stop", """{"order_id":"$NO_SUCH","version":0,"reason":"$reason"}"""),
        Tool("kivra_goal_stop", """{"goal_id":"$NO_SUCH","version":0,"reason":"$reason"}"""),
        Tool("kivra_goal_submit", """{"title":"a title","idempotency_key":"k-submit"}"""),
    )
    private val aiAllowed = setOf(
        "kivra_run_list", "kivra_run_get", "kivra_order_list", "kivra_order_get", "kivra_goal_list", "kivra_goal_get",
        "kivra_selfdev_start", "kivra_order_request_stop",
    )
    private val viewerAllowed = setOf("kivra_run_list", "kivra_run_get", "kivra_order_list", "kivra_order_get", "kivra_goal_list", "kivra_goal_get")

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
        McpSettings.ENV_RATE_PER_MINUTE to "1000",
        McpSettings.ENV_GOAL_READ_ENABLED to "true",
        McpSettings.ENV_GOAL_STOP_ENABLED to "true",
        McpSettings.ENV_GOAL_SUBMIT_ENABLED to "true",
        McpSettings.ENV_GOAL_TEXT_ENABLED to "true",
    )

    private fun deps(db: TestDb, mode: PermissionMode, tokenAuth: McpTokenAuth?): McpRouteDeps {
        val e = env()
        val guard = PermissionGuard(
            PermissionService(mode, RolesDefault.OPERATOR, AI_PRINCIPAL, ROOT_APPROVER, AssigneeStoreLookup(SelfDevAssigneeRepository(db.database))),
        )
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, RepoRef("kigawa-net", "kivra"),
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 5,
            orders = McpOrderDeps(SelfDevOrderRepository(db.database), AI_PRINCIPAL),
            goals = McpGoalDeps(
                SelfDevOperatorRepository(db.database), SelfDevPlanRepository(db.database), db.runs, AI_PRINCIPAL, SelfDevMemoryRecorder.OFF,
                submit = McpGoalSubmitDeps(SelfDevGoalRepository(db.database), "kigawa-net/kivra", 100),
            ),
            guard = guard, tokenAuth = tokenAuth,
        )!!
    }

    private fun productionAuth(db: TestDb, mode: PermissionMode) = McpTokenAuth.forDatabase(db.database, mode)

    private fun ApplicationTestBuilder.setUp(d: McpRouteDeps) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureMcpRoutes(d) }
        }
    }

    private val assignees = HashMap<TestDb, SelfDevAssigneeRepository>()
    private fun repo(db: TestDb) = assignees.getOrPut(db) { SelfDevAssigneeRepository(db.database) }
    private fun tokenRepo(db: TestDb) = SelfDevAssigneeTokenRepository(db.database)

    private fun assignee(db: TestDb, role: AssigneeRole, name: String = "robot"): Assignee =
        assertIs<AssigneeResult.Moved<Assignee>>(repo(db).create(name, role, ADMIN_KEY)).value

    private fun issue(db: TestDb, a: Assignee): IssuedToken =
        assertIs<TokenResult.Ok<IssuedToken>>(tokenRepo(db).issue(a.assigneeId, 90, ADMIN_KEY)).value

    private suspend fun ApplicationTestBuilder.rpc(body: String, token: String): HttpResponse = client.post("/mcp") {
        header(HttpHeaders.Host, McpTestTokens.HOST)
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun callBody(tool: Tool) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"${tool.name}","arguments":${tool.args}}}"""

    private val listBody = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Whether the tools/call answer is the permission refusal `forbidden` (HTTP 200) or the missing scope (HTTP 403). */
    private suspend fun HttpResponse.refused(): Boolean {
        if (status == HttpStatusCode.Forbidden) return true
        if (status != HttpStatusCode.OK) return false
        val result = json()["result"]?.jsonObject ?: return false
        return result["structuredContent"]?.jsonObject?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content == "forbidden"
    }

    private suspend fun ApplicationTestBuilder.allowedTools(token: String): Set<String> {
        val out = mutableSetOf<String>()
        for (tool in tools) {
            val r = rpc(callBody(tool), token)
            if (!r.refused()) {
                assertEquals(HttpStatusCode.OK, r.status, tool.name)
                out += tool.name
            }
        }
        return out
    }

    private fun capture(vararg names: String?, block: () -> Unit): List<String> {
        val loggers = names.map { LoggerFactory.getLogger(it ?: org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger }
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        loggers.forEach { it.addAppender(appender) }
        try {
            block()
        } finally {
            loggers.forEach { it.detachAppender(appender) }
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- (a) off: parity ------------------------------------------------------------------------------------------------

    @Test
    fun `off - a kvt Bearer is the same 401 as before (status, body, challenge), even for a valid token`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.AI)).token
        val d = deps(db, PermissionMode.OFF, tokenAuth = null)
        val metadata = checkNotNull(d.settings.resourceMetadataUrl)
        testApplication {
            setUp(d)
            val lines = capture(null) {
                kotlinx.coroutines.runBlocking {
                    for (t in listOf(token, "kvt_" + "A".repeat(43), "kvt_short")) {
                        val r = rpc(listBody, t)
                        assertEquals(HttpStatusCode.Unauthorized, r.status)
                        assertEquals(UNAUTHORIZED_BODY, r.bodyAsText())
                        assertEquals("Bearer resource_metadata=\"$metadata\"", r.headers[HttpHeaders.WWWAuthenticate])
                    }
                }
            }
            // The log is what the JWT path wrote for a Bearer that is not JWT-shaped, nothing from the token entrance.
            assertTrue(lines.none { it.contains("MCP token") }, lines.toString())
            assertTrue(lines.any { it.contains("reason=NoBearerToken") }, lines.toString())
        }
    }

    @Test
    fun `off - the JWT path is unchanged and the token entrance does not exist`() = TestDb().use { db ->
        val d = deps(db, PermissionMode.OFF, tokenAuth = null)
        testApplication {
            setUp(d)
            assertEquals(HttpStatusCode.OK, rpc(listBody, McpTestTokens.token()).status)
            assertEquals(HttpStatusCode.Unauthorized, rpc(listBody, "a.b.c").status)
        }
        assertFailsWith<IllegalArgumentException> { McpTokenAuth({ TokenVerification.Invalid }, PermissionMode.OFF) }
    }

    // ---- (b) enforce -------------------------------------------------------------------------------------------------------

    @Test
    fun `enforce - an AI token lists by scope and passes the read, stop request and start tools only`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.AI)).token
        testApplication {
            setUp(deps(db, PermissionMode.ENFORCE, productionAuth(db, PermissionMode.ENFORCE)))
            val list = rpc(listBody, token)
            assertEquals(HttpStatusCode.OK, list.status)
            val names = list.json()["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(tools.map { it.name }.toSet(), names)
            assertEquals(aiAllowed, allowedTools(token))
        }
    }

    @Test
    fun `enforce - a VIEWER token holds the read tools only and does not list the write scopes`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.VIEWER)).token
        testApplication {
            setUp(deps(db, PermissionMode.ENFORCE, productionAuth(db, PermissionMode.ENFORCE)))
            val names = rpc(listBody, token).json()["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertTrue(names.none { it in setOf("kivra_selfdev_start", "kivra_order_request_stop", "kivra_goal_stop", "kivra_goal_submit") }, names.toString())
            assertEquals(viewerAllowed, allowedTools(token))
            // Without the scope, the call is the insufficient_scope 403, not the permission refusal.
            val start = rpc(callBody(tools.first { it.name == "kivra_selfdev_start" }), token)
            assertEquals(HttpStatusCode.Forbidden, start.status)
        }
    }

    @Test
    fun `the scopes of a role are one constant, and only AI and VIEWER have one`() {
        assertEquals(setOf(SCOPE_RUNS_READ, SCOPE_SELFDEV_STOP, SCOPE_SELFDEV_START), TOKEN_ROLE_SCOPES.getValue(AssigneeRole.AI))
        assertEquals(setOf(SCOPE_RUNS_READ), TOKEN_ROLE_SCOPES.getValue(AssigneeRole.VIEWER))
        assertEquals(setOf(AssigneeRole.AI, AssigneeRole.VIEWER), TOKEN_ROLE_SCOPES.keys)
    }

    // ---- (c)(e) invalid tokens ----------------------------------------------------------------------------------------------

    private suspend fun HttpResponse.assertUnauthorized(metadata: String) {
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(UNAUTHORIZED_BODY, bodyAsText())
        assertEquals("Bearer resource_metadata=\"$metadata\"", headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun `enforce - every invalid token is the same 401, and revoke, disable and role change act on the next request`() = TestDb().use { db ->
        val d = deps(db, PermissionMode.ENFORCE, productionAuth(db, PermissionMode.ENFORCE))
        val metadata = checkNotNull(d.settings.resourceMetadataUrl)
        val tokens = tokenRepo(db)
        val revokedA = assignee(db, AssigneeRole.AI, "revoked")
        val revoked = issue(db, revokedA)
        val expiredA = assignee(db, AssigneeRole.AI, "expired")
        val expired = issue(db, expiredA)
        transaction(db.database) { exec("UPDATE selfdev_assignee_tokens SET created_at = '1999-01-01 00:00:00', expires_at = '2000-01-01 00:00:00' WHERE assignee_id = '${expiredA.assigneeId}'") }
        val disabledA = assignee(db, AssigneeRole.AI, "disabled")
        val disabled = issue(db, disabledA)
        val roleA = assignee(db, AssigneeRole.AI, "rolechange")
        val roleChanged = issue(db, roleA)
        val liveA = assignee(db, AssigneeRole.AI, "live")
        val live = issue(db, liveA)
        testApplication {
            setUp(d)
            // Valid first, so the later 401 is a change of state and not a bad token.
            for (t in listOf(revoked, disabled, roleChanged, live)) assertEquals(HttpStatusCode.OK, rpc(listBody, t.token).status)
            assertIs<TokenResult.Ok<Unit>>(tokens.revoke(revoked.tokenRef, ADMIN_KEY))
            val version = repo(db).find(disabledA.assigneeId)!!.version
            assertIs<AssigneeResult.Moved<*>>(repo(db).setState(disabledA.assigneeId, AssigneeState.DISABLED, version, ADMIN_KEY))
            val roleVersion = repo(db).find(roleA.assigneeId)!!.version
            assertIs<AssigneeResult.Moved<*>>(repo(db).changeRole(roleA.assigneeId, AssigneeRole.ADMIN, roleVersion, ADMIN_KEY))
            val bad = listOf(
                revoked.token, expired.token, disabled.token, roleChanged.token,
                "kvt_" + "A".repeat(43), "kvt_short", "kvt_", "kvt_" + "A".repeat(44) + "!", "kvt_" + "é".repeat(43),
            )
            for (t in bad) rpc(listBody, t).assertUnauthorized(metadata)
            assertEquals(HttpStatusCode.OK, rpc(listBody, live.token).status)
        }
    }

    // ---- (d) the failure budget -----------------------------------------------------------------------------------------------

    @Test
    fun `the budget - after 30 failures even a valid token is 429 without a database read, and the next window recovers`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.AI)).token
        val repoForVerify = tokenRepo(db)
        val reads = AtomicInteger()
        var now = 1_000_000L
        val logs = mutableListOf<String>()
        val log = McpTokenLog({ now }, 60_000L) { logs += it }
        val auth = McpTokenAuth({ reads.incrementAndGet(); repoForVerify.verify(it) }, PermissionMode.ENFORCE, log, 30, 60_000L) { now }
        testApplication {
            setUp(deps(db, PermissionMode.ENFORCE, auth))
            // A success is not counted.
            repeat(5) { assertEquals(HttpStatusCode.OK, rpc(listBody, token).status) }
            assertEquals(5, reads.get())
            repeat(30) { assertEquals(HttpStatusCode.Unauthorized, rpc(listBody, "kvt_short").status) }
            assertEquals(35, reads.get())
            now += 10_000
            val limited = rpc(listBody, token)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("50", limited.headers[HttpHeaders.RetryAfter])
            assertEquals(-32029, limited.json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
            repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, rpc(listBody, token).status) }
            assertEquals(35, reads.get(), "no read while the budget is used up")
            // A JWT is not touched by the budget.
            assertEquals(HttpStatusCode.OK, rpc(listBody, McpTestTokens.token()).status)
            assertEquals(1, logs.count { it.contains("code=TOKEN_FAILURE_BUDGET") }, logs.toString())
            now += 50_000
            assertEquals(HttpStatusCode.OK, rpc(listBody, token).status)
            assertEquals(36, reads.get())
        }
    }

    @Test
    fun `the budget - 29 failures do not limit, and the counter has no key`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.AI)).token
        var now = 0L
        val repoForVerify = tokenRepo(db)
        val auth = McpTokenAuth({ repoForVerify.verify(it) }, PermissionMode.ENFORCE, McpTokenLog({ now }, 60_000L) {}, 30, 60_000L) { now }
        testApplication {
            setUp(deps(db, PermissionMode.ENFORCE, auth))
            repeat(29) { i -> assertEquals(HttpStatusCode.Unauthorized, rpc(listBody, "kvt_" + "A".repeat(42) + (i % 10)).status) }
            assertEquals(HttpStatusCode.OK, rpc(listBody, token).status)
        }
        // One counter: the class holds no collection.
        assertTrue(McpTokenAuth::class.java.declaredFields.none { Map::class.java.isAssignableFrom(it.type) || Collection::class.java.isAssignableFrom(it.type) })
    }

    // ---- (f) a JWT is the old path ---------------------------------------------------------------------------------------------

    @Test
    fun `enforce - a JWT Bearer goes the old way, and its principal is a legacy admin`() = TestDb().use { db ->
        testApplication {
            setUp(deps(db, PermissionMode.ENFORCE, productionAuth(db, PermissionMode.ENFORCE)))
            assertEquals(HttpStatusCode.OK, rpc(listBody, McpTestTokens.token()).status)
            assertEquals(HttpStatusCode.Unauthorized, rpc(listBody, "a.b.c").status)
            val r = rpc(callBody(tools.first { it.name == "kivra_goal_stop" }), McpTestTokens.token(scope = "kivra.runs.read kivra.selfdev.stop kivra.selfdev.start"))
            assertTrue(!r.refused(), "an operator JWT keeps its default access")
        }
    }

    // ---- (g) shadow --------------------------------------------------------------------------------------------------------------

    @Test
    fun `shadow - a valid token is authenticated but every tool is refused, and a fixed line is recorded`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.AI)).token
        testApplication {
            setUp(deps(db, PermissionMode.SHADOW, productionAuth(db, PermissionMode.SHADOW)))
            val lines = capture("net.kigawa.kivra.mcp.token") {
                kotlinx.coroutines.runBlocking {
                    assertEquals(HttpStatusCode.OK, rpc(listBody, token).status)
                    assertEquals(emptySet(), allowedTools(token))
                }
            }
            assertEquals(1, lines.count { it.contains("code=TOKEN_SHADOW_DENY") }, lines.toString())
        }
    }

    // ---- (h) the log ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `no log line contains a token, its hash or ref, an owner key or the issuer`() = TestDb().use { db ->
        val a = assignee(db, AssigneeRole.AI, "Secret Display Name")
        val issued = issue(db, a)
        val owner = SelfDevAssigneeTokenRepository.ownerKeyOf(a.assigneeId)
        val badToken = "kvt_" + "Z".repeat(43)
        testApplication {
            setUp(deps(db, PermissionMode.ENFORCE, productionAuth(db, PermissionMode.ENFORCE)))
            val lines = capture(null) {
                kotlinx.coroutines.runBlocking {
                    allowedTools(issued.token)
                    repeat(3) { rpc(listBody, badToken) }
                    rpc(listBody, "kvt_short")
                    tokenRepo(db).revoke(issued.tokenRef, ADMIN_KEY)
                    rpc(listBody, issued.token)
                }
            }
            assertTrue(lines.any { it.contains("code=TOKEN_UNKNOWN") }, lines.toString())
            val joined = lines.joinToString("\n")
            for (secret in listOf(issued.token, issued.token.removePrefix("kvt_"), badToken, sha256(issued.token), sha256(badToken), issued.tokenRef, owner, "kivra-assignee", "Secret Display Name")) {
                assertTrue(!joined.contains(secret), "a log line contains a secret: ${secret.take(6)}")
            }
        }
    }

    // ---- (i) REST does not accept the token ------------------------------------------------------------------------------------------------

    @Test
    fun `REST routes do not take a kvt Bearer`() = TestDb().use { db ->
        val token = issue(db, assignee(db, AssigneeRole.AI)).token
        val identity = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks()).asIdentity()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    configureTaskApiRoutes(db.runs, identity, null)
                    configureRunViewRoutes(db.runs, identity, SelfDevSettings.disabled())
                }
            }
            for (path in listOf("/api/selfdev/status", "/api/admin/runs", "/api/runs")) {
                val r = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
                assertEquals(HttpStatusCode.Unauthorized, r.status, path)
            }
            assertNotNull(token)
        }
    }
}
