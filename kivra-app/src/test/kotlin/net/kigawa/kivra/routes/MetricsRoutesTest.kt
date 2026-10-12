package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.config.MetricsConfig
import net.kigawa.kivra.observability.MetricsRegistry

class MetricsRoutesTest {
    private val token = "test-scrape-token-0123456789"

    private fun registry() = MetricsRegistry().also {
        it.counter("kivra_run_total").increment(mapOf("outcome" to "ghp_0123456789abcdefghij"))
        it.counter("kivra_run_total").increment(mapOf("outcome" to "completed"))
    }

    @Test
    fun `metrics is not registered when disabled`() = testApplication {
        application { routing { configureObservabilityRoutes(registry(), MetricsConfig(enabled = false, bearerToken = null)) } }
        assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    @Test
    fun `enabled metrics rejects requests without the bearer token`() = testApplication {
        application { routing { configureObservabilityRoutes(registry(), MetricsConfig(enabled = true, bearerToken = token)) } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
    }

    @Test
    fun `enabled metrics rejects a mismatching bearer token`() = testApplication {
        application { routing { configureObservabilityRoutes(registry(), MetricsConfig(enabled = true, bearerToken = token)) } }
        val response = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer wrong-token") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertFalse(response.bodyAsText().contains("kivra_run_total"))
    }

    @Test
    fun `enabled metrics serves the exposition for the matching token without leaking secrets`() = testApplication {
        application { routing { configureObservabilityRoutes(registry(), MetricsConfig(enabled = true, bearerToken = token)) } }
        val response = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("text/plain"))
        assertTrue(body.contains("kivra_run_total{outcome=\"completed\"} 1"))
        assertFalse(body.contains("ghp_"))
    }
}
