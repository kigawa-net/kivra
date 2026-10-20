package net.kigawa.kivra.selfdev.memory

import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kigawa.kivra.db.MemoryRetentionResult
import org.slf4j.LoggerFactory

/**
 * The daily-style retention step of the self-development memory (ADR 0016 section 9). Each [tick] runs the repository's bounded step
 * until a step removes nothing, or until [MAX_STEPS] steps have run in this tick (the next tick continues the rest).
 *
 * Started only when the memory switch is on (Application.kt). A failure is logged with the exception's class name and the next tick runs.
 */
class SelfDevMemoryRetentionWorker(
    private val purge: () -> MemoryRetentionResult,
    private val interval: Duration,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
    }

    /** One pass. Returns the number of rows the pass touched. */
    fun tick(): Int {
        var touched = 0
        try {
            for (step in 1..MAX_STEPS) {
                val result = purge()
                val n = result.tombstoned + result.tombstonesPurged + result.factsPurged + result.eventsPurged
                touched += n
                if (n == 0) break
            }
        } catch (e: Exception) {
            logger.warn("selfdev memory retention failed: exception={}", e.javaClass.simpleName)
        }
        return touched
    }

    /** Runs [tick] every [interval] in [scope]. It stops when the scope is cancelled. */
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
        private val logger = LoggerFactory.getLogger(SelfDevMemoryRetentionWorker::class.java)
        private const val MAX_STEPS = 100

        /** How often the step runs. The retention periods are days, so an hour is fine. */
        val DEFAULT_INTERVAL: Duration = Duration.ofHours(1)
    }
}
