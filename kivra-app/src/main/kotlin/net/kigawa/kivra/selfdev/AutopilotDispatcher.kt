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
 *    Run was refused by the PatchPolicy), CANCELLED (RUN_RELEASED). A failure feeds the circuit breaker. The task's move and the count are one
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
) {
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
                DevelopmentRunState.CANCELLED -> Triple(TaskState.CANCELLED, AutopilotReason.RUN_RELEASED, null)
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
        for (taskId in operations.queuedTaskIds(BATCH)) {
            val task = operations.task(taskId) ?: continue
            if (task.state != TaskState.QUEUED || !dependenciesCompleted(task)) continue
            if (!orderGate(taskId)) continue
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

    /** True when every dependency is COMPLETED (ADR 0011 section 5.1). A task with no dependency is ready. */
    private fun dependenciesCompleted(task: SelfDevTask): Boolean {
        if (task.dependencies.isEmpty()) return true
        val states = operations.statesOf(task.dependencies)
        return task.dependencies.all { states[it] == TaskState.COMPLETED }
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
