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
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.Transition
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
 *    finishes a goal whose tasks are all COMPLETED, and resumes a task left in SUBMITTING (the same idempotency key, so no second Run).
 * 3. Submits at most one task per goal (the lowest ordinal whose dependencies are all COMPLETED), and only when every guard of
 *    section 5.2 holds. A guard that holds moves the goal to BLOCKED with its fixed reason code, and the next tick re-checks it.
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
                recordOutcome(task, run.state, run.stateReason)
            } catch (e: Exception) {
                logger.warn("autopilot dispatch: outcome failed, goalId={} ordinal={} exception={}", ref.goalId, ref.ordinal, e.javaClass.simpleName)
            }
        }
    }

    private fun recordOutcome(task: SelfDevPlanTask, state: DevelopmentRunState, stateReason: String?) {
        when (state) {
            DevelopmentRunState.COMPLETED -> {
                if (transitionTask(task, TaskState.COMPLETED, AutopilotReason.PR_MERGED) != null) {
                    control.recordRunOutcome(succeeded = true, maxConsecutiveFailures = config.maxConsecutiveFailures)
                }
            }
            DevelopmentRunState.FAILED -> failTask(task, if (stateReason == RUN_POLICY_REJECTED) AutopilotReason.POLICY_REJECTED else AutopilotReason.RUN_FAILED)
            // UNKNOWN is counted when it is first seen (ADR 0011 section 5.2). It is not counted again when the operator releases it.
            DevelopmentRunState.UNKNOWN -> failTask(task, AutopilotReason.RUN_FAILED)
            DevelopmentRunState.CANCELLED -> {
                if (stateReason == PR_ABANDONED_REASON) {
                    failTask(task, AutopilotReason.PR_ABANDONED)
                } else {
                    // A run cancelled by the operator (a release, or a cancel request) is not a failure and is not counted.
                    transitionTask(task, TaskState.CANCELLED, AutopilotReason.RUN_RELEASED)
                }
            }
            else -> Unit
        }
    }

    /** The task fails, the failure is counted once (only the winning CAS counts), and the rest of the plan is skipped. */
    private fun failTask(task: SelfDevPlanTask, reason: AutopilotReason) {
        if (transitionTask(task, TaskState.FAILED, reason) == null) return
        control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = config.maxConsecutiveFailures)
        var skipped = 0
        for (other in plans.tasksOf(task.planId)) {
            if (other.state != TaskState.PENDING_APPROVAL && other.state != TaskState.QUEUED) continue
            if (transitionTask(other, TaskState.SKIPPED, AutopilotReason.GOAL_STOPPED) != null) skipped++
        }
        logger.info("autopilot dispatch: plan stopped, planId={} code={} skipped={}", task.planId, reason.name, skipped)
    }

    // ---- 2 and 3. Dispatch -----------------------------------------------------------------------------------------------------

    private fun dispatchGoal(goalId: String) {
        var goal = goals.get(goalId) ?: return
        val plan = plans.plansOf(goalId).lastOrNull { it.state == PlanState.PROPOSED } ?: return
        var tasks = approveAuto(plan)

        if (goal.state == GoalState.RUNNING && tasks.isNotEmpty() && tasks.all { it.state == TaskState.COMPLETED }) {
            transitionGoal(goal, GoalState.DONE, AutopilotReason.ALL_TASKS_COMPLETED)
            return
        }

        val submitting = tasks.firstOrNull { it.state == TaskState.SUBMITTING }
        if (submitting != null) {
            // A submission that was interrupted. Resumed with the same key, so it cannot create a second Run. It is not a new
            // submission, so it takes no daily slot, and it waits for the guards like any other submission.
            if (globalBlock() == null) finishSubmission(goal, submitting)
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
            if (task.state != TaskState.PENDING_APPROVAL || !isAutoEligible(task)) continue
            if (transitionTask(task, TaskState.QUEUED, AutopilotReason.AUTO_APPROVED) != null) {
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

    /** Moves a BLOCKED goal back when its reason is auto-clearable and the guards hold (GUARD_CLEARED). Manual blocks wait for the operator. */
    private fun resumeBlocked(goal: SelfDevGoal, tasks: List<SelfDevPlanTask>): Boolean {
        if (goal.stateReason !in AUTO_CLEARABLE_REASONS) return false
        val started = tasks.any { it.state != TaskState.PENDING_APPROVAL && it.state != TaskState.QUEUED }
        val target = if (started) GoalState.RUNNING else GoalState.TASKS_QUEUED
        return transitionGoal(goal, target, AutopilotReason.GUARD_CLEARED) != null
    }

    /**
     * Submits one task. The daily slot is taken first, and given back if no Run is created. The order is: slot, goal to RUNNING,
     * task QUEUED to SUBMITTING (CAS), the Run, task SUBMITTING to SUBMITTED with the run id.
     */
    private fun submitCandidate(goal: SelfDevGoal, candidate: SelfDevPlanTask) {
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
            val started = transitionTask(candidate, TaskState.SUBMITTING, null) ?: return
            slotKept = true
            slotKept = finishSubmission(goal, started)
        } finally {
            if (!slotKept) control.releaseRunSlot()
        }
    }

    /**
     * Creates the Run for a task in SUBMITTING (or links the one that already exists under the same key) and moves the task to
     * SUBMITTED. Returns false when the active-run limit refused the Run: the task is then back in QUEUED and no Run exists.
     */
    private fun finishSubmission(goal: SelfDevGoal, task: SelfDevPlanTask): Boolean {
        val principal = principalFor(goal)
        try {
            return when (val result = runs.submitTask(
                principal, task.idempotencyKey, task.title,
                selfDev = true, maxActiveSelfDev = maxActiveSelfDev, body = task.body,
            )) {
                is TaskSubmitResult.Created, is TaskSubmitResult.Replayed -> {
                    if (transitionTask(task, TaskState.SUBMITTED, null, runId = result.run.runId.value) != null) {
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
            transitionTask(task, TaskState.QUEUED, AutopilotReason.ACTIVE_RUN_LIMIT)
            logger.info("autopilot dispatch: waiting code={} goalId={} ordinal={}", ACTIVE_RUN_LIMIT, goal.goalId, task.ordinal)
            return false
        }
    }

    /**
     * The principal of the Run is the goal's creator, for every task, auto-approved or not (ADR 0011 section 2.6). That the task
     * was approved by the autopilot is recorded by the AUTO_APPROVED reason in the task's state transition, not by a principal.
     */
    private fun principalFor(goal: SelfDevGoal): String = goal.createdBy

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
        task: SelfDevPlanTask,
        to: TaskState,
        reason: AutopilotReason?,
        runId: String? = null,
    ): SelfDevPlanTask? {
        return when (val r = plans.transitionTask(task.planId, task.ordinal, task.version, to, reason, runId)) {
            is Transition.Moved -> r.row
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
