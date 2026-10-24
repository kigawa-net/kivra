package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.BudgetOperatorStatus
import net.kigawa.kivra.db.BudgetOperatorView
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptUsageRepository
import net.kigawa.kivra.db.CodingMonitorEventRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.selfdev.FakeGitHub
import net.kigawa.kivra.selfdev.SelfDevSettings

private const val AI = "1:i:ai"
private const val ROOT = "1:i:root"
private const val OTHER = "1:i:other"
private const val NOBODY = "1:i:nobody"
private const val RUN_ID = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The REST routes that had no permission check, behind [PermissionGuard] (ADR 0020, PR 3c): the roles off (today's rules, against an
 * oracle of the old rules), shadow, and enforce, on H2 with the real repositories.
 */
class RestRolesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private class Lookup(val map: Map<String, AssigneeAccess>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = map[ownerKey]
    }

    private class FakeLog : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    private class FixedView : BudgetOperatorView {
        override fun operatorStatus() = BudgetOperatorStatus("2026-10", BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, emptyList())
    }

    private val roleOf = mapOf(
        "r:admin" to Role.ADMIN, "r:operator" to Role.OPERATOR, "r:approver" to Role.APPROVER, "r:viewer" to Role.VIEWER,
    )

    private fun guard(mode: PermissionMode, log: GuardLog = FakeLog()): PermissionGuard {
        val lookup = Lookup(roleOf.mapValues { AssigneeAccess(it.value, true) })
        val service = PermissionService(mode, RolesDefault.NONE, AI, ROOT, if (mode == PermissionMode.OFF) null else lookup)
        return PermissionGuard(service, log)
    }

    /** One target route and the permission it needs. [body] is a valid-enough body; any non-403 status means "let through". */
    private class Target(val name: String, val method: HttpMethod, val path: String, val permission: Permission?, val body: String? = null)

    private val targets = listOf(
        Target("release", HttpMethod.Post, "/api/admin/selfdev/runs/$RUN_ID/release", Permission.BUDGET_OVERRIDE, """{"reason":"x"}"""),
        Target("release-unbound-pr", HttpMethod.Post, "/api/admin/selfdev/runs/$RUN_ID/release-unbound-pr", Permission.BUDGET_OVERRIDE, """{"reason":"x"}"""),
        Target("budget-status", HttpMethod.Get, "/api/admin/budget/status", Permission.READ),
        Target("budget-fx", HttpMethod.Get, "/api/admin/budget/fx", Permission.READ),
        Target("budget-ack", HttpMethod.Post, "/api/admin/budget/acknowledge", Permission.BUDGET_OVERRIDE, "{}"),
        Target("task-submit", HttpMethod.Post, "/api/tasks", Permission.SPEND_START, "{}"),
        Target("task-get", HttpMethod.Get, "/api/runs/$RUN_ID", Permission.READ),
        Target("task-list", HttpMethod.Get, "/api/runs", Permission.READ),
        Target("selfdev-submit", HttpMethod.Post, "/api/selfdev/tasks", Permission.SPEND_START, "{}"),
        Target("selfdev-get", HttpMethod.Get, "/api/selfdev/runs/$RUN_ID", Permission.READ),
        Target("selfdev-list", HttpMethod.Get, "/api/selfdev/runs", Permission.READ),
        Target("operations", HttpMethod.Get, "/api/runs/$RUN_ID/operations", Permission.READ),
        Target("selfdev-status", HttpMethod.Get, "/api/selfdev/status", Permission.READ),
        Target("admin-runs", HttpMethod.Get, "/api/admin/runs", Permission.READ),
        Target("admin-run", HttpMethod.Get, "/api/admin/runs/$RUN_ID", Permission.READ),
    )

    private fun ApplicationTestBuilder.setUp(db: TestDb, guard: PermissionGuard) {
        val view = FixedView()
        val budget = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")))
        // The gate is the real one in the header mode: only the creator reads a Run (no operator), so the owner rule is the old one.
        val exec = RunExecutionDeps(
            runs = db.runs,
            attempts = CodingAttemptRepository(db.database),
            monitor = CodingMonitorEventRepository(db.database),
            usage = CodingAttemptUsageRepository(db.database),
            gate = DefaultRunExecutionGate(db.runs, null, identity),
            reserveUsd = BigDecimal("0.50"),
            guard = guard,
        )
        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing {
                configureSelfDevAdminRoutes(db.runs, identity, guard)
                configureBudgetAdminRoutes(budget, identity, operatorView = view, guard = guard)
                configureTaskApiRoutes(db.runs, identity, null, guard)
                configureSelfDevRoutes(SelfDevRouteDeps(db.runs, identity, RepoRef("kigawa-net", "kivra"), 1000, FakeGitHub(), guard))
                configureOperationApiRoutes(db.runs, db.journal, identity, guard)
                configureRunViewRoutes(db.runs, identity, SelfDevSettings.fromEnv(emptyMap()), guard)
                configureRunExecutionRoutes(exec)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(t: Target, principal: String?): HttpResponse = client.request(t.path) {
        method = t.method
        principal?.let { header("X-Test-Principal", it) }
        if (t.body != null) {
            contentType(ContentType.Application.Json)
            setBody(t.body)
        }
    }

    private suspend fun ApplicationTestBuilder.statusOf(t: Target, principal: String?) = call(t, principal).status

    @Test
    fun `off lets the AI, the root approver and another operator through every target route, as before`() = TestDb().use { db ->
        testApplication {
            setUp(db, guard(PermissionMode.OFF))
            // Oracle of the old rules: a target route had no permission check, so any authenticated caller got past it.
            for (t in targets) {
                for (p in listOf(AI, ROOT, OTHER)) {
                    val s = statusOf(t, p)
                    assertTrue(s != HttpStatusCode.Forbidden && s != HttpStatusCode.Unauthorized, "${t.name} $p -> $s")
                }
                assertEquals(HttpStatusCode.Unauthorized, statusOf(t, null), t.name)
            }
        }
    }

    @Test
    fun `off with the default guard is the same as the old routes`() = TestDb().use { db ->
        testApplication {
            setUp(db, offPermissionGuard())
            for (t in targets) assertTrue(statusOf(t, AI) != HttpStatusCode.Forbidden, t.name)
        }
    }

    @Test
    fun `shadow acts on the old rules and records only the AI's budget and release calls, with fixed fields`() = TestDb().use { db ->
        val log = FakeLog()
        testApplication {
            setUp(db, guard(PermissionMode.SHADOW, log))
            for (t in targets) assertTrue(statusOf(t, AI) != HttpStatusCode.Forbidden, t.name)
        }
        val denies = log.lines.filter { it.startsWith("permission shadow: wouldDeny") }
        assertEquals(3, denies.size, denies.toString())
        assertTrue(denies.all { it.contains("permission=BUDGET_OVERRIDE code=PERMISSION_DENIED source=ENV_AI role=AI route=") && it.contains("via=rest") })
        assertTrue(denies.none { it.contains(AI) })
        assertEquals(
            setOf("selfdev_release", "selfdev_release_unbound_pr", "budget_acknowledge"),
            denies.map { Regex("route=(\\S+)").find(it)!!.groupValues[1] }.toSet(),
        )
    }

    @Test
    fun `enforce gives each role exactly its permissions on every target route`() = TestDb().use { db ->
        testApplication {
            setUp(db, guard(PermissionMode.ENFORCE))
            val principals = mapOf(
                Role.ADMIN to "r:admin", Role.OPERATOR to "r:operator", Role.APPROVER to "r:approver", Role.AI to AI, Role.VIEWER to "r:viewer",
            )
            for (t in targets) {
                for ((role, p) in principals) {
                    val allowed = t.permission in ROLE_TABLE.getValue(role)
                    val s = statusOf(t, p)
                    if (allowed) {
                        assertTrue(s != HttpStatusCode.Forbidden, "${t.name} $role -> $s")
                    } else {
                        assertEquals(HttpStatusCode.Forbidden, s, "${t.name} $role")
                    }
                }
                // No assignee and no default: refused. Unauthenticated stays 401, before any permission.
                assertEquals(HttpStatusCode.Forbidden, statusOf(t, NOBODY), "${t.name} nobody")
                assertEquals(HttpStatusCode.Unauthorized, statusOf(t, null), t.name)
            }
        }
    }

    @Test
    fun `enforce checks READ on the execution routes for the creator of the run, whatever the role`() = TestDb().use { db ->
        val principals = listOf("r:admin", "r:operator", "r:approver", "r:viewer", AI, NOBODY)
        val runs = principals.mapIndexed { i, p -> p to (db.runs.submitTask(p, "k$i", "title") as TaskSubmitResult.Created).run.runId.value }
        testApplication {
            setUp(db, guard(PermissionMode.ENFORCE))
            for ((p, runId) in runs) {
                for (suffix in listOf("execution", "execution/events?attemptId=01AAAAAAAAAAAAAAAAAAAAAAAA")) {
                    val s = statusOf(Target("exec", HttpMethod.Get, "/api/admin/runs/$runId/$suffix", Permission.READ), p)
                    if (p == NOBODY) assertEquals(HttpStatusCode.Forbidden, s, "$p $suffix") else assertTrue(s != HttpStatusCode.Forbidden, "$p $suffix -> $s")
                }
            }
        }
    }

    @Test
    fun `the AI cannot release a budget or a run in enforce, and can submit`() = TestDb().use { db ->
        testApplication {
            setUp(db, guard(PermissionMode.ENFORCE))
            for (name in listOf("release", "release-unbound-pr", "budget-ack")) {
                assertEquals(HttpStatusCode.Forbidden, statusOf(targets.first { it.name == name }, AI), name)
            }
            assertTrue(statusOf(targets.first { it.name == "task-submit" }, AI) != HttpStatusCode.Forbidden)
            assertTrue(statusOf(targets.first { it.name == "selfdev-submit" }, AI) != HttpStatusCode.Forbidden)
        }
    }

    @Test
    fun `the execution gate's owner rule is unchanged, and READ is checked after it`() = TestDb().use { db ->
        val runId = (db.runs.submitTask("r:viewer", "k1", "title") as TaskSubmitResult.Created).run.runId.value
        val own = "/api/admin/runs/$runId/execution"
        for (mode in listOf(PermissionMode.OFF, PermissionMode.ENFORCE)) {
            testApplication {
                setUp(db, guard(mode))
                val get = Target("exec", HttpMethod.Get, own, Permission.READ)
                // The creator reads his own run (a viewer holds READ). Someone else's run is the gate's 404, before the permission.
                assertEquals(HttpStatusCode.OK, statusOf(get, "r:viewer"), "$mode creator")
                assertEquals(HttpStatusCode.NotFound, statusOf(get, "r:admin"), "$mode other")
            }
        }
        // A creator with no READ permission is refused by the guard in enforce, and let through when the roles are off.
        val loner = (db.runs.submitTask(NOBODY, "k2", "title") as TaskSubmitResult.Created).run.runId.value
        val lonerPath = Target("exec", HttpMethod.Get, "/api/admin/runs/$loner/execution", Permission.READ)
        testApplication {
            setUp(db, guard(PermissionMode.ENFORCE))
            assertEquals(HttpStatusCode.Forbidden, statusOf(lonerPath, NOBODY))
            assertEquals(HttpStatusCode.Forbidden, statusOf(Target("s", HttpMethod.Get, "/api/admin/runs/$loner/execution/events", Permission.READ), NOBODY))
        }
        testApplication {
            setUp(db, guard(PermissionMode.OFF))
            assertEquals(HttpStatusCode.OK, statusOf(lonerPath, NOBODY))
        }
    }

    private companion object {
        val ROLE_TABLE = net.kigawa.kivra.auth.ROLE_PERMISSIONS
    }
}
