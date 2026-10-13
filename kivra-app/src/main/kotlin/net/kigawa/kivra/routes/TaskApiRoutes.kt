package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.TaskSubmitResult

/**
 * Task API (Issue #112): authenticated, persisted Task submission. Every Run here is SIMULATED and CREATED only. No
 * external system is called and no Agent is started. The principal comes from [TaskApiIdentity]; a caller sees only
 * the Runs it submitted (another caller's run id is 404).
 *
 * Registered only when `KIVRA_AUTH_MODE` is set (see [net.kigawa.kivra.auth.resolveTaskApiIdentity]).
 */
@Serializable
data class SubmitTaskRequest(val title: String, val idempotencyKey: String)

/** The Run snapshot returned by POST, GET /api/runs/{id} and GET /api/runs. */
@Serializable
data class TaskRunResponse(
    val runId: String,
    val taskId: String,
    val title: String,
    val state: String,
    val mode: String,
    val executed: Boolean,
    val createdAt: String,
)

@Serializable
private data class TaskApiError(val error: String)

internal const val MAX_TASK_TITLE_CODE_POINTS = 200
internal const val MAX_IDEMPOTENCY_KEY_CODE_POINTS = 128

/** Returns the trimmed value, or null when it is empty, too long (in code points) or contains control characters. */
internal fun validatedText(raw: String, maxCodePoints: Int): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.codePointCount(0, value.length) > maxCodePoints) return null
    if (value.any { it.isISOControl() }) return null
    return value
}

internal fun DevelopmentRun.toTaskRunResponse(): TaskRunResponse = TaskRunResponse(
    runId = runId.value,
    taskId = taskId,
    title = taskTitle.orEmpty(),
    state = state.name,
    mode = "SIMULATED",
    executed = false,
    createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
)

internal fun Route.configureTaskApiRoutes(runs: DevelopmentRunRepository, identity: TaskApiIdentity) {
    post("/api/tasks") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@post
        }
        val request = try { call.receive<SubmitTaskRequest>() } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, TaskApiError("invalid request body"))
            return@post
        }
        val title = validatedText(request.title, MAX_TASK_TITLE_CODE_POINTS)
        val key = validatedText(request.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        if (title == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, TaskApiError("title must be 1-200 characters and idempotencyKey 1-128 characters, without control characters"))
            return@post
        }
        when (val result = runs.submitTask(principal, key, title)) {
            is TaskSubmitResult.Created -> call.respond(HttpStatusCode.Accepted, result.run.toTaskRunResponse())
            is TaskSubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.run.toTaskRunResponse())
            is TaskSubmitResult.ConflictingTitle -> call.respond(HttpStatusCode.Conflict, TaskApiError("idempotency key was already used with a different title"))
        }
    }

    get("/api/runs/{id}") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@get
        }
        val run = call.parameters["id"]?.let { runs.getTask(RunId(it), principal) }
        if (run == null) call.respond(HttpStatusCode.NotFound, TaskApiError("run not found"))
        else call.respond(run.toTaskRunResponse())
    }

    get("/api/runs") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@get
        }
        call.respond(runs.listTasks(principal, limit = 50).map { it.toTaskRunResponse() })
    }
}
