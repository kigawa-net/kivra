package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {
    @Test fun rpmIsSharedAcrossPrincipalsButNotProviders() = runTest {
        val limiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 })
        val p = provider("p").copy(rateLimit = RateLimit(requestsPerMinute = 1))
        limiter.acquire(p, "a", 10)
        val waiting = async { limiter.acquire(p, "b", 10) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        limiter.acquire(p.copy(id = "q"), "a", 10)
        advanceTimeBy(60_001)
        waiting.await()
    }

    @Test fun tpmWaitsAndOversizedRequestsFailWithoutSending() = runTest {
        val limiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 })
        val p = provider("p").copy(rateLimit = RateLimit(tokensPerMinute = 100))
        limiter.acquire(p, "a", 100)
        val waiting = async { limiter.acquire(p, "a", 50) }
        advanceTimeBy(29_999)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(101)
        waiting.await()
        val error = assertFailsWith<GatewayException> { limiter.acquire(p, "a", 101) }.error
        assertEquals(ErrorKind.RATE_LIMITED, error.kind)
        assertEquals(SendState.NOT_SENT, error.sendState)
    }

    @Test fun exhaustedPrincipalDoesNotBlockOthers() = runTest {
        val limiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 })
        val p = provider("p").copy(rateLimit = RateLimit(10), principalRateLimit = RateLimit(1))
        limiter.acquire(p, "a", 1)
        val waiting = async { limiter.acquire(p, "a", 1) }
        runCurrent()
        limiter.acquire(p, "b", 1)
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        advanceTimeBy(60_001)
        limiter.acquire(p, "a", 1)
    }

    @Test fun interactiveQueuePrecedesBatch() = runTest {
        val limiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 })
        val p = provider("p").copy(rateLimit = RateLimit(1))
        val order = mutableListOf<String>()
        limiter.acquire(p, "a", 1)
        val batch = async { limiter.acquire(p, "b", 1, RateLimitPriority.BATCH); order.add("batch") }
        val user = async { limiter.acquire(p, "u", 1, RateLimitPriority.USER_INTERACTION); order.add("user") }
        advanceTimeBy(60_101)
        assertEquals(listOf("user"), order)
        user.await()
        batch.cancelAndJoin()
    }

    @Test fun retryAfterIsNotCappedAndAimdReducesRefill() = runTest {
        val limiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 })
        val p = provider("p").copy(rateLimit = RateLimit(1))
        limiter.acquire(p, "a", 1)
        limiter.rateLimited(p.id, 90_000)
        val waiting = async { limiter.acquire(p, "a", 1) }
        advanceTimeBy(90_001)
        assertFalse(waiting.isCompleted) // half-speed refill needs 120 seconds
        advanceTimeBy(30_100)
        waiting.await()
    }

    @Test fun gatewayDeadlineWhileQueuedDoesNotChargeOrSealRequestId() = runTest {
        for (streaming in listOf(false, true)) {
            val p = provider("p").copy(rateLimit = RateLimit(1))
            val m = model("m", "p")
            val fake = FakeProvider("p") { okResponse(m.ref) }
            val b = budget()
            val g = ModelGateway(registryOf(listOf(p), listOf(m)), mapOf("p" to fake), b,
                ledger = InMemoryRequestLedger(), rateLimiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 }))
            g.invoke(request(id = "first"))
            val req = request(id = "queued").copy(timeout = 50.milliseconds)
            if (streaming) {
                assertEquals(ErrorKind.TIMEOUT, (g.stream(req).toList().single() as ModelEvent.Failed).error.kind)
            } else {
                assertEquals(ErrorKind.TIMEOUT, assertFailsWith<GatewayException> { g.invoke(req) }.error.kind)
            }
            assertEquals(1, fake.calls)
            assertEquals(0, b.usage("task").reserved.signum())
            advanceTimeBy(60_001)
            g.invoke(req)
            assertEquals(2, fake.calls)
        }
    }

    @Test fun gatewayHonoursProviderCooldownOnEveryAttemptInBothPaths() = runTest {
        for (streaming in listOf(false, true)) {
            val p = provider("p").copy(rateLimit = RateLimit(600, 100_000))
            val m = model("m", "p")
            val times = mutableListOf<Long>()
            val fake = FakeProvider("p") { attempt ->
                times.add(testScheduler.currentTime)
                if (attempt == 1) fail(ErrorKind.RATE_LIMITED, retryAfterMs = 2000, notSent = true)
                okResponse(m.ref)
            }
            val g = ModelGateway(registryOf(listOf(p), listOf(m)), mapOf("p" to fake), budget(),
                config = GatewayConfig(backoffBaseMs = 0, backoffMaxMs = 0),
                ledger = InMemoryRequestLedger(), rateLimiter = RateLimiter(nanoTime = { testScheduler.currentTime * 1_000_000 }))
            if (streaming) g.stream(request()).toList() else g.invoke(request())
            assertEquals(2, fake.calls)
            assertEquals(2000, times[1] - times[0])
        }
    }

    @Test fun validatesLimits() {
        assertFailsWith<IllegalArgumentException> { RateLimit(0) }
        assertFailsWith<IllegalArgumentException> { RateLimit(tokensPerMinute = -1) }
    }
}
