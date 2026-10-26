package net.kigawa.kivra.selfdev.llm

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class StopJudgeLimiterTest {
    private class FakeClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = at
    }

    private val t0 = Instant.parse("2026-10-27T10:10:00Z")
    private val allowed = LimitDecision.Allowed

    private fun limiter(clock: Clock, limits: StopJudgeLimits = StopJudgeLimits(maxApprovalsPerHour = 2, maxCallsPerHour = 3, maxInputTokens = 100, maxMilliYenPerMonth = 1000)) =
        StopJudgeLimiter(limits, InMemoryLimitsStore(), clock)

    @Test
    fun `calls per hour stop at the limit, exactly and one above`() {
        val l = limiter(FakeClock(t0))
        repeat(3) { assertEquals(allowed, l.reserveCall(10)) }
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_CALLS), l.reserveCall(10))
    }

    @Test
    fun `approvals per hour stop at the limit`() {
        val l = limiter(FakeClock(t0))
        repeat(2) { assertEquals(allowed, l.reserveApproval()) }
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_APPROVALS), l.reserveApproval())
    }

    @Test
    fun `the hour window rolls with the clock`() {
        val clock = FakeClock(t0)
        val l = limiter(clock)
        repeat(3) { l.reserveCall(10) }
        repeat(2) { l.reserveApproval() }
        clock.at = Instant.parse("2026-10-27T10:59:59Z")
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_CALLS), l.reserveCall(10))
        clock.at = Instant.parse("2026-10-27T11:00:00Z")
        assertEquals(allowed, l.reserveCall(10))
        assertEquals(allowed, l.reserveApproval())
    }

    @Test
    fun `input tokens are checked at the limit and one above, and a refusal takes no call`() {
        val l = limiter(FakeClock(t0))
        assertEquals(allowed, l.reserveCall(100))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_TOKENS), l.reserveCall(101))
        assertEquals(allowed, l.reserveCall(1))
        assertEquals(allowed, l.reserveCall(1))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_CALLS), l.reserveCall(1))
    }

    @Test
    fun `cost is added up to the limit and a sum over it is refused and not added`() {
        val l = limiter(FakeClock(t0))
        assertEquals(allowed, l.recordCost(600))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_COST), l.recordCost(401))
        assertEquals(allowed, l.recordCost(400))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_COST), l.recordCost(1))
        // The month is used up: no call is made.
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_COST), l.reserveCall(10))
    }

    @Test
    fun `a refused cost leaves the counter as it was`() {
        val store = InMemoryLimitsStore()
        val l = StopJudgeLimiter(StopJudgeLimits(maxMilliYenPerMonth = 1000), store, FakeClock(t0))
        l.recordCost(900)
        l.recordCost(200)
        assertEquals(900, store.current(LimitCounter.COST_MILLI_YEN, 202610))
    }

    @Test
    fun `the month rolls in Japan time`() {
        val clock = FakeClock(Instant.parse("2026-10-31T14:59:59Z")) // 23:59:59 JST on Oct 31
        val l = limiter(clock)
        assertEquals(allowed, l.recordCost(1000))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_COST), l.recordCost(1))
        clock.at = Instant.parse("2026-10-31T15:00:00Z") // 00:00 JST on Nov 1
        assertEquals(allowed, l.recordCost(1000))
    }

    @Test
    fun `a zero limit refuses everything`() {
        val l = limiter(FakeClock(t0), StopJudgeLimits(maxApprovalsPerHour = 0, maxCallsPerHour = 0, maxMilliYenPerMonth = 0))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_COST), l.reserveCall(1))
        assertEquals(LimitDecision.Denied(LimitCode.LIMIT_APPROVALS), l.reserveApproval())
    }

    @Test
    fun `concurrent cost additions never pass the limit`() {
        val store = InMemoryLimitsStore()
        val l = StopJudgeLimiter(StopJudgeLimits(maxMilliYenPerMonth = 1000), store, FakeClock(t0))
        val threads = (1..8).map { Thread { repeat(500) { l.recordCost(3) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(999, store.current(LimitCounter.COST_MILLI_YEN, 202610))
    }

    @Test
    fun `pricing rounds up and does not overflow`() {
        val p = JudgePricing(1_000_000, 5_000_000)
        assertEquals(1, p.costMilliYen(JudgeUsage(1, 0)))
        assertEquals(1000 + 500, p.costMilliYen(JudgeUsage(1000, 100)))
        assertEquals(0, p.costMilliYen(JudgeUsage(0, 0)))
        JudgePricing(Long.MAX_VALUE, Long.MAX_VALUE).costMilliYen(JudgeUsage(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
