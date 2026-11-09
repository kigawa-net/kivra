package net.kigawa.kivra.mcp

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.ProjectMemberLookup
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.oidcOwnerKey
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectMemberStore
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.routes.GuardLog
import net.kigawa.kivra.routes.PermissionGuard

/**
 * The read scope of the projects (ADR 0022 PR-D3) on the MCP read tools `kivra_run_list`, `kivra_run_get`, `kivra_order_list` and
 * `kivra_order_get`, through the production wiring on H2. Three callers: the global ADMIN, a member of Alpha (and not of Beta), and an
 * outsider. Rows: Alpha's, Beta's, and rows with no project; the member created all of them.
 *
 *  - OFF: every row, as before, and no membership is read.
 *  - SHADOW: every row, as before. The rows ENFORCE would hide are logged with fixed codes and `via=mcp`.
 *  - ENFORCE: a list shows the rows of the caller's projects (the global ADMIN all of them). A row of another project is the same
 *    not-found as a missing one, byte for byte. A failed membership store shows nothing.
 */
class McpReadScopeTest {
    private val clock: Clock = Clock.systemUTC()
    private val repo = RepoRef("kigawa-net", "kivra")
    private val adminOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-admin")!!
    private val memberOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-member")!!
    private val outsiderOwner = oidcOwnerKey(OidcTestTokens.ISSUER, "sub-outsider")!!

    private class Log : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    /** The membership store, which can be made to fail. Counts the reads. */
    private class Members(private val inner: ProjectMemberStore) : ProjectMemberLookup {
        var fail = false
        var reads = 0
        override fun roleIn(projectId: String, assigneeId: String): Role? = inner.roleIn(projectId, assigneeId)
        override fun rolesOf(assigneeId: String): Map<String, Role> {
            reads++
            if (fail) throw IllegalStateException("store down")
            return inner.rolesOf(assigneeId)
        }
    }

    private class Owners(private val map: Map<String, AssigneeAccess>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = map[ownerKey]
    }

    /** The data of one test: two projects, the memberships, and the rows of each kind. */
    private inner class Fixture(val db: TestDb) {
        val projects = ProjectRepository(db.database, clock)
        val alpha: String = projects.create("Alpha", null).projectId
        val beta: String = projects.create("Beta", null).projectId
        private val adminId = assignee("admin", AssigneeRole.ADMIN)
        private val memberId = assignee("member", AssigneeRole.OPERATOR)
        private val outsiderId = assignee("outsider", AssigneeRole.OPERATOR)
        val store = Members(ProjectMemberStore(db.database))

        init {
            // The member belongs to Alpha only. Its rows in Beta are from before it left.
            ProjectMemberRepository(db.database, clock).put(alpha, memberId, "OPERATOR")
        }

        private fun assignee(name: String, role: AssigneeRole): String =
            assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(db.database).create(name, role, "root")).value.assigneeId

        private val owners = Owners(
            mapOf(
                adminOwner to AssigneeAccess(Role.ADMIN, true, adminId),
                memberOwner to AssigneeAccess(Role.OPERATOR, true, memberId),
                outsiderOwner to AssigneeAccess(Role.OPERATOR, true, outsiderId),
            ),
        )

        /** The guard of the roles ENFORCE with the given project mode. The member's runs and orders are its own, whatever the project. */
        fun guard(projectMode: PermissionMode, log: GuardLog = Log()): PermissionGuard = PermissionGuard(
            PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, null, null, owners, projectMode, store),
            log,
        )

        val runAlpha = run("run-a", alpha)
        val runBeta = run("run-b", beta)
        val runNone = run("run-n", null)
        val orderAlpha = order("order-a", alpha)
        val orderBeta = order("order-b", beta)
        val orderNone = order("order-n", null)

        private fun run(key: String, projectId: String?): String =
            (db.runs.submitTask(memberOwner, key, "Run $key", selfDev = true, projectId = projectId) as TaskSubmitResult.Created)
                .run.runId.value

        private fun order(key: String, projectId: String?): String =
            assertIs<CreateRootResult.Created>(
                SelfDevOrderRepository(db.database).createRoot(memberOwner, "Order $key", "policy", "kigawa-net/kivra", 1, projectId),
            ).order.orderId
    }

    private fun env(): Map<String, String> = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
        McpSettings.ENV_ENABLED to "true",
        McpSettings.ENV_AUDIENCE to McpTestTokens.MCP_AUDIENCE,
        McpSettings.ENV_ALLOWED_HOSTS to McpTestTokens.HOST,
        McpSettings.ENV_ALLOWED_ORIGINS to McpTestTokens.ORIGIN,
        McpSettings.ENV_WRITE_ENABLED to "false",
    )

    /** The production wiring as Application builds it, with the given guard and the order tools on. */
    private fun deps(db: TestDb, guard: PermissionGuard): McpRouteDeps {
        val e = env()
        return buildMcpRouteDeps(
            McpSettings.fromEnv(e), e, db.runs, repo,
            jwkProvider = OidcTestTokens.jwks(), selfDevEnabled = true, maxActiveSelfDev = 1,
            orders = McpOrderDeps(SelfDevOrderRepository(db.database), null),
            guard = guard,
            projects = ProjectRepository(db.database, clock),
        )!!
    }

    private fun ApplicationTestBuilder.setUp(deps: McpRouteDeps) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureMcpRoutes(deps) }
        }
    }

    private suspend fun ApplicationTestBuilder.rpc(body: String, subject: String): HttpResponse =
        client.post("/mcp") {
            header(HttpHeaders.Host, McpTestTokens.HOST)
            header(HttpHeaders.Authorization, "Bearer ${McpTestTokens.token(subject = subject)}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun call(name: String, args: String) =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""

    private suspend fun ApplicationTestBuilder.callTool(name: String, args: String, subject: String): String =
        rpc(call(name, args), subject).bodyAsText()

    private fun JsonObject.structured(): JsonObject = this["result"]!!.jsonObject["structuredContent"]!!.jsonObject
    private fun JsonObject.isError(): Boolean = this["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean()

    private fun ids(body: String, list: String, key: String): List<String> =
        Json.parseToJsonElement(body).jsonObject.structured()[list]!!.jsonArray.map { it.jsonObject[key]!!.jsonPrimitive.content }

    private fun runIds(body: String) = ids(body, "runs", "run_id")
    private fun orderIds(body: String) = ids(body, "orders", "order_id")

    private fun runListArgs() = "{}"
    private fun idArgs(key: String, id: String) = """{"$key":"$id"}"""
    private val missing = "0".repeat(25) + "1"

    // ---- OFF: nothing changes, and nothing is read ----

    @Test
    fun `project mode off lists every row and reads no membership`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.guard(PermissionMode.OFF)))
            val runs = runIds(callTool("kivra_run_list", runListArgs(), "sub-member"))
            assertTrue(fx.runAlpha in runs && fx.runBeta in runs && fx.runNone in runs, "every run of the member: $runs")
            val orders = orderIds(callTool("kivra_order_list", "{}", "sub-member"))
            assertTrue(fx.orderAlpha in orders && fx.orderBeta in orders && fx.orderNone in orders, "every order: $orders")
            assertTrue(Json.parseToJsonElement(callTool("kivra_run_get", idArgs("run_id", fx.runBeta), "sub-member")).jsonObject.structured()
                .let { it["run_id"]!!.jsonPrimitive.content == fx.runBeta })
            assertFalse(Json.parseToJsonElement(callTool("kivra_order_get", idArgs("order_id", fx.orderBeta), "sub-member")).jsonObject.isError())
            assertEquals(0, fx.store.reads, "OFF reads no membership")
        }
    }

    // ---- SHADOW: the answers are the same as OFF, and the rows ENFORCE would hide are logged ----

    @Test
    fun `shadow answers every read as before and logs what enforce would hide, with fixed codes and via mcp`() = TestDb().use { db ->
        val fx = Fixture(db)
        val log = Log()
        testApplication {
            setUp(deps(db, fx.guard(PermissionMode.SHADOW, log)))
            val runs = runIds(callTool("kivra_run_list", runListArgs(), "sub-member"))
            assertTrue(fx.runBeta in runs && fx.runNone in runs, "shadow: the member still lists Beta's and the unassigned run")
            val orders = orderIds(callTool("kivra_order_list", "{}", "sub-member"))
            assertTrue(fx.orderBeta in orders, "shadow: the member still lists Beta's order")
            assertFalse(Json.parseToJsonElement(callTool("kivra_run_get", idArgs("run_id", fx.runBeta), "sub-member")).jsonObject.isError())
        }
        val text = log.lines.joinToString("\n")
        assertTrue("project read shadow: wouldHide code=PROJECT_READ_NOT_MEMBER" in text, "not a member is logged: $text")
        assertTrue("project read shadow: wouldHide code=PROJECT_READ_UNASSIGNED" in text, "the unassigned row is logged: $text")
        assertTrue("via=mcp" in text, "the log says it came through MCP")
        for (secret in listOf(memberOwner, outsiderOwner, fx.alpha, fx.beta, fx.runBeta, fx.orderBeta, "sub-member")) {
            assertFalse(secret in text, "the log names no person and no row")
        }
    }

    // ---- ENFORCE: the lists show the caller's projects, and another project's row is the same not-found as a missing one ----

    @Test
    fun `enforce lists the rows of the member's projects, and the global ADMIN sees all of them`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.guard(PermissionMode.ENFORCE)))
            val runs = runIds(callTool("kivra_run_list", runListArgs(), "sub-member"))
            assertEquals(listOf(fx.runAlpha), runs, "only Alpha's run: no Beta run, no unassigned run")
            val orders = orderIds(callTool("kivra_order_list", "{}", "sub-member"))
            assertEquals(listOf(fx.orderAlpha), orders, "only Alpha's order")

            // The run lists are the caller's own runs (the member's, not the admin's). The order list is every order: the global ADMIN sees all.
            assertEquals(emptyList(), runIds(callTool("kivra_run_list", runListArgs(), "sub-admin")), "the admin owns no run here")
            val adminOrders = orderIds(callTool("kivra_order_list", "{}", "sub-admin"))
            assertTrue(fx.orderAlpha in adminOrders && fx.orderBeta in adminOrders && fx.orderNone in adminOrders, "the global ADMIN lists every order")
        }
    }

    @Test
    fun `enforce answers a row of another project exactly as a missing one, on the get tools`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.guard(PermissionMode.ENFORCE)))
            val runOwn = callTool("kivra_run_get", idArgs("run_id", fx.runAlpha), "sub-member")
            assertTrue(runOwn.contains(fx.runAlpha), "the member reads its own project's run")
            assertEquals(
                callTool("kivra_run_get", idArgs("run_id", missing), "sub-member"),
                callTool("kivra_run_get", idArgs("run_id", fx.runBeta), "sub-member"),
                "a run of Beta is the same answer as a missing run",
            )
            assertEquals(
                callTool("kivra_run_get", idArgs("run_id", missing), "sub-member"),
                callTool("kivra_run_get", idArgs("run_id", fx.runNone), "sub-member"),
                "a run with no project is the same answer as a missing run",
            )
            assertTrue(callTool("kivra_order_get", idArgs("order_id", fx.orderAlpha), "sub-member").contains(fx.orderAlpha))
            assertEquals(
                callTool("kivra_order_get", idArgs("order_id", missing), "sub-member"),
                callTool("kivra_order_get", idArgs("order_id", fx.orderBeta), "sub-member"),
                "an order of Beta is the same answer as a missing order",
            )
            assertTrue(callTool("kivra_order_get", idArgs("order_id", fx.orderBeta), "sub-admin").contains(fx.orderBeta), "the global ADMIN gets Beta's order")
        }
    }

    @Test
    fun `enforce with a failed membership store lists nothing, and a get is not found`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.guard(PermissionMode.ENFORCE)))
            fx.store.fail = true
            assertEquals(emptyList(), runIds(callTool("kivra_run_list", runListArgs(), "sub-member")))
            assertEquals(emptyList(), orderIds(callTool("kivra_order_list", "{}", "sub-member")))
            assertEquals(
                callTool("kivra_run_get", idArgs("run_id", missing), "sub-member"),
                callTool("kivra_run_get", idArgs("run_id", fx.runAlpha), "sub-member"),
                "a failed store is the same not-found",
            )
            // The global ADMIN does not ask the store, so it still reads.
            assertTrue(callTool("kivra_order_get", idArgs("order_id", fx.orderBeta), "sub-admin").contains(fx.orderBeta))
        }
    }

    @Test
    fun `enforce lists nothing to an outsider with no membership`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(deps(db, fx.guard(PermissionMode.ENFORCE)))
            // The outsider's own runs: none, since the member created them. Its lists show no row of any project.
            assertEquals(emptyList(), runIds(callTool("kivra_run_list", runListArgs(), "sub-outsider")))
            assertEquals(emptyList(), orderIds(callTool("kivra_order_list", "{}", "sub-outsider")))
        }
    }
}
