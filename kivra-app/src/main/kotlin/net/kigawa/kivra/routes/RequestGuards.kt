package net.kigawa.kivra.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
private data class GuardError(val error: String)

private const val UNSUPPORTED_MEDIA_TYPE_TEXT = "content-type must be application/json"
private const val CROSS_SITE_TEXT = "cross-site request refused"

/**
 * CSRF guard for the state-changing POST routes (self-development, budget acknowledgement, Task API submit). Call it after the
 * authentication check, so unauthenticated callers still get 401. Returns true when the request may proceed. Otherwise it has
 * already responded and the caller must return:
 *
 * 1. `Content-Type` must be `application/json` (a charset parameter is allowed). A cross-site `text/plain` or form POST is a
 *    "simple" request and gets no CORS preflight, so this alone stops it. A cross-site JSON POST needs a preflight, and kivra
 *    returns no CORS headers. Anything else gets 415.
 * 2. `Sec-Fetch-Site`, when present, must be `same-origin` or `none` (`none` is a user-typed URL or a bookmark). Any other value
 *    gets 403. A missing header passes, so older clients and curl keep working.
 * 3. `Origin`, when present, must have the same authority (host and port) as the `Host` header, else 403. The `Host` header is
 *    used, never `X-Forwarded-Host`. oauth2-proxy forwards the original Host to kivra, and the scheme is not compared because
 *    TLS ends before kivra. A missing `Origin` passes, so curl and Bearer clients keep working.
 *
 * Responses use fixed text and never echo a header value. Nothing here is logged.
 */
internal suspend fun ApplicationCall.requireJsonSameOrigin(): Boolean {
    val mediaType = request.contentType().withoutParameters()
    if (!mediaType.match(ContentType.Application.Json)) {
        respond(HttpStatusCode.UnsupportedMediaType, GuardError(UNSUPPORTED_MEDIA_TYPE_TEXT))
        return false
    }
    val fetchSite = request.headers[SEC_FETCH_SITE]
    if (fetchSite != null && fetchSite.trim().lowercase() !in ALLOWED_FETCH_SITES) {
        respond(HttpStatusCode.Forbidden, GuardError(CROSS_SITE_TEXT))
        return false
    }
    val origin = request.headers[HttpHeaders.Origin]
    if (origin != null) {
        val host = request.headers[HttpHeaders.Host]
        val originAuthority = origin.trim().substringAfter("://", missingDelimiterValue = "")
        if (host.isNullOrEmpty() || !defaultPortFree(originAuthority).equals(defaultPortFree(host), ignoreCase = true)) {
            respond(HttpStatusCode.Forbidden, GuardError(CROSS_SITE_TEXT))
            return false
        }
    }
    return true
}

/** Drops a default port (80, 443), since a browser omits it from Origin while some clients send it in Host. */
private fun defaultPortFree(authority: String): String = authority.removeSuffix(":80").removeSuffix(":443")

private const val SEC_FETCH_SITE = "Sec-Fetch-Site"
private val ALLOWED_FETCH_SITES = setOf("same-origin", "none")
