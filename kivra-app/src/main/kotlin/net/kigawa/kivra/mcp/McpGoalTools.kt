package net.kigawa.kivra.mcp

import java.time.Clock
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.DailyGoalLimit
import net.kigawa.kivra.db.DailyGoalLimitException
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.routes.MAX_IDEMPOTENCY_KEY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TASK_BODY_CODE_POINTS
import net.kigawa.kivra.routes.MAX_TASK_TITLE_CODE_POINTS
import net.kigawa.kivra.routes.operatorTag
import net.kigawa.kivra.routes.validatedBody
import net.kigawa.kivra.routes.validatedText
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.slf4j.LoggerFactory

private val GOAL_ID_SHAPE = Regex("[0-9A-Z]{26}")
private const val GOAL_LIST_LIMIT = 50
private const val SHORT_ID_LENGTH = 12
private const val FORBIDDEN = "forbidden"
private const val INVALID_INPUT = "INVALID_INPUT"

/** The operator log of the autopilot, the same name as the REST routes' (SelfDevGoalRoutes): fixed codes and a short goal id only. */
private val goalOperatorLog = LoggerFactory.getLogger("net.kigawa.kivra.selfdev.autopilot.operator")

/**
 * The Goal dependencies of `/mcp` (ADR 0019 S1). Built only while the autopilot is on, so with it off no Goal tool is registered at all.
 * [aiPrincipal] is the AI principal of the REST routes (`KIVRA_SELFDEV_AI_PRINCIPAL`): that caller is refused the stop, as `requireHuman`
 * refuses it on the REST route. [memory] is the same recorder the REST stop writes to. [submit] is set only when the target repository is
 * configured (the submit tool is then registered when its switch is on).
 */
class McpGoalDeps(
    val operations: SelfDevOperatorRepository,
    val plans: SelfDevPlanRepository,
    val runs: DevelopmentRunRepository,
    val aiPrincipal: String?,
    val memory: SelfDevMemoryRecorder,
    val submit: McpGoalSubmitDeps? = null,
)

/**
 * What `kivra_goal_submit` needs beyond the stop (ADR 0019 S2): the same repository call as `POST /api/selfdev/goals`, the configured
 * target repository, and the per-operator daily cap ([dailyLimit], the UTC day by [clock]).
 */
class McpGoalSubmitDeps(
    val goals: SelfDevGoalRepository,
    val targetRepo: String,
    val dailyLimit: Int,
    val clock: Clock = Clock.systemUTC(),
)

/**
 * The Goal tools of ADR 0019 S1 on `/mcp`: `kivra_goal_list` and `kivra_goal_get` (read), and `kivra_goal_stop` (the operator's stop).
 * `kivra_goal_submit` (S2, the operator's submission) is defined here too. The AI principal has read access only: it is refused the stop and
 * the submission.
 *
 * - The reads need `kivra.runs.read` and return fixed codes, IDs, versions, timestamps and counts. A goal's title, body and the text of its
 *   tasks are never returned (they are untrusted, ADR 0019 section 3).
 * - `kivra_goal_stop` needs `kivra.selfdev.stop`. It calls the same repository function as `POST /api/selfdev/goals/{id}/stop`
 *   ([SelfDevOperatorRepository.stopGoal]). The input is strict: the goal id, the version and one fixed reason code, which is not stored or
 *   logged beyond its code (ADR 0011 U-18). The result says whether a submitted Run is still active: a stop does not stop a running Run.
 *   The stop is not replayable: a repeat after a success is refused by the version check or the state machine.
 */
class McpGoalTools(private val deps: McpGoalDeps) {

    /**
     * The Goal tools for the current settings. The read tools need [readEnabled]; the stop tool needs [stopEnabled]; the submit tool needs
     * [submitEnabled] and the submit dependency ([McpGoalDeps.submit]).
     */
    fun tools(readEnabled: Boolean, stopEnabled: Boolean, submitEnabled: Boolean = false): List<McpTool> =
        (if (readEnabled) listOf(goalList(), goalGet()) else emptyList()) +
            (if (stopEnabled) listOf(goalStop()) else emptyList()) +
            (if (submitEnabled) listOfNotNull(deps.submit?.let { goalSubmit(it) }) else emptyList())

    private fun goalList() = McpTool(
        name = "kivra_goal_list",
        description = "List the self-development goals, newest update first (up to 50). Returns ids, state codes, reason codes, versions, " +
            "timestamps and task counts by state. No title, body or task text.",
        inputSchema = objectSchema(),
        scope = SCOPE_RUNS_READ,
    ) { _, args ->
        args.requireOnly()
        val goals = deps.operations.listGoals(GOAL_LIST_LIMIT).map { it.summaryJson(latestTasks(it.goalId)) }
        ToolOutcome(false, "${goals.size} goal(s)", buildJsonObject { put("goals", JsonArray(goals)) }, "OK")
    }

    private fun goalGet() = McpTool(
        name = "kivra_goal_get",
        description = "Get one goal by goal_id: its state and reason code, its version, its task counts, and for each task of its latest plan " +
            "the ordinal, state, reason code and run id. No title, body or task text.",
        inputSchema = objectSchema(
            "goal_id" to goalIdSchema(),
            required = listOf("goal_id"),
        ),
        scope = SCOPE_RUNS_READ,
    ) { _, args ->
        args.requireOnly("goal_id")
        val id = args.requiredString("goal_id", GOAL_ID_SHAPE)
        val goal = deps.operations.goal(id)
        if (goal == null) {
            refusal("GOAL_NOT_FOUND", "goal not found")
        } else {
            val tasks = latestTasks(id)
            val structured = buildJsonObject {
                goal.summaryJson(tasks).forEach { (key, value) -> put(key, value) }
                put("tasks", JsonArray(tasks.sortedBy { it.ordinal }.map { it.json() }))
            }
            ToolOutcome(false, "goal ${goal.goalId}: ${goal.state.name}", structured, "OK")
        }
    }

    private fun goalStop() = McpTool(
        name = "kivra_goal_stop",
        description = "Stop a self-development goal (operator only). The goal becomes STOPPED and its tasks that were not submitted are skipped. " +
            "A Run already submitted is not stopped (see run_still_active). Give goal_id, the version (from kivra_goal_get) and one fixed reason code.",
        inputSchema = objectSchema(
            "goal_id" to goalIdSchema(),
            "version" to buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
                put("description", "The goal's version, as kivra_goal_get returned it.")
            },
            "reason" to buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { StopRequestReason.entries.forEach { add(JsonPrimitive(it.name)) } }
            },
            required = listOf("goal_id", "version", "reason"),
        ),
        scope = SCOPE_SELFDEV_STOP,
        write = true,
    ) { caller, args -> stop(caller, args) }

    private fun goalSubmit(sub: McpGoalSubmitDeps) = McpTool(
        name = "kivra_goal_submit",
        description = "Submit a self-development goal (operator only). Give a title, an optional body, and an idempotency_key that you generate. " +
            "The same key with the same content returns the same goal (replayed: true). A limited number per UTC day. The title and body are " +
            "not returned.",
        inputSchema = objectSchema(
            "title" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_TASK_TITLE_CODE_POINTS)
            },
            "body" to buildJsonObject {
                put("type", "string")
                put("maxLength", MAX_TASK_BODY_CODE_POINTS)
                put("description", "Optional goal text. Untrusted: it is data for the planner, never instructions to kivra.")
            },
            "idempotency_key" to buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", MAX_IDEMPOTENCY_KEY_CODE_POINTS)
            },
            required = listOf("title", "idempotency_key"),
        ),
        scope = SCOPE_SELFDEV_START,
        write = true,
    ) { caller, args -> submit(sub, caller, args) }

    /**
     * The same rules as `POST /api/selfdev/goals`: the AI principal is refused before the input is read; the title, the key and the body pass
     * the same validators; the repository call is the REST one, with the per-operator daily cap (a replay is answered before the cap).
     */
    private fun submit(sub: McpGoalSubmitDeps, caller: McpCaller, args: JsonObject): ToolOutcome {
        if (caller.principal == deps.aiPrincipal) {
            goalOperatorLog.info("autopilot operator refused: action=SUBMIT via=mcp actor=AI result={}", FORBIDDEN)
            return refusal(FORBIDDEN, FORBIDDEN)
        }
        args.requireOnly("title", "body", "idempotency_key")
        val title = validatedText(args.requiredString("title"), MAX_TASK_TITLE_CODE_POINTS)
        val key = validatedText(args.requiredString("idempotency_key"), MAX_IDEMPOTENCY_KEY_CODE_POINTS)
        val rawBody = args.optionalString("body")
        // A blank body is none; a non-blank body must pass validatedBody (as the REST route, Issue #229).
        val body = rawBody?.let { validatedBody(it) }
        if (title == null || key == null || (rawBody != null && body == null && rawBody.isNotBlank())) {
            return refusal(INVALID_INPUT, "the input is not valid")
        }
        val since = LocalDate.now(sub.clock).atStartOfDay()
        val result = try {
            sub.goals.submit(caller.principal, key, title, body, sub.targetRepo, DailyGoalLimit(sub.dailyLimit, since))
        } catch (_: DailyGoalLimitException) {
            goalOperatorLog.info("autopilot operator refused: action=SUBMIT via=mcp actor={} result=DAILY_LIMIT", operatorTag(caller.principal))
            return refusal("DAILY_LIMIT", "the daily limit of goals is reached")
        }
        return when (result) {
            is SubmitResult.Created -> {
                goalOperatorLog.info(
                    "autopilot operator action: action=SUBMIT via=mcp actor={} goalId={} idempotencyKey=present result=GOAL_SUBMITTED",
                    operatorTag(caller.principal), result.goal.goalId.take(SHORT_ID_LENGTH),
                )
                // The title only (ADR 0016 U-6), as the REST route records it. The body is never recorded.
                deps.memory.goalTitle(result.goal.goalId, title, caller.principal)
                submitted(result.goal, replayed = false)
            }
            is SubmitResult.Replayed -> submitted(result.goal, replayed = true)
            // The existing goal's id is not revealed on a conflict.
            SubmitResult.KeyConflict -> refusal("IDEMPOTENCY_CONFLICT", "idempotency key was already used with a different request")
        }
    }

    private fun submitted(goal: SelfDevGoal, replayed: Boolean): ToolOutcome {
        val structured = buildJsonObject {
            put("goal_id", goal.goalId)
            put("state", goal.state.name)
            put("replayed", replayed)
        }
        val text = if (replayed) "existing goal returned" else "goal submitted"
        return ToolOutcome(false, text, structured, if (replayed) "REPLAYED" else "CREATED")
    }

    private fun stop(caller: McpCaller, args: JsonObject): ToolOutcome {
        // The AI principal is refused before the input is read, as the REST route's requireHuman does.
        if (caller.principal == deps.aiPrincipal) {
            goalOperatorLog.info("autopilot operator refused: action=STOP via=mcp actor=AI result={}", FORBIDDEN)
            return refusal(FORBIDDEN, FORBIDDEN)
        }
        args.requireOnly("goal_id", "version", "reason")
        val id = args.requiredString("goal_id", GOAL_ID_SHAPE)
        val version = args.requiredVersion()
        val reason = args.requiredString("reason").let { raw ->
            StopRequestReason.entries.firstOrNull { it.name == raw }
                ?: throw InvalidParamsException("reason is not one of the fixed codes")
        }
        return when (val r = deps.operations.stopGoal(id, version)) {
            is Transition.Moved -> {
                goalOperatorLog.info(
                    "autopilot operator action: action=STOP via=mcp actor={} goalId={} reason={} result=STOPPED",
                    operatorTag(caller.principal), id.take(SHORT_ID_LENGTH), reason.name,
                )
                deps.memory.goalFact(id, r.row.stateReason)
                val structured = buildJsonObject {
                    put("state", r.row.state.name)
                    put("reason_code", r.row.stateReason?.name)
                    put("version", r.row.version)
                    put("run_still_active", runStillActive(id))
                }
                ToolOutcome(false, "goal stopped", structured, "STOPPED")
            }
            Transition.NotFound -> refusal("GOAL_NOT_FOUND", "goal not found")
            Transition.StaleVersion -> refusal("STALE_VERSION", "the goal changed; reload and try again")
            is Transition.Refused -> refusal(r.reason.name, "the state does not allow this operation")
        }
    }

    /**
     * True while a task of the goal (in any of its plans) is SUBMITTING, or is linked to a Run that has not ended. The stop never changes a
     * Run, so this tells the caller that the Run is still going.
     */
    private fun runStillActive(goalId: String): Boolean = deps.plans.plansOf(goalId).any { plan ->
        deps.plans.tasksOf(plan.planId).any { task ->
            task.state == TaskState.SUBMITTING ||
                task.runId?.let { runId -> deps.runs.get(RunId(runId))?.state?.isTerminal == false } == true
        }
    }

    /** The tasks of the goal's latest plan (highest revision), or none when it has no plan. */
    private fun latestTasks(goalId: String): List<SelfDevPlanTask> =
        deps.plans.plansOf(goalId).maxByOrNull { it.revision }?.let { deps.plans.tasksOf(it.planId) }.orEmpty()

    /** A refusal as a tool result: `isError` and `structuredContent.error.code`, a fixed code and a fixed text. */
    private fun refusal(code: String, text: String): ToolOutcome = ToolOutcome(
        isError = true,
        text = text,
        structured = buildJsonObject { putJsonObject("error") { put("code", code) } },
        code = code,
    )

    private fun goalIdSchema(): JsonObject = buildJsonObject {
        put("type", "string")
        put("pattern", "^[0-9A-Z]{26}\$")
    }
}

/** The goal summary, field by field: no title and no body. Task counts are for the given tasks, every state present (zero when none). */
private fun SelfDevGoal.summaryJson(tasks: List<SelfDevPlanTask>): JsonObject = buildJsonObject {
    put("goal_id", goalId)
    put("state", state.name)
    put("state_reason", stateReason?.name)
    put("version", version)
    put("created_at", createdAt.utc())
    put("updated_at", updatedAt.utc())
    putJsonObject("task_counts") {
        TaskState.entries.forEach { state -> put(state.name, tasks.count { it.state == state }) }
    }
}

/** One task of a goal: its ordinal, state, reason code and run id. Never its title or body. */
private fun SelfDevPlanTask.json(): JsonObject = buildJsonObject {
    put("ordinal", ordinal)
    put("state", state.name)
    put("state_reason", stateReason?.name)
    put("run_id", runId)
}
