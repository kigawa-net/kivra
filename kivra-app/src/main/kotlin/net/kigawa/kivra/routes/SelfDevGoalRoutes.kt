package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import net.kigawa.kivra.selfdev.stage1ToStage2Readout
import org.slf4j.LoggerFactory

/*
 * The autopilot's operator API (ADR 0011 section 1.2, Issue #233 PR D), on the V40 Task model (`selfdev_tasks`). Restored after V40 removed
 * the Goal and Plan routes; the paths stay as the UI calls them. A goal is a task now: there is no plan layer, so the approval of a task
 * (PENDING_APPROVAL -> QUEUED) is the approval of the goal, and the same id serves both paths.
 *
 * Authentication is the operator's: the OIDC access token with the required role (`admin-panel:admin`), checked by [OperatorAuthenticator].
 * A token that also carries the agent role is refused with 403 (an agent is never an operator). Every POST passes [requireJsonSameOrigin].
 *
 * Everything answers 503 `{"error":"AUTOPILOT_DISABLED"}` while the autopilot is disabled (ADR 0011 section U-12): no task is accepted and
 * nothing is read or changed. The UI uses that 503 to hide the autopilot section.
 *
 * Changing calls take `{"version": n}`, the version the caller read, so a stale view is a 409 (compare and set). Errors use fixed text
 * and never echo a request value. Task text is returned only by the detail view, never logged.
 *
 * Who may do what is decided by [PermissionGuard] (ADR 0020, PR 3b). With the roles off (the default) the AI principal
 * (`KIVRA_SELFDEV_AI_PRINCIPAL`) may read, and may not change a task or the autopilot's control (pause, resume, the breaker reset, the
 * automatic merge's pause and resume): every such write answers 403 `forbidden` for it, before the body is read (ADR 0019). With the roles
 * on, the role table decides: READ for a read, ORDER_MANAGE for the control, GOAL_WRITE for a task, GOAL_STOP for the stop.
 */
class SelfDevGoalRouteDeps(
    val operators: OperatorAuthenticator,
    val autopilot: AutopilotSettings,
    /** `owner/repo` of the self-development target. Stored with each task at submission (fixed; not changeable from the UI). */
    val targetRepo: String?,
    val operations: SelfDevOperatorRepository,
    /** The projects a new goal may be assigned to (ADR 0022). A goal's projectId must name one of them. */
    val projects: ProjectRepository,
    val control: SelfDevAutopilotControlRepository,
    /** The shadow record of the self-development memory (ADR 0016 P1). Off unless the memory switch is on. Never read here. */
    val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
    /** The AI principal's owner key (`KIVRA_SELFDEV_AI_PRINCIPAL`), or null when it is not configured. Null changes nothing. */
    val aiPrincipal: String? = null,
    /** The permission entrance. The default is the roles off and no store: today's rules, from [aiPrincipal]. */
    val guard: PermissionGuard = PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, null, null)),
)

@Serializable
data class SubmitGoalRequest(
    val title: String,
    val body: String? = null,
    val idempotencyKey: String,
    /** The project the goal belongs to (ADR 0022). Required; a missing one is a 400. Nullable here so that the route answers with its own text. */
    val projectId: String? = null,
)

@Serializable
data class VersionRequest(val version: Long)

@Serializable
data class StopGoalRequest(val version: Long, val reason: String)

@Serializable
data class TaskSummaryResponse(
    val taskId: String,
    val title: String,
    val state: String,
    val stateReason: String?,
    val revision: Int,
    val version: Long,
    val targetRepo: String,
    val requiresApproval: Boolean,
    val dependencies: List<String>,
    val runId: String?,
    val attempts: Int,
    val createdAt: String,
    val updatedAt: String,
    /** The project of the task (ADR 0022). Null for a task that predates the project or is a system task without one. */
    val projectId: String? = null,
)

@Serializable
data class TaskDetailResponse(val task: TaskSummaryResponse, val body: String?)

@Serializable
data class AutopilotStatusResponse(
    val enabled: Boolean,
    val targetRepo: String?,
    val paused: Boolean,
    /** The automatic merge's own pause (ADR 0014 U-10). Read-only here; the AI principal cannot change it. */
    val autoMergePaused: Boolean,
    val breakerOpen: Boolean,
    val consecutiveFailures: Int,
    val runsToday: Int,
    val maxRunsPerDay: Int,
    val maxConsecutiveFailures: Int,
    val approval: String,
)

/** The stage 1 -> 2 readout (ADR 0011 section 6.3). Fixed ids and numbers only; the UI names each id. */
@Serializable
data class PromotionReadoutResponse(val conditions: List<PromotionConditionResponse>)

@Serializable
data class PromotionConditionResponse(
    val id: String,
    val measured: Boolean,
    val current: Long?,
    val required: Long?,
    val met: Boolean?,
)

@Serializable
private data class GoalError(val error: String)

@Serializable
private class EmptyBody

private val strictJson = Json { ignoreUnknownKeys = false }
private val TASK_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val MAX_REASON_CODE_POINTS = 500
private const val LIST_LIMIT = 50

private val operatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.autopilot.operator")

internal fun Route.configureSelfDevGoalRoutes(deps: SelfDevGoalRouteDeps) {
    get("/api/selfdev/autopilot") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "goal.read", "READ")) return@get
        val config = checkNotNull(deps.autopilot.config)
        val control = deps.control.read()
        call.respond(
            AutopilotStatusResponse(
                enabled = true,
                targetRepo = deps.targetRepo,
                paused = control.paused,
                autoMergePaused = control.autoMergePaused,
                breakerOpen = control.breakerOpen,
                consecutiveFailures = control.consecutiveFailures,
                runsToday = if (control.dayUtc == LocalDate.now(ZoneOffset.UTC)) control.runsToday else 0,
                maxRunsPerDay = config.maxRunsPerDay,
                maxConsecutiveFailures = config.maxConsecutiveFailures,
                approval = config.approval.name.lowercase(),
            ),
        )
    }

    get("/api/selfdev/autopilot/promotion") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "goal.read", "READ")) return@get
        val readout = stage1ToStage2Readout(deps.operations.promotionCounts())
        call.respond(
            PromotionReadoutResponse(
                conditions = readout.conditions.map {
                    PromotionConditionResponse(it.id, it.measured, it.current, it.required, it.met)
                },
            ),
        )
    }

    post("/api/selfdev/autopilot/pause") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "goal.autopilot_pause", "AUTOPILOT_PAUSE")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setPaused(true, principal)
        operatorLog.info("autopilot operator action: action=PAUSE actor={} result=PAUSED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("paused" to true))
    }

    post("/api/selfdev/autopilot/resume") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "goal.autopilot_resume", "AUTOPILOT_RESUME")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setPaused(false, principal)
        operatorLog.info("autopilot operator action: action=RESUME actor={} result=RESUMED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("paused" to false))
    }

    post("/api/selfdev/autopilot/auto-merge/pause") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "goal.auto_merge_pause", "AUTO_MERGE_PAUSE")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setAutoMergePaused(true)
        operatorLog.info("autopilot operator action: action=AUTO_MERGE_PAUSE actor={} result=PAUSED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("autoMergePaused" to true))
    }

    post("/api/selfdev/autopilot/auto-merge/resume") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "goal.auto_merge_resume", "AUTO_MERGE_RESUME")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setAutoMergePaused(false)
        operatorLog.info("autopilot operator action: action=AUTO_MERGE_RESUME actor={} result=RESUMED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("autoMergePaused" to false))
    }

    post("/api/selfdev/autopilot/reset-breaker") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "goal.autopilot_reset_breaker", "AUTOPILOT_RESET_BREAKER")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.resetBreaker()
        operatorLog.info("autopilot operator action: action=RESET_BREAKER actor={} reason={}", operatorTag(principal), AutopilotReason.OPERATOR_RESUMED)
        call.respond(HttpStatusCode.OK, mapOf("breakerOpen" to false))
    }

    // The old routes keep their answers; they only announce the alias (ADR 0022 PR-E).
    get("/api/selfdev/goals") {
        call.deprecatedBy("/api/projects/{projectId}/tasks", call.request.queryParameters["projectId"])
        listGoals(deps, call)
    }
    post("/api/selfdev/goals") {
        call.deprecatedBy("/api/projects/{projectId}/tasks", null)
        submitGoal(deps, call)
    }

    // The alias of the list and the submission under a project (ADR 0022 PR-E). The same handlers; the path's project is the project.
    get("/api/projects/{projectId}/tasks") { listGoals(deps, call) }
    post("/api/projects/{projectId}/tasks") { submitGoal(deps, call) }

    get("/api/selfdev/goals/{id}") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "goal.read", "READ")) return@get
        val task = taskIdOf(call)?.let { deps.operations.task(it) }
        // A goal of a project the caller may not read is the same 404 as a missing one (ADR 0022 PR-D3).
        if (task != null) deps.guard.recordReadShadow(caller.access, listOf(task.projectId), "goal.read")
        if (task == null || !deps.guard.readScope(caller.access).sees(task.projectId)) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@get
        }
        // The body is shown to the creator and to the operators. Every caller here holds the operator role, so this is every operator.
        call.respond(TaskDetailResponse(task = task.summary(), body = task.body))
    }

    post("/api/selfdev/goals/{id}/approve-plan") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val taskId = taskIdOf(call)
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.goal_approve_plan", "GOAL_APPROVE_PLAN", deps.projectOfTask(taskId))) {
            return@post
        }
        val version = call.readVersion() ?: return@post
        if (taskId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.approveTask(taskId, version, principal)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=PLAN_APPROVED actor={} taskId={} result=QUEUED", operatorTag(principal), taskId)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/goals/{id}/replan") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.goal_replan", "GOAL_REPLAN")) return@post
        val taskId = taskIdOf(call)
        val version = call.readVersion() ?: return@post
        if (taskId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.replanBlocked(taskId, version)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=REPLAN actor={} taskId={} reason={}", operatorTag(principal), taskId, AutopilotReason.REPLAN_REQUESTED)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/goals/{id}/stop") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val taskId = taskIdOf(call)
        if (!deps.permit(call, caller, Permission.GOAL_STOP, "goal.goal_stop", "GOAL_STOP", deps.projectOfTask(taskId))) return@post
        val request = try {
            strictJson.decodeFromString(StopGoalRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, GoalError(INVALID_BODY))
            return@post
        }
        // The reason is checked here and is not stored or logged: the task has no audit row of its own yet (ADR 0011 U-18).
        val reason = request.reason.trim()
        if (reason.isEmpty() || reason.codePointCount(0, reason.length) > MAX_REASON_CODE_POINTS || reason.any { it.isISOControl() }) {
            call.respond(HttpStatusCode.BadRequest, GoalError(REASON_INVALID))
            return@post
        }
        if (taskId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.stopTask(taskId, request.version)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=STOP actor={} taskId={} reason={}", operatorTag(principal), taskId, AutopilotReason.OPERATOR_STOPPED)
                deps.memory.goalFact(taskId, r.row.stateReason)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/approve") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val taskId = taskIdOf(call)
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.task_approve", "TASK_APPROVE", deps.projectOfTask(taskId))) return@post
        val version = call.readVersion() ?: return@post
        if (taskId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.approveTask(taskId, version, principal)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=TASK_APPROVED actor={} taskId={} result=QUEUED", operatorTag(principal), taskId)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/reject") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        val taskId = taskIdOf(call)
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.task_reject", "TASK_REJECT", deps.projectOfTask(taskId))) return@post
        val version = call.readVersion() ?: return@post
        if (taskId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.skipTask(taskId, version)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=TASK_REJECTED actor={} taskId={} result=SKIPPED", operatorTag(principal), taskId)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }
}

/** POST the goal (task) of the project: the old route (the body's projectId) or the alias (the path's project). */
private suspend fun submitGoal(deps: SelfDevGoalRouteDeps, call: ApplicationCall) {
    val pinned = call.pathProjectId()
    val caller = deps.requireOperator(call) ?: return
    val principal = caller.principal
    if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return
    if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.goal_submit", "GOAL_SUBMIT")) return
    val request = try {
        strictJson.decodeFromString(SubmitGoalRequest.serializer(), call.receiveText())
    } catch (_: Exception) {
        call.respond(HttpStatusCode.BadRequest, GoalError(INVALID_BODY))
        return
    }
    val title = validatedText(request.title, MAX_TASK_TITLE_CODE_POINTS)
    val key = validatedText(request.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
    if (title == null || key == null) {
        call.respond(HttpStatusCode.BadRequest, GoalError(TASK_FIELDS_INVALID))
        return
    }
    // Same rule as the Task submission (Issue #229): a blank body is none; a non-blank body must pass validatedBody.
    val rawBody = request.body
    val body = validatedBody(rawBody)
    if (rawBody != null && body == null && rawBody.isNotBlank()) {
        call.respond(HttpStatusCode.BadRequest, GoalError(BODY_INVALID))
        return
    }
    val repo = deps.targetRepo ?: run {
        call.respond(HttpStatusCode.ServiceUnavailable, GoalError(AUTOPILOT_DISABLED))
        return
    }
    // The alias (ADR 0022 PR-E) takes the path's project; the body's projectId, when given, must be the same.
    val projectId = when (val scope = pinned?.let { deps.projects.scopeOfPath(it, request.projectId) } ?: deps.projects.scopeOf(request.projectId)) {
        is ProjectScope.Refused -> {
            call.respond(scope.status, GoalError(scope.error))
            return
        }
        is ProjectScope.Assigned -> scope.projectId
    }
    if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.goal_submit", "GOAL_SUBMIT", projectId)) return
    when (val result = deps.operations.submitTask(principal, key, title, body, repo, projectId)) {
        is SelfDevSubmitResult.Created -> {
            operatorLog.info("autopilot operator action: action=GOAL_SUBMITTED actor={} taskId={} result=GOAL_SUBMITTED", operatorTag(principal), result.task.taskId)
            // The title only (ADR 0016 U-6). The body is never recorded.
            deps.memory.goalTitle(result.task.taskId, title, principal)
            call.respond(HttpStatusCode.Accepted, result.task.summary())
        }
        is SelfDevSubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.task.summary())
        SelfDevSubmitResult.KeyConflict -> call.respond(HttpStatusCode.Conflict, GoalError(KEY_CONFLICT))
    }
}

/**
 * GET the goals (tasks) of a project: the old route with its `projectId` query, or the alias with the path's project (ADR 0022 PR-E). The alias
 * answers 404 for a project that does not exist, before the permission check; the check then runs with the project.
 */
private suspend fun listGoals(deps: SelfDevGoalRouteDeps, call: ApplicationCall) {
    val caller = deps.requireOperator(call) ?: return
    if (!deps.requireEnabled(call)) return
    val pinned = call.pathProjectId()
    if (pinned != null && !deps.projects.projectExistsForPath(pinned)) {
        call.respond(HttpStatusCode.NotFound, GoalError(PROJECT_NOT_FOUND))
        return
    }
    if (!deps.permit(call, caller, Permission.READ, "goal.read", "READ")) return
    // The alias answers 404 for a project the caller may not read, as for an unknown one (ADR 0022 PR-D3). READ is held by every project role,
    // so the membership is the whole check here.
    val scope = deps.guard.readScope(caller.access)
    if (pinned != null && !scope.sees(pinned)) return call.respond(HttpStatusCode.NotFound, GoalError(PROJECT_NOT_FOUND))
    val project = when (val s = listScopeOf(pinned, call.request.queryParameters["projectId"])) {
        is ListScope.Refused -> return call.respond(s.status, GoalError(s.error))
        is ListScope.Filtered -> scope.narrow(s.filter)
    }
    val rows = deps.operations.listTasks(LIST_LIMIT, project)
    deps.guard.recordReadShadow(caller.access, rows.map { it.projectId }, "goal.read")
    call.respond(rows.map { it.summary() })
}

private const val TASK_FIELDS_INVALID = "title must be 1-200 characters and idempotencyKey 1-128 characters, without control characters"
private const val BODY_INVALID = "body must be at most 8000 characters, and may contain line breaks but no other control characters"
private const val KEY_CONFLICT = "idempotency key was already used with a different request"
private const val TASK_NOT_FOUND = "task not found"
private const val INVALID_BODY = "invalid request body"
private const val REASON_INVALID = "reason must be 1-500 characters without control characters"
/** The machine-readable 503 body while the autopilot is disabled. Clients match this exact value. */
private const val AUTOPILOT_DISABLED = "AUTOPILOT_DISABLED"
private const val STALE = "the task changed; reload and try again"
private const val REFUSED = "the state does not allow this operation"
private const val FORBIDDEN = "forbidden"

/**
 * Authenticates the caller. Responds 401 (no valid operator token) or 403 (an agent token), and returns the principal otherwise.
 * It does not look at the autopilot state first, so an unauthenticated caller learns nothing about it.
 */
private suspend fun SelfDevGoalRouteDeps.requireOperator(call: ApplicationCall): GoalCaller? =
    when (val auth = operators.authenticate(call.request.headers)) {
        // The authenticator accepts operators only, so the caller is an operator by today's rule (legacyAdmin).
        is OperatorAuth.Authenticated -> GoalCaller(auth.principal, guard.resolve(auth.principal, legacyAdmin = true))
        OperatorAuth.Unauthenticated -> {
            call.respond(HttpStatusCode.Unauthorized, GoalError("unauthorized"))
            null
        }
        OperatorAuth.AgentRefused -> {
            call.respond(HttpStatusCode.Forbidden, GoalError("forbidden"))
            null
        }
    }

/** 503 `AUTOPILOT_DISABLED` while the autopilot is disabled. Returns false after responding. */
private suspend fun SelfDevGoalRouteDeps.requireEnabled(call: ApplicationCall): Boolean {
    if (autopilot.enabled && autopilot.config != null) return true
    call.respond(HttpStatusCode.ServiceUnavailable, GoalError(AUTOPILOT_DISABLED))
    return false
}

/**
 * The permission check (ADR 0020, PR 3b). Responds 403 `forbidden` and returns false when refused; runs before the body is read, so a refused
 * request stores nothing. The refusal is logged with the action name and a fixed code only (no principal, title, body or reason).
 */
private suspend fun SelfDevGoalRouteDeps.permit(
    call: ApplicationCall,
    caller: GoalCaller,
    permission: Permission,
    route: String,
    action: String,
    /** The request's project (ADR 0022 PR-D). Null: the plain check. */
    projectId: String? = null,
): Boolean {
    val result = guard.checkInProject(caller.access, projectId, permission, route)
    if (result is GuardResult.Allow) return true
    val code = (result as GuardResult.Deny).code
    operatorLog.info("autopilot operator refused: action={} actor={} result={}", action, if (caller.access.isAi) "AI" else "OPERATOR", if (caller.access.isAi) FORBIDDEN else code)
    call.respond(HttpStatusCode.Forbidden, GoalError(FORBIDDEN))
    return false
}

private class GoalCaller(val principal: String, val access: GuardAccess)

/** The task id from the path (a ULID shape), or null when it is not a ULID shape. */
private fun taskIdOf(call: ApplicationCall): String? = call.pathParam("id")?.takeIf { TASK_ID_SHAPE.matches(it) }

/**
 * The project of a task, for the project check (ADR 0022 PR-D). Read only while the project check is on; otherwise nothing is read and the
 * result is null (the plain check). A missing task is null too, and then the request is answered 404 after the check, as before.
 */
private fun SelfDevGoalRouteDeps.projectOfTask(taskId: String?): String? =
    if (taskId == null || !guard.projectScopeOn) null else operations.task(taskId)?.projectId

/** `{"version": n}`, strictly. Responds 400 and returns null when it is malformed or negative. */
private suspend fun ApplicationCall.readVersion(): Long? {
    val parsed = try {
        strictJson.decodeFromString(VersionRequest.serializer(), receiveText())
    } catch (_: Exception) {
        null
    }
    if (parsed == null || parsed.version < 0) {
        respond(HttpStatusCode.BadRequest, GoalError(INVALID_BODY))
        return null
    }
    return parsed.version
}

/** Accepts an empty body or `{}`. Responds 400 and returns false otherwise. */
private suspend fun ApplicationCall.readEmptyBody(): Boolean {
    val text = receiveText()
    if (text.isBlank()) return true
    val ok = try {
        strictJson.decodeFromString(EmptyBody.serializer(), text)
        true
    } catch (_: Exception) {
        false
    }
    if (!ok) respond(HttpStatusCode.BadRequest, GoalError(INVALID_BODY))
    return ok
}

private suspend fun ApplicationCall.respondTransition(r: TaskTransition) = when (r) {
    TaskTransition.NotFound -> respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
    TaskTransition.StaleVersion -> respond(HttpStatusCode.Conflict, GoalError(STALE))
    is TaskTransition.Refused -> respond(HttpStatusCode.Conflict, GoalError(REFUSED))
    is TaskTransition.Moved -> respond(HttpStatusCode.OK)
}

private fun SelfDevTask.summary() = TaskSummaryResponse(
    taskId = taskId,
    title = title,
    state = state.name,
    stateReason = stateReason?.name,
    revision = revision,
    version = version,
    targetRepo = targetRepo,
    requiresApproval = requiresApproval,
    dependencies = dependencies,
    runId = runId,
    attempts = attempts,
    createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
    updatedAt = updatedAt.toInstant(ZoneOffset.UTC).toString(),
    projectId = projectId,
)
