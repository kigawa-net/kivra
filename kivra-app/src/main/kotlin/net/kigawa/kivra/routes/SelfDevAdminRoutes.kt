package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.OPERATOR_RELEASED_REASON
import net.kigawa.kivra.db.ReleaseResult

/** Body of `POST /api/admin/selfdev/runs/{id}/release`. Strict: any other field is refused. */
@Serializable
data class ReleaseSelfDevRunRequest(val reason: String)

@Serializable
data class ReleaseSelfDevRunResponse(val runId: String, val state: String, val reasonCode: String)

@Serializable
private data class SelfDevAdminError(val error: String)

private const val MAX_RELEASE_REASON_CHARS = 500
private val releaseJson = Json { ignoreUnknownKeys = false }
private val ADMIN_RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")

/**
 * Operator release of an UNKNOWN self-development run (ADR 0009). Authenticated like the budget acknowledgement (ADR 0003): [identity]
 * is the Task API identity, and the operator is always the authenticated principal. When [identity] is null the route is not
 * registered (fail closed).
 *
 * Responses: 200 released (UNKNOWN -> CANCELLED), 400 invalid body (fixed text), 401 unauthenticated, 404 no such self-development
 * run, 409 the run is not UNKNOWN. The reason is written to the audit payload only. It is never logged or echoed.
 */
internal fun Route.configureSelfDevAdminRoutes(runs: DevelopmentRunRepository, identity: TaskApiIdentity?) {
    if (identity == null) return
    post("/api/admin/selfdev/runs/{id}/release") {
        val operator = identity.principal(call.request.headers)
        if (operator == null) {
            call.respond(HttpStatusCode.Unauthorized, SelfDevAdminError("unauthorized"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val body = try {
            releaseJson.decodeFromString(ReleaseSelfDevRunRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, SelfDevAdminError(INVALID_RELEASE_REQUEST))
            return@post
        }
        if (body.reason.isBlank() || body.reason.codePointCount(0, body.reason.length) > MAX_RELEASE_REASON_CHARS) {
            call.respond(HttpStatusCode.BadRequest, SelfDevAdminError(INVALID_RELEASE_REQUEST))
            return@post
        }
        val id = call.parameters["id"]?.takeIf { ADMIN_RUN_ID_SHAPE.matches(it) }
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, SelfDevAdminError("run not found"))
            return@post
        }
        when (runs.releaseUnknownSelfDev(RunId(id), operator, body.reason)) {
            is ReleaseResult.Released -> call.respond(
                HttpStatusCode.OK,
                ReleaseSelfDevRunResponse(runId = id, state = "CANCELLED", reasonCode = OPERATOR_RELEASED_REASON),
            )
            ReleaseResult.NotFound -> call.respond(HttpStatusCode.NotFound, SelfDevAdminError("run not found"))
            ReleaseResult.NotUnknown -> call.respond(HttpStatusCode.Conflict, SelfDevAdminError("run is not UNKNOWN"))
        }
    }
}

private const val INVALID_RELEASE_REQUEST = "invalid release request"
