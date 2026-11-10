package net.kigawa.kivra.routes

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
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.newDbStore
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A path parameter is the path's, even when a query of the same name is given (ADR 0022 follow-up, the path shadowing): on every
 * `/api/projects/{projectId}/...` route, `?projectId=B` must not move the request to project B. Each test puts the data in A and asks with
 * `?projectId=B`; the answer (or the write) is for A.
 */
class ProjectPathShadowingTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private class Lookup(private val roles: Map<String, Role>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = roles[ownerKey]?.let { AssigneeAccess(it, true) }
    }

    private val operatorGuard = PermissionGuard(
        PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root", Lookup(mapOf("operator" to Role.ADMIN))),
    )

    private fun ApplicationTestBuilder.setUp(db: TestDb) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    ProjectRepository(db.database, Clock.systemUTC()),
                    db.runs,
                    identity,
                    operatorGuard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, Clock.systemUTC()),
                    budgets = newDbStore(db, Clock.systemUTC()),
                    drain = ProjectDrainRepository(db.database, newDbStore(db, Clock.systemUTC())),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, body: String? = null) = client.request(path) {
        this.method = method
        header("X-Test-Principal", "operator")
        contentType(ContentType.Application.Json)
        body?.let { setBody(it) }
    }

    private fun TestDb.projectOf(name: String): String = ProjectRepository(database, Clock.systemUTC()).create(name, null).projectId

    private fun TestDb.assignee(name: String): String {
        val created = assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(database).create(name, AssigneeRole.OPERATOR, "root")).value
        return created.assigneeId
    }

    private fun TestDb.memberRows(projectId: String): List<String> = transaction(database) {
        execQuery("SELECT assignee_id FROM project_members WHERE project_id = ? ORDER BY assignee_id", projectId) { it.getString(1) }
    }

    private fun TestDb.repoKeys(projectId: String): List<String> = transaction(database) {
        execQuery("SELECT repo_key FROM project_repos WHERE project_id = ? ORDER BY repo_key", projectId) { it.getString(1) }
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the repos routes act on the path's project, not on the query's`() = TestDb().use { db ->
        val a = db.projectOf("Alpha")
        val b = db.projectOf("Beta")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "/api/projects/$a/repos?projectId=$b", """{"repoKey":"kigawa-net/only-a"}""").status)
            assertEquals(listOf("kigawa-net/only-a"), db.repoKeys(a))
            assertEquals(emptyList(), db.repoKeys(b))

            val listed = call(HttpMethod.Get, "/api/projects/$a/repos?projectId=$b").bodyAsText()
            assertTrue("kigawa-net/only-a" in listed)

            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/projects/$a/repos?projectId=$b&repoKey=kigawa-net/only-a", "{}").status)
            assertEquals(emptyList(), db.repoKeys(a))
        }
    }

    @Test
    fun `the members routes list and change the path's project, not the query's`() = TestDb().use { db ->
        val a = db.projectOf("Alpha")
        val b = db.projectOf("Beta")
        val member = db.assignee("member")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "/api/projects/$a/members/$member?projectId=$b", """{"role":"VIEWER"}""").status)
            assertEquals(listOf(member), db.memberRows(a))
            assertEquals(emptyList(), db.memberRows(b))

            assertTrue(member in call(HttpMethod.Get, "/api/projects/$a/members?projectId=$b").bodyAsText())

            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/projects/$a/members/$member?projectId=$b", "{}").status)
            assertEquals(emptyList(), db.memberRows(a))
        }
    }

    @Test
    fun `the budget routes read and write the path's project, not the query's`() = TestDb().use { db ->
        val a = db.projectOf("Alpha")
        val b = db.projectOf("Beta")
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/projects/$a/budget?projectId=$b", """{"monthlyLimitJpy":1000}""").status)

            val onA = obj(call(HttpMethod.Get, "/api/projects/$a/budget?projectId=$b").bodyAsText())
            assertEquals(a, onA["projectId"]!!.jsonPrimitive.content)
            assertEquals("1000", onA["monthlyLimitJpy"]!!.jsonPrimitive.content)

            // B has no budget row: the query's project is never read.
            val onB = obj(call(HttpMethod.Get, "/api/projects/$b/budget").bodyAsText())
            assertEquals("null", onB["monthlyLimitJpy"].toString())

            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/projects/$a/budget?projectId=$b", "{}").status)
            val cleared = obj(call(HttpMethod.Get, "/api/projects/$a/budget").bodyAsText())
            assertEquals("null", cleared["monthlyLimitJpy"].toString())
        }
    }

    @Test
    fun `the dashboard and the project itself are the path's project, not the query's`() = TestDb().use { db ->
        val a = db.projectOf("Alpha")
        val b = db.projectOf("Beta")
        testApplication {
            setUp(db)
            val dashboard = call(HttpMethod.Get, "/api/projects/$a/dashboard?projectId=$b")
            assertEquals(HttpStatusCode.OK, dashboard.status)
            val project = obj(dashboard.bodyAsText())["project"]!!.jsonObject
            assertEquals(a, project["projectId"]!!.jsonPrimitive.content)
            assertEquals("Alpha", project["name"]!!.jsonPrimitive.content)

            val got = obj(call(HttpMethod.Get, "/api/projects/$a?projectId=$b").bodyAsText())
            assertEquals(a, got["projectId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `an unknown path project is 404 even when the query names a real project`() = TestDb().use { db ->
        val b = db.projectOf("Beta")
        testApplication {
            setUp(db)
            val missing = "01MISSINGPROJECTAAAAAAAAA"
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/$missing/budget?projectId=$b").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/$missing/dashboard?projectId=$b").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/$missing/repos?projectId=$b").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/$missing/members?projectId=$b").status)
        }
    }
}
