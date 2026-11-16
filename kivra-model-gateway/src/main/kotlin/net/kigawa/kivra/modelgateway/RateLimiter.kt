package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/** Null dimensions are unlimited; configured limits must be positive. One minute of burst capacity. */
data class RateLimit(val requestsPerMinute: Long? = null, val tokensPerMinute: Long? = null) {
    init {
        require(requestsPerMinute == null || requestsPerMinute > 0)
        require(tokensPerMinute == null || tokensPerMinute > 0)
    }
}

enum class RateLimitPriority { USER_INTERACTION, RUNNING_TASK, BATCH }

/** Process-local, atomic provider + principal token buckets. Share one instance across Gateway callers. */
class RateLimiter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private class Bucket(val limit: RateLimit, var updated: Long) {
        var requests = limit.requestsPerMinute?.toDouble() ?: Double.POSITIVE_INFINITY
        var tokens = limit.tokensPerMinute?.toDouble() ?: Double.POSITIVE_INFINITY
        fun refill(now: Long, factor: Double) {
            val minutes = (now - updated).coerceAtLeast(0) / 60_000_000_000.0
            requests = minOf(limit.requestsPerMinute?.toDouble() ?: Double.POSITIVE_INFINITY,
                requests + minutes * (limit.requestsPerMinute ?: 0) * factor)
            tokens = minOf(limit.tokensPerMinute?.toDouble() ?: Double.POSITIVE_INFINITY,
                tokens + minutes * (limit.tokensPerMinute ?: 0) * factor)
            updated = now
        }
        fun waitMs(amount: Long, factor: Double): Long {
            if (limit.tokensPerMinute != null && amount > limit.tokensPerMinute) throw limited("request exceeds token bucket capacity")
            val requestsWait = if (requests >= 1) 0.0 else (1 - requests) / (limit.requestsPerMinute!! * factor)
            val tokensWait = if (tokens >= amount) 0.0 else (amount - tokens) / (limit.tokensPerMinute!! * factor)
            return ceil(maxOf(requestsWait, tokensWait) * 60_000).toLong()
        }
        fun take(amount: Long) { requests -= 1; tokens -= amount }
    }
    private class Ticket(val principal: String, val tokens: Long, val priority: RateLimitPriority)
    private class State {
        val buckets = mutableMapOf<String?, Bucket>()
        val queue = mutableListOf<Ticket>()
        var factor = 1.0
        var blockedUntil = Long.MIN_VALUE
    }
    private val states = ConcurrentHashMap<String, State>()

    /** Cancellation removes the waiter without consuming either bucket. Gateway's total timeout bounds waiting. */
    suspend fun acquire(provider: ProviderEntry, principal: String, tokens: Long,
                        priority: RateLimitPriority = RateLimitPriority.RUNNING_TASK) {
        require(tokens >= 0)
        listOfNotNull(provider.rateLimit, provider.principalRateLimit).forEach {
            if (it.tokensPerMinute != null && tokens > it.tokensPerMinute) throw limited("request exceeds token bucket capacity")
        }
        if (provider.rateLimit == null && provider.principalRateLimit == null && !states.containsKey(provider.id)) return
        val state = states.computeIfAbsent(provider.id) { State() }
        val ticket = Ticket(principal, tokens, priority)
        synchronized(state) { state.queue.add(ticket) }
        try {
            while (true) {
                val wait = synchronized(state) {
                    val now = nanoTime()
                    fun buckets(t: Ticket): List<Bucket> = listOfNotNull(
                        provider.rateLimit?.let { state.buckets.getOrPut(null) { Bucket(it, now) } },
                        provider.principalRateLimit?.let { state.buckets.getOrPut(t.principal) { Bucket(it, now) } },
                    ).onEach { it.refill(now, state.factor) }
                    // Skip principals whose own bucket is exhausted, so they cannot block other principals.
                    val eligible = state.queue.sortedBy { it.priority.ordinal }.firstOrNull { t ->
                        buckets(t).all { it.waitMs(t.tokens, state.factor) == 0L }
                    }
                    val own = buckets(ticket)
                    val cooldown = ceil((state.blockedUntil.toDouble() - now) / 1_000_000).toLong().coerceAtLeast(0)
                    if (eligible === ticket && cooldown == 0L) {
                        own.forEach { it.take(tokens) }
                        state.queue.remove(ticket)
                        state.factor = minOf(1.0, state.factor + 0.05) // additive recovery after successful admission
                        0L
                    } else {
                        maxOf(1L, cooldown, own.maxOfOrNull { it.waitMs(tokens, state.factor) } ?: 1L).coerceAtMost(100L)
                    }
                }
                if (wait == 0L) return
                sleep(wait)
            }
        } finally { synchronized(state) { state.queue.remove(ticket) } }
    }

    /** Multiplicative decrease and an uncapped Retry-After cooldown, including for later invocations. */
    fun rateLimited(providerId: String, retryAfterMs: Long?) {
        val state = states[providerId] ?: return
        synchronized(state) {
            val now = nanoTime()
            state.buckets.values.forEach { it.refill(now, state.factor) }
            state.factor = maxOf(0.1, state.factor / 2)
            val duration = (retryAfterMs ?: 1000L).coerceIn(0, Long.MAX_VALUE / 1_000_000)
            val until = now + duration * 1_000_000
            state.blockedUntil = maxOf(state.blockedUntil, until)
        }
    }

    companion object {
        private fun limited(message: String) = GatewayException(GatewayError(
            ErrorKind.RATE_LIMITED, message, code = "RATE_LIMITED", sendState = SendState.NOT_SENT,
        ))
    }
}
