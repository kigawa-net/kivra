package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevTaskSubmitRoutesTest {
    private fun runId(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("runId").jsonPrimitive.content

    @Test
    fun `submit is simulated and can be looked up`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { configureDevTaskSubmitRoutes() }
        }
        val result = client.post("/api/dev/tasks") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"title":"Review a repository issue","idempotencyKey":"issue-1"}""")
        }
        assertEquals(HttpStatusCode.Accepted, result.status)
        val id = runId(result.bodyAsText())
        val lookup = client.get("/api/dev/runs/$id")
        assertEquals(HttpStatusCode.OK, lookup.status)
        assertTrue(lookup.bodyAsText().contains("\\"state\\":\\"NOT_EXECUTED\\""))
        assertTrue(lookup.bodyAsText().contains("\\"mode\\":\\"SIMULATED\\""))
    }

    @Test
    fun `idempotent replay returns same run and conflicting payload is rejected`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { configureDevTaskSubmitRoutes() }
        }
        suspend fun submit(title: String) = client.post("/api/dev/tasks") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"title":"$title","idempotencyKey":"shared-key"}""")
        }
        val first = submit("First")
        val repeated = submit("First")
        val conflicting = submit("Different")
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(HttpStatusCode.Accepted, repeated.status)
        assertEquals(runId(first.bodyAsText()), runId(repeated.bodyAsText()))
        assertEquals(HttpStatusCode.Conflict, conflicting.status)
    }

    @Test
    fun `invalid input and unknown run do not produce success`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { configureDevTaskSubmitRoutes() }
        }
        val invalid = client.post("/api/dev/tasks") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"title":" ","idempotencyKey":"valid-key"}""")
        }
        val malformed = client.post("/api/dev/tasks") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json")
        }
        val missing = client.get("/api/dev/runs/unknown-id")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `concurrent identical submits allocate only one simulated run`() {
        val store = DevTaskStore()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..50).map {
                Callable { store.submit("Same task", "same-request-id").runId }
            }
            val ids = pool.invokeAll(jobs).map { it.get() }.toSet()
            assertEquals(1, ids.size)
            assertEquals("NOT_EXECUTED", store.get(ids.single())?.state)
        } finally {
            pool.shutdownNow()
        }
    }
}
