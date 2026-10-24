package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.OPERATOR_RELEASED_REASON
import net.kigawa.kivra.db.OPERATOR_RELEASED_UNBOUND_PR_REASON
import net.kigawa.kivra.db.ReleaseResult
import net.kigawa.kivra.db.UnboundPrReleaseResult

/** Body of `POST /api/admin/selfdev/runs/{id}/release` and `.../release-unbound-pr`. Strict: any other field is refused. */
@Serializable
data class ReleaseSelfDevRunRequest(val reason: String)

@Serializable
data class ReleaseSelfDevRunResponse(val runId: String, val state: String, val reasonCode: String)

@Serializable
private data class SelfDevAdminError(val error: String)

private const val MAX_RELEASE_REASON_CHARS = 500
private val releaseJson = Json { ignoreUnknownKeys = false }
private val ADMIN_RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val INVALID_RELEASE_REQUEST = "invalid release request"

/**
 * Operator release of a self-development run (ADR 0009 and its addendum). Authenticated like the budget acknowledgement (ADR 0003):
 * [identity] is the Task API identity, and the operator is always the authenticated principal. When [identity] is null the routes
 * are not registered (fail closed).
 *
 * - `POST .../release`: UNKNOWN -> CANCELLED (ADR 0009). 200 released, 400 invalid body (fixed text), 401 unauthenticated,
 *   404 no such self-development run, 409 the run is not UNKNOWN.
 * - `POST .../release-unbound-pr`: PULL_REQUEST without a binding -> CANCELLED (ADR 0009 addendum). 200 released, 400 and 401
 *   as above, 404 no such self-development run, 409 the run is not a PULL_REQUEST without a binding (a bound one completes on merge).
 *
 * The reason is written to the audit payload only. It is never logged or echoed. Neither route writes to GitHub.
 */
internal fun Route.configureSelfDevAdminRoutes(
    runs: DevelopmentRunRepository,
    identity: TaskApiIdentity?,
    guard: PermissionGuard = offPermissionGuard(),
) {
    if (identity == null) return
    post("/api/admin/selfdev/runs/{id}/release") {
        val operator = identity.principal(call.request.headers)
        if (operator == null) {
            call.respond(HttpStatusCode.Unauthorized, SelfDevAdminError("unauthorized"))
            return@post
        }
        if (!guard.permits(operator, Permission.BUDGET_OVERRIDE, "selfdev_release")) {
            call.respond(HttpStatusCode.Forbidden, SelfDevAdminError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val reason = call.readReleaseReason() ?: return@post
        val id = call.parameters["id"]?.takeIf { ADMIN_RUN_ID_SHAPE.matches(it) }
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, SelfDevAdminError("run not found"))
            return@post
        }
        when (runs.releaseUnknownSelfDev(RunId(id), operator, reason)) {
            is ReleaseResult.Released -> call.respond(
                HttpStatusCode.OK,
                ReleaseSelfDevRunResponse(runId = id, state = "CANCELLED", reasonCode = OPERATOR_RELEASED_REASON),
            )
            ReleaseResult.NotFound -> call.respond(HttpStatusCode.NotFound, SelfDevAdminError("run not found"))
            ReleaseResult.NotUnknown -> call.respond(HttpStatusCode.Conflict, SelfDevAdminError("run is not UNKNOWN"))
        }
    }
    post("/api/admin/selfdev/runs/{id}/release-unbound-pr") {
        val operator = identity.principal(call.request.headers)
        if (operator == null) {
            call.respond(HttpStatusCode.Unauthorized, SelfDevAdminError("unauthorized"))
            return@post
        }
        if (!guard.permits(operator, Permission.BUDGET_OVERRIDE, "selfdev_release_unbound_pr")) {
            call.respond(HttpStatusCode.Forbidden, SelfDevAdminError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val reason = call.readReleaseReason() ?: return@post
        val id = call.parameters["id"]?.takeIf { ADMIN_RUN_ID_SHAPE.matches(it) }
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, SelfDevAdminError("run not found"))
            return@post
        }
        when (runs.releaseUnboundPullRequestSelfDev(RunId(id), operator, reason)) {
            is UnboundPrReleaseResult.Released -> call.respond(
                HttpStatusCode.OK,
                ReleaseSelfDevRunResponse(runId = id, state = "CANCELLED", reasonCode = OPERATOR_RELEASED_UNBOUND_PR_REASON),
            )
            UnboundPrReleaseResult.NotFound -> call.respond(HttpStatusCode.NotFound, SelfDevAdminError("run not found"))
            UnboundPrReleaseResult.NotEligible -> call.respond(
                HttpStatusCode.Conflict,
                SelfDevAdminError("run is not a PULL_REQUEST without a binding"),
            )
        }
    }
}

/** The validated release reason, or null after a 400 has been sent. The body is decoded strictly and the reason is 1 to 500 characters. */
private suspend fun ApplicationCall.readReleaseReason(): String? {
    val body = try {
        releaseJson.decodeFromString(ReleaseSelfDevRunRequest.serializer(), receiveText())
    } catch (_: Exception) {
        respond(HttpStatusCode.BadRequest, SelfDevAdminError(INVALID_RELEASE_REQUEST))
        return null
    }
    if (body.reason.isBlank() || body.reason.codePointCount(0, body.reason.length) > MAX_RELEASE_REASON_CHARS) {
        respond(HttpStatusCode.BadRequest, SelfDevAdminError(INVALID_RELEASE_REQUEST))
        return null
    }
    return body.reason
}
