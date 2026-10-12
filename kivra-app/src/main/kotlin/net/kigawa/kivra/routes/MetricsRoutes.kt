package net.kigawa.kivra.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.kigawa.kivra.observability.MetricsRegistry

/**
 * Prometheus scrape endpoint, served next to /health.
 *
 * No authentication: this assumes an internal network only (the scraper is in-cluster). Whether
 * /metrics needs auth or a separate port is an open question, see README.
 */
fun Route.configureMetricsRoutes(registry: MetricsRegistry) {
    get("/metrics") {
        call.respondText(registry.renderPrometheus(), ContentType.parse("text/plain; version=0.0.4"), HttpStatusCode.OK)
    }
}
