package net.kigawa.kivra.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TaskRequestRepository
import net.kigawa.kivra.db.TaskRequestState
import net.kigawa.kivra.db.TaskRequestSubmitResult
import net.kigawa.kivra.db.TestDb

private const val ALICE = "alice@example.com"
private const val BOB = "bob@example.com"
private const val ROOT = "root@example.com"
private const val AI = "ai@example.com"
private const val APPROVER = "approver@example.com"
private const val VIEWER = "viewer@example.com"
private const val NO_SUCH_REQUEST = "01AAAAAAAAAAAAAAAAAAAAAAAA"

/**
 * The approval requests (ADR 0025) over HTTP. The identity is a header, so a test names its caller. OFF uses today's rules: the root
 * approver decides, every other operator requests. The ENFORCE test uses the assignee store.
 */
class TaskRequestRoutesTest {

    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }

    private fun offGuard() = PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, AI, ROOT, null))

    private class Roles(private val map: Map<String, AssigneeAccess>) : AssigneeLookup {
        override fun accessOf(ownerKey: String): AssigneeAccess? = map[ownerKey]
    }

    private fun enforceGuard() = PermissionGuard(
        PermissionService(
            PermissionMode.ENFORCE,
            RolesDefault.NONE,
            AI,
            ROOT,
            Roles(
                mapOf(
                    ALICE to AssigneeAccess(Role.OPERATOR, true),
                    BOB to AssigneeAccess(Role.OPERATOR, true),
                    APPROVER to AssigneeAccess(Role.APPROVER, true),
                    VIEWER to AssigneeAccess(Role.VIEWER, true),
                ),
            ),
        ),
    )

    private var project = ""

    private fun ensureProject(db: TestDb) {
        if (project.isEmpty()) project = ProjectRepository(db.database, java.time.Clock.systemUTC()).create("Requests", null).projectId
    }

    private fun ApplicationTestBuilder.setUp(db: TestDb, guard: PermissionGuard = offGuard()) {
        val requests = TaskRequestRepository(db.database)
        val projects = ProjectRepository(db.database, java.time.Clock.systemUTC())
        ensureProject(db)
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureTaskApiRoutes(db.runs, identity, guard = guard)
                configureTaskRequestRoutes(requests, db.runs, identity, guard, projects)
            }
        }
    }

    private suspend fun HttpClient.postJson(
        path: String,
        who: String?,
        body: String = "{}",
        contentType: String = ContentType.Application.Json.toString(),
    ): HttpResponse = post(path) {
        header(HttpHeaders.ContentType, contentType)
        who?.let { header("X-Test-Principal", it) }
        setBody(body)
    }

    private suspend fun HttpClient.getAs(path: String, who: String?): HttpResponse = get(path) {
        who?.let { header("X-Test-Principal", it) }
    }

    private suspend fun HttpClient.submit(who: String, title: String, key: String): HttpResponse =
        postJson("/api/projects/$project/requests", who, """{"title":"$title","idempotencyKey":"$key"}""")

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.items(): List<JsonObject> = Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpResponse.requestId(): String = obj().getValue("requestId").jsonPrimitive.content

    /** A string field, or null when the field is absent or JSON null. */
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `every request route is 401 without an identity`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/projects/$project/requests", null, """{"title":"t","idempotencyKey":"k"}""").status)
            assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/projects/$project/requests", null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/projects/$project/requests/$NO_SUCH_REQUEST", null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/projects/$project/requests/$NO_SUCH_REQUEST/approve", null).status)
            assertEquals(0, db.count("task_requests"))
        }
    }

    @Test
    fun `the project in the path must be a ULID of a known project, and the body project must match it`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val body = """{"title":"t","idempotencyKey":"k"}"""
            // Not a ULID, and a ULID of no project: the same 404.
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/projects/not-a-ulid/requests", ALICE, body).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/projects/$NO_SUCH_REQUEST/requests", ALICE, body).status)
            assertEquals(HttpStatusCode.NotFound, client.getAs("/api/projects/$NO_SUCH_REQUEST/requests", ALICE).status)
            // The body project, when given, is the path's.
            val mismatch = client.postJson("/api/projects/$project/requests", ALICE, """{"title":"t","idempotencyKey":"k","projectId":"$NO_SUCH_REQUEST"}""")
            assertEquals(HttpStatusCode.BadRequest, mismatch.status)
            assertEquals("PROJECT_MISMATCH", mismatch.obj().str("error"))
            assertEquals(HttpStatusCode.Created, client.postJson("/api/projects/$project/requests", ALICE, """{"title":"t","idempotencyKey":"k","projectId":"$project"}""").status)
            assertEquals(1, db.count("task_requests"))
        }
    }

    @Test
    fun `a request of another project is not found through this project`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val id = client.submit(ALICE, "Write the release notes", "rn-1").requestId()
            val other = ProjectRepository(db.database, java.time.Clock.systemUTC()).create("Other", null).projectId
            assertEquals(HttpStatusCode.NotFound, client.getAs("/api/projects/$other/requests/$id", ALICE).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/projects/$other/requests/$id/approve", ROOT).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/projects/$other/requests/$id/cancel", ALICE).status)
            assertEquals(0, client.getAs("/api/projects/$other/requests", ALICE).items().size)
            assertEquals(1, client.getAs("/api/projects/$project/requests", ALICE).items().size)
            assertEquals(HttpStatusCode.OK, client.postJson("/api/projects/$project/requests/$id/approve", ROOT).status)
        }
    }

    @Test
    fun `a submission is 201 PENDING, a replay is 200, a different title is 409`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val created = client.submit(ALICE, "Write the release notes", "rn-1")
            assertEquals(HttpStatusCode.Created, created.status)
            val body = created.obj()
            assertEquals("PENDING", body.str("state"))
            assertNull(body.str("runId"))
            assertNull(body.str("requestedBy"), "the requester does not see who else")
            assertEquals(HttpStatusCode.OK, client.submit(ALICE, "Write the release notes", "rn-1").status)
            assertEquals(HttpStatusCode.Conflict, client.submit(ALICE, "Something else", "rn-1").status)
            assertEquals(1, db.count("task_requests"))
        }
    }

    @Test
    fun `invalid input is 400 and a non-JSON content type is 415`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.BadRequest, client.submit(ALICE, "", "k").status)
            // The JSON escape \u0007 is a control character once decoded: the title is refused.
            assertEquals(HttpStatusCode.BadRequest, client.submit(ALICE, "bad\\u0007title", "k").status)
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/projects/$project/requests", ALICE, "not json").status)
            assertEquals(
                HttpStatusCode.UnsupportedMediaType,
                client.postJson("/api/projects/$project/requests", ALICE, """{"title":"t","idempotencyKey":"k"}""", contentType = "text/plain").status,
            )
            assertEquals(0, db.count("task_requests"))
        }
    }

    @Test
    fun `the requester sees its own requests, and a decider also sees the others' PENDING requests`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val mine = client.submit(ALICE, "mine", "a-1").requestId()
            val bobs = client.submit(BOB, "bob's", "b-1").requestId()
            assertEquals(listOf(mine), client.getAs("/api/projects/$project/requests", ALICE).items().map { it.str("requestId") })
            val decider = client.getAs("/api/projects/$project/requests", ROOT).items()
            assertEquals(setOf(mine, bobs), decider.map { it.str("requestId") }.toSet(), "the root decides, so it sees the PENDING requests of the others")
            assertEquals(BOB, decider.single { it.str("requestId") == bobs }.str("requestedBy"))
        }
    }

    @Test
    fun `a request of another caller is 404 for a read, and a decider reads it`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val id = client.submit(ALICE, "mine", "a-1").requestId()
            assertEquals(HttpStatusCode.OK, client.getAs("/api/projects/$project/requests/$id", ALICE).status)
            assertEquals(HttpStatusCode.NotFound, client.getAs("/api/projects/$project/requests/$id", BOB).status)
            assertEquals(HttpStatusCode.OK, client.getAs("/api/projects/$project/requests/$id", ROOT).status)
            assertEquals(HttpStatusCode.NotFound, client.getAs("/api/projects/$project/requests/$NO_SUCH_REQUEST", ALICE).status)
        }
    }

    @Test
    fun `the requester may not approve its own request, and the decider approves it into one Run of the requester`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val id = client.submit(ALICE, "Write the release notes", "rn-1").requestId()
            assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/projects/$project/requests/$id/approve", ALICE).status)
            val approved = client.postJson("/api/projects/$project/requests/$id/approve", ROOT)
            assertEquals(HttpStatusCode.OK, approved.status)
            val body = approved.obj()
            assertEquals("APPROVED", body.str("state"))
            val runId = assertNotNull(body.str("runId"))
            // The run belongs to the requester, as a Task API submission does.
            assertEquals(HttpStatusCode.OK, client.getAs("/api/runs/$runId", ALICE).status)
            assertEquals(HttpStatusCode.NotFound, client.getAs("/api/runs/$runId", ROOT).status)
            assertEquals(1, db.count("development_runs"))
            assertEquals(1, db.count("task_request_events", "kind = 'RUN_LINKED'"))
        }
    }

    @Test
    fun `a second approval is 409 and creates no second Run`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val id = client.submit(ALICE, "Write the release notes", "rn-1").requestId()
            assertEquals(HttpStatusCode.OK, client.postJson("/api/projects/$project/requests/$id/approve", ROOT).status)
            assertEquals(HttpStatusCode.Conflict, client.postJson("/api/projects/$project/requests/$id/approve", ROOT).status)
            assertEquals(1, db.count("development_runs"))
        }
    }

    @Test
    fun `an approval whose run was not linked is finished by the next approval, with the same single Run`() = TestDb().use { db ->
        // The state a failed link leaves behind: APPROVED with no run. The repository makes it directly, as a crash would.
        val requests = TaskRequestRepository(db.database)
        ensureProject(db)
        val request = (requests.submit(project, ALICE, "rn-2", "Write the release notes") as TaskRequestSubmitResult.Created).request
        requests.decide(project, request.requestId, ROOT, approve = true, note = null)
        testApplication {
            setUp(db)
            val body = client.postJson("/api/projects/$project/requests/${request.requestId}/approve", ROOT).obj()
            assertEquals("APPROVED", body.str("state"))
            assertNotNull(body.str("runId"))
            assertEquals(1, db.count("development_runs"))
            assertEquals(TaskRequestState.APPROVED, requests.get(project, request.requestId)!!.state)
        }
    }

    @Test
    fun `a rejection takes an optional reason, and a rejected request cannot be approved`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val id = client.submit(ALICE, "Risky", "r-1").requestId()
            assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/projects/$project/requests/$id/reject", ALICE).status)
            val rejected = client.postJson("/api/projects/$project/requests/$id/reject", ROOT, """{"reason":"not this week"}""")
            assertEquals(HttpStatusCode.OK, rejected.status)
            assertEquals("not this week", rejected.obj().str("decisionNote"))
            assertEquals(HttpStatusCode.Conflict, client.postJson("/api/projects/$project/requests/$id/approve", ROOT).status)
            assertEquals(HttpStatusCode.Conflict, client.postJson("/api/projects/$project/requests/$id/reject", ROOT).status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `a rejection with no body or a blank reason is accepted, and an over-long reason is 400`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val a = client.submit(ALICE, "A", "a-1").requestId()
            val b = client.submit(ALICE, "B", "b-1").requestId()
            val c = client.submit(ALICE, "C", "c-1").requestId()
            assertEquals(HttpStatusCode.OK, client.postJson("/api/projects/$project/requests/$a/reject", ROOT, body = "").status)
            val blank = client.postJson("/api/projects/$project/requests/$b/reject", ROOT, """{"reason":"  "}""")
            assertEquals(HttpStatusCode.OK, blank.status)
            assertNull(blank.obj().str("decisionNote"))
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/projects/$project/requests/$c/reject", ROOT, """{"reason":"${"x".repeat(501)}"}""").status)
            assertEquals(TaskRequestState.PENDING.name, client.getAs("/api/projects/$project/requests/$c", ALICE).obj().str("state"))
        }
    }

    @Test
    fun `only the requester cancels, a cancelled request cannot be approved, and another caller's cancel is 404`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val id = client.submit(ALICE, "Cancel me", "c-1").requestId()
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/projects/$project/requests/$id/cancel", ROOT).status)
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/projects/$project/requests/$id/cancel", BOB).status)
            assertEquals("CANCELLED", client.postJson("/api/projects/$project/requests/$id/cancel", ALICE).obj().str("state"))
            assertEquals(HttpStatusCode.Conflict, client.postJson("/api/projects/$project/requests/$id/approve", ROOT).status)
            assertEquals(HttpStatusCode.Conflict, client.postJson("/api/projects/$project/requests/$id/cancel", ALICE).status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `the Task API refuses a key with the reserved prefix, so no key can collide with a request's run`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/tasks", ALICE, """{"title":"t","idempotencyKey":"request:01ABC"}""").status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `under ENFORCE a viewer may not request, an approver decides, and an AI may request but not decide`() = TestDb().use { db ->
        testApplication {
            setUp(db, enforceGuard())
            assertEquals(HttpStatusCode.Forbidden, client.submit(VIEWER, "v", "v-1").status)
            assertEquals(HttpStatusCode.OK, client.getAs("/api/projects/$project/requests", VIEWER).status)
            val id = client.submit(ALICE, "By alice", "a-1").requestId()
            assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/projects/$project/requests/$id/approve", VIEWER).status)
            assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/projects/$project/requests/$id/approve", ALICE).status)
            assertEquals(HttpStatusCode.OK, client.postJson("/api/projects/$project/requests/$id/approve", APPROVER).status)
            val aiId = client.submit(AI, "By the AI", "ai-1").requestId()
            assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/projects/$project/requests/$aiId/approve", AI).status)
            assertEquals(HttpStatusCode.OK, client.postJson("/api/projects/$project/requests/$aiId/cancel", AI).status)
        }
    }
}
