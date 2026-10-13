package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevPreviewRoutesTest {
    @Test
    fun `simulated tasks render valid JSON`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { configureDevPreviewRoutes() }
        }
        val response = client.get("/api/dev/tasks")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"mode\":\"SIMULATED\""))
        assertTrue(response.bodyAsText().contains("demo-1"))
    }

    @Test
    fun `preview is unambiguously not executed`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { configureDevPreviewRoutes() }
        }
        val response = client.get("/api/dev/runs/preview")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"NOT_EXECUTED\""))
        assertTrue(response.bodyAsText().contains("READ_ISSUE"))
    }
}
