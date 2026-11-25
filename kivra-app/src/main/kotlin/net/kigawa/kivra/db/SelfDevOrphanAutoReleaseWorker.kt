package net.kigawa.kivra.db

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The periodic automatic release of UNKNOWN self-development runs (ADR 0009 追補 2026-11-16, 2026-11-18). Each [tick] releases at most
 * [batch] runs that have been UNKNOWN with CODING_ORPHANED or CODING_NEEDS_HUMAN, without any GitHub write, for at least [grace]
 * ([DevelopmentRunRepository.autoReleaseUnknownRuns]); the rest wait for the next tick. Started only while
 * `KIVRA_SELFDEV_ORPHAN_AUTO_RELEASE_ENABLED` is true (Application.kt). The log line keeps its name and code from the first version and
 * counts both reasons.
 *
 * A failure never stops the worker: a run that failed is retried on the next tick, and an exception is logged with its class name only.
 * The log carries counts and fixed codes only (no run ids, no task text, no reasons).
 */
class SelfDevOrphanAutoReleaseWorker(
    private val release: (grace: Duration, batch: Int) -> AutoReleaseReport,
    private val grace: Duration,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val batch: Int = DEFAULT_BATCH,
) {
    init {
        require(!grace.isNegative && !grace.isZero) { "grace must be positive" }
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(batch > 0) { "batch must be positive" }
    }

    /** One pass. Returns its report, or null when it failed (logged). */
    fun tick(): AutoReleaseReport? = try {
        release(grace, batch).also { r ->
            if (r.failed > 0) {
                logger.warn(
                    "selfdev orphan auto release: code=AUTO_RELEASED_ORPHAN released={} alreadyReleased={} failed={} reasons={}",
                    r.released, r.alreadyReleased, r.failed, r.reasons,
                )
            } else if (r.released > 0) {
                logger.info(
                    "selfdev orphan auto release: code=AUTO_RELEASED_ORPHAN released={} alreadyReleased={} failed={} reasons={}",
                    r.released, r.alreadyReleased, r.failed, r.reasons,
                )
            }
        }
    } catch (e: Exception) {
        logger.warn("selfdev orphan auto release failed: code=AUTO_RELEASE_FAILED exception={}", e.javaClass.simpleName)
        null
    }

    /** Runs [tick] every [interval] in [scope] until the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            }
            delay(interval.toMillis())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SelfDevOrphanAutoReleaseWorker::class.java)

        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(60)

        /** The most runs released in one tick. */
        const val DEFAULT_BATCH = 20
    }
}
