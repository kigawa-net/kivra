package net.kigawa.kivra.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.response.respond
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Everything the `/mcp` routes need. Built only when `KIVRA_MCP_ENABLED=true` (see [buildMcpRouteDeps]).
 */
class McpRouteDeps(
    val settings: McpSettings,
    val verifier: McpJwtVerifier,
    val issuer: String,
    val dispatcher: McpDispatcher,
    val rateLimiter: McpRateLimiter,
)

/** RFC 9728 protected resource metadata. Explicit fields, so no key is added or dropped by the serializer. */
@Serializable
data class ProtectedResourceMetadata(
    val resource: String,
    @kotlinx.serialization.SerialName("authorization_servers") val authorizationServers: List<String>,
    @kotlinx.serialization.SerialName("scopes_supported") val scopesSupported: List<String>,
    @kotlinx.serialization.SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String>,
)

private val logger = LoggerFactory.getLogger("net.kigawa.kivra.mcp.McpRoutes")
private val metadataJson = Json { explicitNulls = false }

/**
 * Registers `POST /mcp` (Streamable HTTP, stateless), `GET /mcp` (405) and the protected resource metadata (ADR 0013 sections 2, 4, 6).
 *
 * Order of the checks on `POST /mcp`: Host and Origin (403), Bearer token (401 with `WWW-Authenticate`, or 403 for an agent token),
 * Accept (406), Content-Type (415), `MCP-Protocol-Version` (400), rate limit (429), body size (413), JSON (400), batch (400), dispatch.
 * Nothing about a failure is logged except its fixed reason.
 */
internal fun Route.configureMcpRoutes(deps: McpRouteDeps) {
    val metadataUrl = checkNotNull(deps.settings.resourceMetadataUrl)
    val resource = checkNotNull(deps.settings.audience)

    get("/.well-known/oauth-protected-resource") {
        if (!call.guardHostAndOrigin(deps)) return@get
        call.respondMetadata(deps, resource)
    }
    get("/.well-known/oauth-protected-resource/mcp") {
        if (!call.guardHostAndOrigin(deps)) return@get
        call.respondMetadata(deps, resource)
    }

    get("/mcp") {
        if (!call.guardHostAndOrigin(deps)) return@get
        call.response.headers.append(HttpHeaders.Allow, "POST")
        call.respondJsonRpc(HttpStatusCode.MethodNotAllowed, rpcError(null, RpcCode.INVALID_REQUEST, "method not allowed"))
    }

    post("/mcp") {
        if (!call.guardHostAndOrigin(deps)) return@post
        val caller = when (val auth = deps.verifier.authenticate(call.request.headers)) {
            is McpAuth.Authenticated -> McpCaller(auth.principal, auth.scopes)
            McpAuth.Unauthenticated -> {
                call.challenge(metadataUrl, error = null)
                call.respondJsonRpc(HttpStatusCode.Unauthorized, rpcError(null, RpcCode.UNAUTHORIZED, "invalid or missing token"))
                return@post
            }
            McpAuth.AgentRefused -> {
                call.respondJsonRpc(HttpStatusCode.Forbidden, rpcError(null, RpcCode.FORBIDDEN, "forbidden"))
                return@post
            }
        }
        if (!call.acceptsJsonOrStream()) {
            call.respondJsonRpc(HttpStatusCode.NotAcceptable, rpcError(null, RpcCode.INVALID_REQUEST, "accept must include application/json"))
            return@post
        }
        if (!call.request.contentType().withoutParameters().match(ContentType.Application.Json)) {
            call.respondJsonRpc(HttpStatusCode.UnsupportedMediaType, rpcError(null, RpcCode.INVALID_REQUEST, "content-type must be application/json"))
            return@post
        }
        val version = call.request.headers["MCP-Protocol-Version"]
        if (version != null && version !in MCP_SUPPORTED_VERSIONS) {
            call.respondJsonRpc(HttpStatusCode.BadRequest, rpcError(null, RpcCode.INVALID_REQUEST, "unsupported protocol version"))
            return@post
        }
        if (!deps.rateLimiter.allow(caller.principal)) {
            call.respondJsonRpc(HttpStatusCode.TooManyRequests, rpcError(null, RpcCode.RATE_LIMITED, "rate limit exceeded"))
            return@post
        }
        val text = call.readBoundedText(MCP_MAX_BODY_BYTES)
        if (text == null) {
            call.respondJsonRpc(HttpStatusCode.PayloadTooLarge, rpcError(null, RpcCode.PAYLOAD_TOO_LARGE, "request body too large"))
            return@post
        }
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            call.respondJsonRpc(HttpStatusCode.BadRequest, rpcError(null, RpcCode.PARSE_ERROR, "parse error"))
            return@post
        }
        when (element) {
            is JsonArray -> {
                // Batching was removed in protocol 2025-06-18, and kivra does not support it in any version it speaks.
                call.respondJsonRpc(HttpStatusCode.BadRequest, rpcError(null, RpcCode.INVALID_REQUEST, "batch requests are not supported"))
                return@post
            }
            !is JsonObject -> {
                call.respondJsonRpc(HttpStatusCode.BadRequest, rpcError(null, RpcCode.INVALID_REQUEST, "a JSON object is required"))
                return@post
            }
            else -> Unit
        }
        val dispatch = deps.dispatcher.dispatch(element, caller)
        logger.info(mcpAuditLine(dispatch.method, dispatch.tool, caller.principal, dispatch.outcome))
        val response = dispatch.response
        when {
            response == null -> call.respond(HttpStatusCode.Accepted)
            dispatch.requiredScope != null -> {
                call.challenge(metadataUrl, error = "insufficient_scope", scope = dispatch.requiredScope)
                call.respondJsonRpc(HttpStatusCode.Forbidden, response)
            }
            else -> call.respondJsonRpc(HttpStatusCode.OK, response)
        }
    }
}

/** Host must be on the allow-list. An Origin header, when present, must be on the allow-list too. A request without Origin passes. */
private suspend fun ApplicationCall.guardHostAndOrigin(deps: McpRouteDeps): Boolean {
    val host = request.headers[HttpHeaders.Host]?.trim()?.lowercase()
    if (host == null || host !in deps.settings.allowedHosts) {
        respondJsonRpc(HttpStatusCode.Forbidden, rpcError(null, RpcCode.FORBIDDEN, "forbidden"))
        return false
    }
    val origin = request.headers[HttpHeaders.Origin]
    if (origin != null && origin.trim().lowercase() !in deps.settings.allowedOrigins) {
        respondJsonRpc(HttpStatusCode.Forbidden, rpcError(null, RpcCode.FORBIDDEN, "forbidden"))
        return false
    }
    return true
}

/** `WWW-Authenticate` for the protected resource. [error] is `null` for an unauthenticated request and `insufficient_scope` otherwise. */
private fun ApplicationCall.challenge(metadataUrl: String, error: String?, scope: String? = null) {
    val parts = buildList {
        if (error != null) add("error=\"$error\"")
        if (scope != null) add("scope=\"$scope\"")
        add("resource_metadata=\"$metadataUrl\"")
    }
    response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer " + parts.joinToString(", "))
}

private fun ApplicationCall.acceptsJsonOrStream(): Boolean {
    val accept = request.headers[HttpHeaders.Accept] ?: return true
    return listOf("application/json", "text/event-stream", "*/*").any { accept.contains(it, ignoreCase = true) }
}

private suspend fun ApplicationCall.respondJsonRpc(status: HttpStatusCode, body: JsonObject) {
    respondText(body.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondMetadata(deps: McpRouteDeps, resource: String) {
    val body = ProtectedResourceMetadata(
        resource = resource,
        authorizationServers = listOf(deps.issuer),
        scopesSupported = listOf("kivra.runs.read", "kivra.selfdev.start", "kivra.selfdev.stop"),
        bearerMethodsSupported = listOf("header"),
    )
    respondText(metadataJson.encodeToString(ProtectedResourceMetadata.serializer(), body), ContentType.Application.Json)
}

/** Reads at most [limit] bytes. Null when the body is larger (a declared Content-Length is checked first, then the bytes actually read). */
private suspend fun ApplicationCall.readBoundedText(limit: Int): String? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) return null
    val bytes = receiveChannel().readRemaining(limit + 1L).readByteArray()
    if (bytes.size > limit) return null
    return bytes.toString(Charsets.UTF_8)
}

/**
 * Requests per principal per minute (fixed one-minute window, ADR 0013 section 5). The principal is the owner key, never a header value.
 * [clock] is in milliseconds and can be injected for tests.
 */
class McpRateLimiter(
    private val perMinute: Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Window(var startMillis: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(principal: String): Boolean {
        val now = clock()
        val window = windows.computeIfAbsent(principal) { Window(now, 0) }
        synchronized(window) {
            if (now - window.startMillis >= WINDOW_MILLIS) {
                window.startMillis = now
                window.count = 0
            }
            if (window.count >= perMinute) return false
            window.count += 1
            return true
        }
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
    }
}
