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
import net.kigawa.kivra.db.DailyTaskLimit
import net.kigawa.kivra.db.DailyTaskLimitException
import net.kigawa.kivra.db.FlatTaskSubmitResult
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.SelfDevTaskRepository
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.slf4j.LoggerFactory

class SelfDevTaskRouteDeps(
    val operators: OperatorAuthenticator,
    val autopilot: AutopilotSettings,
    val targetRepo: String?,
    val tasks: SelfDevTaskRepository,
    val operations: SelfDevOperatorRepository,
    val control: SelfDevAutopilotControlRepository,
    val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
    val aiPrincipal: String? = null,
    val guard: PermissionGuard = PermissionGuard(PermissionService(PermissionMode.OFF, RolesDefault.OPERATOR, aiPrincipal, null, null)),
    val dailyLimit: Int = 50,
)

@Serializable
data class SubmitSelfDevTaskReq(val title: String, val body: String? = null, val idempotencyKey: String)

@Serializable
data class VersionRequest(val version: Long)

@Serializable
data class StopTaskRequest(val version: Long, val reason: String)

@Serializable
data class TaskSummaryResponse(
    val taskId: String,
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
data class TaskDetailResponse(
    val task: TaskSummaryResponse,
    val body: String?,
    val dependencies: List<String>,
    val requiresApproval: Boolean,
    val runId: String?,
)

@Serializable
data class AutopilotStatusResponse(
    val enabled: Boolean,
    val targetRepo: String?,
    val paused: Boolean,
    val autoMergePaused: Boolean,
    val breakerOpen: Boolean,
    val consecutiveFailures: Int,
    val runsToday: Int,
    val maxRunsPerDay: Int,
    val maxConsecutiveFailures: Int,
    val approval: String,
)

@Serializable
private data class TaskError(val error: String)

@Serializable
private class EmptyBody

private val strictJson = Json { ignoreUnknownKeys = false }
private val TASK_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val MAX_REASON_CODE_POINTS = 500
private const val LIST_LIMIT = 50

fun requiredAgentRole(env: Map<String, String>): RequiredRole {
    val raw = env[AGENT_ROLE_ENV]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("$AUTOPILOT_ENV_ENABLED=true requires $AGENT_ROLE_ENV")
    return RequiredRole.parse(raw, AGENT_ROLE_ENV)
}

private const val AGENT_ROLE_ENV = "KIVRA_AGENT_REQUIRED_ROLE"
private const val AUTOPILOT_ENV_ENABLED = "KIVRA_SELFDEV_AUTOPILOT_ENABLED"

private val operatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.autopilot.operator")

internal fun operatorTag(principal: String): String =
    MessageDigest.getInstance("SHA-256").digest(principal.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

internal fun Route.configureSelfDevTaskRoutes(deps: SelfDevTaskRouteDeps) {
    get("/api/selfdev/autopilot") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "task.read", "READ")) return@get
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

    post("/api/selfdev/autopilot/pause") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "task.autopilot_pause", "AUTOPILOT_PAUSE")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setPaused(true, principal)
        operatorLog.info("autopilot operator action: action=PAUSE actor={} result=PAUSED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("paused" to true))
    }

    post("/api/selfdev/autopilot/resume") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "task.autopilot_resume", "AUTOPILOT_RESUME")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setPaused(false, principal)
        operatorLog.info("autopilot operator action: action=RESUME actor={} result=RESUMED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("paused" to false))
    }

    post("/api/selfdev/autopilot/auto-merge/pause") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "task.auto_merge_pause", "AUTO_MERGE_PAUSE")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setAutoMergePaused(true)
        operatorLog.info("autopilot operator action: action=AUTO_MERGE_PAUSE actor={} result=PAUSED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("autoMergePaused" to true))
    }

    post("/api/selfdev/autopilot/auto-merge/resume") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "task.auto_merge_resume", "AUTO_MERGE_RESUME")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.setAutoMergePaused(false)
        operatorLog.info("autopilot operator action: action=AUTO_MERGE_RESUME actor={} result=RESUMED", operatorTag(principal))
        call.respond(HttpStatusCode.OK, mapOf("autoMergePaused" to false))
    }

    post("/api/selfdev/autopilot/reset-breaker") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.ORDER_MANAGE, "task.autopilot_reset_breaker", "AUTOPILOT_RESET_BREAKER")) return@post
        if (!call.readEmptyBody()) return@post
        deps.control.resetBreaker()
        operatorLog.info("autopilot operator action: action=RESET_BREAKER actor={} reason={}", operatorTag(principal), AutopilotReason.OPERATOR_RESUMED)
        call.respond(HttpStatusCode.OK, mapOf("breakerOpen" to false))
    }

    post("/api/selfdev/tasks") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "task.task_submit", "TASK_SUBMIT")) return@post
        val request = try {
            strictJson.decodeFromString(SubmitSelfDevTaskReq.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, TaskError("invalid request body"))
            return@post
        }
        val title = validatedText(request.title, MAX_TASK_TITLE_CODE_POINTS)
        val key = validatedText(request.idempotencyKey, MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        if (title == null || key == null) {
            call.respond(HttpStatusCode.BadRequest, TaskError(TASK_FIELDS_INVALID))
            return@post
        }
        val rawBody = request.body
        val body = validatedBody(rawBody)
        if (rawBody != null && body == null && rawBody.isNotBlank()) {
            call.respond(HttpStatusCode.BadRequest, TaskError(BODY_INVALID))
            return@post
        }
        val repo = deps.targetRepo ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, TaskError(AUTOPILOT_DISABLED))
            return@post
        }
        val dailyLimit = if (deps.dailyLimit > 0) DailyTaskLimit(deps.dailyLimit, LocalDate.now().atStartOfDay()) else null
        val result = try {
            deps.tasks.submit(principal, key, title, body, repo, dailyLimit)
        } catch (_: DailyTaskLimitException) {
            call.respond(HttpStatusCode.Conflict, TaskError(DAILY_LIMIT_REACHED))
            return@post
        }
        when (result) {
            is FlatTaskSubmitResult.Created -> {
                operatorLog.info("autopilot operator action: action=TASK_SUBMITTED actor={} taskId={} result=TASK_SUBMITTED", operatorTag(principal), result.task.taskId)
                deps.memory.taskTitle(result.task.taskId, title, principal)
                call.respond(HttpStatusCode.Accepted, result.task.summary())
            }
            is FlatTaskSubmitResult.Replayed -> call.respond(HttpStatusCode.OK, result.task.summary())
            FlatTaskSubmitResult.KeyConflict -> call.respond(HttpStatusCode.Conflict, TaskError(KEY_CONFLICT))
        }
    }

    get("/api/selfdev/tasks") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "task.read", "READ")) return@get
        call.respond(deps.operations.listTasks(LIST_LIMIT).map { it.summary() })
    }

    get("/api/selfdev/tasks/{id}") {
        val caller = deps.requireOperator(call) ?: return@get
        if (!deps.requireEnabled(call)) return@get
        if (!deps.permit(call, caller, Permission.READ, "task.read", "READ")) return@get
        val id = taskIdOf(call) ?: run {
            call.respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
            return@get
        }
        val task = deps.operations.task(id) ?: run {
            call.respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
            return@get
        }
        call.respond(TaskDetailResponse(
            task = task.summary(),
            body = task.body,
            dependencies = task.dependencies,
            requiresApproval = task.requiresApproval,
            runId = task.runId,
        ))
    }

    post("/api/selfdev/tasks/{id}/approve") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "task.task_approve", "TASK_APPROVE")) return@post
        val id = taskIdOf(call) ?: run {
            call.respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
            return@post
        }
        val version = call.readVersion() ?: return@post
        when (val r = deps.operations.approveTask(id, version, principal)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=TASK_APPROVED actor={} taskId={} result=QUEUED", operatorTag(principal), id)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/skip") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "task.task_skip", "TASK_SKIP")) return@post
        val id = taskIdOf(call) ?: run {
            call.respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
            return@post
        }
        val version = call.readVersion() ?: return@post
        when (val r = deps.operations.skipTask(id, version)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=TASK_SKIPPED actor={} taskId={} result=SKIPPED", operatorTag(principal), id)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/replan") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_WRITE, "task.task_replan", "TASK_REPLAN")) return@post
        val id = taskIdOf(call) ?: run {
            call.respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
            return@post
        }
        val version = call.readVersion() ?: return@post
        when (val r = deps.operations.replanBlocked(id, version)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=REPLAN actor={} taskId={} reason={}", operatorTag(principal), id, AutopilotReason.REPLAN_REQUESTED)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }

    post("/api/selfdev/tasks/{id}/stop") {
        val caller = deps.requireOperator(call) ?: return@post
        val principal = caller.principal
        if (!call.requireJsonSameOrigin() || !deps.requireEnabled(call)) return@post
        if (!deps.permit(call, caller, Permission.GOAL_STOP, "task.task_stop", "TASK_STOP")) return@post
        val id = taskIdOf(call) ?: run {
            call.respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
            return@post
        }
        val request = try {
            strictJson.decodeFromString(StopTaskRequest.serializer(), call.receiveText())
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, TaskError(INVALID_BODY))
            return@post
        }
        val reason = request.reason.trim()
        if (reason.isEmpty() || reason.codePointCount(0, reason.length) > MAX_REASON_CODE_POINTS || reason.any { it.isISOControl() }) {
            call.respond(HttpStatusCode.BadRequest, TaskError(REASON_INVALID))
            return@post
        }
        when (val r = deps.operations.stopTask(id, request.version)) {
            is TaskTransition.Moved -> {
                operatorLog.info("autopilot operator action: action=STOP actor={} taskId={} reason={}", operatorTag(principal), id, AutopilotReason.OPERATOR_STOPPED)
                deps.memory.goalFact(id, r.row.stateReason)
                call.respond(HttpStatusCode.OK, r.row.summary())
            }
            else -> call.respondTransition(r)
        }
    }
}

private const val TASK_FIELDS_INVALID = "title must be 1-200 characters and idempotencyKey 1-128 characters, without control characters"
private const val BODY_INVALID = "body must be at most 8000 characters, and may contain line breaks but no other control characters"
private const val KEY_CONFLICT = "idempotency key was already used with a different request"
private const val TASK_NOT_FOUND = "task not found"
private const val INVALID_BODY = "invalid request body"
private const val REASON_INVALID = "reason must be 1-500 characters without control characters"
private const val AUTOPILOT_DISABLED = "autopilot is disabled"
private const val STALE = "the task changed; reload and try again"
private const val REFUSED = "the state does not allow this operation"
private const val FORBIDDEN = "forbidden"
private const val DAILY_LIMIT_REACHED = "daily task limit reached"

private suspend fun SelfDevTaskRouteDeps.requireOperator(call: ApplicationCall): TaskCaller? =
    when (val auth = operators.authenticate(call.request.headers)) {
        is OperatorAuth.Authenticated -> TaskCaller(auth.principal, guard.resolve(auth.principal, legacyAdmin = true))
        OperatorAuth.Unauthenticated -> {
            call.respond(HttpStatusCode.Unauthorized, TaskError("unauthorized"))
            null
        }
        OperatorAuth.AgentRefused -> {
            call.respond(HttpStatusCode.Forbidden, TaskError("forbidden"))
            null
        }
    }

private suspend fun SelfDevTaskRouteDeps.requireEnabled(call: ApplicationCall): Boolean {
    if (autopilot.enabled && autopilot.config != null) return true
    call.respond(HttpStatusCode.ServiceUnavailable, TaskError(AUTOPILOT_DISABLED))
    return false
}

private suspend fun SelfDevTaskRouteDeps.permit(
    call: ApplicationCall,
    caller: TaskCaller,
    permission: Permission,
    route: String,
    action: String,
): Boolean {
    val result = guard.check(caller.access, permission, route)
    if (result is GuardResult.Allow) return true
    val code = (result as GuardResult.Deny).code
    operatorLog.info("autopilot operator refused: action={} actor={} result={}", action, if (caller.access.isAi) "AI" else "OPERATOR", if (caller.access.isAi) FORBIDDEN else code)
    call.respond(HttpStatusCode.Forbidden, TaskError(FORBIDDEN))
    return false
}

private class TaskCaller(val principal: String, val access: GuardAccess)

private fun taskIdOf(call: ApplicationCall): String? = call.parameters["id"]?.takeIf { TASK_ID_SHAPE.matches(it) }

private suspend fun ApplicationCall.readVersion(): Long? {
    val parsed = try {
        strictJson.decodeFromString(VersionRequest.serializer(), receiveText())
    } catch (_: Exception) {
        null
    }
    if (parsed == null || parsed.version < 0) {
        respond(HttpStatusCode.BadRequest, TaskError(INVALID_BODY))
        return null
    }
    return parsed.version
}

private suspend fun ApplicationCall.readEmptyBody(): Boolean {
    val text = receiveText()
    if (text.isBlank()) return true
    val ok = try {
        strictJson.decodeFromString(EmptyBody.serializer(), text)
        true
    } catch (_: Exception) {
        false
    }
    if (!ok) respond(HttpStatusCode.BadRequest, TaskError(INVALID_BODY))
    return ok
}

private suspend fun ApplicationCall.respondTransition(r: TaskTransition) {
    when (r) {
        is TaskTransition.NotFound -> respond(HttpStatusCode.NotFound, TaskError(TASK_NOT_FOUND))
        is TaskTransition.StaleVersion -> respond(HttpStatusCode.Conflict, TaskError(STALE))
        is TaskTransition.Refused -> respond(HttpStatusCode.Conflict, TaskError(REFUSED))
        is TaskTransition.Moved -> respond(HttpStatusCode.OK, r.row.summary())
    }
}

private fun SelfDevTask.summary() = TaskSummaryResponse(
    taskId = taskId,
    title = title,
    state = state.name,
    stateReason = stateReason?.name,
    revision = revision,
    version = version,
    targetRepo = targetRepo,
    createdAt = createdAt.toInstant(ZoneOffset.UTC).toString(),
    updatedAt = updatedAt.toInstant(ZoneOffset.UTC).toString(),
)

