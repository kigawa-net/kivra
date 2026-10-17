package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.FakeGitHub

/** Routes under the self-development API prefix (ADR 0008 part 7) on H2 with the real repository and fake GitHub. */
class SelfDevRoutesTest {
    private val identity = TaskApiIdentity { headers -> headers["X-Test-Principal"]?.takeIf { it.isNotBlank() } }
    private val title = "SECRET-TITLE-MARKER README を直す"

    private fun ApplicationTestBuilder.setUp(db: TestDb, maxActive: Int = 1) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                configureTaskApiRoutes(db.runs, identity)
                configureSelfDevRoutes(SelfDevRouteDeps(db.runs, identity, RepoRef("kigawa-net", "kivra"), maxActive, FakeGitHub()))
            }
        }
    }

    private suspend fun ApplicationTestBuilder.submit(principal: String?, key: String, body: String = """{"title":"$title","idempotencyKey":"$key"}"""): HttpResponse =
        client.post("/api/selfdev/tasks") {
            principal?.let { header("X-Test-Principal", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `every self-development route requires authentication`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Unauthorized, submit(null, "k1").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selfdev/runs").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/selfdev/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV").status)
        }
    }

    @Test
    fun `an accepted submission creates a self-development run owned by the caller and the response carries no task text`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = submit("alice", "k1")
            assertEquals(HttpStatusCode.Accepted, response.status)
            val text = response.bodyAsText()
            assertFalse("SECRET-TITLE-MARKER" in text)
            val obj = Json.parseToJsonElement(text).jsonObject
            assertEquals("SELF_DEV", obj["mode"]!!.jsonPrimitive.content)
            assertEquals("CREATED", obj["state"]!!.jsonPrimitive.content)
            val run = db.runs.getTask(RunId(obj["runId"]!!.jsonPrimitive.content), "alice")!!
            assertTrue(run.taskId.startsWith("selfdev:"))
            assertEquals(title, run.taskTitle)
        }
    }

    @Test
    fun `the same key with the same title replays the same run and a different title is a conflict`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val first = submit("alice", "k1").json()["runId"]!!.jsonPrimitive.content
            val replay = submit("alice", "k1")
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(first, replay.json()["runId"]!!.jsonPrimitive.content)
            val other = submit("alice", "k1", """{"title":"other","idempotencyKey":"k1"}""")
            assertEquals(HttpStatusCode.Conflict, other.status)
        }
    }

    @Test
    fun `an unknown field is refused and nothing is written`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = submit("alice", "k2", """{"title":"t","idempotencyKey":"k2","target":"B","repo":"x/y"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertFalse("target" in response.bodyAsText())
            assertTrue(db.runs.listTasks("alice", selfDev = true).isEmpty())
        }
    }

    @Test
    fun `invalid input is refused - control characters, empty and oversize values`() = TestDb().use { db ->
        testApplication {
            setUp(db, maxActive = 10)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "k3", """{"title":"a\u0001b","idempotencyKey":"k3"}""").status)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "k4", """{"title":"   ","idempotencyKey":"k4"}""").status)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "k5", """{"title":"${"x".repeat(201)}","idempotencyKey":"k5"}""").status)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "", """{"title":"t","idempotencyKey":""}""").status)
            assertTrue(db.runs.listTasks("alice", selfDev = true).isEmpty())
        }
    }

    @Test
    fun `the active-run cap refuses a new submission with 429 and writes nothing - a replay is still answered`() = TestDb().use { db ->
        testApplication {
            setUp(db, maxActive = 1)
            val first = submit("alice", "k1").json()["runId"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.TooManyRequests, submit("alice", "k2").status)
            assertEquals(1, db.runs.listTasks("alice", selfDev = true).size)
            assertEquals(HttpStatusCode.OK, submit("alice", "k1").status)
            assertEquals(first, db.runs.listTasks("alice", selfDev = true).single().runId.value)
        }
    }

    @Test
    fun `a key used for a Task API run cannot be replayed as a self-development run`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val api = client.post("/api/tasks") {
                header("X-Test-Principal", "alice")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"$title","idempotencyKey":"shared"}""")
            }
            assertEquals(HttpStatusCode.Accepted, api.status)
            assertEquals(HttpStatusCode.Conflict, submit("alice", "shared").status)
            assertTrue(db.runs.listTasks("alice", selfDev = true).isEmpty())
        }
    }

    @Test
    fun `a run is visible only to its owner, and another caller gets the same 404 as a missing run`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val runId = submit("alice", "k1").json()["runId"]!!.jsonPrimitive.content
            val own = client.get("/api/selfdev/runs/$runId") { header("X-Test-Principal", "alice") }
            assertEquals(HttpStatusCode.OK, own.status)
            assertFalse("SECRET-TITLE-MARKER" in own.bodyAsText())
            val other = client.get("/api/selfdev/runs/$runId") { header("X-Test-Principal", "bob") }
            val missing = client.get("/api/selfdev/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV") { header("X-Test-Principal", "bob") }
            assertEquals(HttpStatusCode.NotFound, other.status)
            assertEquals(missing.bodyAsText(), other.bodyAsText())
        }
    }

    @Test
    fun `the list shows only the caller's self-development runs`() = TestDb().use { db ->
        testApplication {
            setUp(db, maxActive = 5)
            submit("alice", "k1")
            client.post("/api/tasks") {
                header("X-Test-Principal", "alice")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"plain","idempotencyKey":"api-1"}""")
            }
            val list = client.get("/api/selfdev/runs") { header("X-Test-Principal", "alice") }
            assertEquals(HttpStatusCode.OK, list.status)
            assertEquals(1, Json.parseToJsonElement(list.bodyAsText()).let { (it as kotlinx.serialization.json.JsonArray).size })
            assertFalse("SECRET-TITLE-MARKER" in list.bodyAsText())
        }
    }

    // ---- the optional task body (Issue #229, addendum) ----

    private fun body(title: String, key: String, text: String?): String = buildJsonObject {
        put("title", title)
        put("idempotencyKey", key)
        if (text != null) put("body", text)
    }.toString()

    @Test
    fun `a body is accepted, stored with the run, and never returned`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            val response = submit("alice", "b1", body(title, "b1", "手順:\n1. README を直す\n\tインデント"))
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertFalse(response.bodyAsText().contains("手順"), "the body is not echoed back")
            val stored = db.runs.listTasks("alice", selfDev = true).single()
            assertEquals("手順:\n1. README を直す\n\tインデント", stored.taskBody)
        }
    }

    @Test
    fun `a body that is too long, or has a control character other than line feed and tab, is 400 and nothing is stored`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "b2", body(title, "b2", "x".repeat(8_001))).status)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "b3", body(title, "b3", "a\u0007b")).status)
            assertEquals(HttpStatusCode.BadRequest, submit("alice", "b4", body(title, "b4", "a\rb")).status)
            assertTrue(db.runs.listTasks("alice", selfDev = true).isEmpty())
        }
    }

    @Test
    fun `a blank body is the same as no body, and the same key with another body is 409`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Accepted, submit("alice", "b5", body(title, "b5", "   ")).status)
            assertEquals(HttpStatusCode.OK, submit("alice", "b5", body(title, "b5", null)).status)
            assertEquals(HttpStatusCode.Conflict, submit("alice", "b5", body(title, "b5", "手順")).status)
        }
    }

    @Test
    fun `a title-only submission is accepted as before, with no body`() = TestDb().use { db ->
        testApplication {
            setUp(db)
            assertEquals(HttpStatusCode.Accepted, submit("alice", "b6").status)
            assertEquals(null, db.runs.listTasks("alice", selfDev = true).single().taskBody)
        }
    }
}
