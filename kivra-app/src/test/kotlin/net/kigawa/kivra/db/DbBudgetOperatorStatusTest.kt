package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.ReserveResult

/** The operator snapshot over the durable ledger: the month's figures, the API / CLI split, and the UNPRICED list. */
class DbBudgetOperatorStatusTest {
    private fun cmp(expected: String, actual: BigDecimal, what: String) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, was $actual")

    @Test
    fun `the split adds up to the committed total, and unpriced rows are listed with their path`() {
        TestDb().use { db ->
            val clock = MutableClock(Instant.parse("2026-10-14T03:00:00Z"))
            val store = newDbStore(db, clock)

            // API path: one UNPRICED (no actual), one COMMITTED with a known actual. Both are reserved before either is
            // settled, since a scope with unpriced usage admits nothing new.
            assertEquals(ReserveResult.Reserved, store.reserve("api-1", listOf(BUDGET_TENANT), jpy("800")))
            assertEquals(ReserveResult.Reserved, store.reserve("api-2", listOf(BUDGET_TENANT), jpy("200")))
            // An open reservation: not charged yet (reserved before any unpriced booking, which closes the scope to new reserves).
            assertEquals(ReserveResult.Reserved, store.reserve("open", listOf(BUDGET_TENANT), jpy("100")))
            store.commit("api-1", null)
            store.commit("api-2", jpy("150"))
            // CLI path: a priced and an unpriced coding: booking.
            assertEquals(true, store.recordSpend("coding:ATT1:1", jpy("300"), unpriced = false).result == SpendResult.RECORDED)
            assertEquals(true, store.recordSpend("coding:ATT2:1", jpy("50"), unpriced = true).result == SpendResult.RECORDED)

            val s = store.operatorStatus()
            cmp("10000", s.limitJpy, "limit")
            cmp("1300", s.committedJpy, "committed")
            cmp("100", s.reservedJpy, "reserved")
            cmp("350", s.cliCommittedJpy, "cli committed")
            assertEquals(2L, s.unpricedCount)
            assertEquals(setOf("api-1" to false, "coding:ATT2:1" to true), s.unpriced.map { it.reservationKey to it.cli }.toSet())
            val api = s.unpriced.single { it.reservationKey == "api-1" }
            cmp("800", api.amountJpy, "api-1 amount")
            assertEquals("UNPRICED", api.state)

            // Acknowledging the API row removes it from the list and the count, and its charge stays (no actual).
            assertTrue(store.acknowledgeUnpriced(AcknowledgeUnpriced(BUDGET_TENANT, "2026-10", "api-1", null, "e", "op", "r")) is AcknowledgeResult.Acknowledged)
            val after = store.operatorStatus()
            assertEquals(1L, after.unpricedCount)
            assertEquals(listOf("coding:ATT2:1"), after.unpriced.map { it.reservationKey })
            cmp("1300", after.committedJpy, "committed after release")
            // The acknowledged CLI-less charge is still on the API side: the split is unchanged.
            cmp("350", after.cliCommittedJpy, "cli committed after release")
        }
    }

    @Test
    fun `an empty ledger reports zeros and an empty list`() {
        TestDb().use { db ->
            val s = newDbStore(db, MutableClock(Instant.parse("2026-10-14T03:00:00Z"))).operatorStatus()
            assertEquals("2026-10", s.period)
            cmp("0", s.committedJpy, "committed")
            cmp("0", s.cliCommittedJpy, "cli")
            assertEquals(0L, s.unpricedCount)
            assertFalse(s.unpriced.any())
        }
    }
}
