package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SelfDevAssigneeRepository
import net.kigawa.kivra.db.TaskRequest
import net.kigawa.kivra.db.TaskRequestRepository
import net.kigawa.kivra.db.TaskRequestState
import net.kigawa.kivra.db.TaskRequestSubmitResult
import net.kigawa.kivra.db.TaskRequestTransition
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.requestRunKey
import org.slf4j.LoggerFactory

/*
 * Approval requests for a Task (ADR 0025). A requester asks "may this title be run?"; a decider who is not the requester approves or
 * rejects it; approval creates one Run through the same submission as POST /api/tasks. Authentication is the Task API's
 * (TaskApiIdentity). Each handler checks its permission first, and the route table in RolesCoverageArchitectureTest names it.
 */

@Serializable
data class SubmitRequestRequest(val title: String, val idempotencyKey: String)

@Serializable
data class RejectRequestRequest(val reason: String? = null)

/**
 * A request as its caller may see it. [requestedBy] is set only in a decider's view of another requester's request; the decider's
 * identifier never goes to the requester. [decisionNote] is the rejecter's reason, shown to the requester.
 */
@Serializable
data class TaskRequestResponse(
    val requestId: String,
    val title: String,
    val state: String,
    val createdAt: String,
    val decidedAt: String? = null,
    val decisionNote: String? = null,
    val runId: String? = null,
    val requestedBy: String? = null,
)

@Serializable
private data class RequestApiError(val error: String)

private const val MAX_REQUEST_REASON_CODE_POINTS = 500
private const val REQUEST_LIST_LIMIT = 50

private val requestLog = LoggerFactory.getLogger("net.kigawa.kivra.task.request")

internal fun TaskRequest.toResponse(showRequester: Boolean): TaskRequestResponse = TaskRequestResponse(
    requestId = requestId,
    title = title,
    state = state.name,
    createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
    decidedAt = decidedAt?.toInstant(ZoneOffset.UTC)?.toString(),
    decisionNote = decisionNote,
    runId = runId,
    requestedBy = if (showRequester) requestedBy else null,
)

/** The actor as the logs and the audit rows show it: the first 12 hex digits of the owner key's SHA-256. Never the key. */
private fun actorOf(principal: String): String = SelfDevAssigneeRepository.actorRef(principal)

internal fun Route.configureTaskRequestRoutes(
    requests: TaskRequestRepository,
    runs: DevelopmentRunRepository,
    identity: TaskApiIdentity,
    guard: PermissionGuard = offPermissionGuard(),
) {
    post("/api/requests") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RequestApiError("unauthorized"))
            return@post
        }
        if (!guard.permits(principal, Permission.REQUEST_SUBMIT, "request_submit")) {
            call.respond(HttpStatusCode.Forbidden, RequestApiError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val body = try { call.receive<SubmitRequestRequest>() } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, RequestApiError("invalid request body"))
            return@post
        }
        val title = validatedText(body.title, MAX_TASK_TITLE_CODE_POINTS)
        val key = validatedText(body.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        if (title == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, RequestApiError("title must be 1-200 characters and idempotencyKey 1-128 characters, without control characters"))
            return@post
        }
        when (val result = requests.submit(principal, key, title)) {
            is TaskRequestSubmitResult.Created -> call.respond(HttpStatusCode.Created, result.request.toResponse(showRequester = false))
            is TaskRequestSubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.request.toResponse(showRequester = false))
            is TaskRequestSubmitResult.ConflictingTitle -> call.respond(HttpStatusCode.Conflict, RequestApiError("idempotency key was already used with a different title"))
        }
    }

    get("/api/requests") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RequestApiError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "request_list")) {
            call.respond(HttpStatusCode.Forbidden, RequestApiError("forbidden"))
            return@get
        }
        // A decider also sees the PENDING requests of the others: the ones it may decide (ADR 0025 section 2).
        val decider = guard.can(guard.resolve(principal, legacyAdmin = true), Permission.REQUEST_DECIDE)
        val rows = requests.list(principal, decidable = decider, limit = REQUEST_LIST_LIMIT)
        call.respond(rows.map { it.toResponse(showRequester = decider && it.requestedBy != principal) })
    }

    get("/api/requests/{id}") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RequestApiError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "request_get")) {
            call.respond(HttpStatusCode.Forbidden, RequestApiError("forbidden"))
            return@get
        }
        val request = call.pathParam("id")?.let { requests.get(it) }
        val decider = guard.can(guard.resolve(principal, legacyAdmin = true), Permission.REQUEST_DECIDE)
        // Another caller's request is the same 404 as a missing one (the Task API's rule). A decider sees the requests it may decide.
        if (request == null || (request.requestedBy != principal && !decider)) {
            call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
        } else {
            call.respond(request.toResponse(showRequester = request.requestedBy != principal))
        }
    }

    post("/api/requests/{id}/approve") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RequestApiError("unauthorized"))
            return@post
        }
        if (!guard.permits(principal, Permission.REQUEST_DECIDE, "request_approve")) {
            call.respond(HttpStatusCode.Forbidden, RequestApiError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val requestId = call.pathParam("id")
        if (requestId == null) {
            call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
            return@post
        }
        when (val result = requests.decide(requestId, principal, approve = true, note = null)) {
            TaskRequestTransition.NotFound -> call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
            TaskRequestTransition.OwnRequest -> call.respond(HttpStatusCode.Forbidden, RequestApiError("a requester may not decide its own request"))
            is TaskRequestTransition.Moved -> {
                requestLog.info("task request action: action=APPROVED actor={} requestId={} result=APPROVED", actorOf(principal), requestId)
                linkRunAndRespond(call, requests, runs, result.request, principal)
            }
            is TaskRequestTransition.NotPending -> {
                // A request approved earlier whose run was not linked (the first call failed after the approval) is finished here.
                // The same call again creates no second Run: the run key is idempotent (ADR 0025 section 3).
                val request = result.request
                if (request.state == TaskRequestState.APPROVED && request.runId == null) {
                    linkRunAndRespond(call, requests, runs, request, principal)
                } else {
                    call.respond(HttpStatusCode.Conflict, RequestApiError("request is not pending"))
                }
            }
        }
    }

    post("/api/requests/{id}/reject") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RequestApiError("unauthorized"))
            return@post
        }
        if (!guard.permits(principal, Permission.REQUEST_DECIDE, "request_reject")) {
            call.respond(HttpStatusCode.Forbidden, RequestApiError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        // The reason is optional: an empty body, or a body with no reason, is a rejection without one.
        val text = call.receiveText()
        val reason = if (text.isBlank()) {
            null
        } else {
            val parsed = try { Json.decodeFromString<RejectRequestRequest>(text) } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, RequestApiError("invalid request body"))
                return@post
            }
            parsed.reason?.takeIf { it.isNotBlank() }?.let { value ->
                validatedText(value, MAX_REQUEST_REASON_CODE_POINTS) ?: run {
                    call.respond(HttpStatusCode.BadRequest, RequestApiError("reason must be 1-500 characters, without control characters"))
                    return@post
                }
            }
        }
        val requestId = call.pathParam("id")
        if (requestId == null) {
            call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
            return@post
        }
        when (val result = requests.decide(requestId, principal, approve = false, note = reason)) {
            TaskRequestTransition.NotFound -> call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
            TaskRequestTransition.OwnRequest -> call.respond(HttpStatusCode.Forbidden, RequestApiError("a requester may not decide its own request"))
            is TaskRequestTransition.Moved -> {
                requestLog.info("task request action: action=REJECTED actor={} requestId={} result=REJECTED", actorOf(principal), requestId)
                call.respond(result.request.toResponse(showRequester = true))
            }
            is TaskRequestTransition.NotPending -> call.respond(HttpStatusCode.Conflict, RequestApiError("request is not pending"))
        }
    }

    post("/api/requests/{id}/cancel") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, RequestApiError("unauthorized"))
            return@post
        }
        if (!guard.permits(principal, Permission.REQUEST_SUBMIT, "request_cancel")) {
            call.respond(HttpStatusCode.Forbidden, RequestApiError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val requestId = call.pathParam("id")
        if (requestId == null) {
            call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
            return@post
        }
        when (val result = requests.cancel(requestId, principal)) {
            is TaskRequestTransition.Moved -> {
                requestLog.info("task request action: action=CANCELLED actor={} requestId={} result=CANCELLED", actorOf(principal), requestId)
                call.respond(result.request.toResponse(showRequester = false))
            }
            is TaskRequestTransition.NotPending -> call.respond(HttpStatusCode.Conflict, RequestApiError("request is not pending"))
            // Only the requester cancels, so a request of another caller is the same 404 as a missing one.
            TaskRequestTransition.NotFound, TaskRequestTransition.OwnRequest -> call.respond(HttpStatusCode.NotFound, RequestApiError("request not found"))
        }
    }
}

/**
 * Creates (or finds, by the same key) the Run of an APPROVED request, links it, and responds with the request. The Run belongs to the
 * requester's principal, as a Task API submission does. [request] is APPROVED and has no run yet, or was just approved.
 */
private suspend fun linkRunAndRespond(
    call: ApplicationCall,
    requests: TaskRequestRepository,
    runs: DevelopmentRunRepository,
    request: TaskRequest,
    decider: String,
) {
    val run = when (val result = runs.submitTask(request.requestedBy, requestRunKey(request.requestId), request.title)) {
        is TaskSubmitResult.Created -> result.run
        is TaskSubmitResult.Replayed -> result.run
        // The requester's own key namespace holds this key with another title (ADR 0025 section 3): nothing is linked.
        is TaskSubmitResult.ConflictingTitle -> {
            call.respond(HttpStatusCode.Conflict, RequestApiError("the run for this request could not be created"))
            return
        }
    }
    // Null when another call linked the run first. That run is the same one (the key is idempotent), so the current row is answered.
    val linked = requests.linkRun(request.requestId, run.runId.value, decider) ?: requests.get(request.requestId) ?: request
    requestLog.info("task request action: action=RUN_LINKED actor={} requestId={} result=APPROVED", actorOf(decider), request.requestId)
    call.respond(linked.toResponse(showRequester = true))
}
