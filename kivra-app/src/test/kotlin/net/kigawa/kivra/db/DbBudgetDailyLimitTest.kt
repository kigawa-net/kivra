package net.kigawa.kivra.db

import io.ktor.server.config.MapApplicationConfig
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import net.kigawa.kivra.config.BudgetConfig
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.ReserveResult

/** The tenant's daily hard limit: refusal within a day, reset at JST midnight, the monthly limit still applies, and settlement by the reserve-time day. */
class DbBudgetDailyLimitTest {
    /** 2026-11-14 10:00 JST, and the next JST day. */
    private val day1 = Instant.parse("2026-11-14T01:00:00Z")
    private val day2 = Instant.parse("2026-11-15T01:00:00Z")
    private val scopes = listOf(BUDGET_TENANT)

    /** The reservation TTL is long here: an unsettled reservation that lapses is charged as unpriced (the store's rule), which these tests are not about. */
    private fun store(db: TestDb, clock: MutableClock, daily: String? = "100", monthly: String = "10000") = DbBudgetStore(
        database = db.database,
        tenantScope = BUDGET_TENANT,
        tenantLimit = BudgetLimit(BigDecimal(monthly), soft = BigDecimal(monthly), currency = "JPY"),
        dailyLimit = daily?.let { BudgetLimit(BigDecimal(it), soft = BigDecimal(it), currency = "JPY") },
        clock = clock,
        reservationTtl = java.time.Duration.ofDays(3),
    )

    @Test
    fun `the daily limit refuses a reservation that would exceed the day, though the month has room`() = TestDb().use { db ->
        val s = store(db, MutableClock(day1))
        assertEquals(ReserveResult.Reserved, s.reserve("a", scopes, jpy("60")))
        assertEquals(BudgetDenial.BUDGET, assertIs<ReserveResult.Denied>(s.reserve("b", scopes, jpy("60"))).code)
    }

    @Test
    fun `the day resets at JST midnight`() = TestDb().use { db ->
        val clock = MutableClock(day1)
        val s = store(db, clock)
        s.reserve("a", scopes, jpy("60"))
        clock.now = day2
        assertEquals(ReserveResult.Reserved, s.reserve("b", scopes, jpy("60")))
    }

    @Test
    fun `the monthly limit still applies when the day has room`() = TestDb().use { db ->
        val s = store(db, MutableClock(day1), daily = "1000", monthly = "100")
        s.reserve("a", scopes, jpy("60"))
        assertEquals(BudgetDenial.BUDGET, assertIs<ReserveResult.Denied>(s.reserve("b", scopes, jpy("60"))).code)
    }

    @Test
    fun `without a daily limit the behaviour is the monthly one`() = TestDb().use { db ->
        val s = store(db, MutableClock(day1), daily = null)
        assertEquals(ReserveResult.Reserved, s.reserve("a", scopes, jpy("60")))
        assertEquals(ReserveResult.Reserved, s.reserve("b", scopes, jpy("60")))
    }

    @Test
    fun `a reservation settles against the day it was made`() = TestDb().use { db ->
        val clock = MutableClock(day1)
        val s = store(db, clock)
        s.reserve("a", scopes, jpy("60")) // day 1: 60 of 100
        clock.now = day2
        assertEquals(ReserveResult.Reserved, s.reserve("b", scopes, jpy("60"))) // day 2: 60 of 100
        s.release("a") // frees day 1 only
        assertEquals(BudgetDenial.BUDGET, assertIs<ReserveResult.Denied>(s.reserve("c", scopes, jpy("60"))).code)
        assertEquals(ReserveResult.Reserved, s.reserve("d", scopes, jpy("40")))
    }

    @Test
    fun `the status shows the current JST day only, and no daily part without a daily limit`() = TestDb().use { db ->
        val clock = MutableClock(day1)
        val s = store(db, clock)
        s.reserve("a", scopes, jpy("60"))
        val today = s.operatorStatus().daily!!
        assertEquals("2026-11-14", today.period)
        assertEquals(0, today.committedJpy.compareTo(BigDecimal.ZERO))
        assertEquals(0, today.reservedJpy.compareTo(BigDecimal("60")))
        assertEquals(0, today.limitJpy.compareTo(BigDecimal("100")))
        clock.now = day2
        val next = s.operatorStatus().daily!!
        assertEquals("2026-11-15", next.period)
        assertEquals(0, next.reservedJpy.compareTo(BigDecimal.ZERO), "an earlier day's reservation is not on the new day")
        assertEquals(null, store(db, clock, daily = null).operatorStatus().daily)
    }

    @Test
    fun `a zero-cost call does not consume the daily limit`() = TestDb().use { db ->
        val s = store(db, MutableClock(day1), daily = "1")
        repeat(3) { assertEquals(ReserveResult.Reserved, s.reserve("z$it", scopes, jpy("0"))) }
    }

    private fun config(daily: String?): BudgetConfig {
        val entries = mutableListOf("kivra.budget.tenantId" to "t")
        if (daily != null) entries += "kivra.budget.dailyLimitJpy" to daily
        return BudgetConfig.from(MapApplicationConfig(*entries.toTypedArray()))
    }

    @Test
    fun `the daily limit is unset by default, blank means unset, and a positive value is read`() {
        assertNull(config(null).dailyLimitJpy)
        assertNull(config("").dailyLimitJpy)
        assertEquals(BigDecimal("5000"), config("5000").dailyLimitJpy)
        assertEquals(BigDecimal("5000"), config("5000").dailyLimit()!!.hard)
        assertNull(config(null).dailyLimit())
    }

    @Test
    fun `an invalid daily limit fails the startup, and the message does not echo the value`() {
        for (bad in listOf("abc", "0", "-3")) {
            val e = assertFailsWith<IllegalStateException>(bad) { config(bad) }
            assertFalse(bad in e.message.orEmpty(), e.message)
        }
    }
}
