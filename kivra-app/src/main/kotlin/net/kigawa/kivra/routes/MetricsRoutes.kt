package net.kigawa.kivra.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest
import net.kigawa.kivra.config.MetricsConfig
import net.kigawa.kivra.observability.MetricsRegistry

/**
 * Prometheus scrape endpoint. Requires `Authorization: Bearer <bearerToken>`; a missing or
 * mismatching token gets 401. Not registered at all unless metrics are enabled (see
 * [configureObservabilityRoutes]), so a disabled deployment answers 404.
 *
 * The API listener is not split: a separate loopback listener is not implemented yet (see docs/observability.md).
 */
fun Route.configureMetricsRoutes(registry: MetricsRegistry, bearerToken: String) {
    require(bearerToken.isNotBlank()) { "bearerToken must not be blank" }
    val expected = "Bearer $bearerToken".toByteArray(Charsets.UTF_8)
    get("/metrics") {
        val provided = call.request.headers[HttpHeaders.Authorization]?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        if (!MessageDigest.isEqual(provided, expected)) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }
        call.respondText(registry.renderPrometheus(), ContentType.parse("text/plain; version=0.0.4"), HttpStatusCode.OK)
    }
}

/** Registers observability endpoints according to [config]. Disabled config registers nothing. */
fun Route.configureObservabilityRoutes(registry: MetricsRegistry, config: MetricsConfig) {
    if (config.enabled) {
        configureMetricsRoutes(registry, requireNotNull(config.bearerToken))
    }
}
