package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.observability.MetricsRegistry

class MetricsRoutesTest {
    @Test
    fun `metrics endpoint serves the prometheus exposition without leaking secrets`() = testApplication {
        val registry = MetricsRegistry()
        registry.counter("kivra_run_total").increment(mapOf("outcome" to "ghp_0123456789abcdefghij"))
        registry.counter("kivra_run_total").increment(mapOf("outcome" to "completed"))
        application { routing { configureMetricsRoutes(registry) } }

        val response = client.get("/metrics")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("text/plain"))
        assertTrue(body.contains("kivra_run_total{outcome=\"completed\"} 1"))
        assertFalse(body.contains("ghp_"))
    }
}
