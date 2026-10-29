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
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ActiveSelfDevLimitException
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.PR_ABANDONED_REASON
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevDispatchQueries
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.SelfDevTaskRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

private const val RUN_POLICY_REJECTED = "POLICY_REJECTED"
private val DISPATCH_STATES = setOf(TaskState.TASK_SUBMITTED, TaskState.PENDING_APPROVAL, TaskState.QUEUED, TaskState.BLOCKED)
private val TERMINAL_TASK_STATES = setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.SKIPPED, TaskState.STOPPED)
private val INTERVAL = Duration.ofSeconds(10)

class AutopilotDispatcher(
    database: Database,
    private val runs: DevelopmentRunRepository,
    private val config: AutopilotConfig,
    private val maxActiveSelfDev: Int,
    private val budgetAllows: (sharePercent: Int) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val orderGate: (taskId: String) -> Boolean = { true },
    private val memory: SelfDevMemoryRecorder = SelfDevMemoryRecorder.OFF,
) {
    private val tasks = SelfDevTaskRepository(database, clock)
    private val control = SelfDevAutopilotControlRepository(database, clock)
    private val queries = SelfDevDispatchQueries(database)

    init {
        require(maxActiveSelfDev >= 1) { "the active self-development run limit must be at least 1" }
    }

    fun tick() {
        syncSubmittedOutcomes()
        if (control.read().paused) {
            logger.info("autopilot dispatch: skipped code={}", AutopilotReason.OPERATOR_PAUSED.name)
            return
        }
        for (taskId in queries.taskIdsInStates(DISPATCH_STATES)) {
            try {
                dispatchTask(taskId)
            } catch (e: Exception) {
                logger.warn("autopilot dispatch: task failed, taskId={} exception={}", taskId, e.javaClass.simpleName)
            }
        }
    }

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
        for (taskId in queries.taskIdsInStates(setOf(TaskState.SUBMITTED))) {
            try {
                val task = tasks.get(taskId) ?: continue
                if (task.state != TaskState.SUBMITTED) continue
                val runId = task.runId ?: continue
                val run = runs.get(RunId(runId)) ?: continue
                if (recordOutcome(taskId, task, run.state, run.stateReason)) {
                    memory.runFact(task, runId, run.stateReason ?: run.state.name)
                }
            } catch (e: Exception) {
                logger.warn("autopilot dispatch: outcome failed, taskId={} exception={}", taskId, e.javaClass.simpleName)
            }
        }
    }

    private fun recordOutcome(taskId: String, task: SelfDevTask, state: DevelopmentRunState, stateReason: String?): Boolean = when (state) {
        DevelopmentRunState.COMPLETED -> {
            val moved = transitionTask(taskId, task, TaskState.COMPLETED, AutopilotReason.PR_MERGED) != null
            if (moved) control.recordRunOutcome(succeeded = true, maxConsecutiveFailures = config.maxConsecutiveFailures)
            moved
        }
        DevelopmentRunState.FAILED ->
            failTask(taskId, task, if (stateReason == RUN_POLICY_REJECTED) AutopilotReason.POLICY_REJECTED else AutopilotReason.RUN_FAILED)
        DevelopmentRunState.UNKNOWN -> failTask(taskId, task, AutopilotReason.RUN_FAILED)
        DevelopmentRunState.CANCELLED ->
            if (stateReason == PR_ABANDONED_REASON) {
                failTask(taskId, task, AutopilotReason.PR_ABANDONED)
            } else {
                transitionTask(taskId, task, TaskState.CANCELLED, AutopilotReason.RUN_RELEASED) != null
            }
        else -> false
    }

    private fun failTask(taskId: String, task: SelfDevTask, reason: AutopilotReason): Boolean {
        if (transitionTask(taskId, task, TaskState.FAILED, reason) == null) return false
        control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = config.maxConsecutiveFailures)
        var skipped = 0
        for (depId in task.dependencies) {
            val dep = tasks.get(depId) ?: continue
            if (dep.state != TaskState.PENDING_APPROVAL && dep.state != TaskState.QUEUED) continue
            if (transitionTask(depId, dep, TaskState.SKIPPED, AutopilotReason.TASK_STOPPED) != null) skipped++
        }
        logger.info("autopilot dispatch: task stopped, taskId={} code={} skipped={}", taskId, reason.name, skipped)
        return true
    }

    // ---- 2 and 3. Dispatch -----------------------------------------------------------------------------------------------------

    private fun dispatchTask(taskId: String) {
        if (!orderGate(taskId)) {
            logger.info("autopilot dispatch: refused code={} taskId={}", ORDER_NOT_ACTIVE, taskId)
            return
        }
        val task = tasks.get(taskId) ?: return

        // Task is terminal: nothing to do
        if (task.state in TERMINAL_TASK_STATES) return

        // Handle SUBMITTING task (interrupted submission)
        if (task.state == TaskState.SUBMITTING) {
            if (globalBlock() == null) {
                if (tasks.get(taskId)?.state == TaskState.SUBMITTING) {
                    finishSubmission(task)
                }
            }
            return
        }

        // Handle BLOCKED task
        if (task.state == TaskState.BLOCKED) {
            if (globalBlock() == null) {
                resumeBlocked(task)
            }
            return
        }

        // Handle QUEUED task
        if (task.state == TaskState.QUEUED) {
            if (activeRunsLimitReached()) {
                logger.info("autopilot dispatch: waiting code={} taskId={}", ACTIVE_RUN_LIMIT, taskId)
                return
            }
            val reason = globalBlock()
            if (reason != null) {
                transitionTask(taskId, task, TaskState.BLOCKED, reason)
                logger.info("autopilot dispatch: blocked code={} taskId={}", reason.name, taskId)
                return
            }
            submitCandidate(task)
        }
    }

    private fun resumeBlocked(task: SelfDevTask): Boolean {
        val reason = when (task.stateReason) {
            in AUTO_CLEARABLE_REASONS -> AutopilotReason.GUARD_CLEARED
            AutopilotReason.CIRCUIT_OPEN -> AutopilotReason.OPERATOR_RESUMED
            else -> return false
        }
        return transitionTask(task.taskId, task, TaskState.QUEUED, reason) != null
    }

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

    private fun submitCandidate(task: SelfDevTask) {
        if (!orderGate(task.taskId)) {
            logger.info("autopilot dispatch: refused code={} taskId={}", ORDER_NOT_ACTIVE, task.taskId)
            return
        }
        if (!control.tryReserveRun(config.maxRunsPerDay)) {
            logger.info("autopilot dispatch: blocked code={} taskId={}", AutopilotReason.DAILY_RUN_CAP.name, task.taskId)
            transitionTask(task.taskId, task, TaskState.BLOCKED, AutopilotReason.DAILY_RUN_CAP)
            return
        }
        var slotKept = false
        try {
            if (transitionTask(task.taskId, task, TaskState.SUBMITTING, null) == null) return
            slotKept = finishSubmission(task)
        } finally {
            if (!slotKept) control.releaseRunSlot()
        }
    }

    private fun finishSubmission(task: SelfDevTask): Boolean {
        val principal = task.approvedBy ?: task.createdBy
        try {
            return when (val result = runs.submitTask(
                principal, task.idempotencyKey, task.title,
                selfDev = true, maxActiveSelfDev = maxActiveSelfDev, body = task.body,
            )) {
                is TaskSubmitResult.Created, is TaskSubmitResult.Replayed -> {
                    if (transitionTask(task.taskId, task, TaskState.SUBMITTED, null) != null) {
                        logger.info("autopilot dispatch: submitted taskId={} runId={}", task.taskId, result.run)
                    }
                    true
                }
                is TaskSubmitResult.ConflictingTitle -> {
                    logger.warn("autopilot dispatch: code={} taskId={}", IDEMPOTENCY_CONFLICT, task.taskId)
                    true
                }
            }
        } catch (e: ActiveSelfDevLimitException) {
            transitionTask(task.taskId, task, TaskState.QUEUED, AutopilotReason.ACTIVE_RUN_LIMIT)
            logger.info("autopilot dispatch: waiting code={} taskId={}", ACTIVE_RUN_LIMIT, task.taskId)
            return false
        }
    }

    // ---- transitions -----------------------------------------------------------------------------------------------------------

    private fun transitionTask(
        taskId: String,
        task: SelfDevTask,
        to: TaskState,
        reason: AutopilotReason?,
        runId: String? = null,
    ): SelfDevTask? {
        val r = tasks.transition(taskId, task.version, to, reason)
        return when (r) {
            is net.kigawa.kivra.db.TaskTransition.Moved -> {
                if (runId != null) {
                    // Update run_id separately
                }
                logger.info(
                    "autopilot task: taskId={} from={} to={} reason={}",
                    taskId, task.state.name, to.name, reason?.name ?: "none",
                )
                memory.taskFact(r.row, reason)
                r.row
            }
            is net.kigawa.kivra.db.TaskTransition.StaleVersion -> {
                logger.info("autopilot task not changed: taskId={} code=STALE_VERSION", taskId)
                null
            }
            is net.kigawa.kivra.db.TaskTransition.Refused -> {
                logger.warn("autopilot task refused: taskId={} code={}", taskId, r.reason.name)
                null
            }
            is net.kigawa.kivra.db.TaskTransition.NotFound -> null
        }
    }

    private fun isAutoEligible(task: SelfDevTask): Boolean =
        config.approval == AutopilotApproval.AUTO

    private companion object {
        private val logger = LoggerFactory.getLogger(AutopilotDispatcher::class.java)
        private const val ORDER_NOT_ACTIVE = "ORDER_NOT_ACTIVE"
        private const val ACTIVE_RUN_LIMIT = "ACTIVE_RUN_LIMIT"
        private const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
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

