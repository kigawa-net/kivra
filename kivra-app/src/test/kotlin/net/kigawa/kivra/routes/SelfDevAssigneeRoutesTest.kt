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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeStoreLookup
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevAssigneeTokenRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.TokenVerification
import net.kigawa.kivra.mcp.McpRateLimiter
import org.slf4j.LoggerFactory

private const val PRINCIPAL_HEADER = "X-Test-Principal"
private const val AGENT_HEADER = "X-Test-Agent"
private const val ISSUER = "https://kc.example/realms/test"
private const val SEED = "9:seed:seed"
private const val ROOT_APPROVER = "1:i:root"
private const val ENV_AI = "1:i:ai"
private const val ADMIN = "1:i:admin"
private const val OPERATOR = "1:i:oper"
private const val APPROVER = "1:i:appr"
private const val VIEWER = "1:i:view"
private const val DB_AI = "1:i:dbai"
private const val UNBOUND = "1:i:nobody"
private const val NAME_MARK = "SecretDisplayName-4711"
private const val SUB_MARK = "secret-sub-4711"
private const val ULID = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The assignee admin API and `/me` (ADR 0020 addendum, PR T3), on H2 with the real repositories: the roles off (503, nothing read),
 * shadow (judged by the effective access) and enforce, the AI refused, the fixed codes, the token shown once, and what never appears
 * in a response, a log line or a table.
 */
class SelfDevAssigneeRoutesTest {
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

    private fun guardOf(mode: PermissionMode, lookup: AssigneeLookup?) =
        PermissionGuard(PermissionService(mode, RolesDefault.OPERATOR, ENV_AI, ROOT_APPROVER, lookup))

    /** An ADMIN, an OPERATOR, an APPROVER, a VIEWER and an AI assignee, each bound to its key. */
    private fun seed(db: TestDb): SelfDevAssigneeRepository {
        val repo = SelfDevAssigneeRepository(db.database)
        for ((role, key) in listOf(
            AssigneeRole.ADMIN to ADMIN, AssigneeRole.OPERATOR to OPERATOR, AssigneeRole.APPROVER to APPROVER,
            AssigneeRole.VIEWER to VIEWER, AssigneeRole.AI to DB_AI,
        )) {
            val made = assertIs<AssigneeResult.Moved<*>>(repo.create("n-${role.name}", role, SEED)).value as Assignee
            assertIs<AssigneeResult.Moved<*>>(repo.bindMember(made.assigneeId, key, SEED))
        }
        return repo
    }

    private fun ApplicationTestBuilder.setUp(
        db: TestDb,
        mode: PermissionMode,
        issuer: String? = ISSUER,
        limiter: McpRateLimiter = McpRateLimiter(1_000),
        lookup: AssigneeLookup? = null,
    ) {
        val repo = if (mode == PermissionMode.OFF) null else SelfDevAssigneeRepository(db.database)
        val tokens = if (mode == PermissionMode.OFF) null else SelfDevAssigneeTokenRepository(db.database)
        val guard = guardOf(mode, lookup ?: repo?.let { AssigneeStoreLookup(it) })
        val deps = SelfDevAssigneeRouteDeps(StubAuth, guard, repo, tokens, issuer, limiter)
        application {
            install(ContentNegotiation) { json() }
            routing { configureSelfDevAssigneeRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.send(
        method: HttpMethod,
        path: String,
        body: String? = "{}",
        principal: String? = ADMIN,
        agent: Boolean = false,
        extra: Map<String, String> = emptyMap(),
        json: Boolean = true,
    ): HttpResponse = client.request(path) {
        this.method = method
        principal?.let { header(PRINCIPAL_HEADER, it) }
        if (agent) header(AGENT_HEADER, "1")
        extra.forEach { (k, v) -> header(k, v) }
        if (body != null) {
            if (json) contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.create(role: String, name: String = "n", by: String = ADMIN): JsonObject {
        val r = send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"$name","role":"$role"}""", by)
        assertEquals(HttpStatusCode.Created, r.status)
        return r.obj()
    }

    private class Captured(val appender: ListAppender<ILoggingEvent>, val logger: Logger, val level: Level?) {
        fun text() = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
    }

    private fun capture(): Captured {
        // Only kivra's own loggers: the JDBC driver, Hikari, Exposed and Flyway log parameter values at TRACE/DEBUG, which is not what is tested.
        val logger = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
        val c = Captured(ListAppender<ILoggingEvent>().apply { start() }, logger, logger.level)
        logger.level = Level.ALL
        logger.addAppender(c.appender)
        return c
    }

    private fun release(c: Captured) {
        c.logger.detachAppender(c.appender)
        c.logger.level = c.level
    }

    /** Every cell of the assignee tables, as one text. */
    private fun dump(db: TestDb): String = db.dataSource.connection.use { conn ->
        listOf("selfdev_assignees", "selfdev_assignee_members", "selfdev_assignee_events", "selfdev_assignee_tokens").joinToString("\n") { table ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM $table").use { rs ->
                    buildString {
                        while (rs.next()) for (i in 1..rs.metaData.columnCount) append(rs.getString(i)).append('|')
                    }
                }
            }
        }
    }

    /** The routes of the admin API with a body that passes the request checks, for an assignee [id] and a token ref. */
    private class R(val method: HttpMethod, val path: String, val body: String?)

    private fun adminRoutes(id: String = ULID, ref: String = "0123456789abcdef") = listOf(
        R(HttpMethod.Get, "/api/selfdev/assignees", null),
        R(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"x","role":"AI"}"""),
        R(HttpMethod.Get, "/api/selfdev/assignees/$id/members", null),
        R(HttpMethod.Post, "/api/selfdev/assignees/$id/role", """{"role":"VIEWER","version":0}"""),
        R(HttpMethod.Post, "/api/selfdev/assignees/$id/disable", """{"version":0}"""),
        R(HttpMethod.Post, "/api/selfdev/assignees/$id/enable", """{"version":0}"""),
        R(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"abc"}"""),
        R(HttpMethod.Delete, "/api/selfdev/assignees/$id/members/$ref", "{}"),
        R(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "{}"),
        R(HttpMethod.Get, "/api/selfdev/assignees/$id/tokens", null),
        R(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens/$ref/revoke", "{}"),
    )

    // ---- (a) off ------------------------------------------------------------------------------------------------------------

    @Test
    fun `off - every admin route is 503 ROLES_DISABLED, even for the operator, and the store is never read`() = testApplication {
        val db = newDb()
        val boom = object : AssigneeLookup {
            override fun accessOf(ownerKey: String) = throw AssertionError("the store must not be read while the roles are off")
        }
        setUp(db, PermissionMode.OFF, lookup = boom)
        for (r in adminRoutes()) {
            val res = send(r.method, r.path, r.body, ADMIN)
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status, r.path)
            assertEquals("ROLES_DISABLED", res.obj().str("code"), r.path)
        }
        // authentication still comes first
        for (r in adminRoutes()) {
            assertEquals(HttpStatusCode.Unauthorized, send(r.method, r.path, r.body, principal = null).status, r.path)
            assertEquals(HttpStatusCode.Forbidden, send(r.method, r.path, r.body, principal = null, agent = true).status, r.path)
        }
    }

    @Test
    fun `off - me is 200 with mode off and today's rules`() = testApplication {
        val db = newDb()
        setUp(db, PermissionMode.OFF)
        val operator = send(HttpMethod.Get, "/api/selfdev/me", null, OPERATOR).obj()
        assertEquals("off", operator.str("mode"))
        assertEquals("OPERATOR", operator.str("role"))
        assertEquals("LEGACY_DEFAULT", operator.str("source"))
        assertTrue(operator.getValue("permissions").jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("READ", "ORDER_MANAGE")))
        assertFalse(operator.getValue("permissions").jsonArray.any { it.jsonPrimitive.content == "ASSIGNEE_MANAGE" })
        val ai = send(HttpMethod.Get, "/api/selfdev/me", null, ENV_AI).obj()
        assertEquals("AI", ai.str("role"))
        assertEquals("ENV_AI", ai.str("source"))
        assertEquals(HttpStatusCode.Unauthorized, send(HttpMethod.Get, "/api/selfdev/me", null, principal = null).status)
    }

    // ---- (b) enforce ----------------------------------------------------------------------------------------------------------

    @Test
    fun `enforce - an ADMIN creates, re-roles, disables, binds, issues, lists and revokes`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val made = create("AI", NAME_MARK)
        val id = made.str("assigneeId")
        assertEquals("AI", made.str("role"))
        assertEquals("ACTIVE", made.str("state"))
        assertEquals(0, made.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(NAME_MARK, made.str("displayName"))
        assertEquals(setOf("assigneeId", "displayName", "role", "state", "version", "memberCount", "activeTokenCount"), made.keys)

        val list = send(HttpMethod.Get, "/api/selfdev/assignees", null).bodyAsText()
        assertTrue(list.contains(id))
        assertFalse(list.contains(ADMIN))
        assertFalse(list.contains("owner"))

        // a token for the AI, then the counts
        val issued = send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", """{"ttlDays":30}""")
        assertEquals(HttpStatusCode.Created, issued.status)
        val tokenRef = issued.obj().str("tokenRef")
        val summary = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/assignees", null).bodyAsText())
        val mine = (summary as JsonArray).map { it.jsonObject }.single { it.str("assigneeId") == id }
        assertEquals(1, mine.getValue("activeTokenCount").jsonPrimitive.content.toInt())
        assertEquals(0, mine.getValue("memberCount").jsonPrimitive.content.toInt(), "the synthetic key is not a person")

        val tokens = Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/assignees/$id/tokens", null).bodyAsText()).jsonArray
        assertEquals(1, tokens.size)
        assertEquals("ACTIVE", tokens.single().jsonObject.str("status"))
        assertEquals(setOf("tokenRef", "status", "createdBy", "createdAt", "expiresAt"), tokens.single().jsonObject.keys)

        val revoked = send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens/$tokenRef/revoke")
        assertEquals(HttpStatusCode.NoContent, revoked.status)
        assertEquals("REVOKED", Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/assignees/$id/tokens", null).bodyAsText()).jsonArray.single().jsonObject.str("status"))
        assertEquals("ALREADY_REVOKED", send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens/$tokenRef/revoke").obj().str("code"))

        // bind a person, list the member refs, unbind
        val bound = send(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"person-1"}""")
        assertEquals(HttpStatusCode.Created, bound.status)
        val memberRef = bound.obj().str("memberRef")
        assertEquals(memberRef, Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/assignees/$id/members", null).bodyAsText()).jsonArray.single().jsonObject.str("memberRef"))
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/selfdev/assignees/$id/members/$memberRef", "").status)
        assertEquals("NOT_BOUND", send(HttpMethod.Delete, "/api/selfdev/assignees/$id/members/$memberRef", "").obj().str("code"))

        // role change and disable/enable, with the version
        val changed = send(HttpMethod.Post, "/api/selfdev/assignees/$id/role", """{"role":"VIEWER","version":0}""").obj()
        assertEquals("VIEWER", changed.str("role"))
        assertEquals(1, changed.getValue("version").jsonPrimitive.content.toInt())
        val disabled = send(HttpMethod.Post, "/api/selfdev/assignees/$id/disable", """{"version":1}""").obj()
        assertEquals("DISABLED", disabled.str("state"))
        val enabled = send(HttpMethod.Post, "/api/selfdev/assignees/$id/enable", """{"version":2}""").obj()
        assertEquals("ACTIVE", enabled.str("state"))
        assertEquals(3, enabled.getValue("version").jsonPrimitive.content.toInt())
    }

    @Test
    fun `enforce - every other role, the AI and an unbound person are 403 on every admin route`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        for (who in listOf(OPERATOR, APPROVER, VIEWER, DB_AI, ENV_AI, UNBOUND, ROOT_APPROVER)) {
            for (r in adminRoutes()) {
                val res = send(r.method, r.path, r.body, who)
                assertEquals(HttpStatusCode.Forbidden, res.status, "$who ${r.method.value} ${r.path}")
                assertEquals("FORBIDDEN", res.obj().str("code"))
            }
        }
        // nothing was made by any of them
        assertEquals(5, SelfDevAssigneeRepository(db.database).list().size)
    }

    // ---- (c) shadow -----------------------------------------------------------------------------------------------------------

    @Test
    fun `shadow - a person bound to an ADMIN assignee manages by the effective access, the rest cannot`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.SHADOW)
        // under today's rules nobody holds ASSIGNEE_MANAGE; the effective access is what lets the first AI assignee be made
        val ai = create("AI", "robot")
        assertEquals("AI", ai.str("role"))
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/assignees/${ai.str("assigneeId")}/tokens", "{}").status)
        for (who in listOf(OPERATOR, APPROVER, VIEWER, DB_AI, ENV_AI, UNBOUND, ROOT_APPROVER)) {
            for (r in adminRoutes()) assertEquals(HttpStatusCode.Forbidden, send(r.method, r.path, r.body, who).status, "$who ${r.path}")
        }
        // /me shows the effective role in shadow
        val me = send(HttpMethod.Get, "/api/selfdev/me", null, ADMIN).obj()
        assertEquals("shadow", me.str("mode"))
        assertEquals("ADMIN", me.str("role"))
        assertEquals("ASSIGNEE", me.str("source"))
        assertTrue(me.getValue("permissions").jsonArray.any { it.jsonPrimitive.content == "ASSIGNEE_MANAGE" })
    }

    // ---- (d) the AI is always refused -------------------------------------------------------------------------------------------

    @Test
    fun `the environment AI and a database AI are refused in shadow and enforce, even when bound to an ADMIN`() {
        for (mode in listOf(PermissionMode.SHADOW, PermissionMode.ENFORCE)) testApplication {
            val db = newDb()
            val repo = seed(db)
            // the environment AI bound to an ADMIN assignee in the store: the role stays AI (ENV_AI_CONFLICT)
            val admin = repo.list().single { it.role == AssigneeRole.ADMIN }
            assertIs<AssigneeResult.Moved<*>>(repo.bindMember(admin.assigneeId, ENV_AI, SEED))
            setUp(db, mode)
            for (who in listOf(ENV_AI, DB_AI)) {
                for (r in adminRoutes()) assertEquals(HttpStatusCode.Forbidden, send(r.method, r.path, r.body, who).status, "$mode $who ${r.path}")
            }
            val me = send(HttpMethod.Get, "/api/selfdev/me", null, ENV_AI).obj()
            assertEquals("AI", me.str("role"))
        }
    }

    @Test
    fun `a failing store refuses the admin routes (fail closed)`() = testApplication {
        val db = newDb()
        seed(db)
        val failing = object : AssigneeLookup {
            override fun accessOf(ownerKey: String) = throw IllegalStateException("down")
        }
        setUp(db, PermissionMode.ENFORCE, lookup = failing)
        for (r in adminRoutes()) assertEquals(HttpStatusCode.Forbidden, send(r.method, r.path, r.body, ADMIN).status, r.path)
        assertEquals("NONE", send(HttpMethod.Get, "/api/selfdev/me", null, ADMIN).obj().str("role"))
    }

    // ---- (e) the repository's fixed codes -----------------------------------------------------------------------------------------

    @Test
    fun `self change, no change, a stale version and a missing assignee are fixed codes`() = testApplication {
        val db = newDb()
        val repo = seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val own = repo.list().single { it.role == AssigneeRole.ADMIN }
        val demote = send(HttpMethod.Post, "/api/selfdev/assignees/${own.assigneeId}/role", """{"role":"VIEWER","version":${own.version}}""")
        assertEquals(HttpStatusCode.Conflict, demote.status)
        assertEquals("SELF_CHANGE", demote.obj().str("code"))
        assertEquals("SELF_CHANGE", send(HttpMethod.Post, "/api/selfdev/assignees/${own.assigneeId}/disable", """{"version":${own.version}}""").obj().str("code"))
        assertEquals(AssigneeRole.ADMIN, repo.find(own.assigneeId)!!.role)

        val other = repo.list().single { it.role == AssigneeRole.VIEWER }
        assertEquals("NO_CHANGE", send(HttpMethod.Post, "/api/selfdev/assignees/${other.assigneeId}/role", """{"role":"VIEWER","version":0}""").obj().str("code"))
        val stale = send(HttpMethod.Post, "/api/selfdev/assignees/${other.assigneeId}/role", """{"role":"AI","version":7}""")
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("STALE_VERSION", stale.obj().str("code"))
        assertEquals("NOT_FOUND", send(HttpMethod.Post, "/api/selfdev/assignees/$ULID/disable", """{"version":0}""").obj().str("code"))
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/selfdev/assignees/not-an-id/disable", """{"version":0}""").status)
        assertEquals("INVALID_ROLE", send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"x","role":"ROOT"}""").obj().str("code"))
        assertEquals("INVALID_DISPLAY_NAME", send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"   ","role":"AI"}""").obj().str("code"))
        assertEquals("INVALID_BODY", send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"x","role":"AI","extra":1}""").obj().str("code"))
        assertEquals("INVALID_VERSION", send(HttpMethod.Post, "/api/selfdev/assignees/${other.assigneeId}/disable", """{"version":-1}""").obj().str("code"))
    }

    @Test
    fun `the last ADMIN cannot be unbound through the API either`() = testApplication {
        val db = newDb()
        val repo = seed(db)
        setUp(db, PermissionMode.ENFORCE)
        // A second ADMIN acts; it unbinds the first ADMIN's only member: another ACTIVE ADMIN with a member remains, so it passes.
        // Then the first ADMIN is gone as a person, and the second cannot unbind itself: nobody would be left.
        val second = create("ADMIN", "second")
        val secondId = second.str("assigneeId")
        val bound = send(HttpMethod.Post, "/api/selfdev/assignees/$secondId/members", """{"sub":"second-admin"}""")
        assertEquals(HttpStatusCode.Created, bound.status)
        val secondKey = oidcOwnerKey(ISSUER, "second-admin")!!
        val firstId = repo.list().single { it.role == AssigneeRole.ADMIN && it.assigneeId != secondId }.assigneeId
        val firstRef = SelfDevAssigneeRepository.memberRef(ADMIN)
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Delete, "/api/selfdev/assignees/$firstId/members/$firstRef", "", secondKey).status)
        val own = send(HttpMethod.Delete, "/api/selfdev/assignees/$secondId/members/${bound.obj().str("memberRef")}", "", secondKey)
        assertEquals(HttpStatusCode.Conflict, own.status)
        assertEquals("LAST_ADMIN", own.obj().str("code"))
    }

    // ---- (f) the token's plaintext ----------------------------------------------------------------------------------------------

    @Test
    fun `the plaintext token is in the issue response once, with no-store, and nowhere else`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val logs = capture()
        try {
            val id = create("AI", NAME_MARK).str("assigneeId")
            val issued = send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "{}")
            assertEquals(HttpStatusCode.Created, issued.status)
            assertEquals("no-store", issued.headers["Cache-Control"])
            assertEquals("no-cache", issued.headers["Pragma"])
            val body = issued.obj()
            assertEquals(setOf("token", "tokenRef", "expiresAt"), body.keys)
            val token = body.str("token")
            assertTrue(Regex("kvt_[A-Za-z0-9_-]{43}").matches(token))

            // every later read is free of it, and says no-store
            for (r in listOf("/api/selfdev/assignees", "/api/selfdev/assignees/$id/tokens", "/api/selfdev/assignees/$id/members", "/api/selfdev/me")) {
                val res = send(HttpMethod.Get, r, null)
                assertEquals("no-store", res.headers["Cache-Control"], r)
                assertFalse(res.bodyAsText().contains(token), r)
            }
            // the error responses too
            assertFalse(send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "{}", OPERATOR).bodyAsText().contains(token))
            // the repository agrees that it is a valid AI token (T2's entrance is not used here)
            val verified = SelfDevAssigneeTokenRepository(db.database).verify(token)
            assertIs<TokenVerification.Valid>(verified)
            assertEquals(AssigneeRole.AI, verified.role)

            val text = logs.text()
            assertFalse(text.contains(token), "token in a log")
            assertFalse(text.contains(token.removePrefix("kvt_")), "token body in a log")
            assertFalse(text.contains(NAME_MARK), "display name in a log")
            assertFalse(dump(db).contains(token), "token in a table")
            assertFalse(dump(db).contains(token.removePrefix("kvt_")), "token body in a table")
            // the events hold the hashed references only
            assertTrue(dump(db).contains("TOKEN_ISSUED"))
        } finally {
            release(logs)
        }
    }

    @Test
    fun `an issue response left to toString does not show the token`() {
        val s = IssuedTokenResponse("kvt_secretsecretsecret", "ref", "t").toString()
        assertFalse(s.contains("secretsecret"))
    }

    @Test
    fun `ttlDays defaults to 90, 365 is the most and out of range is a fixed code`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val id = create("AI").str("assigneeId")
        val dflt = java.time.Instant.parse(send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "").obj().str("expiresAt"))
        val days = java.time.Duration.between(java.time.Instant.now(), dflt).toDays()
        assertTrue(days in 89..90, "days=$days")
        for (bad in listOf(0, 366, -1)) {
            val res = send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", """{"ttlDays":$bad}""")
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertEquals("TTL_OUT_OF_RANGE", res.obj().str("code"))
        }
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", """{"ttlDays":365}""").status)
        // two valid tokens at most
        assertEquals("TOO_MANY_ACTIVE", send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "{}").obj().str("code"))
    }

    @Test
    fun `a token ref of another assignee is not revocable through this one`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val a = create("AI").str("assigneeId")
        val b = create("AI").str("assigneeId")
        val ref = send(HttpMethod.Post, "/api/selfdev/assignees/$a/tokens", "{}").obj().str("tokenRef")
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Post, "/api/selfdev/assignees/$b/tokens/$ref/revoke").status)
        assertEquals("ACTIVE", Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/assignees/$a/tokens", null).bodyAsText()).jsonArray.single().jsonObject.str("status"))
    }

    // ---- (g) a person's role takes no token -----------------------------------------------------------------------------------------

    @Test
    fun `a token for ADMIN, OPERATOR and APPROVER assignees is refused, for VIEWER it is allowed`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        for (role in listOf("ADMIN", "OPERATOR", "APPROVER")) {
            val id = create(role).str("assigneeId")
            val res = send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "{}")
            assertEquals(HttpStatusCode.Conflict, res.status, role)
            assertEquals("ROLE_NOT_ALLOWED", res.obj().str("code"))
            assertFalse(res.bodyAsText().contains("kvt_"))
        }
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/assignees/${create("VIEWER").str("assigneeId")}/tokens", "{}").status)
    }

    // ---- (h) binding a person ---------------------------------------------------------------------------------------------------------

    @Test
    fun `sub is validated, the owner key is built by the server, and only the memberRef comes back`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val logs = capture()
        try {
            val id = create("OPERATOR", NAME_MARK).str("assigneeId")
            for (bad in listOf("", "a b", "x/y", "a\\u0000b", "é", "x".repeat(256), "a\\nb")) {
                val res = send(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"$bad"}""")
                assertTrue(res.status == HttpStatusCode.BadRequest, "[$bad] -> ${res.status}")
                assertTrue(res.obj().str("code") in setOf("INVALID_SUB", "INVALID_BODY"), "[$bad]")
            }
            val res = send(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"$SUB_MARK"}""")
            assertEquals(HttpStatusCode.Created, res.status)
            val text = res.bodyAsText()
            val key = oidcOwnerKey(ISSUER, SUB_MARK)!!
            assertEquals(setOf("memberRef"), Json.parseToJsonElement(text).jsonObject.keys)
            assertEquals(SelfDevAssigneeRepository.memberRef(key), Json.parseToJsonElement(text).jsonObject.str("memberRef"))
            assertFalse(text.contains(SUB_MARK))
            assertFalse(text.contains(key))
            // binding the same person twice is a fixed code
            assertEquals("OWNER_KEY_ALREADY_BOUND", send(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"$SUB_MARK"}""").obj().str("code"))
            // the person now acts as an OPERATOR (the key the login builds)
            val me = send(HttpMethod.Get, "/api/selfdev/me", null, key).obj()
            assertEquals("OPERATOR", me.str("role"))
            assertEquals("ASSIGNEE", me.str("source"))
            assertFalse(me.toString().contains(id))
            assertFalse(me.toString().contains(NAME_MARK))
            val all = logs.text()
            assertFalse(all.contains(SUB_MARK), "sub in a log")
            assertFalse(all.contains(key), "owner key in a log")
            assertFalse(all.contains(NAME_MARK), "display name in a log")
            assertFalse(all.contains(SelfDevAssigneeRepository.memberRef(key)), "member ref in a log")
        } finally {
            release(logs)
        }
    }

    @Test
    fun `without the configured issuer binding a person is 503, and the synthetic key is refused`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE, issuer = null)
        val id = create("OPERATOR").str("assigneeId")
        val res = send(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"abc"}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("BINDING_UNAVAILABLE", res.obj().str("code"))
        // the other routes still work
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/assignees", null).status)
    }

    @Test
    fun `an issuer that makes the synthetic owner key is refused as SYNTHETIC_KEY_RESERVED`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE, issuer = SelfDevAssigneeTokenRepository.ISSUER)
        val id = create("AI").str("assigneeId")
        val res = send(HttpMethod.Post, "/api/selfdev/assignees/$id/members", """{"sub":"$ULID"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("SYNTHETIC_KEY_RESERVED", res.obj().str("code"))
    }

    @Test
    fun `the synthetic key of the tokens cannot be unbound by its ref`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val id = create("AI").str("assigneeId")
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/assignees/$id/tokens", "{}").status)
        val ref = SelfDevAssigneeRepository.memberRef(SelfDevAssigneeTokenRepository.ownerKeyOf(id))
        val res = send(HttpMethod.Delete, "/api/selfdev/assignees/$id/members/$ref", "")
        assertEquals("SYNTHETIC_KEY_RESERVED", res.obj().str("code"))
        // and the members list shows people only
        assertEquals(0, Json.parseToJsonElement(send(HttpMethod.Get, "/api/selfdev/assignees/$id/members", null).bodyAsText()).jsonArray.size)
    }

    // ---- (i) CSRF ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a write from another origin, a cross-site fetch or a non-JSON body is refused and changes nothing`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        val before = SelfDevAssigneeRepository(db.database).list().size
        val id = SelfDevAssigneeRepository(db.database).list().first().assigneeId
        for (r in adminRoutes(id).filter { it.method != HttpMethod.Get }) {
            val cross = send(r.method, r.path, r.body, extra = mapOf("Origin" to "https://evil.example"))
            assertEquals(HttpStatusCode.Forbidden, cross.status, "origin ${r.path}")
            val site = send(r.method, r.path, r.body, extra = mapOf("Sec-Fetch-Site" to "cross-site"))
            assertEquals(HttpStatusCode.Forbidden, site.status, "site ${r.path}")
            val plain = send(r.method, r.path, r.body, json = false)
            assertEquals(HttpStatusCode.UnsupportedMediaType, plain.status, "type ${r.path}")
        }
        assertEquals(before, SelfDevAssigneeRepository(db.database).list().size)
        // the same origin is fine
        val same = send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"x","role":"AI"}""", extra = mapOf("Origin" to "https://kivra.example", "Host" to "kivra.example"))
        assertEquals(HttpStatusCode.Created, same.status, same.bodyAsText())
    }

    // ---- (j) /me -------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `me has the four fields only and no owner key, assignee id or display name`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE)
        for ((who, role) in listOf(ADMIN to "ADMIN", OPERATOR to "OPERATOR", APPROVER to "APPROVER", VIEWER to "VIEWER", DB_AI to "AI", ENV_AI to "AI", UNBOUND to "OPERATOR")) {
            val res = send(HttpMethod.Get, "/api/selfdev/me", null, who)
            assertEquals(HttpStatusCode.OK, res.status)
            assertEquals("no-store", res.headers["Cache-Control"])
            val text = res.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("mode", "role", "permissions", "source"), json.keys, who)
            assertEquals(role, json.str("role"), who)
            assertEquals("enforce", json.str("mode"))
            assertFalse(text.contains(who), who)
            for (a in SelfDevAssigneeRepository(db.database).list()) {
                assertFalse(text.contains(a.assigneeId))
                assertFalse(text.contains(a.displayName))
            }
        }
        // a disabled person has no role
        val repo = SelfDevAssigneeRepository(db.database)
        val viewer = repo.list().single { it.role == AssigneeRole.VIEWER }
        assertIs<AssigneeResult.Moved<*>>(repo.setState(viewer.assigneeId, net.kigawa.kivra.db.AssigneeState.DISABLED, viewer.version, SEED))
        val off = send(HttpMethod.Get, "/api/selfdev/me", null, VIEWER).obj()
        assertEquals("NONE", off.str("role"))
        assertEquals("ASSIGNEE_DISABLED", off.str("source"))
        assertEquals(0, off.getValue("permissions").jsonArray.size)
    }

    // ---- the write rate limit ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `writes are limited per principal, reads are not, and a refused caller uses none of the allowance`() = testApplication {
        val db = newDb()
        seed(db)
        setUp(db, PermissionMode.ENFORCE, limiter = McpRateLimiter(2))
        // a refused caller does not count
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"x","role":"AI"}""", OPERATOR).status) }
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"a","role":"AI"}""").status)
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"b","role":"AI"}""").status)
        val limited = send(HttpMethod.Post, "/api/selfdev/assignees", """{"displayName":"c","role":"AI"}""")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("RATE_LIMITED", limited.obj().str("code"))
        repeat(5) { assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/api/selfdev/assignees", null).status) }
        assertEquals(7, SelfDevAssigneeRepository(db.database).list().size)
    }
}
