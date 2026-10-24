package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.OperatorAuth
import net.kigawa.kivra.auth.OperatorAuthenticator
import net.kigawa.kivra.auth.Permission
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.PermissionService
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.auth.RolesDefault
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlan
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import net.kigawa.kivra.selfdev.stage1ToStage2Readout
import org.slf4j.LoggerFactory

/*
 * The autopilot's operator API (ADR 0011 section 1.2, Issue #233 PR D). Authentication is the operator's: the OIDC access token with
 * the required role (`admin-panel:admin`), checked by [OperatorAuthenticator]. A token that also carries the agent role is refused
 * with 403 (an agent is never an operator). Every POST passes [requireJsonSameOrigin].
 *
 * Everything answers 503 while the autopilot is disabled (ADR 0011 section U-12): no goal is accepted and nothing is read or changed.
 * The UI uses that 503 to hide the autopilot section.
 *
 * Changing calls take `{"version": n}`, the version the caller read, so a stale view is a 409 (compare and set). Errors use fixed text
 * and never echo a request value. Goal, plan and task text is returned only by the detail view, never logged.
 *
 * Who may do what is decided by [PermissionGuard] (ADR 0020, PR 3b). With the roles off (the default) the AI principal
 * (`KIVRA_SELFDEV_AI_PRINCIPAL`) may read, and may not change a goal, a task or the autopilot's control (pause, resume, the breaker reset,
 * the automatic merge's pause and resume): every such write answers 403 `forbidden` for it, before the body is read (ADR 0019; the same rule
 * as the order routes, ADR 0015 section 4.6). With the roles on, the role table decides: READ for a read, ORDER_MANAGE for the control,
 * GOAL_WRITE for a goal or a task, GOAL_STOP for the stop.
 */
class SelfDevGoalRouteDeps(
    val operators: OperatorAuthenticator,
    val autopilot: AutopilotSettings,
    /** `owner/repo` of the self-development target. Stored with each goal at submission (fixed; not changeable from the UI). */
    val targetRepo: String?,
    val goals: SelfDevGoalRepository,
    val plans: SelfDevPlanRepository,
    val operations: SelfDevOperatorRepository,
    val control: SelfDevAutopilotControlRepository,
    /** The shadow record of the self-development memory (ADR 0016 P1). Off unless the memory switch is on. Never read here. */
    val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
    /** The AI principal's owner key (`KIVRA_SELFDEV_AI_PRINCIPAL`), or null when it is not configured. Null changes nothing. */
    val aiPrincipal: String? = null,
    /** The permission entrance. The default is the roles off and no store: today's rules, from [aiPrincipal]. */
    val guard: PermissionGuard = PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, null, null)),
)

@Serializable
data class SubmitGoalRequest(val title: String, val body: String? = null, val idempotencyKey: String)

@Serializable
data class VersionRequest(val version: Long)

@Serializable
data class StopGoalRequest(val version: Long, val reason: String)

@Serializable
data class GoalSummaryResponse(
    val goalId: String,
    val title: String,
    val state: String,
    val stateReason: String?,
    val revision: Int,
    val version: Long,
    val targetRepo: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class GoalDetailResponse(val goal: GoalSummaryResponse, val body: String?, val plans: List<PlanResponse>)

@Serializable
data class PlanResponse(
    val planId: String,
    val revision: Int,
    val state: String,
    val summary: String?,
    val plannerModel: String,
    val createdAt: String,
    val tasks: List<TaskResponse>,
)

@Serializable
data class TaskResponse(
    val taskId: String,
    val ordinal: Int,
    val title: String,
    val body: String?,
    val targetPaths: List<String>,
    val sizeEstimate: String,
    val dependsOn: List<Int>,
    val requiresApproval: Boolean,
    val state: String,
    val stateReason: String?,
    val runId: String?,
    val version: Long,
)

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
private val GOAL_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val TASK_ID_SHAPE = Regex("([0-9A-Z]{26})-([1-9][0-9]{0,4})")
private const val MAX_REASON_CODE_POINTS = 500
private const val LIST_LIMIT = 50

/**
 * The agent role the autopilot refuses (`KIVRA_AGENT_REQUIRED_ROLE`, `<client>:<role>`). Read only while the autopilot is enabled, and
 * required then: without it the agent check could not be made, so startup fails.
 */
fun requiredAgentRole(env: Map<String, String>): RequiredRole {
    val raw = env[AGENT_ROLE_ENV]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("$AUTOPILOT_ENV_ENABLED=true requires $AGENT_ROLE_ENV")
    return RequiredRole.parse(raw, AGENT_ROLE_ENV)
}

private const val AGENT_ROLE_ENV = "KIVRA_AGENT_REQUIRED_ROLE"
private const val AUTOPILOT_ENV_ENABLED = "KIVRA_SELFDEV_AUTOPILOT_ENABLED"

private val operatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.autopilot.operator")

/** A pseudonym for the operator in logs: a short SHA-256 prefix. The principal itself is never logged. */
internal fun operatorTag(principal: String): String =
    MessageDigest.getInstance("SHA-256").digest(principal.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

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
        val readout = stage1ToStage2Readout(deps.plans.promotionCounts())
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

    post("/api/selfdev/goals") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.goal_submit", "GOAL_SUBMIT")) return@post
        val request = try {
            strictJson.decodeFromString(SubmitGoalRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, GoalError("invalid request body"))
            return@post
        }
        val title = validatedText(request.title, MAX_TASK_TITLE_CODE_POINTS)
        val key = validatedText(request.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        if (title == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, GoalError(GOAL_FIELDS_INVALID))
            return@post
        }
        // Same rule as the Task submission (Issue #229): a blank body is none; a non-blank body must pass validatedBody.
        val rawBody = request.body
        val body = validatedBody(rawBody)
        if (rawBody != null && body == null && rawBody.isNotBlank()) {
            call.respond(HttpStatusCode.BadRequest, GoalError(BODY_INVALID))
            return@post
        }
        val repo = deps.targetRepo ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, GoalError(AUTOPILOT_DISABLED))
            return@post
        }
        when (val result = deps.goals.submit(principal, key, title, body, repo)) {
            is SubmitResult.Created -> {
                operatorLog.info("autopilot operator action: action=GOAL_SUBMITTED actor={} goalId={} result=GOAL_SUBMITTED", operatorTag(principal), result.goal.goalId)
                // The title only (ADR 0016 U-6). The body is never recorded.
                deps.memory.goalTitle(result.goal.goalId, title, principal)
                call.respond(HttpStatusCode.Accepted, result.goal.summary())
            }
            is SubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.goal.summary())
            SubmitResult.KeyConflict -> call.respond(HttpStatusCode.Conflict, GoalError(KEY_CONFLICT))
        }
    }

    get("/api/selfdev/goals") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "goal.read", "READ")) return@get
        call.respond(deps.operations.listGoals(LIST_LIMIT).map { it.summary() })
    }

    get("/api/selfdev/goals/{id}") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "goal.read", "READ")) return@get
        val goal = goalIdOf(call)?.let { deps.operations.goal(it) }
        if (goal == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(GOAL_NOT_FOUND))
            return@get
        }
        val plans = deps.plans.plansOf(goal.goalId).map { plan -> plan.response(deps.plans.tasksOf(plan.planId)) }
        // The body is shown to the creator and to the operators. Every caller here holds the operator role, so this is every operator.
        call.respond(GoalDetailResponse(goal = goal.summary(), body = goal.body, plans = plans))
    }

    post("/api/selfdev/goals/{id}/approve-plan") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.goal_approve_plan", "GOAL_APPROVE_PLAN")) return@post
        val goalId = goalIdOf(call)
        val version = call.readVersion() ?: return@post
        if (goalId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(GOAL_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.approvePlan(goalId, version, principal)) {
            is Transition.Moved -> {
                operatorLog.info("autopilot operator action: action=PLAN_APPROVED actor={} goalId={} result=TASKS_QUEUED", operatorTag(principal), goalId)
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
        val goalId = goalIdOf(call)
        val version = call.readVersion() ?: return@post
        if (goalId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(GOAL_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.replanBlocked(goalId, version)) {
            is Transition.Moved -> {
                operatorLog.info("autopilot operator action: action=REPLAN actor={} goalId={} reason={}", operatorTag(principal), goalId, AutopilotReason.REPLAN_REQUESTED)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/goals/{id}/stop") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_STOP, "goal.goal_stop", "GOAL_STOP")) return@post
        val goalId = goalIdOf(call)
        val request = try {
            strictJson.decodeFromString(StopGoalRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, GoalError(INVALID_BODY))
            return@post
        }
        // The reason is checked here and is not stored or logged: the goal has no audit row of its own yet (ADR 0011 U-18).
        val reason = request.reason.trim()
        if (reason.isEmpty() || reason.codePointCount(0, reason.length) > MAX_REASON_CODE_POINTS || reason.any { it.isISOControl() }) {
            call.respond(HttpStatusCode.BadRequest, GoalError(REASON_INVALID))
            return@post
        }
        if (goalId == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(GOAL_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.stopGoal(goalId, request.version)) {
            is Transition.Moved -> {
                operatorLog.info("autopilot operator action: action=STOP actor={} goalId={} reason={}", operatorTag(principal), goalId, AutopilotReason.OPERATOR_STOPPED)
                deps.memory.goalFact(goalId, r.row.stateReason)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/approve") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.task_approve", "TASK_APPROVE")) return@post
        val id = taskIdOf(call)
        val version = call.readVersion() ?: return@post
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.approveTask(id.first, id.second, version, principal)) {
            is Transition.Moved -> {
                operatorLog.info("autopilot operator action: action=TASK_APPROVED actor={} planId={} ordinal={} result=QUEUED", operatorTag(principal), id.first, id.second)
                deps.memory.taskFact({ deps.operations.goalIdOfPlan(id.first) }, r.row, r.row.stateReason)
                call.respond(HttpStatusCode.OK, r.row.response())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/reject") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "goal.task_reject", "TASK_REJECT")) return@post
        val id = taskIdOf(call)
        val version = call.readVersion() ?: return@post
        if (id == null) {
            call.respond(HttpStatusCode.NotFound, GoalError(TASK_NOT_FOUND))
            return@post
        }
        when (val r = deps.operations.rejectTask(id.first, id.second, version)) {
            is Transition.Moved -> {
                operatorLog.info("autopilot operator action: action=TASK_REJECTED actor={} planId={} ordinal={} result=SKIPPED", operatorTag(principal), id.first, id.second)
                deps.memory.taskFact({ deps.operations.goalIdOfPlan(id.first) }, r.row, r.row.stateReason)
                call.respond(HttpStatusCode.OK, r.row.response())
            }
            else -> call.respondTransition(r)
        }
    }
}

private const val GOAL_FIELDS_INVALID = "title must be 1-200 characters and idempotencyKey 1-128 characters, without control characters"
private const val BODY_INVALID = "body must be at most 8000 characters, and may contain line breaks but no other control characters"
private const val KEY_CONFLICT = "idempotency key was already used with a different request"
private const val GOAL_NOT_FOUND = "goal not found"
private const val TASK_NOT_FOUND = "task not found"
private const val INVALID_BODY = "invalid request body"
private const val REASON_INVALID = "reason must be 1-500 characters without control characters"
private const val AUTOPILOT_DISABLED = "autopilot is disabled"
private const val STALE = "the goal or task changed; reload and try again"
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

/** 503 while the autopilot is disabled. Returns false after responding. */
private suspend fun SelfDevGoalRouteDeps.requireEnabled(call: ApplicationCall): Boolean {
    if (autopilot.enabled && autopilot.config != null) return true
    call.respond(HttpStatusCode.ServiceUnavailable, GoalError(AUTOPILOT_DISABLED))
    return false
}

/**
 * The permission check (ADR 0020, PR 3b). Responds 403 `forbidden` and returns false when refused; runs before the body is read, so a refused
 * request stores nothing. The refusal is logged with the action name and a fixed code only (no principal, title, body or reason): for the
 * AI that is the line the AI refusal has always written (`actor=AI result=forbidden`).
 */
private suspend fun SelfDevGoalRouteDeps.permit(
    call: ApplicationCall,
    caller: GoalCaller,
    permission: Permission,
    route: String,
    action: String,
): Boolean {
    val result = guard.check(caller.access, permission, route)
    if (result is GuardResult.Allow) return true
    val code = (result as GuardResult.Deny).code
    operatorLog.info("autopilot operator refused: action={} actor={} result={}", action, if (caller.access.isAi) "AI" else "OPERATOR", if (caller.access.isAi) FORBIDDEN else code)
    call.respond(HttpStatusCode.Forbidden, GoalError(FORBIDDEN))
    return false
}

private class GoalCaller(val principal: String, val access: GuardAccess)

/** The goal id from the path, or null when it is not a ULID shape. */
private fun goalIdOf(call: ApplicationCall): String? = call.parameters["id"]?.takeIf { GOAL_ID_SHAPE.matches(it) }

/** The task id `{planId}-{ordinal}` from the path, or null when malformed. */
private fun taskIdOf(call: ApplicationCall): Pair<String, Int>? {
    val match = call.parameters["id"]?.let { TASK_ID_SHAPE.matchEntire(it) } ?: return null
    return match.groupValues[1] to match.groupValues[2].toInt()
}

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

private suspend fun ApplicationCall.respondTransition(r: Transition<*>) = when (r) {
    Transition.NotFound -> respond(HttpStatusCode.NotFound, GoalError(GOAL_NOT_FOUND))
    Transition.StaleVersion -> respond(HttpStatusCode.Conflict, GoalError(STALE))
    is Transition.Refused -> respond(HttpStatusCode.Conflict, GoalError(REFUSED))
    is Transition.Moved<*> -> respond(HttpStatusCode.OK)
}

private fun SelfDevGoal.summary() = GoalSummaryResponse(
    goalId = goalId,
    title = title,
    state = state.name,
    stateReason = stateReason?.name,
    revision = revision,
    version = version,
    targetRepo = targetRepo,
    createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
    updatedAt = updatedAt.toInstant(ZoneOffset.UTC).toString(),
)

private fun SelfDevPlan.response(tasks: List<SelfDevPlanTask>) = PlanResponse(
    planId = planId,
    revision = revision,
    state = state.name,
    summary = summary,
    plannerModel = plannerModel,
    createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
    tasks = tasks.map { it.response() },
)

private fun SelfDevPlanTask.response() = TaskResponse(
    taskId = "$planId-$ordinal",
    ordinal = ordinal,
    title = title,
    body = body,
    targetPaths = targetPaths,
    sizeEstimate = sizeEstimate,
    dependsOn = dependsOn,
    requiresApproval = requiresApproval,
    state = state.name,
    stateReason = stateReason?.name,
    runId = runId,
    version = version,
)
