package net.kigawa.kivra.selfdev

import java.math.BigDecimal
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
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ActiveSelfDevLimitException
import net.kigawa.kivra.db.AutopilotControl
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevTask
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TaskTransition
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/**
 * The periodic loop that submits the autopilot's tasks as Runs (ADR 0011 section 5). One [tick] runs these steps in order, each contained:
 *
 * 1. Records the outcome of every SUBMITTED task whose Run has ended: COMPLETED (PR_MERGED), FAILED (RUN_FAILED, or POLICY_REJECTED when the
 *    Run was refused by the PatchPolicy), CANCELLED (RUN_RELEASED). A failure feeds the circuit breaker; so does a CANCELLED Run that the
 *    automatic release ended (ADR 0009 追補 2026-11-18). The task's move and the count are one
 *    transaction. The Run itself is never changed here.
 *    Then settles every plan root whose planned tasks have all ended (DONE, or STOPPED with TASKS_INCOMPLETE); a catch-up for the settlement.
 * 2. Stops here while the autopilot is paused (nothing is submitted; no state moves).
 * 3. Clears the dispatch blocks whose guard now holds (GUARD_CLEARED), and the circuit block once the operator has reset the breaker.
 * 4. Submits again a SUBMITTING task that has no Run yet (an interrupted submission), with the same key, so no second Run is made.
 * 5. Dispatches each QUEUED task whose dependencies are COMPLETED, after the guards: the circuit breaker, the pending pull request limit,
 *    the monthly budget share, and the daily Run cap. Then SUBMITTING -> SUBMITTED with the Run's id.
 *
 * A dispatch guard that holds moves the task to BLOCKED with its reason; it is not an error. The breaker, the paused switch and the
 * self-development run limit are the same ones the operator uses. Logs carry IDs and fixed codes only (never a title or a body).
 */
class AutopilotDispatcher(
    database: Database,
    private val runs: DevelopmentRunRepository,
    private val config: AutopilotConfig,
    private val maxActiveSelfDev: Int,
    /** True when the monthly budget still has the autopilot's share left (`share` is the percent). Read once per check. */
    private val budgetAllows: (Int) -> Boolean,
    /**
     * True when the task may be submitted from its order's point of view (ADR 0015 section 5.4): its order and the ancestors are ACTIVE. A
     * task with no order is allowed. Checked before any guard, so a held task is not blocked, only left QUEUED until its order runs again.
     */
    private val orderGate: (taskId: String) -> Boolean = { true },
    /** The clock of the waiting summary's 10-minute repeat. */
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    /**
     * Why the QUEUED tasks of one pass were left QUEUED without a guard (ADR 0011 addendum 2026-11-18). These skips write nothing, so this
     * summary is the only trace of them. Counts and fixed words only: never a task id list, a title, a body or a reason text.
     */
    data class WaitingSummary(
        /** A dependency has not COMPLETED yet and may still (it is QUEUED, SUBMITTING, SUBMITTED, BLOCKED, ...). */
        val dependencyPending: Int = 0,
        /** A dependency ended without COMPLETED (FAILED, CANCELLED, SKIPPED or STOPPED): it waits for an operator (requeue or skip). */
        val dependencyFailed: Int = 0,
        /** The task's order, or an ancestor order, is not ACTIVE. */
        val orderHeld: Int = 0,
        /** Another planned task of the same plan is SUBMITTING or SUBMITTED (one Run per plan at a time). */
        val siblingInFlight: Int = 0,
        /** The submission was refused for another reason (a stale version, a dependency across projects). */
        val refused: Int = 0,
    ) {
        val isEmpty: Boolean get() = this == WaitingSummary()
    }

    private var lastSummary = WaitingSummary()
    private var lastSummaryAt: java.time.Instant? = null

    private val operations = SelfDevOperatorRepository(database)
    private val control = SelfDevAutopilotControlRepository(database)

    /** One pass. Blocking (database and Run repository calls only). */
    fun tick() {
        step("run outcomes") { recordRunOutcomes() }
        // Bookkeeping, not dispatch: it runs while paused too, so a plan whose tasks all ended is never left waiting.
        step("finished plans") { operations.settleFinishedPlans(BATCH) }
        if (control.read().paused) return
        step("dispatch blocks") { clearDispatchBlocks() }
        step("interrupted submissions") { resubmitInterrupted() }
        step("dispatch") { dispatchQueued() }
    }

    /** Starts the loop in [scope]. It stops when the scope is cancelled (application stop). A failed pass is logged, and the loop goes on. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info(
            "autopilot dispatcher started: intervalSeconds={} batch={} maxPendingPrs={} maxRunsPerDay={}",
            DISPATCH_INTERVAL.seconds, BATCH, config.maxPendingPrs, config.maxRunsPerDay,
        )
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("autopilot dispatch pass failed: exception={}", e.javaClass.simpleName)
            }
            delay(DISPATCH_INTERVAL.toMillis())
        }
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn("autopilot dispatch step failed: step={} exception={}", name, e.javaClass.simpleName)
        }
    }

    private fun recordRunOutcomes() {
        for (submitted in operations.submittedRuns(BATCH)) {
            val run = runs.get(RunId(submitted.runId)) ?: continue
            // `succeeded` feeds the circuit breaker; null means the outcome does not count either way.
            val (to, reason, succeeded) = when (run.state) {
                DevelopmentRunState.COMPLETED -> Triple(TaskState.COMPLETED, AutopilotReason.PR_MERGED, true)
                DevelopmentRunState.FAILED -> Triple(TaskState.FAILED, failureReasonOf(run.stateReason), false)
                // An automatic release (ADR 0009 追補 2026-11-18) ends a Run the agent could not finish (its lease ran out, or it asked for a
                // human). It counts as a failure, so a host fault that repeats opens the breaker instead of cancelling the queue task by task.
                // An operator's release or a cancel still does not count.
                DevelopmentRunState.CANCELLED -> Triple(
                    TaskState.CANCELLED,
                    AutopilotReason.RUN_RELEASED,
                    if (run.stateReason in net.kigawa.kivra.db.SELF_DEV_AUTO_RELEASE_REASONS.values) false else null,
                )
                else -> continue
            }
            val task = operations.task(submitted.taskId) ?: continue
            // The move and the breaker's count are one transaction, so a crash keeps neither or both.
            if (operations.settleRunOutcome(task.taskId, task.version, to, reason, succeeded, config.maxConsecutiveFailures) !is TaskTransition.Moved) continue
            logger.info("autopilot task outcome: taskId={} runId={} state={} reason={}", task.taskId, submitted.runId, to.name, reason.name)
        }
    }

    /** The task's reason for a failed Run: a change the PatchPolicy refused is POLICY_REJECTED (it counts in the promotion readout), any other is RUN_FAILED. */
    private fun failureReasonOf(runStateReason: String?): AutopilotReason =
        if (runStateReason != null && runStateReason in POLICY_REJECTED_RUN_REASONS) AutopilotReason.POLICY_REJECTED else AutopilotReason.RUN_FAILED

    private fun clearDispatchBlocks() {
        val ctl = control.read()
        for (task in operations.blockedTasks(BATCH)) {
            val blocked = task.stateReason ?: continue
            val resume: AutopilotReason = when (blocked) {
                AutopilotReason.CIRCUIT_OPEN -> if (!ctl.breakerOpen) AutopilotReason.OPERATOR_RESUMED else continue
                AutopilotReason.PENDING_PR_LIMIT -> if (pendingPrRuns() < config.maxPendingPrs.toLong()) AutopilotReason.GUARD_CLEARED else continue
                AutopilotReason.DAILY_RUN_CAP -> if (runsToday(ctl) < config.maxRunsPerDay) AutopilotReason.GUARD_CLEARED else continue
                AutopilotReason.BUDGET_HOLD -> if (budgetAllows(config.budgetSharePercent)) AutopilotReason.GUARD_CLEARED else continue
                // Planning blocks and scope blocks are not cleared here: an operator decides (ADR 0011 sections 2.8 and 5.4).
                else -> continue
            }
            if (operations.moveTask(task.taskId, task.version, TaskState.QUEUED, resume) is TaskTransition.Moved) {
                logger.info("autopilot dispatch unblocked: taskId={} reason={}", task.taskId, resume.name)
            }
        }
    }

    private fun resubmitInterrupted() {
        for (taskId in operations.interruptedSubmissionIds(BATCH)) {
            val task = operations.task(taskId) ?: continue
            if (task.state == TaskState.SUBMITTING && orderGate(taskId)) submit(task)
        }
    }

    private fun dispatchQueued() {
        val ctl = control.read()
        var waiting = WaitingSummary()
        try {
            dispatchQueuedCounting(ctl) { waiting = it(waiting) }
        } finally {
            reportWaiting(waiting)
        }
    }

    private fun dispatchQueuedCounting(ctl: AutopilotControl, count: ((WaitingSummary) -> WaitingSummary) -> Unit) {
        for (taskId in operations.queuedTaskIds(BATCH)) {
            val task = operations.task(taskId) ?: continue
            if (task.state != TaskState.QUEUED) continue
            when (dependencyState(task)) {
                DependencyState.COMPLETED -> Unit
                DependencyState.PENDING -> { count { it.copy(dependencyPending = it.dependencyPending + 1) }; continue }
                DependencyState.FAILED -> { count { it.copy(dependencyFailed = it.dependencyFailed + 1) }; continue }
            }
            if (!orderGate(taskId)) {
                count { it.copy(orderHeld = it.orderHeld + 1) }
                continue
            }
            if (operations.siblingInFlight(task)) {
                count { it.copy(siblingInFlight = it.siblingInFlight + 1) }
                continue
            }
            val guard = when {
                ctl.breakerOpen -> AutopilotReason.CIRCUIT_OPEN
                pendingPrRuns() >= config.maxPendingPrs.toLong() -> AutopilotReason.PENDING_PR_LIMIT
                !budgetAllows(config.budgetSharePercent) -> AutopilotReason.BUDGET_HOLD
                else -> null
            }
            if (guard != null) {
                block(task, guard)
                continue
            }
            if (!control.tryReserveRun(config.maxRunsPerDay)) {
                block(task, AutopilotReason.DAILY_RUN_CAP)
                continue
            }
            val begun = operations.beginSubmission(task.taskId, task.version)
            if (begun !is TaskTransition.Moved) {
                // Refused by the dependency or sibling rule, or moved by someone else: the slot is not used.
                control.releaseRunSlot()
                count { it.copy(refused = it.refused + 1) }
                continue
            }
            submit(begun.row)
        }
    }

    /**
     * Creates (or finds, by the task's key) the Run of a SUBMITTING task, then records it on the task. A self-development limit refusal
     * puts the task back to QUEUED and is not a failure. Any other failure leaves the task SUBMITTING: the next pass submits it again with
     * the same key, which returns the same Run.
     */
    private fun submit(row: SelfDevTask) {
        val result = try {
            runs.submitTask(
                principal = row.approvedBy ?: row.createdBy,
                idempotencyKey = row.idempotencyKey,
                title = row.title,
                selfDev = true,
                maxActiveSelfDev = maxActiveSelfDev,
                body = row.body,
                // The Run takes its task's project (ADR 0022); a task with no project gives a Run with none.
                projectId = row.projectId,
            )
        } catch (_: ActiveSelfDevLimitException) {
            control.releaseRunSlot()
            operations.moveTask(row.taskId, row.version, TaskState.QUEUED, AutopilotReason.ACTIVE_RUN_LIMIT)
            logger.info("autopilot dispatch waits: taskId={} reason={}", row.taskId, AutopilotReason.ACTIVE_RUN_LIMIT.name)
            return
        } catch (e: Exception) {
            logger.warn("autopilot submission failed, will retry: taskId={} exception={}", row.taskId, e.javaClass.simpleName)
            return
        }
        if (result is TaskSubmitResult.ConflictingTitle) {
            // The key was used for another title. Nothing is attached; the task stays SUBMITTING for the operator to see.
            logger.warn("autopilot submission conflicts: taskId={}", row.taskId)
            return
        }
        val runId = result.run.runId.value
        when (operations.attachRun(row.taskId, row.version, runId)) {
            is TaskTransition.Moved -> logger.info("autopilot task submitted: taskId={} runId={}", row.taskId, runId)
            else -> logger.warn("autopilot attach refused: taskId={} runId={}", row.taskId, runId)
        }
    }

    private fun block(task: SelfDevTask, reason: AutopilotReason) {
        if (operations.moveTask(task.taskId, task.version, TaskState.BLOCKED, reason) is TaskTransition.Moved) {
            logger.info("autopilot dispatch blocked: taskId={} reason={}", task.taskId, reason.name)
        }
    }

    private enum class DependencyState { COMPLETED, PENDING, FAILED }

    /**
     * COMPLETED when every dependency is COMPLETED (ADR 0011 section 5.1; a task with no dependency is ready). FAILED when one ended without
     * COMPLETED (it is not resubmitted on its own), else PENDING. A missing dependency is PENDING, as before (it is never submitted).
     */
    private fun dependencyState(task: SelfDevTask): DependencyState {
        if (task.dependencies.isEmpty()) return DependencyState.COMPLETED
        val states = operations.statesOf(task.dependencies)
        if (task.dependencies.all { states[it] == TaskState.COMPLETED }) return DependencyState.COMPLETED
        return if (task.dependencies.any { states[it] in ENDED_NOT_COMPLETED }) DependencyState.FAILED else DependencyState.PENDING
    }

    /**
     * Logs the waiting summary of a pass when it differs from the last one logged, or [WAITING_REPEAT] after it (so a lasting wait is seen
     * again). Nothing is logged while there is nothing to report and nothing was reported before.
     */
    private fun reportWaiting(summary: WaitingSummary) {
        val now = clock.instant()
        val due = lastSummaryAt?.let { !now.isBefore(it.plus(WAITING_REPEAT)) } ?: true
        val changed = summary != lastSummary
        if (!changed && (summary.isEmpty || !due)) return
        logger.info(
            "autopilot dispatch waiting: dependencyPending={} dependencyFailed={} orderHeld={} siblingInFlight={} refused={}",
            summary.dependencyPending, summary.dependencyFailed, summary.orderHeld, summary.siblingInFlight, summary.refused,
        )
        lastSummary = summary
        lastSummaryAt = now
    }

    /** The self-development Runs that wait for a pull request to be merged or closed (ADR 0011 section 5.2). */
    private fun pendingPrRuns(): Long =
        runs.countSelfDevInState(DevelopmentRunState.PULL_REQUEST) + runs.countSelfDevInState(DevelopmentRunState.WAITING_APPROVAL)

    private fun runsToday(ctl: AutopilotControl): Int =
        if (ctl.dayUtc == LocalDate.now(ZoneOffset.UTC)) ctl.runsToday else 0

    private companion object {
        val logger = LoggerFactory.getLogger(AutopilotDispatcher::class.java)
        const val BATCH = 20
        val DISPATCH_INTERVAL: Duration = Duration.ofSeconds(10)
        /** How often a waiting summary that did not change is logged again. */
        val WAITING_REPEAT: Duration = Duration.ofMinutes(10)
        /** A dependency in one of these ended without COMPLETED: the dependent waits for an operator. */
        val ENDED_NOT_COMPLETED = setOf(TaskState.FAILED, TaskState.CANCELLED, TaskState.SKIPPED, TaskState.STOPPED)
        /** The Run state reasons of a refused change: the Run's own (SelfDevRunner) and the developer's (`DEVELOPER_` + its refusal). */
        val POLICY_REJECTED_RUN_REASONS = setOf("POLICY_REJECTED", "DEVELOPER_POLICY_REJECTED")
    }
}

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
