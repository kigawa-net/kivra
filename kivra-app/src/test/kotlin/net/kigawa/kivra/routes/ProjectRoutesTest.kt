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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
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
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb

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
            routing { configureProjectRoutes(projects, db.runs, identity, guard) }
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

    @Test
    fun `missing projects and runs return not found`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/projects/01AAAAAAAAAAAAAAAAAAAAAAAA", "operator").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/runs/01AAAAAAAAAAAAAAAAAAAAAAAA/project", "operator", """{"projectId":"missing"}""").status)
        }
    }
}
