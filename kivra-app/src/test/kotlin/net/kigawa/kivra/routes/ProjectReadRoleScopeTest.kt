package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.ProjectMemberLookup
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.MemoryDeleteResult
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectMemberStore
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.StopEnd
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryChannel

/**
 * The role inside a project, on the old list routes and the aggregates (ADR 0022 PR-D3, the follow-up). The old list routes check the
 * global role only; each one now also keeps the rows of the projects where the caller's project role holds the list's permission.
 * Callers: the global ADMIN; `member` (global OPERATOR, a project OPERATOR in Alpha); `viewer` (global OPERATOR, a project VIEWER in Alpha);
 * `approver` (global APPROVER, a project OPERATOR in Alpha and a project APPROVER in Beta); and `outsider` (no membership).
 *
 *  - OFF: every row and every count, as before.
 *  - SHADOW: the same answers as OFF; the rows ENFORCE would hide are logged.
 *  - ENFORCE: the old list keeps the rows whose project role holds the permission (READ_TEXT for the memory, STOP_DECIDE for the stop requests).
 *    The alias keeps its answer: a project role without the permission is a 403 there, as before.
 *  - The status count (`/api/selfdev/status`) and the memory counts (tombstones, the deletion audit) cover the caller's projects only.
 */
class ProjectReadRoleScopeTest {
    private val clock: Clock = Clock.systemUTC()
    private val identity = TaskApiIdentity { headers -> headers[PRINCIPAL]?.takeIf { it.isNotBlank() } }

    private class Log : GuardLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
    }

    private class Members(private val inner: ProjectMemberStore) : ProjectMemberLookup {
        override fun roleIn(projectId: String, assigneeId: String): Role? = inner.roleIn(projectId, assigneeId)
        override fun rolesOf(assigneeId: String): Map<String, Role> = inner.rolesOf(assigneeId)
    }

    private class Owners(private val map: Map<String, AssigneeAccess>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = map[ownerKey]
    }

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: Headers): OperatorAuth = headers[PRINCIPAL]?.takeIf { it.isNotBlank() }
            ?.let { OperatorAuth.Authenticated(it) } ?: OperatorAuth.Unauthenticated
    }

    /** The data of one test: the projects, the memberships of the four callers, and the rows. */
    private inner class Fixture(val db: TestDb) {
        val projects = ProjectRepository(db.database, clock)
        val alpha: String = projects.create("Alpha", null).projectId
        val beta: String = projects.create("Beta", null).projectId
        private val adminId = assignee("admin", AssigneeRole.ADMIN)
        private val memberId = assignee("member", AssigneeRole.OPERATOR)
        private val viewerId = assignee("viewer", AssigneeRole.OPERATOR)
        private val approverId = assignee("approver", AssigneeRole.APPROVER)
        private val outsiderId = assignee("outsider", AssigneeRole.OPERATOR)
        val store = Members(ProjectMemberStore(db.database))

        init {
            val members = ProjectMemberRepository(db.database, clock)
            members.put(alpha, memberId, "OPERATOR")
            members.put(alpha, viewerId, "VIEWER")
            members.put(alpha, approverId, "OPERATOR")
            members.put(beta, approverId, "APPROVER")
        }

        private fun assignee(name: String, role: AssigneeRole): String =
            assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(db.database).create(name, role, "root")).value.assigneeId

        val owners = Owners(
            mapOf(
                ADMIN to AssigneeAccess(Role.ADMIN, true, adminId),
                MEMBER to AssigneeAccess(Role.OPERATOR, true, memberId),
                VIEWER to AssigneeAccess(Role.OPERATOR, true, viewerId),
                APPROVER to AssigneeAccess(Role.APPROVER, true, approverId),
                OUTSIDER to AssigneeAccess(Role.OPERATOR, true, outsiderId),
            ),
        )

        fun guard(projectMode: PermissionMode, log: GuardLog = Log()): PermissionGuard = PermissionGuard(
            PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, AI, ROOT, owners, projectMode, store),
            log,
        )

        /** Goal titles: the memory rows take the project of their goal. */
        val memoryAlpha = memory(goal("goal-a", alpha), "memory a")
        val memoryBeta = memory(goal("goal-b", beta), "memory b")

        /** Notes deleted by the operator: each one is a tombstone, and each deletion is one audit row. */
        val tombstoneAlpha = memory(goal("goal-ta", alpha), "tombstone a").also { delete(it) }
        val tombstoneBeta = memory(goal("goal-tb", beta), "tombstone b").also { delete(it) }

        val orderAlpha = order("order-a", alpha)
        val stopAlpha = stopRequest(orderAlpha)
        val orderBeta = order("order-b", beta)

        /**
         * The database allows one pending root stop request at a time, so Beta's request is made only after Alpha's has ended
         * ([endStop] then [betaStop]).
         */
        fun endStop(requestId: String) {
            SelfDevOrderRepository(db.database).endStopRequest(requestId, StopEnd.CANCELLED, MEMBER, OrderActor.HUMAN)
        }

        fun betaStop(): String = stopRequest(orderBeta)

        /** Active self-development runs: one per project and one with no project. */
        val runAlpha = run("run-a", alpha)
        val runBeta = run("run-b", beta)
        val runNone = run("run-n", null)

        private fun run(key: String, projectId: String?): String =
            (db.runs.submitTask(MEMBER, key, "Run $key", selfDev = true, projectId = projectId) as TaskSubmitResult.Created)
                .run.runId.value

        private fun goal(key: String, projectId: String?): String =
            assertIs<SelfDevSubmitResult.Created>(
                SelfDevOperatorRepository(db.database).submitTask(MEMBER, key, "Goal $key", null, TARGET, projectId),
            ).task.taskId

        private fun order(key: String, projectId: String?): Pair<String, Long> =
            assertIs<CreateRootResult.Created>(
                SelfDevOrderRepository(db.database).createRoot(MEMBER, "Order $key", "policy", TARGET, 1, projectId),
            ).order.let { it.orderId to it.version }

        private fun stopRequest(order: Pair<String, Long>): String {
            val result = SelfDevOrderRepository(db.database)
                .requestStop(order.first, order.second, MEMBER, OrderActor.HUMAN, StopRequestReason.REQUESTABLE.first())
            check(result is OrderResult.Moved) { "stop request refused: $result for ${order.first}" }
            return result.value.requestId
        }

        private fun memory(goalId: String, body: String): String =
            SelfDevMemoryRepository(db.database, enabled = true).save(
                MemoryCandidate(kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = MEMBER, goalId = goalId, body = body),
            ).let { (it as MemorySaveResult.Written).recordId }

        private fun delete(recordId: String) {
            assertIs<MemoryDeleteResult.Deleted>(SelfDevMemoryRepository(db.database, enabled = true).deleteOne(recordId, MEMBER))
        }
    }

    /** The old list routes, the alias, the status and the aggregates, on one application, with the given guard and settings. */
    private fun ApplicationTestBuilder.setUp(db: TestDb, fx: Fixture, guard: PermissionGuard, selfDev: SelfDevSettings = SelfDevSettings.disabled()) {
        val autopilot = enabledAutopilot()
        val orders = SelfDevOrderRouteDeps(
            operators = StubAuth, autopilot = autopilot,
            orderSettings = OrderSettings.fromEnv(mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to ROOT), autopilot, aiPrincipal = null),
            aiPrincipal = AI, targetRepo = TARGET, orders = SelfDevOrderRepository(db.database),
            operations = SelfDevOperatorRepository(db.database), projects = fx.projects, guard = guard,
        )
        val memory = SelfDevMemoryRouteDeps(
            operators = StubAuth, memory = SelfDevMemoryRepository(db.database, enabled = true), aiPrincipal = AI, projects = fx.projects, guard = guard,
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureRunViewRoutes(db.runs, identity, selfDev, guard, fx.projects)
                configureSelfDevOrderRoutes(orders)
                configureSelfDevMemoryRoutes(memory)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.get(path: String, who: String): HttpResponse = client.request(path) {
        method = HttpMethod.Get
        header(PRINCIPAL, who)
    }

    private suspend fun ApplicationTestBuilder.text(path: String, who: String): String = get(path, who).bodyAsText()

    private fun enabledAutopilot(): AutopilotSettings = AutopilotSettings.fromEnv(
        mapOf(
            AutopilotSettings.ENV_ENABLED to "true",
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        ),
        enabledSelfDev(),
    )

    private fun enabledSelfDev(): SelfDevSettings = SelfDevSettings.fromEnv(
        mapOf(
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
        ),
    )

    private fun countOf(body: String, field: String): Int = Regex("\"$field\":(\\d+)").find(body)!!.groupValues[1].toInt()

    private fun stopIds(body: String): List<String> = Regex("\"requestId\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()

    // ---- OFF: nothing changes ----

    @Test
    fun `project mode off keeps every memory row, count, stop request and active run for every caller`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.OFF), enabledSelfDev())
            // Today's global rules: the old memory list needs READ_TEXT (an OPERATOR has it, an APPROVER does not).
            for (who in listOf(VIEWER, MEMBER, ADMIN)) {
                val memory = text("/api/selfdev/memory", who)
                assertTrue(fx.memoryAlpha in memory && fx.memoryBeta in memory, "every memory row for $who")
                assertEquals(2, countOf(memory, "tombstones"), "every tombstone for $who")
                assertEquals(2, countOf(memory, "deletionAudit"), "every deletion audit for $who")
            }
            val stops = stopIds(text("/api/selfdev/stop-requests?state=PENDING", APPROVER))
            assertTrue(fx.stopAlpha in stops, "every pending stop for the approver")
            assertEquals(3, countOf(text("/api/selfdev/status", MEMBER), "activeRuns"), "every active run in the status")
        }
    }

    // ---- ENFORCE: the old list keeps the rows the project role allows ----

    @Test
    fun `enforce the old memory list keeps the rows of the projects where the role holds READ_TEXT`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            val member = text("/api/selfdev/memory", MEMBER)
            assertTrue(fx.memoryAlpha in member, "the OPERATOR of Alpha reads Alpha's memory")
            assertFalse(fx.memoryBeta in member, "not Beta's memory")

            // The global role is OPERATOR (READ_TEXT), but the project role in Alpha is VIEWER, which has no READ_TEXT.
            val viewer = text("/api/selfdev/memory", VIEWER)
            assertFalse(fx.memoryAlpha in viewer, "a VIEWER in Alpha does not read Alpha's memory on the old route")
            assertFalse(fx.memoryBeta in viewer)
            assertEquals(HttpStatusCode.Forbidden, get("/api/projects/${fx.alpha}/memory", VIEWER).status, "the alias keeps its 403 for the VIEWER")
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.alpha}/memory", MEMBER).status, "the alias for the OPERATOR")

            assertTrue(fx.memoryAlpha in text("/api/selfdev/memory", ADMIN) && fx.memoryBeta in text("/api/selfdev/memory", ADMIN), "the global ADMIN")
        }
    }

    @Test
    fun `enforce the memory counts cover the caller's projects, and the deletion audit is 0 for a narrowed view`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            val admin = text("/api/selfdev/memory", ADMIN)
            assertEquals(2, countOf(admin, "tombstones"), "the global ADMIN counts every tombstone")
            assertEquals(2, countOf(admin, "deletionAudit"), "and every deletion audit row")
            val member = text("/api/selfdev/memory", MEMBER)
            assertEquals(1, countOf(member, "tombstones"), "the member counts its own project's tombstone only")
            assertEquals(0, countOf(member, "deletionAudit"), "the audit rows carry no project: a narrowed view cannot count them")
            val viewer = text("/api/selfdev/memory", VIEWER)
            assertEquals(0, countOf(viewer, "tombstones"), "a VIEWER in Alpha counts none")
            assertEquals(0, countOf(text("/api/selfdev/memory", OUTSIDER), "tombstones"), "an outsider counts none")
        }
    }

    @Test
    fun `enforce the old stop request list keeps the rows of the projects where the role holds STOP_DECIDE`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            // The approver is an APPROVER globally (STOP_DECIDE), but an OPERATOR in Alpha, which has no STOP_DECIDE.
            val approver = text("/api/selfdev/stop-requests?state=PENDING", APPROVER)
            assertFalse(fx.stopAlpha in approver, "the OPERATOR role of Alpha does not decide Alpha's stop request")
            assertEquals(HttpStatusCode.Forbidden, get("/api/projects/${fx.alpha}/approvals?state=PENDING", APPROVER).status, "the alias keeps its 403")
            assertTrue(fx.stopAlpha in text("/api/selfdev/stop-requests?state=PENDING", ADMIN), "the global ADMIN decides every stop request")

            // Alpha's request ends, and Beta's is made: the APPROVER role of Beta decides it.
            fx.endStop(fx.stopAlpha)
            val betaStop = fx.betaStop()
            assertTrue(betaStop in text("/api/selfdev/stop-requests?state=PENDING", APPROVER), "the APPROVER role of Beta decides Beta's stop request")
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.beta}/approvals?state=PENDING", APPROVER).status)
            assertTrue(betaStop in text("/api/projects/${fx.beta}/approvals?state=PENDING", APPROVER), "the alias of Beta lists it")
        }
    }

    @Test
    fun `enforce the status count covers the caller's projects, and every run for the global ADMIN`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE), enabledSelfDev())
            assertEquals(3, countOf(text("/api/selfdev/status", ADMIN), "activeRuns"), "the global ADMIN counts every active run")
            assertEquals(1, countOf(text("/api/selfdev/status", MEMBER), "activeRuns"), "the member counts Alpha's run only")
            assertEquals(0, countOf(text("/api/selfdev/status", OUTSIDER), "activeRuns"), "an outsider counts none")
            assertEquals(1, countOf(text("/api/selfdev/status", VIEWER), "activeRuns"))
            // Unchanged by the narrowing: the setting itself.
            assertTrue("\"enabled\":true" in text("/api/selfdev/status", OUTSIDER))
        }
    }

    // ---- SHADOW: the same answers as OFF, and the rows the project role would hide are logged ----

    @Test
    fun `shadow answers the old memory and stop lists as before, and logs the rows the role would hide`() = TestDb().use { db ->
        val fx = Fixture(db)
        val log = Log()
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.SHADOW, log))
            val viewer = text("/api/selfdev/memory", VIEWER)
            assertTrue(fx.memoryAlpha in viewer, "shadow: the VIEWER still reads Alpha's memory on the old route")
            val approver = text("/api/selfdev/stop-requests?state=PENDING", APPROVER)
            assertTrue(fx.stopAlpha in approver, "shadow: the approver still sees Alpha's stop request")
        }
        val text = log.lines.joinToString("\n")
        assertTrue("project read shadow: wouldHide code=PROJECT_READ_NOT_MEMBER" in text && "route=memory.list" in text, "the memory role is logged: $text")
        assertTrue("route=order.stop.list" in text, "the stop request role is logged: $text")
        for (secret in listOf(VIEWER, APPROVER, fx.alpha, fx.beta, fx.memoryAlpha, fx.stopAlpha)) {
            assertFalse(secret in text, "the log names no person and no row")
        }
    }

    // ---- The failed store is the same as before for the role narrowing: nothing of the project is shown ----

    @Test
    fun `enforce with the membership store failing shows no memory and no stop request to a member`() = TestDb().use { db ->
        val fx = Fixture(db)
        val failing = object : ProjectMemberLookup {
            override fun roleIn(projectId: String, assigneeId: String): Role? = throw IllegalStateException("store down")
            override fun rolesOf(assigneeId: String): Map<String, Role> = throw IllegalStateException("store down")
        }
        val guard = PermissionGuard(
            PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, AI, ROOT, fx.owners, PermissionMode.ENFORCE, failing),
            Log(),
        )
        testApplication {
            setUp(db, fx, guard)
            assertFalse(fx.memoryAlpha in text("/api/selfdev/memory", MEMBER), "a failed store shows the member no memory")
            assertFalse(fx.stopAlpha in text("/api/selfdev/stop-requests?state=PENDING", APPROVER), "a failed store shows the approver no stop request")
            // The global ADMIN does not ask the store, so it still reads every row.
            assertTrue(fx.memoryAlpha in text("/api/selfdev/memory", ADMIN))
        }
    }

    companion object {
        private const val PRINCIPAL = "X-Test-Principal"
        private const val ADMIN = "1:i:owner-admin"
        private const val MEMBER = "1:i:owner-member"
        private const val VIEWER = "1:i:owner-viewer"
        private const val APPROVER = "1:i:owner-approver"
        private const val OUTSIDER = "1:i:owner-outsider"
        private const val AI = "1:i:owner-ai"
        private const val ROOT = "1:i:owner-root"
        private const val TARGET = "kigawa-net/kivra"    }
}
