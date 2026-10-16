package net.kigawa.kivra.selfdev

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.core.RunId
import org.slf4j.LoggerFactory

/**
 * Periodically moves the active self-development runs forward (ADR 0008 part 7). One [tick] makes one [advance] call per active run,
 * in order. A failure is contained to its run: it is logged with a fixed reason code and the next run, and the next tick, still run.
 * Exclusion between workers is the runner's lease, not this loop.
 *
 * Logs carry no task text, no run content and no exception message. Cancellation is never swallowed.
 */
class SelfDevWorker(
    private val activeRunIds: suspend () -> List<RunId>,
    private val advance: suspend (RunId) -> AdvanceResult,
    private val interval: Duration,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
    }

    /** One pass over the active runs. Returns the number of runs that were given a call. */
    suspend fun tick(): Int {
        val runIds = try {
            activeRunIds()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logger.warn("self-development worker: listing failed, reason={}", LIST_FAILED)
            return 0
        }
        for (runId in runIds) {
            try {
                advance(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                logger.warn("self-development worker: advance failed, reason={}", ADVANCE_FAILED)
            }
        }
        return runIds.size
    }

    /** Starts the loop in [scope]. It stops when the scope is cancelled (application stop). */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            tick()
            delay(interval.toMillis())
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SelfDevWorker::class.java)
        const val LIST_FAILED = "LIST_FAILED"
        const val ADVANCE_FAILED = "ADVANCE_FAILED"
    }
}
