package net.kigawa.kivra.coding

import java.time.Clock

/**
 * Fixed one-second windows per attempt for the monitor event route (ADR 0012 section 2.1, U-10): at most [limitPerSecond] requests for
 * one attempt in any one second. The request beyond that is refused (429).
 *
 * The state is in this process only. Each instance applies the limit to its own requests, so with several instances the total is a
 * multiple of the limit. That is accepted: the monitor is best effort and the limit is there to stop a flood, not to be exact.
 * Memory is bounded by [maxTrackedAttempts]: when the map is full, the windows that have ended are dropped.
 */
class MonitorRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val limitPerSecond: Int = DEFAULT_LIMIT_PER_SECOND,
    private val maxTrackedAttempts: Int = DEFAULT_MAX_TRACKED,
) {
    init {
        require(limitPerSecond > 0 && maxTrackedAttempts > 0) { "limit and capacity must be positive" }
    }

    private class Window(var startMillis: Long, var count: Int)

    private val windows = HashMap<String, Window>()

    /** True when the request may proceed. Counts the request whether it proceeds or not. */
    @Synchronized
    fun allow(attemptId: String): Boolean {
        val now = clock.millis()
        if (windows.size >= maxTrackedAttempts && !windows.containsKey(attemptId)) {
            windows.entries.removeIf { now - it.value.startMillis >= WINDOW_MILLIS }
        }
        val window = windows[attemptId]
        if (window == null || now - window.startMillis >= WINDOW_MILLIS) {
            if (windows.size < maxTrackedAttempts || window != null) {
                windows[attemptId] = Window(now, 1)
                return true
            }
            // The map is full of windows that are still open: refuse rather than grow without bound.
            return false
        }
        window.count++
        return window.count <= limitPerSecond
    }

    companion object {
        const val DEFAULT_LIMIT_PER_SECOND = 5
        const val DEFAULT_MAX_TRACKED = 10_000
        private const val WINDOW_MILLIS = 1_000L
    }
}
