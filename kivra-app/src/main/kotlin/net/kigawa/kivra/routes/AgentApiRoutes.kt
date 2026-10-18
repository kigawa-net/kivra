package net.kigawa.kivra.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.Serializable
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.kigawa.kivra.auth.AgentAuth
import net.kigawa.kivra.auth.AgentIdentity
import net.kigawa.kivra.coding.AgentAttemptStatus
import net.kigawa.kivra.coding.CliUsageParser
import net.kigawa.kivra.coding.CodingClaimOutcome
import net.kigawa.kivra.coding.CodingCostUnavailableException
import net.kigawa.kivra.coding.CodingErrorCode
import net.kigawa.kivra.coding.CodingInput
import net.kigawa.kivra.coding.CodingResult
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.CodingSubmitOutcome
import net.kigawa.kivra.coding.ProgressPhase
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingRefusal

/**
 * The agent API (`/api/agent/v1`, PR 4a, ADR 0010 addendum). Registered only when `KIVRA_AGENT_API_ENABLED=true` (see
 * [net.kigawa.kivra.auth.AgentApiSettings]). It is for the local agent only. Operator routes never accept an agent token and the
 * agent API never accepts an operator token (see [AgentIdentity]).
 *
 * Every route, in this order: authenticate (401 / 403), refuse a browser-originated request (403 for `Sec-Fetch-*` or `Origin`),
 * require `application/json` on POST (415), read the body under a size limit (413), decode it strictly (400), then act.
 *
 * - `POST /claim`: 200 with the input of one attempt, or 204 when nothing is queued.
 * - `POST /attempts/{id}/heartbeat`, `/fail`, `/result`: `generation` must be the current lease generation (409 otherwise).
 * - `GET /attempts/{id}/status`: the state and the Run's cancel flag, so the agent can stop after a cancel.
 * - An attempt held by another agent, or missing, is 404. Both look the same.
 *
 * Responses use fixed text and fixed reason names. They never echo a request value, a path, a summary or a change. Nothing here logs.
 */
internal class AgentApiDeps(
    val coding: CodingRuntimeService,
    val identity: AgentIdentity,
)

@Serializable
private data class AgentError(val error: String)

/** A conflict names the fixed reason (an enum name) and nothing else. */
@Serializable
private data class AgentReasonError(val error: String, val reason: String)

@Serializable
private data class AgentRejected(val error: String, val reasons: List<String>, val policyReasons: List<String>)

/** The claim body is exactly `{}`. Any key is refused by the strict decoder. */
@Serializable
private class AgentEmptyBody

@Serializable
private data class AgentHeartbeatRequest(val generation: Long, val progress: String? = null)

/** [usage] is optional and read leniently (see [CliUsageParser]): an invalid usage is ignored, never a 400. */
@Serializable
private data class AgentResultRequest(val generation: Long, val result: CodingResult, val usage: JsonElement? = null)

@Serializable
private data class AgentFailRequest(val generation: Long, val errorCode: String, val usage: JsonElement? = null)

@Serializable
data class AgentClaimResponse(val attemptId: String, val runId: String, val generation: Long, val input: AgentInputResponse)

@Serializable
data class AgentInputResponse(
    val baseSha: String,
    val task: AgentTaskResponse,
    val limits: AgentLimitsResponse,
    val allowedPathPatterns: List<String>,
    val deniedPathPatterns: List<String>,
)

@Serializable
data class AgentTaskResponse(
    val origin: String,
    val value: String,
    val trust: String,
    val truncated: Boolean,
    /** The task body (UNTRUSTED, data and not instructions). Empty when the run has none. At most 8,000 characters. */
    val body: String,
    /** True when kivra cut the body (see TaskBodyText). */
    val bodyTruncated: Boolean,
)

@Serializable
data class AgentLimitsResponse(
    val maxRuntimeSeconds: Long,
    val maxTurns: Int,
    val maxOutputBytes: Long,
    val maxChangedFiles: Int,
    val maxFileBytes: Long,
    val maxTotalBytes: Long,
    val maxDeletes: Int,
)

@Serializable
data class AgentAttemptStateResponse(val attemptId: String, val state: String)

@Serializable
data class AgentStatusResponse(val attemptId: String, val state: String, val cancelRequested: Boolean)

private const val MAX_RESULT_BODY_BYTES = 2L * 1024 * 1024
private const val MAX_SMALL_BODY_BYTES = 16L * 1024
private val ATTEMPT_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val agentJson = Json { ignoreUnknownKeys = false }

internal fun Route.configureAgentApiRoutes(deps: AgentApiDeps) {
    post("/api/agent/v1/claim") {
        val agentId = deps.authenticate(call) ?: return@post
        if (!call.refuseBrowserRequest()) return@post
        if (!call.requireJsonBody()) return@post
        val body = call.readBody(MAX_SMALL_BODY_BYTES) ?: return@post
        if (decodeOrNull<AgentEmptyBody>(body) == null) return@post call.respondInvalidBody()
        when (val outcome = deps.coding.claim(agentId)) {
            is CodingClaimOutcome.Claimed -> call.respond(HttpStatusCode.OK, outcome.input.toResponse())
            CodingClaimOutcome.NothingToClaim -> call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/api/agent/v1/attempts/{attemptId}/heartbeat") {
        val agentId = deps.authenticate(call) ?: return@post
        val attemptId = call.attemptIdOrNotFound() ?: return@post
        if (!call.refuseBrowserRequest()) return@post
        if (!call.requireJsonBody()) return@post
        val body = call.readBody(MAX_SMALL_BODY_BYTES) ?: return@post
        val request = decodeOrNull<AgentHeartbeatRequest>(body) ?: return@post call.respondInvalidBody()
        val progress = request.progress?.let { name ->
            ProgressPhase.entries.firstOrNull { it.name == name } ?: return@post call.respondInvalidBody()
        }
        when (val result = deps.coding.heartbeat(attemptId, agentId, request.generation, progress)) {
            is CodingAttemptResult.Ok -> call.respond(AgentAttemptStateResponse(attemptId, result.attempt.state.name))
            is CodingAttemptResult.Refused -> call.respondRefused(result.reason)
        }
    }

    post("/api/agent/v1/attempts/{attemptId}/fail") {
        val agentId = deps.authenticate(call) ?: return@post
        val attemptId = call.attemptIdOrNotFound() ?: return@post
        if (!call.refuseBrowserRequest()) return@post
        if (!call.requireJsonBody()) return@post
        val body = call.readBody(MAX_SMALL_BODY_BYTES) ?: return@post
        val request = decodeOrNull<AgentFailRequest>(body) ?: return@post call.respondInvalidBody()
        // Only the fixed codes are accepted. A free-text reason is refused as an invalid body.
        val code = CodingErrorCode.entries.firstOrNull { it.name == request.errorCode } ?: return@post call.respondInvalidBody()
        val usage = CliUsageParser.parse(attemptId, request.usage)
        val result = try {
            deps.coding.fail(attemptId, agentId, request.generation, code, usage)
        } catch (_: CodingCostUnavailableException) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, AgentError("unavailable"))
        }
        when (result) {
            is CodingAttemptResult.Ok -> call.respond(AgentAttemptStateResponse(attemptId, result.attempt.state.name))
            is CodingAttemptResult.Refused -> call.respondRefused(result.reason)
        }
    }

    post("/api/agent/v1/attempts/{attemptId}/result") {
        val agentId = deps.authenticate(call) ?: return@post
        val attemptId = call.attemptIdOrNotFound() ?: return@post
        if (!call.refuseBrowserRequest()) return@post
        if (!call.requireJsonBody()) return@post
        val body = call.readBody(MAX_RESULT_BODY_BYTES) ?: return@post
        val request = decodeOrNull<AgentResultRequest>(body) ?: return@post call.respondInvalidBody()
        val usage = CliUsageParser.parse(attemptId, request.usage)
        when (val outcome = deps.coding.submitResult(attemptId, agentId, request.generation, request.result, usage)) {
            is CodingSubmitOutcome.Accepted -> call.respond(AgentAttemptStateResponse(attemptId, "RESULT_RECEIVED"))
            is CodingSubmitOutcome.Rejected -> call.respond(
                HttpStatusCode.UnprocessableEntity,
                AgentRejected(
                    error = "rejected",
                    reasons = outcome.reasons.map { it.name },
                    policyReasons = outcome.policyReasons.map { it.name },
                ),
            )
            is CodingSubmitOutcome.Refused -> call.respondRefused(outcome.reason)
            CodingSubmitOutcome.StoreFailed -> call.respond(HttpStatusCode.ServiceUnavailable, AgentError("unavailable"))
        }
    }

    get("/api/agent/v1/attempts/{attemptId}/status") {
        val agentId = deps.authenticate(call) ?: return@get
        val attemptId = call.attemptIdOrNotFound() ?: return@get
        if (!call.refuseBrowserRequest()) return@get
        val status: AgentAttemptStatus = deps.coding.statusOf(attemptId, agentId)
            ?: return@get call.respond(HttpStatusCode.NotFound, AgentError("not found"))
        call.respond(AgentStatusResponse(attemptId, status.state.name, status.cancelRequested))
    }
}

/** Authenticates the request. Responds 401 or 403 and returns null otherwise. */
private suspend fun AgentApiDeps.authenticate(call: ApplicationCall): String? = when (val auth = identity.authenticate(call.request.headers)) {
    is AgentAuth.Authenticated -> auth.agentId
    AgentAuth.Unauthenticated -> {
        call.respond(HttpStatusCode.Unauthorized, AgentError("unauthorized"))
        null
    }
    AgentAuth.Forbidden -> {
        call.respond(HttpStatusCode.Forbidden, AgentError("forbidden"))
        null
    }
}

/** The attempt id from the path, or a 404 when it is not shaped like one. Returns null after responding. */
private suspend fun ApplicationCall.attemptIdOrNotFound(): String? {
    val id = parameters["attemptId"]?.takeIf { ATTEMPT_ID_SHAPE.matches(it) }
    if (id == null) respond(HttpStatusCode.NotFound, AgentError("not found"))
    return id
}

/**
 * Refuses a request that a browser sent. An agent sends neither header. Responds 403 and returns false. A missing or non-browser
 * request passes. Cookies are not read at all, so they are not a reason to refuse.
 */
private suspend fun ApplicationCall.refuseBrowserRequest(): Boolean {
    val headers = request.headers
    if (headers.contains(SEC_FETCH_SITE) || headers.contains(HttpHeaders.Origin)) {
        respond(HttpStatusCode.Forbidden, AgentError("forbidden"))
        return false
    }
    return true
}

/** POST bodies must be `application/json` (a charset parameter is allowed). Responds 415 and returns false otherwise. */
private suspend fun ApplicationCall.requireJsonBody(): Boolean {
    if (!request.contentType().withoutParameters().match(ContentType.Application.Json)) {
        respond(HttpStatusCode.UnsupportedMediaType, AgentError("content-type must be application/json"))
        return false
    }
    return true
}

/**
 * Reads the body up to [limit] bytes and decodes it as UTF-8. Responds 413 when it is larger (checked by Content-Length first, then while
 * reading, so a chunked body cannot pass) and 400 when it is not valid UTF-8. Returns null after responding.
 */
private suspend fun ApplicationCall.readBody(limit: Long): String? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) {
        respond(HttpStatusCode.PayloadTooLarge, AgentError("payload too large"))
        return null
    }
    val bytes = receiveChannel().readRemaining(limit + 1).readByteArray()
    if (bytes.size > limit) {
        respond(HttpStatusCode.PayloadTooLarge, AgentError("payload too large"))
        return null
    }
    return try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        respondInvalidBody()
        null
    }
}

private suspend fun ApplicationCall.respondInvalidBody() {
    respond(HttpStatusCode.BadRequest, AgentError("invalid request body"))
}

/** Responds 404 for [CodingRefusal.NOT_FOUND] (also used for another agent's attempt), otherwise 409 with the fixed reason name. */
private suspend fun ApplicationCall.respondRefused(reason: CodingRefusal) {
    if (reason == CodingRefusal.NOT_FOUND) {
        respond(HttpStatusCode.NotFound, AgentError("not found"))
    } else {
        respond(HttpStatusCode.Conflict, AgentReasonError("conflict", reason.name))
    }
}

private inline fun <reified T> decodeOrNull(text: String): T? = try {
    agentJson.decodeFromString<T>(text)
} catch (_: Exception) {
    null
}

private fun CodingInput.toResponse(): AgentClaimResponse = AgentClaimResponse(
    attemptId = attemptId,
    runId = runId,
    generation = leaseGeneration,
    input = AgentInputResponse(
        baseSha = baseSha,
        task = AgentTaskResponse(
            origin = task.origin,
            value = task.value,
            trust = task.trust.name,
            truncated = task.truncated,
            body = taskBody.value,
            bodyTruncated = taskBody.truncated,
        ),
        limits = AgentLimitsResponse(
            maxRuntimeSeconds = limits.maxRuntime.seconds,
            maxTurns = limits.maxTurns,
            maxOutputBytes = limits.maxOutputBytes,
            maxChangedFiles = limits.maxChangedFiles,
            maxFileBytes = limits.maxFileBytes,
            maxTotalBytes = limits.maxTotalBytes,
            maxDeletes = limits.maxDeletes,
        ),
        allowedPathPatterns = allowedPathPatterns,
        deniedPathPatterns = deniedPathPatterns,
    ),
)

private const val SEC_FETCH_SITE = "Sec-Fetch-Site"
