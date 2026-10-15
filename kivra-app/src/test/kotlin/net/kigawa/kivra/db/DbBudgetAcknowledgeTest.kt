package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.ReserveResult
import org.jetbrains.exposed.sql.transactions.transaction

/** DB-only behaviour of [AcknowledgeUnpriced]: the period it applies to, the audit record, and rollback. */
class DbBudgetAcknowledgeTest {
    private val t = BUDGET_TENANT

    private fun ack(
        key: String,
        period: String,
        actual: BudgetAmount? = null,
        operator: String = "op-audit",
        evidence: String = "invoice checked",
        reason: String = "lost response",
    ) = AcknowledgeUnpriced(t, period, key, actual, evidence = evidence, operatorId = operator, reason = reason)

    private fun assertMicro(db: TestDb, period: String, expected: String) {
        val actual = transaction(db.database) {
            execQuery("SELECT committed_micro_yen FROM budget_periods WHERE scope_id = ? AND period = ?", t, period) { it.getLong(1) }
                .single()
                .let { BigDecimal.valueOf(it, 6) }
        }
        assertEquals(0, BigDecimal(expected).compareTo(actual), "committed in $period was $actual")
    }

    private fun assertUnpriced(db: TestDb, period: String, expected: Long) {
        val actual = transaction(db.database) {
            execQuery("SELECT unpriced_calls FROM budget_periods WHERE scope_id = ? AND period = ?", t, period) { it.getLong(1) }.single()
        }
        assertEquals(expected, actual, "unpriced calls in $period")
    }

    @Test
    fun `a release applies to the period the reservation was made in, after the month has changed (h)`() {
        TestDb().use { db ->
            // 2026-10-31 23:59:59 JST
            val clock = MutableClock(Instant.parse("2026-10-31T14:59:59Z"))
            val store = newDbStore(db, clock)
            assertEquals(ReserveResult.Reserved, store.reserve("oct-u", listOf(t), jpy("800")))
            store.commit("oct-u", null)

            // 2026-11-01 00:00:00 JST: the new month starts clean and the old reservation still belongs to October.
            clock.now = Instant.parse("2026-10-31T15:00:00Z")
            assertEquals(0L, store.usage(t).unpricedCalls)
            assertEquals(AcknowledgeResult.NotFound, store.acknowledgeUnpriced(ack("oct-u", "2026-11")))
            assertEquals(ReserveResult.Reserved, store.reserve("nov-ok", listOf(t), jpy("9000")))

            assertEquals(AcknowledgeResult.Acknowledged("oct-u", actualKnown = false), store.acknowledgeUnpriced(ack("oct-u", "2026-10")))
            assertMicro(db, "2026-10", "800")
            assertUnpriced(db, "2026-10", 0)
        }
    }

    @Test
    fun `the release is recorded in the same transaction with who, why, on what evidence and the amounts (i)`() {
        TestDb().use { db ->
            val store = newDbStore(db, MutableClock())
            store.reserve("aud", listOf(t), jpy("1000"))
            store.commit("aud", null)

            val request = ack("aud", "2026-10", jpy("600"), operator = "op-audit", evidence = "EVIDENCE-TEXT-123", reason = "R-REASON")
            assertEquals(AcknowledgeResult.Acknowledged("aud", actualKnown = true), store.acknowledgeUnpriced(request))

            val row = transaction(db.database) {
                execQuery(
                    "SELECT scope_id, period, operator_id, reason, evidence, charged_before_micro_yen, actual_micro_yen, actual_known FROM budget_acknowledgements WHERE reservation_key = ?",
                    "aud",
                ) { rs ->
                    listOf(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6).toString(), rs.getLong(7).toString(), rs.getBoolean(8).toString(),
                    )
                }.single()
            }
            assertEquals(
                listOf(t, "2026-10", "op-audit", "R-REASON", "EVIDENCE-TEXT-123", "1000000000", "600000000", "true"),
                row,
            )
            assertEquals(1, db.count("budget_reservations", "reservation_key = 'aud' AND state = 'ACKNOWLEDGED'"))

            // The record is never rewritten: a second release by another operator is refused and leaves the row as it was.
            assertEquals(
                AcknowledgeResult.AlreadyAcknowledged,
                store.acknowledgeUnpriced(ack("aud", "2026-10", jpy("100"), operator = "op-other", evidence = "other")),
            )
            assertEquals(1, db.count("budget_acknowledgements"))
            assertEquals(1, db.count("budget_acknowledgements", "reservation_key = 'aud' AND operator_id = 'op-audit'"))
            assertMicro(db, "2026-10", "600")
        }
    }

    @Test
    fun `a failure while recording the release changes nothing and reports Failure (k)`() {
        TestDb().use { db ->
            val store = newDbStore(db, MutableClock())
            store.reserve("fail", listOf(t), jpy("1000"))
            store.commit("fail", null)

            store.afterAcknowledgeWrite = { error("injected failure") }
            assertEquals(AcknowledgeResult.Failure, store.acknowledgeUnpriced(ack("fail", "2026-10", jpy("600"))))
            assertEquals(0, db.count("budget_acknowledgements"))
            assertEquals(1, db.count("budget_reservations", "reservation_key = 'fail' AND state = 'UNPRICED'"))
            assertMicro(db, "2026-10", "1000")
            assertUnpriced(db, "2026-10", 1)
            assertTrue(store.admission(listOf(t), jpy("1"))?.code == "UNKNOWN_COST")

            store.afterAcknowledgeWrite = null
            assertEquals(AcknowledgeResult.Acknowledged("fail", actualKnown = true), store.acknowledgeUnpriced(ack("fail", "2026-10", jpy("600"))))
            assertMicro(db, "2026-10", "600")
            assertUnpriced(db, "2026-10", 0)
        }
    }
}
