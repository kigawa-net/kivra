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
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectMemberStore
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.newDbStore
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.OrderSettings
import net.kigawa.kivra.selfdev.SelfDevSettings
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryChannel

/**
 * The read scope of the projects (ADR 0022 PR-D3) on the real routes and H2. Three callers: the global ADMIN, a member of Alpha (and no
 * other project), and an outsider (no project). Rows: Alpha's, Beta's (the member left Beta), and rows with no project.
 *
 *  - OFF: every row, as before. No membership store is read.
 *  - SHADOW: every row, as before. The rows ENFORCE would hide are logged with fixed codes, and no id or person is in the log.
 *  - ENFORCE: a list shows the member's projects only (the global ADMIN all, the rows with no project for the global ADMIN only). A single
 *    row or an alias of a project the caller may not read is a 404, the same as an unknown project. A failed store shows nothing.
 *
 * The autonomous loops are not on these routes: the dispatcher's queries are checked in `ProjectScopeReadTest`, and they take every row.
 */
class ProjectReadScopeTest {
    private val clock: Clock = Clock.systemUTC()
    private val identity = TaskApiIdentity { headers -> headers[PRINCIPAL]?.takeIf { it.isNotBlank() } }

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

    private object StubAuth : OperatorAuthenticator {
        override suspend fun authenticate(headers: Headers): OperatorAuth = headers[PRINCIPAL]?.takeIf { it.isNotBlank() }
            ?.let { OperatorAuth.Authenticated(it) } ?: OperatorAuth.Unauthenticated
    }

    /** The data of one test: two projects, three callers, and the rows of each kind. */
    private inner class Fixture(val db: TestDb) {
        val projects = ProjectRepository(db.database, clock)
        val alpha: String = projects.create("Alpha", null).projectId
        val beta: String = projects.create("Beta", null).projectId
        private val adminAssignee = assignee("admin", AssigneeRole.ADMIN)
        private val memberAssignee = assignee("member", AssigneeRole.OPERATOR)
        private val outsiderAssignee = assignee("outsider", AssigneeRole.OPERATOR)
        val store = Members(ProjectMemberStore(db.database))

        init {
            // The member belongs to Alpha only. In Beta it has rows, from before it left.
            ProjectMemberRepository(db.database, clock).put(alpha, memberAssignee, "OPERATOR")
        }

        private fun assignee(name: String, role: AssigneeRole): String =
            assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(db.database).create(name, role, "root")).value.assigneeId

        private val owners = Owners(
            mapOf(
                ADMIN to AssigneeAccess(Role.ADMIN, true, adminAssignee),
                MEMBER to AssigneeAccess(Role.OPERATOR, true, memberAssignee),
                OUTSIDER to AssigneeAccess(Role.OPERATOR, true, outsiderAssignee),
            ),
        )

        /** The guard of the roles ENFORCE with the given project mode. [projectMode] OFF is the roles on with no project check. */
        fun guard(projectMode: PermissionMode, log: GuardLog = Log()): PermissionGuard = PermissionGuard(
            PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, AI, ROOT, owners, projectMode, store),
            log,
        )

        val runAlpha = run("run-a", alpha, MEMBER)
        val runBeta = run("run-b", beta, MEMBER)
        val runNone = run("run-n", null, MEMBER)
        val runBetaAdmin = run("run-ba", beta, ADMIN)

        val goalAlpha = goal("goal-a", alpha)
        val goalBeta = goal("goal-b", beta)

        val orderAlpha = order("order-a", alpha)
        val orderBeta = order("order-b", beta)

        /** Goal titles: the memory rows take the project of their goal. */
        val memoryAlpha = memory(goalAlpha, "memory a")
        val memoryBeta = memory(goalBeta, "memory b")

        private fun run(key: String, projectId: String?, principal: String): String =
            (db.runs.submitTask(principal, key, "Run $key", projectId = projectId) as TaskSubmitResult.Created).run.runId.value

        private fun goal(key: String, projectId: String?): String =
            assertIs<SelfDevSubmitResult.Created>(
                SelfDevOperatorRepository(db.database).submitTask(MEMBER, key, "Goal $key", null, TARGET, projectId),
            ).task.taskId

        private fun order(key: String, projectId: String?): String =
            assertIs<CreateRootResult.Created>(
                SelfDevOrderRepository(db.database).createRoot(MEMBER, "Order $key", "policy", TARGET, 1, projectId),
            ).order.orderId

        private fun memory(goalId: String, body: String): String =
            SelfDevMemoryRepository(db.database, enabled = true).save(
                MemoryCandidate(kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = MEMBER, goalId = goalId, body = body),
            ).let { (it as MemorySaveResult.Written).recordId }
    }

    /** The routes that read the projects, on one application, with the given guard. */
    private fun ApplicationTestBuilder.setUp(db: TestDb, fx: Fixture, guard: PermissionGuard) {
        val autopilot = enabledAutopilot()
        val goals = SelfDevGoalRouteDeps(
            operators = StubAuth, autopilot = autopilot, targetRepo = TARGET, operations = SelfDevOperatorRepository(db.database),
            projects = fx.projects, control = SelfDevAutopilotControlRepository(db.database), aiPrincipal = AI, guard = guard,
        )
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
                configureProjectRoutes(
                    fx.projects, db.runs, identity, guard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, clock),
                    budgets = newDbStore(db, clock),
                )
                configureRunViewRoutes(db.runs, identity, SelfDevSettings.disabled(), guard, fx.projects)
                configureTaskApiRoutes(db.runs, identity, guard = guard, projectRepo = fx.projects)
                configureSelfDevGoalRoutes(goals)
                configureSelfDevOrderRoutes(orders)
                configureSelfDevMemoryRoutes(memory)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.get(path: String, who: String): HttpResponse = client.request(path) {
        method = HttpMethod.Get
        header(PRINCIPAL, who)
    }

    private suspend fun HttpResponse.text(): String = bodyAsText()

    /** The status and the body of a request, for the comparison of two answers. */
    private data class Answer(val status: HttpStatusCode, val body: String)

    private suspend fun ApplicationTestBuilder.answer(path: String, who: String): Answer {
        val r = get(path, who)
        return Answer(r.status, r.text())
    }

    private fun enabledAutopilot(): AutopilotSettings = AutopilotSettings.fromEnv(
        mapOf(
            AutopilotSettings.ENV_ENABLED to "true",
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
        ),
        SelfDevSettings.fromEnv(
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
        ),
    )

    // ---- OFF: nothing changes, and nothing is read ----

    @Test
    fun `project mode off shows every row to every caller, and reads no membership`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.OFF))
            for (who in listOf(OUTSIDER, MEMBER)) {
                val projects = get("/api/projects", who).text()
                assertTrue(fx.alpha in projects && fx.beta in projects, "every project for $who")
                val runs = get("/api/admin/runs?limit=100", who).text()
                assertTrue(fx.runNone in runs && fx.runBeta in runs && fx.runAlpha in runs, "every run for $who")
                assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.beta}", who).status)
                assertEquals(HttpStatusCode.OK, get("/api/selfdev/orders/${fx.orderBeta}", who).status)
            }
            assertEquals(0, fx.store.reads, "OFF reads no membership")
        }
    }

    // ---- SHADOW: the answers are the same as OFF, and the rows ENFORCE would hide are logged ----

    @Test
    fun `shadow answers every read as before and logs the rows enforce would hide, with fixed codes only`() = TestDb().use { db ->
        val fx = Fixture(db)
        val log = Log()
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.SHADOW, log))
            val projects = get("/api/projects", MEMBER).text()
            assertTrue(fx.alpha in projects && fx.beta in projects, "shadow: the member still sees Beta")
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.beta}", MEMBER).status)
            assertEquals(HttpStatusCode.OK, get("/api/selfdev/orders/${fx.orderBeta}", MEMBER).status)
            assertTrue(get("/api/admin/runs?limit=100", MEMBER).text().contains(fx.runNone), "shadow: the row with no project is still listed")
        }
        val text = log.lines.joinToString("\n")
        assertTrue("project read shadow: wouldHide code=PROJECT_READ_NOT_MEMBER" in text, "not-a-member is logged: $text")
        assertTrue("project read shadow: wouldHide code=PROJECT_READ_UNASSIGNED" in text, "no-project row is logged: $text")
        // No person, no project, no row in the log.
        for (secret in listOf(MEMBER, OUTSIDER, fx.alpha, fx.beta, fx.runBeta, fx.orderBeta, "owner-member", "owner-outsider")) {
            // The owner keys, the projects, and the rows of Beta and of no project never reach the log.
            assertFalse(secret in text, "the log names nothing that identifies a person or a row")
        }
    }

    @Test
    fun `shadow never refuses an admin's read and logs nothing for the global ADMIN`() = TestDb().use { db ->
        val fx = Fixture(db)
        val log = Log()
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.SHADOW, log))
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.beta}", ADMIN).status)
            assertTrue(get("/api/admin/runs?limit=100", ADMIN).text().contains(fx.runNone))
        }
        assertTrue(log.lines.none { "project read shadow" in it }, "the global ADMIN is not in the shadow's count of refusals")
    }

    // ---- ENFORCE: the lists show the member's projects, the single rows are a 404 when not visible ----

    @Test
    fun `enforce lists the projects the caller is a member of, the global ADMIN all of them`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            val member = get("/api/projects", MEMBER).text()
            assertTrue(fx.alpha in member, "a member sees its project")
            assertFalse(fx.beta in member, "a member does not see another project")
            assertEquals("[]", get("/api/projects", OUTSIDER).text().trim(), "a caller with no membership lists no project")
            val admin = get("/api/projects", ADMIN).text()
            assertTrue(fx.alpha in admin && fx.beta in admin, "the global ADMIN sees every project")
        }
    }

    @Test
    fun `enforce answers a project the caller may not read as 404, the same as an unknown one`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            val notMember = answer("/api/projects/${fx.beta}", MEMBER)
            val unknown = answer("/api/projects/01ZZZZZZZZZZZZZZZZZZZZZZZZ", MEMBER)
            assertEquals(HttpStatusCode.NotFound, notMember.status)
            assertEquals(unknown, notMember, "the answer does not tell a project it cannot read from an unknown one")
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.alpha}", MEMBER).status)
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.beta}", ADMIN).status)
        }
    }

    @Test
    fun `enforce answers the repos, budget and dashboard of a project outside the membership as a 404`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            for (path in listOf("repos", "budget", "dashboard")) {
                assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.beta}/$path", MEMBER).status, "$path of Beta for the member")
                assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.alpha}/$path", MEMBER).status, "$path of Alpha for the member")
            }
            assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.alpha}/members", OUTSIDER).status, "members of Alpha for an outsider")
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.beta}/members", ADMIN).status, "members of Beta for the global ADMIN")
        }
    }

    @Test
    fun `enforce narrows the admin run list in SQL, so the limit counts only the rows the caller may read`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            val member = get("/api/admin/runs?limit=100", MEMBER).text()
            assertTrue(fx.runAlpha in member, "the member sees its project's run")
            assertFalse(fx.runBeta in member, "not Beta's run, although the member created it")
            assertFalse(fx.runNone in member, "not the run with no project")
            assertFalse(fx.runBetaAdmin in member)
            assertFalse(get("/api/admin/runs?limit=100", OUTSIDER).text().contains("runId"), "an outsider lists no run")
            val admin = get("/api/admin/runs?limit=100", ADMIN).text()
            assertTrue(fx.runNone in admin && fx.runBeta in admin && fx.runAlpha in admin, "the global ADMIN lists every run, the ones with no project too")
            // The unassigned choice is empty for the member, and the Beta choice too.
            assertFalse(get("/api/admin/runs?limit=100&projectId=unassigned", MEMBER).text().contains("runId"))
            assertFalse(get("/api/admin/runs?limit=100&projectId=${fx.beta}", MEMBER).text().contains("runId"))
            assertTrue(fx.runAlpha in get("/api/admin/runs?limit=100&projectId=${fx.alpha}", MEMBER).text())
        }
    }

    @Test
    fun `enforce answers a run of a project the caller may not read as 404 on the admin and the Task API routes`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            assertEquals(HttpStatusCode.OK, get("/api/admin/runs/${fx.runAlpha}", MEMBER).status)
            assertEquals(HttpStatusCode.NotFound, get("/api/admin/runs/${fx.runBeta}", MEMBER).status)
            assertEquals(HttpStatusCode.NotFound, get("/api/admin/runs/${fx.runNone}", MEMBER).status)
            assertEquals(HttpStatusCode.OK, get("/api/admin/runs/${fx.runNone}", ADMIN).status)
            // The Task API: the member's own run in Beta is hidden, as is a run it does not own.
            assertEquals(HttpStatusCode.OK, get("/api/runs/${fx.runAlpha}", MEMBER).status)
            assertEquals(HttpStatusCode.NotFound, get("/api/runs/${fx.runBeta}", MEMBER).status)
            assertEquals(HttpStatusCode.NotFound, get("/api/runs/${fx.runNone}", MEMBER).status)
            val taskList = get("/api/runs", MEMBER).text()
            assertTrue(fx.runAlpha in taskList)
            assertFalse(fx.runBeta in taskList, "the member's own run in Beta is not listed once the member left Beta")
            assertFalse(fx.runNone in taskList)
        }
    }

    @Test
    fun `enforce narrows the goal, order and memory lists, and a goal or an order outside the membership is a 404`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            val goals = get("/api/selfdev/goals", MEMBER).text()
            assertTrue(fx.goalAlpha in goals)
            assertFalse(fx.goalBeta in goals)
            assertTrue(fx.goalAlpha in get("/api/projects/${fx.alpha}/tasks", MEMBER).text())
            assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.beta}/tasks", MEMBER).status, "the alias of Beta")
            assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.beta}/tasks", OUTSIDER).status, "the alias of Beta, outsider")
            assertEquals(HttpStatusCode.NotFound, get("/api/selfdev/goals/${fx.goalBeta}", MEMBER).status)
            assertEquals(HttpStatusCode.OK, get("/api/selfdev/goals/${fx.goalAlpha}", MEMBER).status)

            val orders = get("/api/selfdev/orders", MEMBER).text()
            assertTrue(fx.orderAlpha in orders)
            assertFalse(fx.orderBeta in orders)
            assertEquals(HttpStatusCode.NotFound, get("/api/selfdev/orders/${fx.orderBeta}", MEMBER).status)
            assertEquals(HttpStatusCode.NotFound, get("/api/selfdev/orders/${fx.orderBeta}/cycles", MEMBER).status)
            assertEquals(HttpStatusCode.OK, get("/api/selfdev/orders/${fx.orderAlpha}", MEMBER).status)

            val memory = get("/api/selfdev/memory", MEMBER).text()
            assertTrue(fx.memoryAlpha in memory, "the member sees the memory of its project")
            assertFalse(fx.memoryBeta in memory)
            assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.beta}/memory", MEMBER).status)
            assertTrue(fx.memoryBeta in get("/api/projects/${fx.beta}/memory", ADMIN).text(), "the global ADMIN reads Beta's memory")
        }
    }

    @Test
    fun `enforce refuses the approver list of an alias outside the membership as a 404, and keeps the role check inside it`() =
        TestDb().use { db ->
            val fx = Fixture(db)
            testApplication {
                setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
                assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.beta}/approvals?state=PENDING", MEMBER).status)
                assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.beta}/approvals?state=PENDING", OUTSIDER).status)
                // The member is in Alpha, but its OPERATOR role has no STOP_DECIDE: the project role check still refuses it.
                assertEquals(HttpStatusCode.Forbidden, get("/api/projects/${fx.alpha}/approvals?state=PENDING", MEMBER).status)
            }
        }

    @Test
    fun `enforce with a failed membership store shows nothing, and the answers are 404 and empty lists, never the rows`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            fx.store.fail = true
            assertFalse(get("/api/projects", MEMBER).text().contains(fx.alpha))
            assertEquals(HttpStatusCode.NotFound, get("/api/projects/${fx.alpha}", MEMBER).status)
            assertEquals(HttpStatusCode.NotFound, get("/api/selfdev/orders/${fx.orderAlpha}", MEMBER).status)
            assertFalse(get("/api/admin/runs?limit=100", MEMBER).text().contains("runId"))
            assertFalse(get("/api/selfdev/goals", MEMBER).text().contains(fx.goalAlpha))
            // The global ADMIN does not ask the store.
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.alpha}", ADMIN).status)
        }
    }

    @Test
    fun `shadow with a failed membership store answers as before, and logs the lookup failure`() = TestDb().use { db ->
        val fx = Fixture(db)
        val log = Log()
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.SHADOW, log))
            fx.store.fail = true
            assertEquals(HttpStatusCode.OK, get("/api/projects/${fx.alpha}", MEMBER).status)
        }
        assertTrue(log.lines.any { "code=PROJECT_LOOKUP_FAILED" in it }, "the lookup failure is logged")
    }

    @Test
    fun `enforce refuses a caller with no assignee at the global check, before any project read`() = TestDb().use { db ->
        val fx = Fixture(db)
        testApplication {
            setUp(db, fx, fx.guard(PermissionMode.ENFORCE))
            // An owner key that is bound to no assignee: the global check refuses it before the project check.
            assertEquals(HttpStatusCode.Forbidden, get("/api/projects", "1:i:nobody").status)
        }
    }

    /** The autonomous loops are not narrowed: the same rows, with no project, are still in their default queries. */
    @Test
    fun `the autonomous queries keep the rows with no project and the rows of every project`() = TestDb().use { db ->
        val fx = Fixture(db)
        val ops = SelfDevOperatorRepository(db.database)
        val ids = ops.listTasks(100).map { it.taskId }.toSet()
        assertTrue(fx.goalBeta in ids && fx.goalAlpha in ids)
        assertTrue(db.runs.listAllTasks(100).map { it.runId }.containsAll(listOf(RunId(fx.runNone), RunId(fx.runBeta))))
    }

    companion object {
        private const val PRINCIPAL = "X-Test-Principal"
        private const val ADMIN = "1:i:owner-admin"
        private const val MEMBER = "1:i:owner-member"
        private const val OUTSIDER = "1:i:owner-outsider"
        private const val AI = "1:i:owner-ai"
        private const val ROOT = "1:i:owner-root"
        private const val TARGET = "kigawa-net/kivra"
    }
}
