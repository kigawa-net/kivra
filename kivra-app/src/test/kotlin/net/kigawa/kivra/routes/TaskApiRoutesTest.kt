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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.appModule
import net.kigawa.kivra.auth.TaskApiAuthMode
import net.kigawa.kivra.db.TestDb

private const val ALICE = "alice@example.com"
private const val BOB = "bob@example.com"

class TaskApiRoutesTest {
    private fun ApplicationTestBuilder.setUpRoutes(db: TestDb, mode: TaskApiAuthMode) {
        application {
            install(ContentNegotiation) { json() }
            routing { configureTaskApiRoutes(db.runs, mode) }
        }
    }

    private suspend fun HttpClient.submit(body: String, email: String? = ALICE): HttpResponse = post("/api/tasks") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        email?.let { header("X-Auth-Request-Email", it) }
        setBody(body)
    }

    private suspend fun HttpClient.read(path: String, email: String? = ALICE): HttpResponse = get(path) {
        email?.let { header("X-Auth-Request-Email", it) }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.runId(): String = json().getValue("runId").jsonPrimitive.content

    @Test
    fun `request without an identity is 401 for every route`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            assertEquals(HttpStatusCode.Unauthorized, client.submit("""{"title":"t","idempotencyKey":"k"}""", email = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.read("/api/runs", email = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.read("/api/runs/01AAAAAAAAAAAAAAAAAAAAAAAA", email = null).status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `over-long identity header is 401 (control characters are covered in TaskApiAuthTest)`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            assertEquals(HttpStatusCode.Unauthorized, client.submit("""{"title":"t","idempotencyKey":"k"}""", email = "x".repeat(257)).status)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `valid submit is 202 with the simulated run snapshot`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            val response = client.submit("""{"title":"Write the release notes","idempotencyKey":"rn-1"}""")
            assertEquals(HttpStatusCode.Accepted, response.status)
            val body = response.json()
            assertEquals("Write the release notes", body.getValue("title").jsonPrimitive.content)
            assertEquals("CREATED", body.getValue("state").jsonPrimitive.content)
            assertEquals("SIMULATED", body.getValue("mode").jsonPrimitive.content)
            assertEquals("false", body.getValue("executed").toString())
            assertTrue(body.getValue("taskId").jsonPrimitive.content.startsWith("api:"))
            assertTrue(body.getValue("createdAt").jsonPrimitive.content.endsWith("Z"))
        }
    }

    @Test
    fun `replay returns the same run and a different title is 409`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            val first = client.submit("""{"title":"Same","idempotencyKey":"k"}""")
            val replay = client.submit("""{"title":"Same","idempotencyKey":"k"}""")
            val conflict = client.submit("""{"title":"Different","idempotencyKey":"k"}""")
            assertEquals(HttpStatusCode.Accepted, first.status)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(first.runId(), replay.runId())
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals(1, db.count("development_runs"))
        }
    }

    @Test
    fun `another user's run is 404 and listing is scoped to the caller`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            val aliceRun = client.submit("""{"title":"Alice task","idempotencyKey":"a"}""", email = ALICE).runId()
            client.submit("""{"title":"Bob task","idempotencyKey":"b"}""", email = BOB)

            assertEquals(HttpStatusCode.NotFound, client.read("/api/runs/$aliceRun", email = BOB).status)
            val aliceReads = client.read("/api/runs/$aliceRun", email = ALICE)
            assertEquals(HttpStatusCode.OK, aliceReads.status)
            assertEquals("Alice task", aliceReads.json().getValue("title").jsonPrimitive.content)

            val listed = Json.parseToJsonElement(client.read("/api/runs", email = ALICE).bodyAsText()).jsonArray
            assertEquals(listOf("Alice task"), listed.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        }
    }

    @Test
    fun `invalid title, key or body is 400 and nothing is written`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            val bad = listOf(
                """{"title":"   ","idempotencyKey":"k"}""",
                """{"title":"${"a".repeat(201)}","idempotencyKey":"k"}""",
                """{"title":"line\nbreak","idempotencyKey":"k"}""",
                """{"title":"bell\u0007","idempotencyKey":"k"}""",
                """{"title":"t","idempotencyKey":""}""",
                """{"title":"t","idempotencyKey":"${"k".repeat(129)}"}""",
                """{"title":"t"}""",
                "not-json",
            )
            for (body in bad) assertEquals(HttpStatusCode.BadRequest, client.submit(body).status, body)
            assertEquals(0, db.count("development_runs"))
        }
    }

    @Test
    fun `title of 200 characters is accepted`() = TestDb().use { db ->
        testApplication {
            setUpRoutes(db, TaskApiAuthMode.OAUTH2_PROXY)
            assertEquals(HttpStatusCode.Accepted, client.submit("""{"title":"${"a".repeat(200)}","idempotencyKey":"max"}""").status)
        }
    }

    @Test
    fun `module without KIVRA_AUTH_MODE exposes no Task API`() = testApplication {
        environment { config = h2Config("kivra_route_absent") }
        application { appModule(emptyMap()) }
        assertEquals(HttpStatusCode.NotFound, client.submit("""{"title":"t","idempotencyKey":"k"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.read("/api/runs").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `module with oauth2-proxy requires an identity`() = testApplication {
        environment { config = h2Config("kivra_route_oauth") }
        application { appModule(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy")) }
        assertEquals(HttpStatusCode.Unauthorized, client.read("/api/runs", email = null).status)
        assertEquals(HttpStatusCode.OK, client.read("/api/runs").status)
    }

    @Test
    fun `module with none in development accepts unauthenticated submits`() = testApplication {
        environment { config = h2Config("kivra_route_none") }
        application { appModule(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development")) }
        assertEquals(HttpStatusCode.Accepted, client.submit("""{"title":"dev task","idempotencyKey":"dev-1"}""", email = null).status)
    }

    private fun h2Config(name: String) = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:${name}_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )
}
