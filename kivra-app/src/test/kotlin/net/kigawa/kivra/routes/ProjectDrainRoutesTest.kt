package net.kigawa.kivra.routes

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
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
import java.math.BigDecimal
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.MemberPutResult
import net.kigawa.kivra.db.MemorySaveResult
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.RepoAddResult
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.SelfDevMemoryRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.newDbStore
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.ReserveResult
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * The drain of a project (ADR 0022): `POST /api/projects/{id}/drain`. Every row moves to the target, the source's members and budget row
 * are removed (the last ADMIN included), and nothing is deleted, so the emptied project can be deleted. Covers the dry run, idempotence,
 * the checks of the target, auth and CSRF, the busy refusals (a Run that is not terminal, a reservation in progress), and the audit line.
 */
class ProjectDrainRoutesTest {
    private val clock: Clock = Clock.systemUTC()
    private val operator = "1:i:ops"
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private class Lookup(private val roles: Map<String, Role>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = roles[ownerKey]?.let { AssigneeAccess(it, true) }
    }

    private fun guard(roles: Map<String, Role>): PermissionGuard = PermissionGuard(
        PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root", Lookup(roles)),
    )

    private val operatorGuard = guard(mapOf("operator" to Role.ADMIN, "viewer" to Role.VIEWER))

    private fun ApplicationTestBuilder.setUp(db: TestDb) {
        val budgets = newDbStore(db, clock)
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    ProjectRepository(db.database, clock),
                    db.runs,
                    identity,
                    operatorGuard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, clock),
                    budgets = budgets,
                    drain = ProjectDrainRepository(db.database, budgets),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod,
        path: String,
        principal: String?,
        body: String? = null,
        contentTypeHeader: ContentType? = ContentType.Application.Json,
        fetchSite: String? = null,
    ) = client.request(path) {
        this.method = method
        principal?.let { header("X-Test-Principal", it) }
        fetchSite?.let { header("Sec-Fetch-Site", it) }
        if (contentTypeHeader != null) contentType(contentTypeHeader)
        body?.let { setBody(it) }
    }

    private fun drainPath(project: String) = "/api/projects/$project/drain"

    private fun drainBody(target: String) = """{"targetProjectId":"$target"}"""

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun TestDb.projectOf(name: String): String = ProjectRepository(database, clock).create(name, null).projectId

    private fun TestDb.assignee(name: String): String {
        val created = assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(database).create(name, AssigneeRole.OPERATOR, "root")).value
        return created.assigneeId
    }

    private fun TestDb.task(key: String, project: String): String = assertIs<SelfDevSubmitResult.Created>(
        SelfDevOperatorRepository(database).submitTask(operator, key, "Task $key", null, "kigawa-net/$key", project),
    ).task.taskId

    private fun TestDb.runOf(key: String): String =
        (runs.submitTask(operator, "run-$key", "Run $key") as TaskSubmitResult.Created).run.runId.value

    /** Gives [project] one row of each kind, a repository, two members (one ADMIN), a budget, and a Run that is terminal (so it does not block). */
    private fun TestDb.populate(project: String, tag: String): Populated {
        val t1 = task("$tag-1", project)
        val t2 = task("$tag-2", project)
        val order = assertIs<CreateRootResult.Created>(
            SelfDevOrderRepository(database).createRoot(operator, "order $tag", "policy", "kigawa-net/$tag", projectId = project),
        ).order.orderId
        val memory = assertIs<MemorySaveResult.Written>(
            SelfDevMemoryRepository(database, enabled = true).save(
                MemoryCandidate(
                    kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operator,
                    goalId = t1, body = "memo $tag",
                ),
            ),
        ).recordId
        val run = runOf(tag)
        assertEquals(true, ProjectRepository(database, clock).assignRunToProject(run, project))
        forceState(run, DevelopmentRunState.COMPLETED)
        val member = assignee("admin-$tag")
        val other = assignee("op-$tag")
        val member2 = assignee("viewer-$tag")
        assertEquals(MemberPutResult.CREATED, ProjectMemberRepository(database, clock).put(project, member, "ADMIN"))
        assertEquals(MemberPutResult.CREATED, ProjectMemberRepository(database, clock).put(project, other, "OPERATOR"))
        assertEquals(MemberPutResult.CREATED, ProjectMemberRepository(database, clock).put(project, member2, "VIEWER"))
        assertEquals(RepoAddResult.ADDED, ProjectRepository(database, clock).addRepo(project, "kigawa-net/$tag"))
        newDbStore(this, clock).setProjectBudget(project, BigDecimal("1000"), BigDecimal("0.8"))
        val asg = assignee("owned-$tag")
        exec("UPDATE selfdev_assignees SET project_id = ? WHERE assignee_id = ?", project, asg)
        return Populated(t1, t2, order, memory, run, asg, member, other)
    }

    private class Populated(
        val task1: String,
        val task2: String,
        val order: String,
        val memory: String,
        val run: String,
        val assignee: String,
        val admin: String,
        val operatorMember: String,
    )

    private fun TestDb.forceState(run: String, state: DevelopmentRunState) = transaction(database) {
        execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", state.name, run)
    }

    /** The rows of [project] across the tables a drain moves or removes. */
    private fun TestDb.rowsOf(project: String): Int = listOf(
        "project_repos", "project_members", "project_budgets", "selfdev_tasks", "selfdev_orders",
        "selfdev_memory_records", "development_runs", "selfdev_assignees",
    ).sumOf { count(it, "project_id = '$project'") }

    private fun TestDb.projectOfRow(table: String, pk: String, id: String): String? = transaction(database) {
        execQuery("SELECT project_id FROM $table WHERE $pk = ?", id) { it.getString(1) }.firstOrNull()
    }

    private fun counts(body: String): Map<String, Long> = Json.parseToJsonElement(body).jsonObject["counts"]!!.jsonObject
        .mapValues { it.value.jsonPrimitive.content.toLong() }

    private fun dryRunOf(body: String): Boolean = Json.parseToJsonElement(body).jsonObject["dryRun"]!!.jsonPrimitive.content.toBoolean()

    @Test
    fun `draining moves every row to the target, and the emptied project can then be deleted`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        val p = db.populate(source, "s")
        // The target has its own member, budget and task. None of them may change.
        val targetAdmin = db.assignee("target-admin")
        assertEquals(MemberPutResult.CREATED, ProjectMemberRepository(db.database, clock).put(target, targetAdmin, "ADMIN"))
        newDbStore(db, clock).setProjectBudget(target, BigDecimal("500"), BigDecimal("0.8"))
        val targetTask = db.task("target-task", target)
        testApplication {
            setUp(db)
            val response = call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                mapOf(
                    "repos" to 1L, "members" to 3L, "budget" to 1L, "tasks" to 2L, "orders" to 1L,
                    "memory" to 1L, "runs" to 1L, "assignees" to 1L,
                ),
                counts(response.bodyAsText()),
            )
            val repoDump = transaction(db.database) {
                execQuery("SELECT repo_key, project_id FROM project_repos") { "${it.getString(1)}->${it.getString(2)}" }
            }
            assertEquals(listOf("kigawa-net/s->$target"), repoDump, "repo rows after the drain (source=$source, target=$target)")
            for (table in listOf("project_repos", "project_members", "project_budgets", "selfdev_tasks", "selfdev_orders", "selfdev_memory_records", "development_runs", "selfdev_assignees")) {
                assertEquals(0, db.count(table, "project_id = '$source'"), "rows left in $table")
            }
            assertEquals(target, db.projectOfRow("selfdev_tasks", "task_id", p.task1))
            assertEquals(target, db.projectOfRow("selfdev_tasks", "task_id", p.task2))
            assertEquals(target, db.projectOfRow("selfdev_orders", "order_id", p.order))
            assertEquals(target, db.projectOfRow("selfdev_memory_records", "record_id", p.memory))
            assertEquals(target, db.projectOfRow("development_runs", "run_id", p.run))
            assertEquals(target, db.projectOfRow("selfdev_assignees", "assignee_id", p.assignee))
            assertEquals(target, db.projectOfRow("project_repos", "repo_key", "kigawa-net/s"))
            assertEquals(target, db.projectOfRow("selfdev_tasks", "task_id", targetTask))
            // The target keeps its own member and budget.
            assertEquals("ADMIN", db.roleOf(target, targetAdmin))
            assertEquals(1, db.count("project_budgets", "project_id = '$target' AND monthly_limit_jpy = 500"))
            assertEquals(0, db.count("project_budgets", "project_id = '$source'"))

            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/projects/$source", "operator", "{}").status)
        }
    }

    @Test
    fun `a dry run counts what a drain would move and writes nothing`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "d")
        val before = db.rowsOf(source)
        testApplication {
            setUp(db)
            val dry = call(HttpMethod.Post, "${drainPath(source)}?dryRun=true", "operator", drainBody(target))
            assertEquals(HttpStatusCode.OK, dry.status)
            assertTrue(dryRunOf(dry.bodyAsText()))
            assertEquals(before, db.rowsOf(source), "the dry run changed nothing")
            assertEquals(0, db.rowsOf(target))

            // The default is a dry run too.
            val defaultDry = call(HttpMethod.Post, drainPath(source), "operator", drainBody(target))
            assertTrue(dryRunOf(defaultDry.bodyAsText()))
            assertEquals(before, db.rowsOf(source))

            val real = call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target))
            assertFalse(dryRunOf(real.bodyAsText()))
            assertEquals(counts(dry.bodyAsText()), counts(real.bodyAsText()), "the dry run counts what the drain moves")
        }
    }

    @Test
    fun `a second drain moves nothing`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "i")
        testApplication {
            setUp(db)
            val first = call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target))
            assertEquals(HttpStatusCode.OK, first.status)
            val again = call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target))
            assertEquals(HttpStatusCode.OK, again.status)
            assertTrue(counts(again.bodyAsText()).values.all { it == 0L }, "nothing left to move")
            assertEquals(0, db.rowsOf(source))
        }
    }

    @Test
    fun `the target is checked like a new project - same, unknown, malformed, or no body`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "v")
        testApplication {
            setUp(db)
            val path = "${drainPath(source)}?dryRun=false"
            val same = call(HttpMethod.Post, path, "operator", drainBody(source))
            assertEquals(HttpStatusCode.BadRequest, same.status)
            assertTrue(same.bodyAsText().contains("same_project"))
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, path, "operator", drainBody("01ZZZZZZZZZZZZZZZZZZZZZZZZ")).status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, "operator", drainBody("not-a-ulid")).status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, "operator", "{}").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, path, "operator", "not json").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/projects/01ZZZZZZZZZZZZZZZZZZZZZZZZ/drain?dryRun=false", "operator", drainBody(target)).status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "${drainPath(source)}?dryRun=maybe", "operator", drainBody(target)).status)
            assertTrue(db.rowsOf(source) > 0, "every refusal leaves the source alone")
        }
    }

    @Test
    fun `every member of the source is removed, the last ADMIN too, and the normal removal still refuses it`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        val p = db.populate(source, "m")
        val targetOp = db.assignee("target-op")
        assertEquals(MemberPutResult.CREATED, ProjectMemberRepository(db.database, clock).put(target, targetOp, "OPERATOR"))
        testApplication {
            setUp(db)
            // The ordinary removal of the last ADMIN is still refused (409 last_admin): the drain is the only way.
            val other = p.operatorMember
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/projects/$source/members/$other", "operator", "{}").status)
            val last = call(HttpMethod.Delete, "/api/projects/$source/members/${p.admin}", "operator", "{}")
            assertEquals(HttpStatusCode.Conflict, last.status)
            assertTrue(last.bodyAsText().contains("last_admin"))

            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target)).status)
            assertEquals(0, db.count("project_members", "project_id = '$source'"))
            assertEquals(1, db.count("project_members", "project_id = '$target' AND assignee_id = '$targetOp'"), "the target's members are unchanged")
        }
    }

    @Test
    fun `the repositories are re-pointed, so the target lists them`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "r")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target)).status)
            val onTarget = call(HttpMethod.Get, "/api/projects/$target/repos", "operator").bodyAsText()
            assertTrue(onTarget.contains("kigawa-net/r"), onTarget)
            val onSource = call(HttpMethod.Get, "/api/projects/$source/repos", "operator").bodyAsText()
            assertFalse(onSource.contains("kigawa-net/r"), onSource)
        }
    }

    @Test
    fun `auth and CSRF - no principal is 401, a viewer is 403, no JSON is 415, a cross-site request is 403, and nothing moves`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "a")
        val before = db.rowsOf(source)
        testApplication {
            setUp(db)
            val path = "${drainPath(source)}?dryRun=false"
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, path, null, drainBody(target)).status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, path, "viewer", drainBody(target)).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, call(HttpMethod.Post, path, "operator", drainBody(target), contentTypeHeader = null).status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, path, "operator", drainBody(target), fetchSite = "cross-site").status)
            assertEquals(before, db.rowsOf(source))
        }
    }

    @Test
    fun `a Run that is not terminal blocks the drain with 409, in a dry run too, and nothing moves`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "b")
        val active = db.runOf("active")
        assertEquals(true, ProjectRepository(db.database, clock).assignRunToProject(active, source))
        val before = db.rowsOf(source)
        testApplication {
            setUp(db)
            val dry = call(HttpMethod.Post, "${drainPath(source)}?dryRun=true", "operator", drainBody(target))
            assertEquals(HttpStatusCode.Conflict, dry.status)
            assertTrue(dry.bodyAsText().contains("PROJECT_BUSY"))
            val real = call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target))
            assertEquals(HttpStatusCode.Conflict, real.status)
            assertEquals(mapOf("activeRuns" to 1L, "reservedBudget" to 0L), counts(real.bodyAsText()))
            assertEquals(before, db.rowsOf(source), "a refused drain writes nothing")

            // Once the Run is terminal, the same drain goes through.
            db.forceState(active, DevelopmentRunState.CANCELLED)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target)).status)
            assertEquals(0, db.rowsOf(source))
        }
    }

    @Test
    fun `a reservation in progress under the source's scope blocks the drain, and its rows are rolled back`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "res")
        val budgets = newDbStore(db, clock)
        val scopes = budgets.scopesFor(source)
        assertEquals(2, scopes.size, "a limited project's admission has its scope")
        assertEquals(ReserveResult.Reserved, budgets.reserve("res-key", scopes, BudgetAmount(BigDecimal("100"), "JPY", Cost.UNIT_CURRENCY_UNITS)))
        val before = db.rowsOf(source)
        testApplication {
            setUp(db)
            val real = call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target))
            assertEquals(HttpStatusCode.Conflict, real.status)
            assertEquals(mapOf("activeRuns" to 0L, "reservedBudget" to 1L), counts(real.bodyAsText()))
            // The rows were moved before the check, and the check refused: the transaction rolled them back.
            assertEquals(before, db.rowsOf(source), "the refusal rolls every moved row back")
            assertEquals(0, db.rowsOf(target))
            assertEquals(1, db.count("project_budgets", "project_id = '$source'"), "the budget row is kept on refusal")

            budgets.release("res-key")
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target)).status)
            assertEquals(0, db.rowsOf(source))
        }
    }

    @Test
    fun `the audit line has the fixed code, the mode, the result and the counts, and no id or repository name`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        db.populate(source, "log")
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            testApplication {
                setUp(db)
                assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "${drainPath(source)}?dryRun=false", "operator", drainBody(target)).status)
            }
        } finally {
            root.detachAppender(appender)
        }
        val lines = appender.list.filter { it.loggerName == "net.kigawa.kivra.project.admin" }.map { it.formattedMessage }
        val drain = lines.single { it.contains("action=PROJECT_DRAIN") }
        assertTrue(drain.contains("mode=EXECUTE result=OK"), drain)
        assertTrue(drain.contains("counts=repos=1,members=3,budget=1,tasks=2,orders=1,memory=1,runs=1,assignees=1"), drain)
        assertFalse(drain.contains(source) || drain.contains(target) || drain.contains("kigawa-net/"), drain)
    }

    private fun TestDb.roleOf(project: String, assignee: String): String? = transaction(database) {
        execQuery("SELECT role FROM project_members WHERE project_id = ? AND assignee_id = ?", project, assignee) { it.getString(1) }
            .firstOrNull()
    }
}
