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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.kigawa.kivra.auth.AssigneeAccess
import net.kigawa.kivra.auth.AssigneeLookup
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.Role
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.config.ClaudeAccountSettings
import net.kigawa.kivra.db.ProjectBackfillRepository
import net.kigawa.kivra.db.ProjectDrainRepository
import net.kigawa.kivra.db.ProjectMemberRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.newDbStore

class ProjectClaudeAccountRoutesTest {
    private val clock = Clock.systemUTC()
    private val options = """[{"id":"demo-a","label":"Demo A"},{"id":"demo-b","label":"Demo B"}]"""

    private fun ApplicationTestBuilder.setUp(db: TestDb) {
        val roles = object : AssigneeLookup {
            override fun accessOf(ownerKey: String): AssigneeAccess? = when (ownerKey) {
                "operator" -> AssigneeAccess(Role.ADMIN, true)
                "viewer" -> AssigneeAccess(Role.VIEWER, true)
                else -> null
            }
        }
        val guard = PermissionGuard(PermissionService(PermissionMode.ENFORCE, RolesDefault.NONE, "1:i:ai", "1:i:root", roles))
        val budgets = newDbStore(db, clock)
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureProjectRoutes(
                    ProjectRepository(db.database, clock), db.runs, TaskApiIdentity { it["X-Test-Principal"] }, guard,
                    backfill = ProjectBackfillRepository(db.database),
                    members = ProjectMemberRepository(db.database, clock),
                    budgets = budgets,
                    drain = ProjectDrainRepository(db.database, budgets),
                    claudeAccounts = ClaudeAccountSettings.fromEnv(mapOf(ClaudeAccountSettings.ENV_ACCOUNTS to options)),
                )
            }
        }
    }

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod,
        path: String,
        body: String = "{}",
        principal: String = "operator",
        type: ContentType = ContentType.Application.Json,
        fetchSite: String? = null,
    ) = client.request(path) {
        this.method = method
        header("X-Test-Principal", principal)
        fetchSite?.let { header("Sec-Fetch-Site", it) }
        if (method != HttpMethod.Get) {
            contentType(type)
            setBody(body)
        }
    }

    private fun TestDb.projectPath(): String {
        val projectId = ProjectRepository(database, clock).create("Alpha", null).projectId
        return "/api/projects/$projectId/claude-account"
    }

    @Test
    fun `an unset project returns explicit null and the configured choices`() = TestDb().use { db ->
        val path = db.projectPath()
        testApplication {
            setUp(db)
            val response = call(HttpMethod.Get, path)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(Json.parseToJsonElement("""{"accountId":null,"available":$options}"""), Json.parseToJsonElement(response.bodyAsText()))
        }
    }

    @Test
    fun `the operator can set and replace the account and clear it repeatedly`() = TestDb().use { db ->
        val path = db.projectPath()
        testApplication {
            setUp(db)
            for (account in listOf("demo-a", "demo-b")) {
                assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Put, path, """{"accountId":"$account"}""").status)
                val selected = Json.parseToJsonElement(call(HttpMethod.Get, path).bodyAsText()).jsonObject
                assertEquals(JsonPrimitive(account), selected["accountId"])
            }
            repeat(2) {
                assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, path).status)
            }
            assertEquals(JsonNull, Json.parseToJsonElement(call(HttpMethod.Get, path).bodyAsText()).jsonObject["accountId"])
        }
    }

    @Test
    fun `unknown IDs and non-string values are refused without changing the current account`() = TestDb().use { db ->
        val path = db.projectPath()
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Put, path, """{"accountId":"demo-a"}""").status)
            for (body in listOf("""{"accountId":"unknown"}""", """{"accountId":42}""", """{"accountId":null}""")) {
                val response = call(HttpMethod.Put, path, body)
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(JsonPrimitive("invalid_account"), Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"])
            }
            assertEquals(JsonPrimitive("demo-a"), Json.parseToJsonElement(call(HttpMethod.Get, path).bodyAsText()).jsonObject["accountId"])
        }
    }

    @Test
    fun `requests without ORDER_MANAGE are refused`() = TestDb().use { db ->
        val path = db.projectPath()
        testApplication {
            setUp(db)
            for (method in listOf(HttpMethod.Get, HttpMethod.Put, HttpMethod.Delete)) {
                assertEquals(HttpStatusCode.Forbidden, call(method, path, """{"accountId":"demo-a"}""", principal = "viewer").status)
            }
        }
    }

    @Test
    fun `writes require JSON and reject cross-site requests`() = TestDb().use { db ->
        val path = db.projectPath()
        testApplication {
            setUp(db)
            for (method in listOf(HttpMethod.Put, HttpMethod.Delete)) {
                assertEquals(HttpStatusCode.UnsupportedMediaType, call(method, path, type = ContentType.Text.Plain).status)
                assertEquals(HttpStatusCode.Forbidden, call(method, path, fetchSite = "cross-site").status)
            }
        }
    }

    @Test
    fun `a missing project is 404 for all three methods`() = TestDb().use { db ->
        val path = "/api/projects/01MISSINGPROJECTAAAAAAAAA/claude-account"
        testApplication {
            setUp(db)
            for (method in listOf(HttpMethod.Get, HttpMethod.Put, HttpMethod.Delete)) {
                assertEquals(HttpStatusCode.NotFound, call(method, path, """{"accountId":"demo-a"}""").status)
            }
        }
    }
}
