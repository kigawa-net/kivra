package net.kigawa.kivra.selfdev.llmwiring

import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.selfdev.llm.LimitCounter
import org.jetbrains.exposed.sql.transactions.transaction

/** The DB [LimitsStore] (V37) on H2 (MariaDB with `KIVRA_TEST_DB=mariadb`). */
class DbLimitsStoreTest {
    private val hour = 490_000L

    @Test
    fun `adds up to the limit exactly and refuses one above`() = TestDb().use { db ->
        val s = DbLimitsStore(db.database)
        assertTrue(s.tryAdd(LimitCounter.CALLS, hour, 1, 3))
        assertTrue(s.tryAdd(LimitCounter.CALLS, hour, 1, 3))
        assertTrue(s.tryAdd(LimitCounter.CALLS, hour, 1, 3))
        assertFalse(s.tryAdd(LimitCounter.CALLS, hour, 1, 3))
        assertEquals(3, s.current(LimitCounter.CALLS, hour))
        assertTrue(s.tryAdd(LimitCounter.COST_MILLI_YEN, 202610, 600, 1000))
        assertFalse(s.tryAdd(LimitCounter.COST_MILLI_YEN, 202610, 401, 1000))
        assertEquals(600, s.current(LimitCounter.COST_MILLI_YEN, 202610), "a refused add leaves the counter as it was")
        assertTrue(s.tryAdd(LimitCounter.COST_MILLI_YEN, 202610, 400, 1000))
        assertFalse(s.tryAdd(LimitCounter.COST_MILLI_YEN, 202610, 1, 1000))
    }

    @Test
    fun `the first add of a bucket over the limit, a negative amount and a zero limit are refused`() = TestDb().use { db ->
        val s = DbLimitsStore(db.database)
        assertFalse(s.tryAdd(LimitCounter.APPROVALS, hour, 5, 4))
        assertEquals(0, s.current(LimitCounter.APPROVALS, hour))
        assertFalse(s.tryAdd(LimitCounter.APPROVALS, hour, -1, 4))
        assertFalse(s.tryAdd(LimitCounter.APPROVALS, hour, 1, 0))
        assertEquals(0, s.current(LimitCounter.APPROVALS, hour))
    }

    @Test
    fun `an old bucket of the same counter is dropped when a new one is made, and other counters are kept`() = TestDb().use { db ->
        val s = DbLimitsStore(db.database)
        s.tryAdd(LimitCounter.CALLS, hour, 1, 5)
        s.tryAdd(LimitCounter.APPROVALS, hour, 1, 5)
        s.tryAdd(LimitCounter.CALLS, hour + 1, 1, 5)
        assertEquals(0, s.current(LimitCounter.CALLS, hour))
        assertEquals(1, s.current(LimitCounter.CALLS, hour + 1))
        assertEquals(1, s.current(LimitCounter.APPROVALS, hour))
    }

    @Test
    fun `stores of several Pods adding at once never pass the limit, from the first add of the bucket on`() = TestDb().use { db ->
        repeat(5) { round ->
            val bucket = hour + round
            val granted = AtomicLong()
            val pool = Executors.newFixedThreadPool(12)
            try {
                val go = CountDownLatch(1)
                val futures = (1..48).map {
                    val store = DbLimitsStore(db.database) // one store object per task, as one per Pod
                    pool.submit {
                        go.await()
                        if (store.tryAdd(LimitCounter.CALLS, bucket, 1, 10)) granted.incrementAndGet()
                    }
                }
                go.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(10, granted.get())
            assertEquals(10, DbLimitsStore(db.database).current(LimitCounter.CALLS, bucket))
        }
    }

    @Test
    fun `the schema refuses a negative amount, and the outcome CHECK takes WOULD_APPROVE but not an unknown value`() = TestDb().use { db ->
        assertFailsWith<SQLException> {
            transaction(db.database) { execUpdate("INSERT INTO selfdev_llm_stop_limits (counter_name, bucket_key, amount) VALUES ('CALLS', 1, -1)") }
        }
        fun insert(outcome: String) = transaction(db.database) {
            execUpdate(
                "INSERT INTO selfdev_llm_stop_decisions (decision_id, request_id, order_id, outcome, reason_code, model, input_hash, mode, created_at) " +
                    "VALUES (?, 'R', 'O', ?, 'X', 'm', ?, 'shadow', NOW(6))",
                "D$outcome".padEnd(26, '0').take(26), outcome, "a".repeat(64),
            )
        }
        insert("WOULD_APPROVE")
        for (old in listOf("APPROVED", "ABSTAINED", "SKIPPED", "LOST_RACE", "ERROR")) insert(old)
        assertFailsWith<SQLException> { insert("BOGUS") }
    }
}
