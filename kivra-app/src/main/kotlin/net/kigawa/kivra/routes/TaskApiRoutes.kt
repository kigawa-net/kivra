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
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditTrailEntry
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SELF_DEV_TASK_PREFIX
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.github.RepoRef

/**
 * Task API (Issue #112): authenticated, persisted Task submission. Every Run here is SIMULATED and CREATED only. No
 * external system is called and no Agent is started. The principal comes from [TaskApiIdentity]; a caller sees only
 * the Runs it submitted (another caller's run id is 404).
 *
 * Registered only when `KIVRA_AUTH_MODE` is set (see [net.kigawa.kivra.auth.resolveTaskApiIdentity]).
 */
@Serializable
data class SubmitTaskRequest(val title: String, val idempotencyKey: String)

@Serializable
data class SubmitCommentRequest(val body: String, val idempotencyKey: String)

/**
 * The Run snapshot returned by POST, GET /api/runs/{id}, GET /api/runs and the operator views (GET /api/admin/runs...),
 *
 * [title] is the task title the authenticated principal submitted, for both kinds. It is shown only to that principal and to
 * operators (ADR 0008 addendum, 2026-10-17). A self-development run (mode SELF_DEV) has no [executed] (it is not a simulation,
 * and kivra does not claim it ran), and [effect] CREATES_PULL_REQUEST. Its pull request, when one exists, comes from the run's
 * binding in the database. GitHub is not called. [pullRequestUrl] is set only when the repository is the configured target repository.
 */
@Serializable
data class TaskRunResponse(
    val runId: String,
    val taskId: String,
    val title: String,
    val state: String,
    val mode: String,
    val executed: Boolean?,
    val effect: String,
    val reasonCode: String?,
    val createdAt: String,
    val updatedAt: String,
    val pullRequestRepository: String? = null,
    val pullRequestNumber: Long? = null,
    val pullRequestUrl: String? = null,
    /** The reference a self-development run's task was resolved from, as `owner/repo#n`. Null when none. Shown like the title. */
    val taskSource: String? = null,
    /** Only in the operator view (GET /api/admin/runs). The authenticated principal that submitted the run. */
    val createdBy: String? = null,
    /** Only in a detail response. The run's audit events (time, action, result, fixed codes). */
    val events: List<RunEventResponse>? = null,
    /** The project id the run belongs to, or null when unassigned. */
    val projectId: String? = null,
    /** The project name the run belongs to, or null when unassigned. */
    val projectName: String? = null,
)

/** One audit event of a run. [toState] and [reasonCode] are set only for a state transition. Actors and payloads are not shown. */
@Serializable
data class RunEventResponse(
    val seq: Long,
    val occurredAt: String,
    val action: String,
    val result: String,
    val toState: String? = null,
    val reasonCode: String? = null,
)

@Serializable
private data class TaskApiError(val error: String)

internal const val MAX_TASK_TITLE_CODE_POINTS = 200
internal const val MAX_IDEMPOTENCY_KEY_CODE_POINTS = 128

/**
 * True for a bidirectional control (LRE, RLE, PDF, LRO, RLO, LRI, RLI, FSI, PDI, LRM, RLM, ALM). Such a character makes text
 * read differently from how it is stored (Trojan Source). ZWJ, ZWNJ and variation selectors are not listed: emoji and Japanese need them.
 */
internal fun isBidiControl(c: Char): Boolean =
    c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C'

/** Returns the trimmed value, or null when it is empty, too long (in code points), contains control characters or a bidi control. */
internal fun validatedText(raw: String, maxCodePoints: Int): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.codePointCount(0, value.length) > maxCodePoints) return null
    if (value.any { it.isISOControl() || isBidiControl(it) }) return null
    return value
}

/** True for a self-development run (its task id carries [SELF_DEV_TASK_PREFIX]). */
internal fun DevelopmentRun.isSelfDev(): Boolean = taskId.startsWith(SELF_DEV_TASK_PREFIX)

/**
 * The caller-facing view of a run. [binding] is the run's pull request binding (null when there is none), [targetRepo] the
 * configured self-development repository (null when self-development is not configured: then no link is made).
 */
internal fun DevelopmentRun.toTaskRunResponse(
    binding: PullRequestBinding? = null,
    targetRepo: RepoRef? = null,
    createdBy: String? = null,
    events: List<RunEventResponse>? = null,
    projectName: String? = null,
): TaskRunResponse {
    val selfDev = isSelfDev()
    return TaskRunResponse(
        runId = runId.value,
        taskId = taskId,
        title = taskTitle.orEmpty(),
        state = state.name,
        mode = if (selfDev) "SELF_DEV" else "SIMULATED",
        executed = if (selfDev) null else false,
        effect = if (selfDev) "CREATES_PULL_REQUEST" else "SIMULATED_ONLY",
        reasonCode = stateReason,
        createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
        updatedAt = updatedAt.toInstant(ZoneOffset.UTC).toString(),
        pullRequestRepository = binding?.repository,
        pullRequestNumber = binding?.prNumber,
        pullRequestUrl = binding?.let { pullRequestUrlOf(it, targetRepo) },
        taskSource = taskSource,
        createdBy = createdBy,
        events = events,
        projectId = projectId,
        projectName = projectName,
    )
}

/** A link only for the configured repository and a positive pull request number. Anything else is not a link. */
internal fun pullRequestUrlOf(binding: PullRequestBinding, targetRepo: RepoRef?): String? {
    if (targetRepo == null || binding.prNumber <= 0) return null
    if (!binding.repository.equals("${targetRepo.owner}/${targetRepo.repo}", ignoreCase = true)) return null
    return "https://github.com/${targetRepo.owner}/${targetRepo.repo}/pull/${binding.prNumber}"
}

/** Builds the view of a run from the database: its binding (self-development only) and, for a detail, its audit events. */
internal fun DevelopmentRunRepository.viewOf(
    run: DevelopmentRun,
    targetRepo: RepoRef?,
    createdBy: String? = null,
    withEvents: Boolean = false,
    projectRepo: ProjectRepository? = null,
): TaskRunResponse {
    val binding = if (run.isSelfDev()) loadPullRequestBinding(run.runId) else null
    val events = if (withEvents) auditTrail(run.runId).map { it.toRunEventResponse() } else null
    val projectName = run.projectId?.let { projectRepo?.get(it)?.name }
    return run.toTaskRunResponse(binding, targetRepo, createdBy, events, projectName)
}

private fun AuditTrailEntry.toRunEventResponse(): RunEventResponse = RunEventResponse(
    seq = seq,
    occurredAt = occurredAt.toInstant(ZoneOffset.UTC).toString(),
    action = action,
    result = result,
    toState = toState,
    reasonCode = reasonCode,
)

internal fun Route.configureTaskApiRoutes(runs: DevelopmentRunRepository, identity: TaskApiIdentity, targetRepo: RepoRef? = null, guard: PermissionGuard = offPermissionGuard(), projectRepo: ProjectRepository? = null) {
    post("/api/tasks") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@post
        }
        if (!guard.permits(principal, Permission.SPEND_START, "task_submit")) {
            call.respond(HttpStatusCode.Forbidden, TaskApiError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
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
            is TaskSubmitResult.Created -> call.respond(HttpStatusCode.Accepted, result.run.toTaskRunResponse(projectName = result.run.projectId?.let { projectRepo?.get(it)?.name }))
            is TaskSubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.run.toTaskRunResponse(projectName = result.run.projectId?.let { projectRepo?.get(it)?.name }))
            is TaskSubmitResult.ConflictingTitle -> call.respond(HttpStatusCode.Conflict, TaskApiError("idempotency key was already used with a different title"))
        }
    }

    get("/api/runs/{id}") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "task_get")) {
            call.respond(HttpStatusCode.Forbidden, TaskApiError("forbidden"))
            return@get
        }
        val run = call.parameters["id"]?.let { runs.getTask(RunId(it), principal) }
        if (run == null) call.respond(HttpStatusCode.NotFound, TaskApiError("run not found"))
        else call.respond(runs.viewOf(run, targetRepo, withEvents = true, projectRepo = projectRepo))
    }

    get("/api/runs") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@get
        }
        if (!guard.permits(principal, Permission.READ, "task_list")) {
            call.respond(HttpStatusCode.Forbidden, TaskApiError("forbidden"))
            return@get
        }
        call.respond(runs.listTasks(principal, limit = 50).map { runs.viewOf(it, targetRepo, projectRepo = projectRepo) })
    }

    post("/api/runs/{id}/comments") {
        val principal = identity.principal(call.request.headers)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, TaskApiError("unauthorized"))
            return@post
        }
        if (!guard.permits(principal, Permission.SPEND_START, "task_comment")) {
            call.respond(HttpStatusCode.Forbidden, TaskApiError("forbidden"))
            return@post
        }
        if (!call.requireJsonSameOrigin()) return@post
        val request = try { call.receive<SubmitCommentRequest>() } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, TaskApiError("invalid request body"))
            return@post
        }
        val body = validatedText(request.body, 2000)
        val key = validatedText(request.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        if (body == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, TaskApiError("body must be 1-2000 characters and idempotencyKey 1-128 characters, without control characters"))
            return@post
        }
        val runId = call.parameters["id"]
        if (runId == null) {
            call.respond(HttpStatusCode.BadRequest, TaskApiError("missing run id"))
            return@post
        }
        val run = runs.getTask(RunId(runId), principal)
        if (run == null) {
            call.respond(HttpStatusCode.NotFound, TaskApiError("run not found"))
            return@post
        }
        val eventId = runs.enqueueComment(RunId(runId), principal, body)
        if (eventId == null) {
            call.respond(HttpStatusCode.NotFound, TaskApiError("run not found"))
        } else {
            call.respond(HttpStatusCode.Accepted, mapOf("eventId" to eventId))
        }
    }
}
