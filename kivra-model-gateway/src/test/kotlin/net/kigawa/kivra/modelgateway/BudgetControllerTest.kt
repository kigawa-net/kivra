package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BudgetControllerTest {
    private fun bd(s: String) = BigDecimal(s)
    private val limits = mapOf("org" to BudgetLimit(bd("10")), "task" to BudgetLimit(bd("4"), soft = bd("2")))
    private val path = listOf("org", "task")

    @Test fun reserveCommitReleaseFlow() {
        val b = BudgetController(limits)
        assertEquals(ReserveResult.Reserved, b.reserve("k1", path, bd("3")))
        assertEquals(bd("3"), b.usage("task").reserved)
        b.commit("k1", bd("1.5"))
        assertEquals(BudgetUsage(bd("1.5"), bd("0")), b.usage("task"))
        assertEquals(bd("1.5"), b.usage("org").committed)
        b.reserve("k2", path, bd("1"))
        b.release("k2")
        assertEquals(bd("0"), b.usage("task").reserved)
    }

    @Test fun hardLimitDeniesAndDoesNotPartiallyReserve() {
        val b = BudgetController(limits)
        val r = b.reserve("k", path, bd("5"))
        assertTrue(r is ReserveResult.Denied && r.scopeId == "task")
        assertEquals(bd("0"), b.usage("org").reserved)
    }

    @Test fun parentLimitBindsChild() {
        val b = BudgetController(mapOf("org" to BudgetLimit(bd("1")), "task" to BudgetLimit(bd("100"))))
        assertTrue(b.reserve("k", path, bd("2")) is ReserveResult.Denied)
    }

    @Test fun committedSpendCountsAgainstLaterReservations() {
        val b = BudgetController(limits)
        b.reserve("a", path, bd("3")); b.commit("a", bd("3"))
        assertFalse(b.canReserve(path, bd("1.5")))
        assertTrue(b.canReserve(path, bd("1")))
    }

    @Test fun unknownOrEmptyScopeIsDenied() {
        val b = BudgetController(limits)
        assertTrue(b.reserve("k", listOf("nope"), bd("0.01")) is ReserveResult.Denied)
        assertTrue(b.reserve("k", emptyList(), bd("0.01")) is ReserveResult.Denied)
    }

    @Test fun commitIsIdempotentAndEstimatedUsesReservedAmount() {
        val b = BudgetController(limits)
        b.reserve("k", path, bd("2")); b.commit("k", null); b.commit("k", bd("9"))
        assertEquals(bd("2"), b.usage("task").committed)
        b.reserve("k", path, bd("1")) // settled key: no double reservation
        assertEquals(bd("0"), b.usage("task").reserved)
    }

    @Test fun softLimitFlag() {
        val b = BudgetController(limits)
        assertFalse(b.softExceeded(path))
        b.reserve("k", path, bd("2.5"))
        assertTrue(b.softExceeded(path))
    }

    @Test fun expiredReservationStaysChargedUntilSettled() {
        var now = Instant.parse("2026-10-12T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val b = BudgetController(limits, clock, Duration.ofMinutes(1))
        b.reserve("k", path, bd("4"))
        assertFalse(b.canReserve(path, bd("1")))
        now = now.plusSeconds(61)
        assertFalse(b.canReserve(path, bd("1"))) // lapsed reservation is not forgiven (possible in-flight billing)
        b.release("k")                            // only a definitive release refunds it
        assertTrue(b.canReserve(path, bd("4")))
    }

    @Test fun settledOrDuplicateKeyIsNeverReservedAgain() {
        val b = BudgetController(limits)
        assertEquals(ReserveResult.Reserved, b.reserve("k", path, bd("1")))
        assertTrue(b.reserve("k", path, bd("1")) is ReserveResult.Denied) // still reserved
        b.commit("k", bd("1"))
        assertTrue(b.reserve("k", path, bd("1")) is ReserveResult.Denied) // settled
        assertEquals(bd("1"), b.usage("task").committed)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @Test fun expiredReservationIsChargedConservativelyAndTrueUpOnLateCommit() {
        val clock = MutableClock(Instant.parse("2026-10-12T00:00:00Z"))
        val b = BudgetController(limits, clock, Duration.ofMinutes(5))
        b.reserve("k", path, bd("3"))
        clock.now = clock.now.plusSeconds(600)
        assertEquals(BudgetUsage(bd("3"), bd("0")), b.usage("task")) // not dropped
        b.commit("k", bd("1.2"))                                      // late commit trues up
        assertEquals(bd("1.2"), b.usage("task").committed)
        assertEquals(bd("1.2"), b.usage("org").committed)
    }

    @Test fun lateCommitWithoutActualKeepsEstimateAndLateReleaseRefunds() {
        val clock = MutableClock(Instant.parse("2026-10-12T00:00:00Z"))
        val b = BudgetController(limits, clock, Duration.ofMinutes(1))
        b.reserve("a", path, bd("2")); b.reserve("r", path, bd("1"))
        clock.now = clock.now.plusSeconds(120)
        b.commit("a", null); b.release("r")
        assertEquals(bd("2"), b.usage("task").committed)
    }
}
