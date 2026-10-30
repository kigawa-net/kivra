package net.kigawa.kivra.agent

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.JournalEntry
import net.kigawa.kivra.db.OperationJournalRepository
import org.slf4j.LoggerFactory

/** Result of one [AutoRecoveryWorker] pass. Counts only. */
data class AutoRecoveryPass(
    val runsRetried: Int,
    val opsReconciled: Int,
    val opsFailed: Int,
)

/**
 * Periodically recovers runs and operations that are stuck in recoverable states.
 *
 * Recovery actions:
 * 1. **RETRY_WAITING runs**: Moves eligible runs back to DEVELOPING so the Agent Manager can retry them.
 * 2. **UNKNOWN operations**: Reconciles operations whose outcome is unknown by looking up the external system.
 * 3. **RECONCILIATION_REQUIRED operations**: Attempts to resolve operations that need reconciliation.
 *
 * The worker is safe to run multiple instances: each run and operation is processed once per tick
 * due to the conditional updates in the repositories.
 *
 * Logs carry counts and fixed codes only. Never a result, a path, a title or an exception message.
 */
class AutoRecoveryWorker(
    private val runs: DevelopmentRunRepository,
    private val journal: OperationJournalRepository,
    private val leases: LeaseStore,
    private val config: AutoRecoveryConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One pass. Never throws (except cancellation): a failing step is logged with a fixed code and the other step still runs. */
    fun tick(): AutoRecoveryPass {
        val runsRetried = retryWaitingRuns()
        val (opsReconciled, opsFailed) = reconcileOperations()
        val pass = AutoRecoveryPass(runsRetried, opsReconciled, opsFailed)
        if (pass.runsRetried + pass.opsReconciled + pass.opsFailed > 0) {
            logger.info(
                "auto-recovery pass: runsRetried={} opsReconciled={} opsFailed={}",
                pass.runsRetried, pass.opsReconciled, pass.opsFailed,
            )
        }
        return pass
    }

    /**
     * Finds runs in RETRY_WAITING that have been waiting long enough and moves them back to DEVELOPING.
     * The Agent Manager will pick them up on the next run cycle.
     */
    private fun retryWaitingRuns(): Int {
        return try {
            val cutoff = LocalDateTime.ofInstant(clock.instant().minus(config.retryWaitDelay), ZoneOffset.UTC)
            val waitingRuns = runs.getRetryWaitingRuns(config.maxRetryRunsPerTick, cutoff)
            var retried = 0
            for (run in waitingRuns) {
                if (retryRun(run)) {
                    retried++
                }
            }
            retried
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("auto-recovery failed: step=RETRY_RUNS exception={}", e.javaClass.simpleName)
            0
        }
    }

    /**
     * Moves a single RETRY_WAITING run back to DEVELOPING.
     * Returns true if the run was successfully moved.
     */
    private fun retryRun(run: DevelopmentRun): Boolean {
        // Skip if the run is on hold or cancel is requested
        if (run.holdReason != null || run.cancelRequested) {
            return false
        }
        // Skip if the run already has an active lease (another worker is processing it)
        if (run.leaseOwner != null && run.leaseExpiresAt != null && run.leaseExpiresAt.isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            return false
        }
        return try {
            // Acquire the lease for this run
            val lease = leases.acquire(run.runId, "auto-recovery", config.interval, Duration.ofSeconds(5))
            if (lease == null) {
                // Another worker acquired the lease
                return false
            }
            try {
                val currentRun = runs.get(run.runId) ?: return false
                runs.transition(
                    lease = lease,
                    expectedVersion = currentRun.version,
                    to = DevelopmentRunState.DEVELOPING,
                    actor = "auto-recovery",
                    reason = "AUTO_RETRY",
                )
                true
            } catch (e: Exception) {
                // Version conflict or other error - another process may have already moved this run
                logger.debug("auto-recovery retry failed: runId={} exception={}", run.runId, e.javaClass.simpleName)
                false
            } finally {
                runCatching { leases.release(lease) }
            }
        } catch (e: Exception) {
            logger.debug("auto-recovery lease acquire failed: runId={} exception={}", run.runId, e.javaClass.simpleName)
            false
        }
    }

    /**
     * Reconciles operations that are in UNKNOWN or RECONCILIATION_REQUIRED state.
     * Returns a pair of (reconciled count, failed count).
     */
    private fun reconcileOperations(): Pair<Int, Int> {
        return try {
            val cutoff = LocalDateTime.ofInstant(clock.instant().minus(config.unknownOpDelay), ZoneOffset.UTC)
            val unknownOps = journal.getUnknownOperations(config.maxReconcileOpsPerTick, cutoff)
            val reconcileCutoff = LocalDateTime.ofInstant(clock.instant().minus(config.reconcileDelay), ZoneOffset.UTC)
            val reconcileOps = journal.getReconciliationRequiredOperations(config.maxReconcileOpsPerTick, reconcileCutoff)

            var reconciled = 0
            var failed = 0

            for (op in unknownOps) {
                if (reconcileOperation(op)) {
                    reconciled++
                }
            }

            for (op in reconcileOps) {
                if (reconcileOperation(op)) {
                    reconciled++
                } else {
                    failed++
                }
            }

            reconciled to failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("auto-recovery failed: step=RECONCILE_OPS exception={}", e.javaClass.simpleName)
            0 to 0
        }
    }

    /**
     * Attempts to reconcile a single operation.
     * Returns true if the operation was successfully reconciled.
     */
    private fun reconcileOperation(op: JournalEntry): Boolean {
        return try {
            when (op.status) {
                OperationStatus.UNKNOWN -> {
                    // Move UNKNOWN to RECONCILIATION_REQUIRED to trigger reconciliation
                    journal.transitionToReconciliationRequired(op.runId, op.opId, op.version)
                }
                OperationStatus.RECONCILIATION_REQUIRED -> {
                    // For RECONCILIATION_REQUIRED, we mark it as FAILED with a fixed reason
                    // The operator can manually review and decide whether to retry
                    journal.transitionToFailed(op.runId, op.opId, op.version, "AUTO_RECONCILE_TIMEOUT")
                }
                else -> false
            }
        } catch (e: Exception) {
            logger.debug("auto-recovery reconcile failed: opId={} exception={}", op.opId, e.javaClass.simpleName)
            false
        }
    }

    /** Starts the loop in [scope]: a pass, then one every [config.interval]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info(
            "auto-recovery worker started: intervalSeconds={} maxRetryRuns={} maxReconcileOps={}",
            config.interval.seconds, config.maxRetryRunsPerTick, config.maxReconcileOpsPerTick,
        )
        while (isActive) {
            tick()
            delay(config.interval.toMillis())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AutoRecoveryWorker::class.java)
    }
}
