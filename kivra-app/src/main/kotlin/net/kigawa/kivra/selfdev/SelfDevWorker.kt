package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
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
 * Logging: an advance that changed the run (Advanced) is logged at INFO, and a failure at WARN. A result that does not change
 * the run (Waiting, Skipped, Failed) is logged when it differs from the previous one for that run, or at most once per
 * [REPEAT_LOG_INTERVAL] while it repeats. A run is identified by its ULID. Logs carry no task text, no run content, no exception
 * message (only the exception's class name) and no provider detail. Cancellation is never swallowed.
 */
class SelfDevWorker(
    private val activeRunIds: suspend () -> List<RunId>,
    private val advance: suspend (RunId) -> AdvanceResult,
    private val interval: Duration,
    private val clock: Clock = Clock.systemUTC(),
    /** Called once at the start of each tick, before any [advance] of that tick (the pull request read budget resets here, issue #351). */
    private val beginTick: () -> Unit = {},
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
    }

    /** The last result logged for each run, and when. Touched only by [tick], which the single loop calls one at a time. */
    private val lastLogged = HashMap<RunId, Logged>()

    private class Logged(val result: AdvanceResult, val at: Instant)

    /** One pass over the active runs. Returns the number of runs that were given a call. */
    suspend fun tick(): Int {
        beginTick()
        val runIds = try {
            activeRunIds()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("self-development worker: listing failed, reason={} exception={}", LIST_FAILED, e.javaClass.simpleName)
            return 0
        }
        lastLogged.keys.retainAll(runIds.toSet())
        for (runId in runIds) {
            val result = try {
                advance(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "self-development worker: advance failed, runId={} reason={} exception={}",
                    runId.value, ADVANCE_FAILED, e.javaClass.simpleName,
                )
                continue
            }
            logResult(runId, result)
        }
        return runIds.size
    }

    private fun logResult(runId: RunId, result: AdvanceResult) {
        val now = clock.instant()
        val previous = lastLogged[runId]
        val repeated = previous != null && previous.result == result && Duration.between(previous.at, now) < REPEAT_LOG_INTERVAL
        if (repeated && result !is AdvanceResult.Advanced) return
        lastLogged[runId] = Logged(result, now)
        when (result) {
            is AdvanceResult.Advanced -> logger.info(
                "self-development run advanced: runId={} from={} to={} reason={}",
                runId.value, result.from.name, result.to.name, result.reasonCode ?: "none",
            )
            is AdvanceResult.Waiting -> if (result.reasonCode == null) {
                logger.info("self-development run waiting: runId={} reason={}", runId.value, result.reason.name)
            } else {
                logger.info("self-development run waiting: runId={} reason={} code={}", runId.value, result.reason.name, result.reasonCode)
            }
            is AdvanceResult.Skipped -> logger.info("self-development run skipped: runId={} reason={}", runId.value, result.reason.name)
            is AdvanceResult.Failed -> logger.warn("self-development run step failed: runId={} code={}", runId.value, result.code.name)
        }
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

        /** A repeated, unchanged result for the same run is logged at most this often. */
        val REPEAT_LOG_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
