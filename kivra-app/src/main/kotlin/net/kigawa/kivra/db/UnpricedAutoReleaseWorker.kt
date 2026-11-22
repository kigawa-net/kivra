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
 * The periodic automatic release of unpriced reservations (ADR 0003 追補 2026-11-15). Each [tick] releases at most [batch] reservations whose
 * cost has been unknown for at least [grace] ([DbBudgetStore.autoReleaseUnpriced]); the rest wait for the next tick. Started only while
 * `KIVRA_BUDGET_UNPRICED_AUTO_RELEASE_ENABLED` is true (Application.kt).
 *
 * A failure never stops the worker: a key that failed is retried on the next tick, and an exception is logged with its class name only. The
 * log carries counts only (no keys, no amounts).
 */
class UnpricedAutoReleaseWorker(
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
            if (r.released > 0 || r.failed > 0) {
                logger.info("budget unpriced auto release: code=AUTO_UPPER_BOUND released={} alreadyReleased={} failed={}", r.released, r.alreadyReleased, r.failed)
            }
        }
    } catch (e: Exception) {
        logger.warn("budget unpriced auto release failed: code=AUTO_RELEASE_FAILED exception={}", e.javaClass.simpleName)
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
        private val logger = LoggerFactory.getLogger(UnpricedAutoReleaseWorker::class.java)

        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(60)

        /** The most reservations released in one tick. */
        const val DEFAULT_BATCH = 50
    }
}
