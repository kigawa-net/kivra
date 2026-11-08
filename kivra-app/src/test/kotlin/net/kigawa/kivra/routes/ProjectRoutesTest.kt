package net.kigawa.kivra.routes

import io.ktor.client.request.header
import io.ktor.client.request.get
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
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import org.jetbrains.exposed.sql.transactions.transaction

class ProjectRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private class Lookup(private val roles: Map<String, Role>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = roles[ownerKey]?.let { AssigneeAccess(it, true) }
    }

    private fun guard(roles: Map<String, Role>): PermissionGuard = PermissionGuard(
        PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root", Lookup(roles)),
    )

    private fun ApplicationTestBuilder.setUp(db: TestDb, guard: PermissionGuard = offPermissionGuard()) {
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    projects, db.runs, identity, guard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, Clock.systemUTC()),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod,
        path: String,
        principal: String?,
        body: String? = null,
    ) = client.request(path) {
        this.method = method
        principal?.let { header("X-Test-Principal", it) }
        if (body != null || method in listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete)) {
            contentType(ContentType.Application.Json)
        }
        body?.let { setBody(it) }
    }

    private fun TestDb.run(principal: String, key: String): String =
        (runs.submitTask(principal, key, "test task") as TaskSubmitResult.Created).run.runId.value

    @Test
    fun `unauthenticated requests are refused on every endpoint without creating a project`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        testApplication {
            setUp(db)
            val runId = db.run("alice", "unauth-run")
            val requests = listOf(
                call(HttpMethod.Get, "/api/projects", null),
                call(HttpMethod.Post, "/api/projects", null, """{"name":"hidden"}"""),
                call(HttpMethod.Get, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA", null),
                call(HttpMethod.Put, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA", null, """{"name":"hidden"}"""),
                call(HttpMethod.Delete, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA", null),
                call(HttpMethod.Get, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA/dashboard", null),
                call(HttpMethod.Put, "/api/runs/$runId/project", null, """{"projectId":"missing"}"""),
                call(HttpMethod.Delete, "/api/runs/$runId/project", null),
            )
            requests.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
            assertTrue(projects.listAll().isEmpty())
        }
    }

    @Test
    fun `an operator can create read update and delete a project`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        testApplication {
            setUp(db)
            val created = call(HttpMethod.Post, "/api/projects", "operator", """{"name":"First","description":"initial"}""")
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals("First", createdJson["name"]!!.jsonPrimitive.content)
            val id = createdJson["projectId"]!!.jsonPrimitive.content

            val list = call(HttpMethod.Get, "/api/projects", "operator")
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains(id))
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/projects/$id", "operator").status)

            val updated = call(HttpMethod.Put, "/api/projects/$id", "operator", """{"name":"Renamed"}""")
            assertEquals(HttpStatusCode.OK, updated.status)
            assertEquals("Renamed", Json.parseToJsonElement(updated.bodyAsText()).jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/projects/$id", "operator").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/$id", "operator").status)
            assertTrue(projects.listAll().isEmpty())
        }
    }

    @Test
    fun `an operator can assign and unassign their own run`() = TestDb().use { db ->
        val runId = db.run("operator", "own-run")
        val projectId = ProjectRepository(db.database, Clock.systemUTC()).create("Project", null).projectId
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Put, "/api/runs/$runId/project", "operator", """{"projectId":"$projectId"}""").status)
            assertEquals(projectId, db.runs.getTask(RunId(runId), "operator")?.projectId)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/runs/$runId/project", "operator").status)
            assertNull(db.runs.getTask(RunId(runId), "operator")?.projectId)
        }
    }

    @Test
    fun `a viewer can read projects but cannot create delete or assign their own run`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val existing = projects.create("Existing", null)
        val runId = db.run("viewer", "viewer-own")
        testApplication {
            setUp(db, guard(mapOf("viewer" to Role.VIEWER)))
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/projects", "viewer").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/projects", "viewer", """{"name":"Denied"}""").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/api/projects/${existing.projectId}", "viewer").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/runs/$runId/project", "viewer", """{"projectId":"${existing.projectId}"}""").status)
        }
    }

    @Test
    fun `a viewer cannot discover another principals run by assigning it`() = TestDb().use { db ->
        val projectId = ProjectRepository(db.database, Clock.systemUTC()).create("Existing", null).projectId
        val otherRun = db.run("other", "viewer-other")
        testApplication {
            setUp(db, guard(mapOf("viewer" to Role.VIEWER, "other" to Role.VIEWER)))
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/runs/$otherRun/project", "viewer", """{"projectId":"$projectId"}""").status)
        }
    }

    @Test
    fun `an operator can assign another principals run`() = TestDb().use { db ->
        val projectId = ProjectRepository(db.database, Clock.systemUTC()).create("Existing", null).projectId
        val otherRun = db.run("other", "operator-other")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Put, "/api/runs/$otherRun/project", "operator", """{"projectId":"$projectId"}""").status)
        }
    }

    /** A reservation row of the budget ledger, with the columns this test needs. Amounts are micro-yen. */
    private fun TestDb.reserve(key: String, micro: Long, state: String) {
        val at = java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2030, 1, 1, 0, 0))
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            ).use { st ->
                st.setString(1, key)
                st.setLong(2, micro)
                st.setString(3, state)
                st.setTimestamp(4, at)
                st.setTimestamp(5, at)
                st.executeUpdate()
            }
        }
    }

    /** A CLI attempt of [runId] (coding_attempts), so that its ledger key `coding:<attemptId>:<generation>` is one of the run's. */
    private fun TestDb.attempt(runId: String, attemptId: String, generation: Long) {
        val at = java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2030, 1, 1, 0, 0))
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO coding_attempts (attempt_id, run_id, attempt_no, state, lease_generation, queued_expires_at, base_sha, version, created_at, updated_at) " +
                    "VALUES (?, ?, 1, 'FAILED', ?, ?, ?, 0, ?, ?)",
            ).use { st ->
                st.setString(1, attemptId)
                st.setString(2, runId)
                st.setLong(3, generation)
                st.setTimestamp(4, at)
                st.setString(5, "a".repeat(40))
                st.setTimestamp(6, at)
                st.setTimestamp(7, at)
                st.executeUpdate()
            }
        }
    }

    @Test
    fun `the operator's dashboard counts every Run of the project, with creators, cost and activity`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val projectId = projects.create("Alpha", "first").projectId
        val mine = db.run("operator", "dash-own")
        val alice = db.run("alice", "dash-alice")
        val outside = db.run("bob", "dash-outside")
        projects.assignRunToProject(mine, projectId)
        projects.assignRunToProject(alice, projectId)
        testApplication {
            setUp(db)
            val response = call(HttpMethod.Get, "/api/projects/$projectId/dashboard", "operator")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            val body = Json.parseToJsonElement(text).jsonObject
            assertEquals("ALL", body["scope"]!!.jsonPrimitive.content)
            val tasks = body["tasks"]!!.jsonObject
            assertEquals(2, tasks["total"]!!.jsonPrimitive.int)
            assertEquals(2, tasks["byState"]!!.jsonObject.values.sumOf { it.jsonPrimitive.int })
            val runs = body["recentRuns"]!!.jsonArray.map { it.jsonObject }
            assertEquals(setOf(mine, alice), runs.map { it["runId"]!!.jsonPrimitive.content }.toSet())
            assertEquals(setOf("operator", "alice"), runs.map { it["createdBy"]!!.jsonPrimitive.content }.toSet())
            assertEquals("0", body["spend"]!!.jsonObject["totalJpy"]!!.jsonPrimitive.content)
            val activity = body["activity"]!!.jsonArray.map { it.jsonObject["runId"]!!.jsonPrimitive.content }
            assertTrue(activity.isNotEmpty(), "the Runs' creation is in the activity")
            assertTrue(activity.all { it == mine || it == alice })
            assertFalse(text.contains(outside), "a Run outside the project is not in the dashboard")
        }
    }

    @Test
    fun `a viewer's dashboard counts and lists only their own Runs of the project`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val projectId = projects.create("Alpha", null).projectId
        val viewerRun = db.run("viewer", "dash-viewer")
        val aliceRun = db.run("alice", "dash-alice-viewer")
        projects.assignRunToProject(viewerRun, projectId)
        projects.assignRunToProject(aliceRun, projectId)
        testApplication {
            setUp(db, guard(mapOf("viewer" to Role.VIEWER)))
            val response = call(HttpMethod.Get, "/api/projects/$projectId/dashboard", "viewer")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            val body = Json.parseToJsonElement(text).jsonObject
            assertEquals("OWN", body["scope"]!!.jsonPrimitive.content)
            assertEquals(1, body["tasks"]!!.jsonObject["total"]!!.jsonPrimitive.int)
            val runs = body["recentRuns"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(viewerRun), runs.map { it["runId"]!!.jsonPrimitive.content })
            assertTrue(runs.all { (it["createdBy"] ?: JsonNull) is JsonNull }, "the creator is shown to the operator only")
            assertFalse(text.contains(aliceRun), "another principal's Run is not in the dashboard")
        }
    }

    @Test
    fun `the dashboard cost is the ledger of the project's Runs by state, in yen`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val projectId = projects.create("Alpha", null).projectId
        val runId = db.run("operator", "cost-run")
        val outside = db.run("operator", "cost-outside")
        projects.assignRunToProject(runId, projectId)
        val attemptId = "01ATT" + "A".repeat(21)
        db.attempt(runId, attemptId, 3)
        db.reserve("coding:$attemptId:3", 1_500_000, "COMMITTED")
        db.reserve("developer-$runId-1", 250_000, "RESERVED")
        db.reserve("developer-$runId-2", 999_000_000, "RELEASED")
        db.reserve("developer-$outside-1", 5_000_000, "COMMITTED")
        db.reserve("planner-$runId-1", 7_000_000, "COMMITTED")
        testApplication {
            setUp(db)
            val response = call(HttpMethod.Get, "/api/projects/$projectId/dashboard", "operator")
            assertEquals(HttpStatusCode.OK, response.status)
            val spend = Json.parseToJsonElement(response.bodyAsText()).jsonObject["spend"]!!.jsonObject
            assertEquals("1.5", spend["committedJpy"]!!.jsonPrimitive.content, "CLI attempt, COMMITTED")
            assertEquals("0.25", spend["reservedJpy"]!!.jsonPrimitive.content, "API call of the run, RESERVED")
            assertEquals("0", spend["lapsedJpy"]!!.jsonPrimitive.content)
            assertEquals("0", spend["unpricedJpy"]!!.jsonPrimitive.content)
            assertEquals("1.75", spend["totalJpy"]!!.jsonPrimitive.content, "RELEASED, another run and the planner are not counted")
        }
    }

    @Test
    fun `the dashboard of a missing project is not found`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA/dashboard", "operator").status)
        }
    }

    @Test
    fun `missing projects and runs return not found`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA", "operator").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/runs/01AAAAAAAAAAAAAAAAAAAAAAAA/project", "operator", """{"projectId":"missing"}""").status)
        }
    }
    @Test
    fun `an operator can list add and remove the repositories of a project`() = TestDb().use { db ->
        val projectId = ProjectRepository(db.database, Clock.systemUTC()).create("Repos", null).projectId
        testApplication {
            setUp(db)
            val path = "/api/projects/$projectId/repos"
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, path, "operator").status)
            assertEquals("[]", call(HttpMethod.Get, path, "operator").bodyAsText())

            val added = call(HttpMethod.Put, path, "operator", """{"repoKey":"kigawa-net/kivra"}""")
            assertEquals(HttpStatusCode.Created, added.status)
            assertEquals("kigawa-net/kivra", Json.parseToJsonElement(added.bodyAsText()).jsonObject["repoKey"]!!.jsonPrimitive.content)
            assertTrue(call(HttpMethod.Get, path, "operator").bodyAsText().contains("kigawa-net/kivra"))

            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "$path?repoKey=kigawa-net%2Fkivra", "operator", "{}").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "$path?repoKey=kigawa-net%2Fkivra", "operator", "{}").status)
            assertEquals("[]", call(HttpMethod.Get, path, "operator").bodyAsText())
        }
    }

    @Test
    fun `a repository that already belongs to a project is a conflict, a missing project is not found, a bad name is refused`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val alpha = projects.create("Alpha", null).projectId
        val beta = projects.create("Beta", null).projectId
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "/api/projects/$alpha/repos", "operator", """{"repoKey":"kigawa-net/shared"}""").status)
            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "/api/projects/$beta/repos", "operator", """{"repoKey":"kigawa-net/shared"}""").status)
            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "/api/projects/$alpha/repos", "operator", """{"repoKey":"kigawa-net/shared"}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA/repos", "operator", """{"repoKey":"kigawa-net/other"}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA/repos", "operator").status)
            for (bad in listOf("no-slash", "a/b/c", "../etc", "kigawa-net/..", "kigawa-net/x.git", " kigawa-net/kivra", "")) {
                assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/projects/$beta/repos", "operator", """{"repoKey":"$bad"}""").status, bad)
            }
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/api/projects/$alpha/repos?repoKey=no-slash", "operator", "{}").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/api/projects/$alpha/repos", "operator", "{}").status)
            // The mapping is still there and still belongs to alpha.
            assertEquals(listOf("kigawa-net/shared"), projects.listRepos(alpha))
            assertEquals(emptyList(), projects.listRepos(beta))
        }
    }

    @Test
    fun `a viewer reads the repositories but cannot change them or run the backfill`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val projectId = projects.create("Viewed", null).projectId
        projects.addRepo(projectId, "kigawa-net/viewed")
        testApplication {
            setUp(db, guard(mapOf("viewer" to Role.VIEWER)))
            val path = "/api/projects/$projectId/repos"
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, path, "viewer").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, path, "viewer", """{"repoKey":"kigawa-net/denied"}""").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "$path?repoKey=kigawa-net%2Fviewed", "viewer", "{}").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/projects/backfill?dryRun=false", "viewer", "{}").status)
            assertEquals(listOf("kigawa-net/viewed"), projects.listRepos(projectId))
        }
    }

    @Test
    fun `an unauthenticated request cannot read or change repositories or run the backfill`() = TestDb().use { db ->
        val projectId = ProjectRepository(db.database, Clock.systemUTC()).create("Hidden", null).projectId
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/projects/$projectId/repos", null).status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Put, "/api/projects/$projectId/repos", null, """{"repoKey":"kigawa-net/x"}""").status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Delete, "/api/projects/$projectId/repos?repoKey=kigawa-net%2Fx", null).status)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/projects/backfill?dryRun=false", null, "{}").status)
        }
    }

    @Test
    fun `the backfill is a dry run by default and writes only when asked to`() = TestDb().use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val alpha = projects.create("Alpha", null).projectId
        projects.addRepo(alpha, "kigawa-net/alpha")
        val taskId = assertIs<SelfDevSubmitResult.Created>(
            SelfDevOperatorRepository(db.database).submitTask("operator", "route-task", "Task", null, "kigawa-net/alpha", null),
        ).task.taskId
        fun projectOfTask(): String? = transaction(db.database) {
            execQuery("SELECT project_id FROM selfdev_tasks WHERE task_id = ?", taskId) { it.getString(1) }.first()
        }
        testApplication {
            setUp(db)
            val dry = call(HttpMethod.Post, "/api/projects/backfill", "operator", "{}")
            assertEquals(HttpStatusCode.OK, dry.status)
            val dryJson = Json.parseToJsonElement(dry.bodyAsText()).jsonObject
            assertTrue(dryJson["dryRun"]!!.jsonPrimitive.content.toBoolean())
            val tasks = dryJson["tables"]!!.jsonArray.map { it.jsonObject }.first { it["table"]!!.jsonPrimitive.content == "selfdev_tasks" }
            assertEquals(1, tasks["matched"]!!.jsonPrimitive.int)
            assertEquals(0, tasks["updated"]!!.jsonPrimitive.int)
            assertNull(projectOfTask(), "a dry run writes nothing")

            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/projects/backfill?dryRun=maybe", "operator", "{}").status)
            assertNull(projectOfTask())

            val real = call(HttpMethod.Post, "/api/projects/backfill?dryRun=false", "operator", "{}")
            assertEquals(HttpStatusCode.OK, real.status)
            assertEquals(alpha, projectOfTask())
        }
    }

    @Test
    fun `the backfill refuses a request that is not JSON, as the other state-changing routes do`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = client.request("/api/projects/backfill?dryRun=false") {
                method = HttpMethod.Post
                header("X-Test-Principal", "operator")
                contentType(ContentType.Text.Plain)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }
    }
}
