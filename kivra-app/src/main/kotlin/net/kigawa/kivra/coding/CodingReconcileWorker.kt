package net.kigawa.kivra.coding

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
import net.kigawa.kivra.db.ExpireResult
import org.slf4j.LoggerFactory

/** What one pass of [CodingReconcileWorker] did. Counts only. */
data class CodingWorkerPass(val queuedExpired: Int, val orphaned: Int, val resultsPurged: Int)

/**
 * The periodic work of the coding runtime (ADR 0010 addendum 6, Issue #231). Started only when the agent API is on. One [tick] does two
 * things, each contained so that a failure in one does not stop the other:
 *
 * 1. Settles the overdue attempts (QUEUED past the queue time becomes EXPIRED, a lease that ran out becomes ORPHANED) and books their
 *    reserves, through [CodingRuntimeService.expireOverdue]. It does not put a Run on hold: that is the runner's job, under the Run's
 *    lease, on its next advance. So a Run is never moved here, and the worker needs no lease.
 * 2. Deletes the stored change sets older than [retention] that nothing can read any more, at most [purgeLimit] rows per tick
 *    ([CodingResultPurge]). The attempt and usage rows stay.
 *
 * Running more than one instance is safe: each attempt moves once (the CAS in the repository), and each reserve is booked once under
 * its key `coding:<attemptId>:<generation>`, so a second pass books nothing new.
 *
 * Logs carry counts and fixed codes only. Never a result, a path, a title or an exception message (only its class name).
 */
class CodingReconcileWorker(
    private val service: CodingRuntimeService,
    private val purge: CodingResultPurge,
    private val interval: Duration,
    private val retention: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val purgeLimit: Int = DEFAULT_PURGE_LIMIT,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(!retention.isNegative && !retention.isZero) { "retention must be positive" }
        require(purgeLimit > 0) { "purgeLimit must be positive" }
    }

    /** One pass. Never throws (except cancellation): a failing step is logged with a fixed code and the other step still runs. */
    fun tick(): CodingWorkerPass {
        val settled = settle()
        val purged = purgeOld()
        val pass = CodingWorkerPass(settled.expiredQueued.size, settled.orphaned.size, purged)
        if (pass.queuedExpired + pass.orphaned + pass.resultsPurged > 0) {
            logger.info(
                "coding reconcile pass: queuedExpired={} orphaned={} resultsPurged={}",
                pass.queuedExpired, pass.orphaned, pass.resultsPurged,
            )
        }
        return pass
    }

    private fun settle(): ExpireResult = try {
        service.expireOverdue()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("coding reconcile failed: step=SETTLE exception={}", e.javaClass.simpleName)
        ExpireResult(emptyList(), emptyList())
    }

    private fun purgeOld(): Int = try {
        purge.purgeBefore(cutoff(), purgeLimit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("coding reconcile failed: step=PURGE exception={}", e.javaClass.simpleName)
        0
    }

    private fun cutoff(): LocalDateTime = LocalDateTime.ofInstant(clock.instant().minus(retention), ZoneOffset.UTC)

    /** Starts the loop in [scope]: a pass, then one every [interval]. It stops when the scope is cancelled (application stop). */
    fun start(scope: CoroutineScope): Job = scope.launch {
        logger.info("coding reconcile worker started: intervalSeconds={} retentionDays={}", interval.seconds, retention.toDays())
        while (isActive) {
            tick()
            delay(interval.toMillis())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CodingReconcileWorker::class.java)

        /** Rows one pass may delete. A backlog is cleared over several passes, so one pass stays short. */
        const val DEFAULT_PURGE_LIMIT = 200
    }
}
