package net.kigawa.kivra.selfdev

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.AUTO_CLEARABLE_REASONS
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.PlanState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ActiveSelfDevLimitException
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.PR_ABANDONED_REASON
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevDispatchQueries
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevPlan
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmissionStart
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/** The Run's failure reason that the runner writes for a rejected patch (see SelfDevRunner). */
private const val RUN_POLICY_REJECTED = "POLICY_REJECTED"

/**
 * The dispatcher of the self-development autopilot (ADR 0011 section 5). Built and started only when [AutopilotSettings] is enabled.
 *
 * One [tick] does, in order:
 * 1. Reads the outcome of every SUBMITTED task from its Run (the Run is the source of truth). A COMPLETED Run completes the task and
 *    resets the failure count. A failed Run fails the task, counts one failure, and skips the rest of its plan. A run released by the
 *    operator cancels the task and counts nothing.
 * 2. Stops, unless the whole autopilot is paused. Then, for each goal that is queued, running or blocked, it approves auto tasks,
 *    ends a goal whose tasks have all ended (DONE when all are COMPLETED, otherwise STOPPED with TASKS_INCOMPLETE; ADR 0011 section 2.8b),
 *    and resumes a task left in SUBMITTING (the same idempotency key, so no second Run).
 * 3. Submits at most one task per goal (the lowest ordinal whose dependencies are all COMPLETED), and only when every guard of
 *    section 5.2 holds. A guard that holds moves the goal to BLOCKED with its fixed reason code, and the next tick re-checks it.
 *    Before the task moves to SUBMITTING, the goal is read again under its row lock, and a goal that is not TASKS_QUEUED or RUNNING
 *    (stopped or paused in the meantime) gets no Run (GOAL_NOT_DISPATCHABLE).
 *
 * Exclusion needs no lease (section 2.7): the submission is a CAS on the task row (QUEUED to SUBMITTING, by version) and the
 * idempotency key is unique. Two replicas can run this loop; at most one of them moves a task.
 *
 * Logging: fixed codes, identifiers and counts only. Never a goal title or body, a task title or body, a path, or an exception message
 * (the exception's class name only).
 */
class AutopilotDispatcher(
    database: Database,
    private val runs: DevelopmentRunRepository,
    private val config: AutopilotConfig,
    private val maxActiveSelfDev: Int,
    /** True when the monthly budget still has the autopilot's share left (see [autopilotBudgetAllows]). Called only from [tick]. */
    private val budgetAllows: (sharePercent: Int) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * True when the goal may get a Run from its order's point of view (ADR 0015 section 5.4). The default allows every goal, which is the
     * flow without orders: the goal has no order, so nothing changes. Called before a goal's tasks are submitted.
     */
    private val orderGate: (goalId: String) -> Boolean = { true },
    /**
     * The shadow record of the self-development memory (ADR 0016 P1). Written after a transition has won, never read: no decision here
     * depends on it. Off by default.
     */
    private val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
) {
    private val goals = SelfDevGoalRepository(database, clock)
    private val plans = SelfDevPlanRepository(database, clock)
    private val control = SelfDevAutopilotControlRepository(database, clock)
    private val queries = SelfDevDispatchQueries(database)

    init {
        require(maxActiveSelfDev >= 1) { "the active self-development run limit must be at least 1" }
    }

    /** One pass. Blocking (database calls only). A failure is contained to its goal, and the next goal and the next tick still run. */
    fun tick() {
        syncSubmittedOutcomes()
        if (control.read().paused) {
            logger.info("autopilot dispatch: skipped code={}", AutopilotReason.OPERATOR_PAUSED.name)
            return
        }
        for (goalId in queries.goalIdsInStates(DISPATCH_STATES)) {
            try {
                dispatchGoal(goalId)
            } catch (e: Exception) {
                logger.warn("autopilot dispatch: goal failed, goalId={} exception={}", goalId, e.javaClass.simpleName)
            }
        }
    }

    /** Runs [tick] every [INTERVAL] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("autopilot dispatch: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(INTERVAL.toMillis())
        }
    }

    // ---- 1. Run outcomes -------------------------------------------------------------------------------------------------------

    private fun syncSubmittedOutcomes() {
        for (ref in queries.taskRefsInStates(setOf(TaskState.SUBMITTED))) {
            try {
                val task = plans.task(ref.planId, ref.ordinal) ?: continue
                if (task.state != TaskState.SUBMITTED) continue
                val runId = task.runId ?: continue
                val run = runs.get(RunId(runId)) ?: continue
                // The Run's fact is written only when this tick moved the task (a lost race writes nothing).
                if (recordOutcome(ref.goalId, task, run.state, run.stateReason)) {
                    memory.runFact(ref.goalId, task, runId, run.stateReason ?: run.state.name)
                }
            } catch (e: Exception) {
                logger.warn("autopilot dispatch: outcome failed, goalId={} ordinal={} exception={}", ref.goalId, ref.ordinal, e.javaClass.simpleName)
            }
        }
    }

    /** Returns true when the task moved (the outcome was recorded by this call). */
    private fun recordOutcome(goalId: String, task: SelfDevPlanTask, state: DevelopmentRunState, stateReason: String?): Boolean = when (state) {
        DevelopmentRunState.COMPLETED -> {
            val moved = transitionTask(goalId, task, TaskState.COMPLETED, AutopilotReason.PR_MERGED) != null
            if (moved) control.recordRunOutcome(succeeded = true, maxConsecutiveFailures = config.maxConsecutiveFailures)
            moved
        }
        DevelopmentRunState.FAILED ->
            failTask(goalId, task, if (stateReason == RUN_POLICY_REJECTED) AutopilotReason.POLICY_REJECTED else AutopilotReason.RUN_FAILED)
        // UNKNOWN is counted when it is first seen (ADR 0011 section 5.2). It is not counted again when the operator releases it.
        DevelopmentRunState.UNKNOWN -> failTask(goalId, task, AutopilotReason.RUN_FAILED)
        DevelopmentRunState.CANCELLED ->
            if (stateReason == PR_ABANDONED_REASON) {
                failTask(goalId, task, AutopilotReason.PR_ABANDONED)
            } else {
                // A run cancelled by the operator (a release, or a cancel request) is not a failure and is not counted.
                transitionTask(goalId, task, TaskState.CANCELLED, AutopilotReason.RUN_RELEASED) != null
            }
        else -> false
    }

    /**
     * The task fails, the failure is counted once (only the winning CAS counts), and the rest of the plan is skipped. Returns true when
     * the task moved.
     */
    private fun failTask(goalId: String, task: SelfDevPlanTask, reason: AutopilotReason): Boolean {
        if (transitionTask(goalId, task, TaskState.FAILED, reason) == null) return false
        control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = config.maxConsecutiveFailures)
        var skipped = 0
        for (other in plans.tasksOf(task.planId)) {
            if (other.state != TaskState.PENDING_APPROVAL && other.state != TaskState.QUEUED) continue
            if (transitionTask(goalId, other, TaskState.SKIPPED, AutopilotReason.TASK_STOPPED) != null) skipped++
        }
        logger.info("autopilot dispatch: plan stopped, planId={} code={} skipped={}", task.planId, reason.name, skipped)
        return true
    }

    // ---- 2 and 3. Dispatch -----------------------------------------------------------------------------------------------------

    private fun dispatchGoal(goalId: String) {
        if (!orderGate(goalId)) {
            logger.info("autopilot dispatch: refused code={} goalId={}", ORDER_NOT_ACTIVE, goalId)
            return
        }
        var goal = goals.get(goalId) ?: return
        val plan = plans.plansOf(goalId).lastOrNull { it.state == PlanState.PROPOSED } ?: return
        var tasks = approveAuto(plan)

        // Every task has ended (COMPLETED, FAILED or SKIPPED): the goal ends. A goal whose tasks all completed is DONE (RUNNING only).
        // Otherwise a failed or skipped task has stopped it, and it is STOPPED with a fixed reason, so the operator sees it as finished.
        if (tasks.isNotEmpty() && tasks.all { it.state in TERMINAL_TASK_STATES }) {
            val allCompleted = tasks.all { it.state == TaskState.COMPLETED }
            when {
                allCompleted && goal.state == GoalState.RUNNING ->
                    transitionGoal(goal, GoalState.DONE, AutopilotReason.ALL_TASKS_COMPLETED)
                !allCompleted -> transitionGoal(goal, GoalState.STOPPED, AutopilotReason.TASKS_INCOMPLETE)
            }
            return
        }

        val submitting = tasks.firstOrNull { it.state == TaskState.SUBMITTING }
        if (submitting != null) {
            // A submission that was interrupted. Resumed with the same key, so it cannot create a second Run. It is not a new
            // submission, so it takes no daily slot, and it waits for the guards like any other submission. A goal that was stopped
            // or paused since is not resumed: the Run is not created for it.
            if (globalBlock() == null) {
                if (plans.isGoalDispatchable(goalId)) {
                    finishSubmission(goal, submitting)
                } else {
                    logger.info("autopilot dispatch: refused code={} goalId={}", GOAL_NOT_DISPATCHABLE, goalId)
                }
            }
            return
        }

        val candidate = nextCandidate(tasks)
        if (candidate == null) {
            if (goal.state == GoalState.BLOCKED && globalBlock() == null) resumeBlocked(goal, tasks)
            return
        }

        // The concurrency limit (ADR 0011 section 5.2) leaves the goal's state alone: it is a short wait, not a block.
        if (activeRunsLimitReached()) {
            logger.info("autopilot dispatch: waiting code={} goalId={} ordinal={}", ACTIVE_RUN_LIMIT, goal.goalId, candidate.ordinal)
            return
        }

        val reason = globalBlock()
        if (reason != null) {
            if (goal.state == GoalState.TASKS_QUEUED || goal.state == GoalState.RUNNING) transitionGoal(goal, GoalState.BLOCKED, reason)
            logger.info("autopilot dispatch: blocked code={} goalId={} ordinal={}", reason.name, goal.goalId, candidate.ordinal)
            return
        }

        if (goal.state == GoalState.BLOCKED) {
            if (!resumeBlocked(goal, tasks)) return
            goal = goals.get(goalId) ?: return
            tasks = plans.tasksOf(plan.planId)
        }
        submitCandidate(goal, candidate)
    }

    /** Auto-approves the eligible tasks of [plan] (`approval = auto` only). Returns the plan's tasks after the approvals. */
    private fun approveAuto(plan: SelfDevPlan): List<SelfDevPlanTask> {
        val tasks = plans.tasksOf(plan.planId)
        if (config.approval != AutopilotApproval.AUTO) return tasks
        for (task in tasks) {
            // A task that the scope re-check sent back (AUTO_SCOPE_EXCEEDED) waits for the operator: its planned paths still look eligible.
            if (task.state != TaskState.PENDING_APPROVAL || task.stateReason == AutopilotReason.AUTO_SCOPE_EXCEEDED || !isAutoEligible(task)) {
                continue
            }
            if (transitionTask(plan.goalId, task, TaskState.QUEUED, AutopilotReason.AUTO_APPROVED) != null) {
                logger.info(
                    "autopilot dispatch: auto-approved ordinal={} planId={} approval={} code={}",
                    task.ordinal, plan.planId, AutopilotApproval.AUTO.name, AutopilotReason.AUTO_APPROVED.name,
                )
            }
        }
        return plans.tasksOf(plan.planId)
    }

    /** The lowest-ordinal QUEUED task whose dependencies are all COMPLETED. None while another task of the plan is in flight. */
    private fun nextCandidate(tasks: List<SelfDevPlanTask>): SelfDevPlanTask? {
        if (tasks.any { it.state == TaskState.SUBMITTING || it.state == TaskState.SUBMITTED }) return null
        val byOrdinal = tasks.associateBy { it.ordinal }
        return tasks
            .filter { it.state == TaskState.QUEUED && it.dependsOn.all { dep -> byOrdinal[dep]?.state == TaskState.COMPLETED } }
            .minByOrNull { it.ordinal }
    }

    /**
     * The guards of ADR 0011 section 5.2 that are about the whole autopilot, in a fixed order. Null when none of them holds. The
     * pause is checked by [tick] and the active-run limit by the caller, so both leave the goal's state alone.
     */
    private fun globalBlock(): AutopilotReason? {
        val c = control.read()
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        return when {
            c.breakerOpen -> AutopilotReason.CIRCUIT_OPEN
            runs.countSelfDevInState(DevelopmentRunState.PULL_REQUEST) >= config.maxPendingPrs.toLong() -> AutopilotReason.PENDING_PR_LIMIT
            c.dayUtc == today && c.runsToday >= config.maxRunsPerDay -> AutopilotReason.DAILY_RUN_CAP
            !budgetAllows(config.budgetSharePercent) -> AutopilotReason.BUDGET_HOLD
            else -> null
        }
    }

    private fun activeRunsLimitReached(): Boolean = runs.activeSelfDevRunIds(maxActiveSelfDev).size >= maxActiveSelfDev

    /**
     * Moves a BLOCKED goal back when its reason clears. An auto-clearable reason clears by itself (GUARD_CLEARED). CIRCUIT_OPEN is
     * cleared by the operator's reset (ADR 0011 section 5.4): the reset is the only thing that closes the breaker, so this is reached only
     * with the breaker closed (the caller checks [globalBlock] first), and the move records OPERATOR_RESUMED. A scope block is cleared by
     * the operator's re-approval of its task. The move is a CAS on the goal's version, so two dispatchers resume it once. Every other
     * reason (planning blocks, and the goal's own state) stays put.
     */
    private fun resumeBlocked(goal: SelfDevGoal, tasks: List<SelfDevPlanTask>): Boolean {
        val reason = when (goal.stateReason) {
            in AUTO_CLEARABLE_REASONS -> AutopilotReason.GUARD_CLEARED
            AutopilotReason.CIRCUIT_OPEN -> AutopilotReason.OPERATOR_RESUMED
            // A scope block (ADR 0011 section 6.2) clears when the operator approves the task again: that approval is queued with an
            // operator on it. Until then the goal stays blocked.
            AutopilotReason.AUTO_SCOPE_EXCEEDED ->
                if (tasks.any { it.state == TaskState.QUEUED && it.approvedBy != null }) AutopilotReason.OPERATOR_RESUMED else return false
            else -> return false
        }
        val started = tasks.any { it.state != TaskState.PENDING_APPROVAL && it.state != TaskState.QUEUED }
        val target = if (started) GoalState.RUNNING else GoalState.TASKS_QUEUED
        return transitionGoal(goal, target, reason) != null
    }

    /**
     * Submits one task. The daily slot is taken first, and given back if no Run is created. The order is: slot, goal to RUNNING,
     * task QUEUED to SUBMITTING (CAS), the Run, task SUBMITTING to SUBMITTED with the run id.
     */
    private fun submitCandidate(goal: SelfDevGoal, candidate: SelfDevPlanTask) {
        // Read again right before the Run is created: a stop may have been approved since the goal was read (ADR 0015 section 5.4).
        if (!orderGate(goal.goalId)) {
            logger.info("autopilot dispatch: refused code={} goalId={} ordinal={}", ORDER_NOT_ACTIVE, goal.goalId, candidate.ordinal)
            return
        }
        if (!control.tryReserveRun(config.maxRunsPerDay)) {
            logger.info("autopilot dispatch: blocked code={} goalId={} ordinal={}", AutopilotReason.DAILY_RUN_CAP.name, goal.goalId, candidate.ordinal)
            if (goal.state == GoalState.TASKS_QUEUED || goal.state == GoalState.RUNNING) {
                transitionGoal(goal, GoalState.BLOCKED, AutopilotReason.DAILY_RUN_CAP)
            }
            return
        }
        var slotKept = false
        try {
            if (goal.state == GoalState.TASKS_QUEUED && transitionGoal(goal, GoalState.RUNNING, null) == null) return
            // The point of no return: the goal is checked under its lock in the same transaction as the task's move to SUBMITTING.
            when (val start = plans.startSubmission(goal.goalId, candidate.planId, candidate.ordinal, candidate.version)) {
                is SubmissionStart.Moved -> slotKept = finishSubmission(goal, start.task)
                SubmissionStart.GoalNotDispatchable ->
                    logger.info("autopilot dispatch: refused code={} goalId={} ordinal={}", GOAL_NOT_DISPATCHABLE, goal.goalId, candidate.ordinal)
                is SubmissionStart.Rejected -> logRejectedSubmission(candidate, start.result)
            }
        } finally {
            if (!slotKept) control.releaseRunSlot()
        }
    }

    /**
     * Creates the Run for a task in SUBMITTING (or links the one that already exists under the same key) and moves the task to
     * SUBMITTED. Returns false when the active-run limit refused the Run: the task is then back in QUEUED and no Run exists.
     */
    private fun finishSubmission(goal: SelfDevGoal, task: SelfDevPlanTask): Boolean {
        val principal = principalFor(goal, task)
        try {
            return when (val result = runs.submitTask(
                principal, task.idempotencyKey, task.title,
                selfDev = true, maxActiveSelfDev = maxActiveSelfDev, body = task.body,
            )) {
                is TaskSubmitResult.Created, is TaskSubmitResult.Replayed -> {
                    if (transitionTask(goal.goalId, task, TaskState.SUBMITTED, null, runId = result.run.runId.value) != null) {
                        logger.info("autopilot dispatch: submitted goalId={} ordinal={} runId={}", goal.goalId, task.ordinal, result.run.runId.value)
                    }
                    true
                }
                is TaskSubmitResult.ConflictingTitle -> {
                    // The same key already names another Run. The task stays SUBMITTING and nothing is linked; an operator must look.
                    logger.warn("autopilot dispatch: code={} goalId={} ordinal={}", IDEMPOTENCY_CONFLICT, goal.goalId, task.ordinal)
                    true
                }
            }
        } catch (e: ActiveSelfDevLimitException) {
            transitionTask(goal.goalId, task, TaskState.QUEUED, AutopilotReason.ACTIVE_RUN_LIMIT)
            logger.info("autopilot dispatch: waiting code={} goalId={} ordinal={}", ACTIVE_RUN_LIMIT, goal.goalId, task.ordinal)
            return false
        }
    }

    /**
     * The principal of the Run is the operator who approved the task, or else the operator who approved the goal's Plan (`approved_by`,
     * V19). A task that the autopilot approved (AUTO_APPROVED, no operator) runs as the goal's creator. The autopilot is not a principal
     * of its own (ADR 0011 section 2.6): that the task was auto-approved is recorded by the AUTO_APPROVED reason in the state transition.
     */
    private fun principalFor(goal: SelfDevGoal, task: SelfDevPlanTask): String = task.approvedBy ?: goal.approvedBy ?: goal.createdBy

    private fun logRejectedSubmission(task: SelfDevPlanTask, result: Transition<SelfDevPlanTask>) {
        val code = when (result) {
            is Transition.Refused -> result.reason.name
            is Transition.StaleVersion -> "STALE_VERSION"
            else -> "NOT_FOUND"
        }
        logger.info("autopilot task not changed: planId={} ordinal={} code={}", task.planId, task.ordinal, code)
    }

    /** `approval = auto`, the task is not denylisted, and it is low risk (ADR 0011 section 6.1, stage 2). */
    private fun isAutoEligible(task: SelfDevPlanTask): Boolean =
        config.approval == AutopilotApproval.AUTO && !config.requiresApproval(task.requiresApproval) && isLowRisk(task)

    // ---- transitions (each logs its fixed code; a change that did not win is not an error) ------------------------------

    private fun transitionGoal(goal: SelfDevGoal, to: GoalState, reason: AutopilotReason?): SelfDevGoal? {
        return when (val r = goals.transition(goal.goalId, goal.version, to, reason)) {
            is Transition.Moved -> {
                logger.info(
                    "autopilot goal: goalId={} from={} to={} reason={}",
                    goal.goalId, goal.state.name, to.name, reason?.name ?: "none",
                )
                memory.goalFact(goal.goalId, reason)
                r.row
            }
            is Transition.StaleVersion -> {
                logger.info("autopilot goal not changed: goalId={} code=STALE_VERSION", goal.goalId)
                null
            }
            is Transition.Refused -> {
                logger.warn("autopilot goal refused: goalId={} code={}", goal.goalId, r.reason.name)
                null
            }
            is Transition.NotFound -> null
        }
    }

    private fun transitionTask(
        goalId: String,
        task: SelfDevPlanTask,
        to: TaskState,
        reason: AutopilotReason?,
        runId: String? = null,
    ): SelfDevPlanTask? {
        return when (val r = plans.transitionTask(task.planId, task.ordinal, task.version, to, reason, runId)) {
            is Transition.Moved -> {
                memory.taskFact({ goalId }, r.row, reason)
                r.row
            }
            is Transition.StaleVersion -> {
                logger.info("autopilot task not changed: planId={} ordinal={} code=STALE_VERSION", task.planId, task.ordinal)
                null
            }
            is Transition.Refused -> {
                logger.info("autopilot task refused: planId={} ordinal={} code={}", task.planId, task.ordinal, r.reason.name)
                null
            }
            is Transition.NotFound -> null
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(AutopilotDispatcher::class.java)

        /** The goal states that the dispatcher works on. PLAN_PROPOSED waits for the operator; PAUSED and the terminal states are left alone. */
        val DISPATCH_STATES = setOf(GoalState.TASKS_QUEUED, GoalState.RUNNING, GoalState.BLOCKED)

        /** How often the loop runs (ADR 0011 section 5, default 30 seconds). */
        val INTERVAL: Duration = Duration.ofSeconds(30)

        const val ACTIVE_RUN_LIMIT = "ACTIVE_RUN_LIMIT"
        const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
        const val GOAL_NOT_DISPATCHABLE = "GOAL_NOT_DISPATCHABLE"
        const val ORDER_NOT_ACTIVE = "ORDER_NOT_ACTIVE"

        /**
         * The task states that count as ended for the goal (ADR 0011 section 2.8b). FAILED and CANCELLED can be requeued by the operator
         * (section 2.8), but the goal is ended once every task has ended, and a stopped goal is never dispatched again.
         */
        val TERMINAL_TASK_STATES = setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.SKIPPED)

        /** Stage 2 of ADR 0011 section 6.1: docs, README.md, or the test sources of a kivra module; size S; at most 3 paths. */
        private val LOW_RISK_TEST_PATH = Regex("^kivra-[^/]+/src/test/.+$")
        private val LOW_RISK_DOC_PATH = Regex("^docs/.+$")
        private const val LOW_RISK_MAX_PATHS = 3

        /** The stage-2 rule on the planner's own estimate. The actual diff is re-checked in a later step (ADR section 6.2). */
        fun isLowRisk(task: SelfDevPlanTask): Boolean =
            task.sizeEstimate == "S" &&
                task.targetPaths.isNotEmpty() &&
                task.targetPaths.size <= LOW_RISK_MAX_PATHS &&
                task.targetPaths.all { path ->
                    !path.contains("..") && (path == "README.md" || LOW_RISK_DOC_PATH.matches(path) || LOW_RISK_TEST_PATH.matches(path))
                }
    }
}

/**
 * The monthly budget check of ADR 0011 section 5.3, simplified as the PR body records: the dispatch goes on while the month's remaining
 * budget (limit minus committed minus reserved) is at least the autopilot's share (limit times [sharePercent] percent). The
 * autopilot's own spend is not separated, because the ledger does not carry the Run for each attempt.
 */
internal fun autopilotBudgetAllows(
    committed: BigDecimal,
    reserved: BigDecimal,
    monthlyLimit: BigDecimal,
    sharePercent: Int,
): Boolean {
    val remaining = monthlyLimit - committed - reserved
    val share = monthlyLimit.multiply(BigDecimal(sharePercent)).divide(BigDecimal(100))
    return remaining >= share
}
