package net.kigawa.kivra.modelgateway

import java.time.Clock
import java.time.Duration
import java.time.Instant

interface HealthTracker {
    fun isAvailable(providerId: String): Boolean
    fun recordSuccess(providerId: String)
    fun recordFailure(providerId: String, kind: ErrorKind)
}

object AlwaysHealthy : HealthTracker {
    override fun isAvailable(providerId: String) = true
    override fun recordSuccess(providerId: String) {}
    override fun recordFailure(providerId: String, kind: ErrorKind) {}
}

/** Minimal per-provider breaker: opens after consecutive TRANSIENT/TIMEOUT/AUTH failures, half-opens after a cooldown. */
class CircuitHealthTracker(
    private val threshold: Int = 5,
    private val cooldown: Duration = Duration.ofSeconds(30),
    private val clock: Clock = Clock.systemUTC(),
) : HealthTracker {
    private class State(var failures: Int = 0, var openedAt: Instant? = null)
    private val states = HashMap<String, State>()

    @Synchronized
    override fun isAvailable(providerId: String): Boolean {
        val s = states[providerId] ?: return true
        val opened = s.openedAt ?: return true
        return clock.instant() >= opened.plus(cooldown)
    }

    @Synchronized
    override fun recordSuccess(providerId: String) { states.remove(providerId) }

    @Synchronized
    override fun recordFailure(providerId: String, kind: ErrorKind) {
        if (kind != ErrorKind.TRANSIENT && kind != ErrorKind.TIMEOUT && kind != ErrorKind.AUTH) return
        val s = states.getOrPut(providerId) { State() }
        s.failures++
        if (s.failures >= threshold) s.openedAt = clock.instant()
    }
}
