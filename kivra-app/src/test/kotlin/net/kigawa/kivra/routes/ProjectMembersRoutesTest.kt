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
import net.kigawa.kivra.db.Assignee
import net.kigawa.kivra.db.AssigneeRole
import net.kigawa.kivra.db.AssigneeResult
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.newDbStore
import org.jetbrains.exposed.sql.transactions.transaction

/** The project members API (ADR 0022, PR-D2): auth, CSRF, 404/400, idempotence, the last ADMIN, and the bulk import. */
class ProjectMembersRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private class Lookup(private val roles: Map<String, Role>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = roles[ownerKey]?.let { AssigneeAccess(it, true) }
    }

    /** The enforced guard: the principals in [roles] get their role's permissions; the others get none. */
    private fun guard(roles: Map<String, Role>): PermissionGuard = PermissionGuard(
        PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root", Lookup(roles)),
    )

    private val operatorGuard = guard(mapOf("operator" to Role.ADMIN, "viewer" to Role.VIEWER))

    private fun ApplicationTestBuilder.setUp(db: TestDb, guard: PermissionGuard = operatorGuard) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    ProjectRepository(db.database, Clock.systemUTC()),
                    db.runs,
                    identity,
                    guard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, Clock.systemUTC()),
                    budgets = newDbStore(db, Clock.systemUTC()),
                )
            }
        }
    }

    /** A request with the JSON content type that the state-changing routes require (unless [contentTypeHeader] overrides it). */
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

    private fun TestDb.projectOf(name: String = "Alpha"): String = ProjectRepository(database, Clock.systemUTC()).create(name, null).projectId

    private fun TestDb.assignee(name: String, role: AssigneeRole = AssigneeRole.OPERATOR, disabled: Boolean = false): String {
        val created = assertIs<AssigneeResult.Moved<Assignee>>(SelfDevAssigneeRepository(database).create(name, role, "root")).value
        if (disabled) transaction(database) { execUpdate("UPDATE selfdev_assignees SET state = 'DISABLED' WHERE assignee_id = ?", created.assigneeId) }
        return created.assigneeId
    }

    /** The assignee ids of the project's member rows, read straight from the table (not through the API). */
    private fun TestDb.memberRows(projectId: String): List<String> = transaction(database) {
        execQuery("SELECT assignee_id FROM project_members WHERE project_id = ? ORDER BY assignee_id", projectId) { it.getString(1) }
    }

    private fun TestDb.roleOf(projectId: String, assigneeId: String): String? = transaction(database) {
        execQuery("SELECT role FROM project_members WHERE project_id = ? AND assignee_id = ?", projectId, assigneeId) { it.getString(1) }
            .firstOrNull()
    }

    private fun membersPath(projectId: String) = "/api/projects/$projectId/members"

    @Test
    fun `unauthenticated requests are refused on every endpoint without changing a member`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("member")
        testApplication {
            setUp(db)
            val path = membersPath(project)
            val requests = listOf(
                call(HttpMethod.Get, path, null),
                call(HttpMethod.Put, "$path/$member", null, """{"role":"ADMIN"}"""),
                call(HttpMethod.Delete, "$path/$member", null, "{}"),
                call(HttpMethod.Post, "$path/import-all", null, """{"role":"VIEWER"}"""),
            )
            requests.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
            assertEquals(emptyList(), db.memberRows(project))
        }
    }

    @Test
    fun `a viewer is refused every endpoint with 403, and an operator may use them all`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("member")
        testApplication {
            setUp(db)
            val path = membersPath(project)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, path, "viewer").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "$path/$member", "viewer", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "$path/$member", "viewer", "{}").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "$path/import-all", "viewer", """{"role":"VIEWER"}""").status)
            assertEquals(emptyList(), db.memberRows(project))

            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "$path/$member", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, path, "operator").status)
        }
    }

    @Test
    fun `a request without the JSON content type is refused with 415 and changes nothing`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("member")
        testApplication {
            setUp(db)
            val path = membersPath(project)
            val put = call(HttpMethod.Put, "$path/$member", "operator", """{"role":"ADMIN"}""", contentTypeHeader = ContentType.Text.Plain)
            assertEquals(HttpStatusCode.UnsupportedMediaType, put.status)
            val delete = call(HttpMethod.Delete, "$path/$member", "operator", "{}", contentTypeHeader = ContentType.Text.Plain)
            assertEquals(HttpStatusCode.UnsupportedMediaType, delete.status)
            val import = call(HttpMethod.Post, "$path/import-all", "operator", """{"role":"VIEWER"}""", contentTypeHeader = ContentType.Text.Plain)
            assertEquals(HttpStatusCode.UnsupportedMediaType, import.status)
            assertEquals(emptyList(), db.memberRows(project))
        }
    }

    @Test
    fun `a cross-site state change is refused with 403`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("member")
        testApplication {
            setUp(db)
            val response = call(HttpMethod.Put, "${membersPath(project)}/$member", "operator", """{"role":"ADMIN"}""", fetchSite = "cross-site")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(emptyList(), db.memberRows(project))
        }
    }

    @Test
    fun `an unknown project or assignee is 404, and an invalid role or dryRun is 400`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("member")
        val missingProject = "01AAAAAAAAAAAAAAAAAAAAAAAA"
        val missingAssignee = "01BBBBBBBBBBBBBBBBBBBBBBBB"
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, membersPath(missingProject), "operator").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "${membersPath(missingProject)}/$member", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "${membersPath(project)}/$missingAssignee", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "${membersPath(project)}/$member", "operator", "{}").status, "not a member")
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "${membersPath(missingProject)}/import-all", "operator", """{"role":"VIEWER"}""").status)

            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "${membersPath(project)}/$member", "operator", """{"role":"ROOT"}""").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "${membersPath(project)}/$member", "operator", """{"role":"AI_APPROVER"}""").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "${membersPath(project)}/import-all", "operator", """{"role":"nope"}""").status)
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "${membersPath(project)}/import-all?dryRun=maybe", "operator", """{"role":"VIEWER"}""").status)
            assertEquals(emptyList(), db.memberRows(project))
        }
    }

    @Test
    fun `a put adds a member once, the same role again changes nothing, and a new role is a change`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("Alice", AssigneeRole.OPERATOR)
        testApplication {
            setUp(db)
            val path = "${membersPath(project)}/$member"
            val added = call(HttpMethod.Put, path, "operator", """{"role":"OPERATOR"}""")
            assertEquals(HttpStatusCode.Created, added.status)
            assertEquals("OPERATOR", Json.parseToJsonElement(added.bodyAsText()).jsonObject["role"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, path, "operator", """{"role":"OPERATOR"}""").status, "idempotent")
            assertEquals(1, db.memberRows(project).size)

            assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, path, "operator", """{"role":"APPROVER"}""").status)
            assertEquals("APPROVER", db.roleOf(project, member))

            val list = call(HttpMethod.Get, membersPath(project), "operator")
            val item = Json.parseToJsonElement(list.bodyAsText()).jsonArray.single().jsonObject
            assertEquals(member, item["assigneeId"]!!.jsonPrimitive.content)
            assertEquals("Alice", item["displayName"]!!.jsonPrimitive.content)
            assertEquals("APPROVER", item["role"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", item["state"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the last ADMIN of a project cannot be demoted or removed, but a second ADMIN can`() = TestDb().use { db ->
        val project = db.projectOf()
        val first = db.assignee("first", AssigneeRole.ADMIN)
        val second = db.assignee("second", AssigneeRole.ADMIN)
        testApplication {
            setUp(db)
            val path = membersPath(project)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "$path/$first", "operator", """{"role":"ADMIN"}""").status)

            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "$path/$first", "operator", """{"role":"VIEWER"}""").status)
            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Delete, "$path/$first", "operator", "{}").status)
            assertEquals("ADMIN", db.roleOf(project, first), "the refused change wrote nothing")

            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "$path/$second", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "$path/$first", "operator", """{"role":"OPERATOR"}""").status)
            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Delete, "$path/$second", "operator", "{}").status, "now the last ADMIN")
            assertEquals("ADMIN", db.roleOf(project, second))
        }
    }

    @Test
    fun `the last ADMIN is counted per project, so another project's ADMIN does not protect it`() = TestDb().use { db ->
        val alpha = db.projectOf("Alpha")
        val beta = db.projectOf("Beta")
        val alphaAdmin = db.assignee("alpha-admin", AssigneeRole.ADMIN)
        val alphaOperator = db.assignee("alpha-operator", AssigneeRole.OPERATOR)
        val betaAdmin = db.assignee("beta-admin", AssigneeRole.ADMIN)
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "${membersPath(beta)}/$betaAdmin", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "${membersPath(alpha)}/$alphaAdmin", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "${membersPath(alpha)}/$alphaOperator", "operator", """{"role":"OPERATOR"}""").status)
            // beta's ADMIN is not a member of alpha: alpha's only ADMIN is still the last one there.
            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Put, "${membersPath(alpha)}/$alphaAdmin", "operator", """{"role":"VIEWER"}""").status)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "${membersPath(alpha)}/$alphaOperator", "operator", "{}").status)
            assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Delete, "${membersPath(beta)}/$betaAdmin", "operator", "{}").status)
        }
    }

    @Test
    fun `a removed member is gone from the list, and a second removal is 404`() = TestDb().use { db ->
        val project = db.projectOf()
        val member = db.assignee("member", AssigneeRole.OPERATOR)
        val admin = db.assignee("admin", AssigneeRole.ADMIN)
        testApplication {
            setUp(db)
            val path = "${membersPath(project)}/$member"
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, path, "operator", """{"role":"OPERATOR"}""").status)
            assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "${membersPath(project)}/$admin", "operator", """{"role":"ADMIN"}""").status)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, path, "operator", "{}").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, path, "operator", "{}").status)
            assertEquals(null, db.roleOf(project, member))
        }
    }

    @Test
    fun `the bulk import dry run counts and writes nothing, the real run adds the active assignees, and a second run adds nothing`() =
        TestDb().use { db ->
            val project = db.projectOf()
            val existing = db.assignee("existing", AssigneeRole.APPROVER)
            val active = db.assignee("active", AssigneeRole.VIEWER)
            db.assignee("disabled", AssigneeRole.OPERATOR, disabled = true)
            testApplication {
                setUp(db)
                val path = "${membersPath(project)}/import-all"
                assertEquals(HttpStatusCode.Created, call(HttpMethod.Put, "${membersPath(project)}/$existing", "operator", """{"role":"APPROVER"}""").status)

                val dry = call(HttpMethod.Post, path, "operator", """{"role":"OPERATOR"}""")
                assertEquals(HttpStatusCode.OK, dry.status)
                val dryJson = Json.parseToJsonElement(dry.bodyAsText()).jsonObject
                assertEquals(true, dryJson["dryRun"]!!.jsonPrimitive.content.toBoolean(), "the default is a dry run")
                assertEquals("1", dryJson["candidates"]!!.jsonPrimitive.content)
                assertEquals("0", dryJson["added"]!!.jsonPrimitive.content)
                assertEquals("1", dryJson["alreadyMember"]!!.jsonPrimitive.content)
                assertEquals("1", dryJson["disabled"]!!.jsonPrimitive.content)
                assertEquals(1, db.memberRows(project).size, "a dry run writes nothing")

                val real = call(HttpMethod.Post, "$path?dryRun=false", "operator", """{"role":"OPERATOR"}""")
                assertEquals(HttpStatusCode.OK, real.status)
                assertEquals("1", Json.parseToJsonElement(real.bodyAsText()).jsonObject["added"]!!.jsonPrimitive.content)
                assertEquals("OPERATOR", db.roleOf(project, active))
                assertEquals("APPROVER", db.roleOf(project, existing), "a member keeps its role")
                assertEquals(2, db.memberRows(project).size, "the disabled assignee is not added")

                val again = call(HttpMethod.Post, "$path?dryRun=false", "operator", """{"role":"VIEWER"}""")
                assertEquals("0", Json.parseToJsonElement(again.bodyAsText()).jsonObject["added"]!!.jsonPrimitive.content, "idempotent")
                assertEquals("OPERATOR", db.roleOf(project, active), "a second run with another role changes no one")
            }
        }

    @Test
    fun `the bulk import of an unknown project is 404 in both modes`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val path = "${membersPath("01AAAAAAAAAAAAAAAAAAAAAAAA")}/import-all"
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, path, "operator", """{"role":"VIEWER"}""").status)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "$path?dryRun=false", "operator", """{"role":"VIEWER"}""").status)
        }
    }

    @Test
    fun `the members list is empty for a project without members`() = TestDb().use { db ->
        val project = db.projectOf()
        testApplication {
            setUp(db)
            val response = call(HttpMethod.Get, membersPath(project), "operator")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText() == "[]")
        }
    }
}
