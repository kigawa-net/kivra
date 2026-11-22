package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.config.UnpricedAutoReleaseSettings
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.ReserveResult
import net.kigawa.kivra.modelgateway.UnknownCostReason
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The automatic release of unpriced reservations (ADR 0003 追補 2026-11-15) on H2: the grace period, the charge at the reserved amount, the
 * fixed audit row, no double charge across replicas, the batch limit, the scopes (tenant, project, daily), and the settings.
 */
class DbBudgetAutoReleaseTest {
    private val t = BUDGET_TENANT
    private val grace = Duration.ofMinutes(10)

    /** 2026-11-14 10:00 JST. */
    private val start = Instant.parse("2026-11-14T01:00:00Z")

    private fun store(db: TestDb, clock: MutableClock, daily: String? = null, database: org.jetbrains.exposed.sql.Database = db.database) = DbBudgetStore(
        database = database,
        tenantScope = t,
        tenantLimit = BudgetLimit(BigDecimal("10000"), soft = BigDecimal("8000"), currency = "JPY"),
        dailyLimit = daily?.let { BudgetLimit(BigDecimal(it), soft = BigDecimal(it), currency = "JPY") },
        clock = clock,
    )

    private fun unpriced(s: DbBudgetStore, key: String, amount: String = "800") {
        assertEquals(ReserveResult.Reserved, s.reserve(key, listOf(t), jpy(amount)))
        s.commit(key, null)
    }

    private fun committedMicro(db: TestDb, scope: String, period: String): Long = transaction(db.database) {
        execQuery("SELECT committed_micro_yen FROM budget_periods WHERE scope_id = ? AND period = ?", scope, period) { it.getLong(1) }.single()
    }

    private fun unpricedCalls(db: TestDb, scope: String, period: String): Long = transaction(db.database) {
        execQuery("SELECT unpriced_calls FROM budget_periods WHERE scope_id = ? AND period = ?", scope, period) { it.getLong(1) }.single()
    }

    private fun stateOf(db: TestDb, key: String): String = transaction(db.database) {
        execQuery("SELECT state FROM budget_reservations WHERE reservation_key = ?", key) { it.getString(1) }.single()
    }

    @Test
    fun `within the grace nothing is released, after it the reservation is released at its reserved amount`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        unpriced(s, "u1", "800")
        clock.now = start.plus(Duration.ofMinutes(9))
        assertEquals(AutoReleaseReport(0, 0, 0), s.autoReleaseUnpriced(grace, 50))
        assertEquals("UNPRICED", stateOf(db, "u1"))

        clock.now = start.plus(Duration.ofMinutes(11))
        assertEquals(AutoReleaseReport(1, 0, 0), s.autoReleaseUnpriced(grace, 50))
        assertEquals("ACKNOWLEDGED", stateOf(db, "u1"))
        // The charge is the reserved amount (the upper bound), not zero; the unpriced count is gone.
        assertEquals(800_000_000L, committedMicro(db, t, "2026-11"))
        assertEquals(0L, unpricedCalls(db, t, "2026-11"))
    }

    @Test
    fun `the release is audited with the fixed approver and the fixed reason, and the status counts it`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        unpriced(s, "aud", "500")
        clock.now = start.plus(Duration.ofMinutes(20))
        s.autoReleaseUnpriced(grace, 50)
        val row = transaction(db.database) {
            execQuery(
                "SELECT operator_id, reason, evidence, charged_before_micro_yen, actual_micro_yen, actual_known FROM budget_acknowledgements WHERE reservation_key = ?",
                "aud",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getBoolean(6)) }.single()
        }
        assertEquals(listOf<Any>(DbBudgetStore.AUTO_RELEASE_OPERATOR, DbBudgetStore.AUTO_RELEASE_REASON, DbBudgetStore.AUTO_RELEASE_EVIDENCE, 500_000_000L, 500_000_000L, false), row)
        val status = s.operatorStatus()
        assertEquals(1L, status.autoReleasedCount)
        assertNotNull(status.lastAutoReleaseAt)
        assertEquals(0L, status.unpricedCount)
    }

    @Test
    fun `two replicas releasing at the same time charge once`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val a = store(db, clock)
        val replica = org.jetbrains.exposed.sql.Database.connect(db.replicaDataSource())
        val b = store(db, clock, database = replica)
        unpriced(a, "twice", "700")
        clock.now = start.plus(Duration.ofMinutes(30))
        // b listed the key before a released it: its acknowledgement then finds it released.
        val due = b.dueUnpriced(grace, 50)
        assertEquals(listOf("twice"), due.map { it.reservationKey })
        assertEquals(AutoReleaseReport(1, 0, 0), a.autoReleaseUnpriced(grace, 50))
        assertEquals(AutoReleaseReport(0, 0, 0), b.autoReleaseUnpriced(grace, 50), "nothing is due any more")
        assertIs<net.kigawa.kivra.modelgateway.AcknowledgeResult.AlreadyAcknowledged>(
            b.acknowledgeUnpriced(
                net.kigawa.kivra.modelgateway.AcknowledgeUnpriced(
                    due.single().scopeId, due.single().period, "twice", null,
                    evidence = DbBudgetStore.AUTO_RELEASE_EVIDENCE, operatorId = DbBudgetStore.AUTO_RELEASE_OPERATOR, reason = DbBudgetStore.AUTO_RELEASE_REASON,
                ),
            ),
        )
        assertEquals(700_000_000L, committedMicro(db, t, "2026-11"))
        assertEquals(0L, unpricedCalls(db, t, "2026-11"))
    }

    @Test
    fun `one pass releases at most the batch, oldest first, and the next pass the rest`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        // Booked spend (the CLI path) is not admitted, so several unpriced rows can exist at once.
        for (i in 1..5) {
            assertEquals(SpendResult.RECORDED, s.recordSpend("k$i", jpy("10"), unpriced = true).result)
            clock.now = clock.now.plusSeconds(1)
        }
        clock.now = start.plus(Duration.ofHours(1))
        assertEquals(AutoReleaseReport(3, 0, 0), s.autoReleaseUnpriced(grace, 3))
        assertEquals(listOf("ACKNOWLEDGED", "ACKNOWLEDGED", "ACKNOWLEDGED", "UNPRICED", "UNPRICED"), (1..5).map { stateOf(db, "k$it") })
        assertEquals(AutoReleaseReport(2, 0, 0), s.autoReleaseUnpriced(grace, 3))
        assertEquals(AutoReleaseReport(0, 0, 0), s.autoReleaseUnpriced(grace, 3))
    }

    @Test
    fun `an operator's earlier release is left alone, and a lapsed reservation is released too`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        unpriced(s, "manual", "100")
        s.acknowledgeUnpriced(
            net.kigawa.kivra.modelgateway.AcknowledgeUnpriced(t, "2026-11", "manual", null, evidence = "checked", operatorId = "op", reason = "lost"),
        )
        // A reservation never settled lapses at its TTL (5 minutes) and is unpriced from its expiry.
        assertEquals(ReserveResult.Reserved, s.reserve("lapse", listOf(t), jpy("300")))
        clock.now = start.plus(Duration.ofMinutes(5)).plus(Duration.ofMinutes(9))
        assertEquals(AutoReleaseReport(0, 0, 0), s.autoReleaseUnpriced(grace, 50), "lapsed 9 minutes ago: within the grace")
        clock.now = start.plus(Duration.ofMinutes(16))
        assertEquals(AutoReleaseReport(1, 0, 0), s.autoReleaseUnpriced(grace, 50))
        assertEquals("ACKNOWLEDGED", stateOf(db, "lapse"))
        assertEquals(400_000_000L, committedMicro(db, t, "2026-11"))
        val operators = transaction(db.database) {
            execQuery("SELECT operator_id FROM budget_acknowledgements ORDER BY acknowledged_at", ) { it.getString(1) }
        }
        assertEquals(listOf("op", DbBudgetStore.AUTO_RELEASE_OPERATOR), operators)
    }

    @Test
    fun `after the release the admission no longer refuses for an unknown cost`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        unpriced(s, "block", "100")
        val denied = assertNotNull(s.admission(listOf(t), jpy("10")))
        assertEquals(BudgetDenial.UNKNOWN_COST, denied.code)
        assertEquals(UnknownCostReason.UNPRICED_IN_SCOPE, denied.costReason)
        clock.now = start.plus(Duration.ofMinutes(11))
        s.autoReleaseUnpriced(grace, 50)
        assertNull(s.admission(listOf(t), jpy("10")))
    }

    @Test
    fun `a release that leaves no room still releases, and later reservations are refused by the plain limit`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        unpriced(s, "big", "9990")
        clock.now = start.plus(Duration.ofMinutes(11))
        assertEquals(AutoReleaseReport(1, 0, 0), s.autoReleaseUnpriced(grace, 50))
        assertEquals(BudgetDenial.BUDGET, assertNotNull(s.admission(listOf(t), jpy("100"))).code)
    }

    @Test
    fun `a booking on the tenant, the project and the day is released on every scope`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock, daily = "5000")
        val project = ProjectRepository(db.database, clock).create("P", null).projectId
        assertEquals(SpendResult.RECORDED, s.recordSpend("coding:a:1", jpy("250"), unpriced = true, projectId = project).result)
        val scopes = listOf(t to "2026-11", "project:$project" to "2026-11", "$t:daily" to "2026-11-14")
        scopes.forEach { (id, p) -> assertEquals(1L, unpricedCalls(db, id, p), id) }
        clock.now = start.plus(Duration.ofMinutes(11))
        assertEquals(AutoReleaseReport(1, 0, 0), s.autoReleaseUnpriced(grace, 50))
        scopes.forEach { (id, p) ->
            assertEquals(0L, unpricedCalls(db, id, p), id)
            assertEquals(250_000_000L, committedMicro(db, id, p), id)
        }
    }

    @Test
    fun `a reservation charged nothing is never released automatically`() = TestDb().use { db ->
        val clock = MutableClock(start)
        val s = store(db, clock)
        assertEquals(SpendResult.RECORDED, s.recordSpend("coding:zero:1", BudgetAmount(BigDecimal.ZERO, "JPY"), unpriced = true).result)
        clock.now = start.plus(Duration.ofHours(2))
        assertEquals(AutoReleaseReport(0, 0, 0), s.autoReleaseUnpriced(grace, 50))
        assertEquals("UNPRICED", stateOf(db, "coding:zero:1"))
    }

    @Test
    fun `the worker does nothing when its pass finds nothing, survives a failure, and runs the store's release`() {
        var calls = 0
        val failing = UnpricedAutoReleaseWorker({ _, _ -> calls++; error("db down") }, grace)
        assertNull(failing.tick())
        assertNull(failing.tick(), "a failure does not stop the next pass")
        assertEquals(2, calls)
        var seen: Pair<Duration, Int>? = null
        val ok = UnpricedAutoReleaseWorker({ g, b -> seen = g to b; AutoReleaseReport(1, 0, 0) }, grace)
        assertEquals(AutoReleaseReport(1, 0, 0), ok.tick())
        assertEquals(grace to UnpricedAutoReleaseWorker.DEFAULT_BATCH, seen)
    }

    @Test
    fun `the settings are off by default, take a grace of at least a minute, and refuse invalid values`() {
        val default = UnpricedAutoReleaseSettings.fromEnv(emptyMap())
        assertFalse(default.enabled)
        assertEquals(Duration.ofSeconds(600), default.grace)
        assertFalse(UnpricedAutoReleaseSettings.fromEnv(mapOf(UnpricedAutoReleaseSettings.ENV_ENABLED to "false")).enabled)
        val on = UnpricedAutoReleaseSettings.fromEnv(
            mapOf(UnpricedAutoReleaseSettings.ENV_ENABLED to "true", UnpricedAutoReleaseSettings.ENV_GRACE_SECONDS to "60"),
        )
        assertTrue(on.enabled)
        assertEquals(Duration.ofSeconds(60), on.grace)
        for (bad in listOf("59", "0", "-1", "ten", "1.5")) {
            val e = assertFailsWith<IllegalStateException> {
                UnpricedAutoReleaseSettings.fromEnv(mapOf(UnpricedAutoReleaseSettings.ENV_GRACE_SECONDS to bad))
            }
            // A fixed message: the value is not echoed.
            assertTrue(
                e.message == "${UnpricedAutoReleaseSettings.ENV_GRACE_SECONDS} must be a whole number of seconds" ||
                    e.message == "${UnpricedAutoReleaseSettings.ENV_GRACE_SECONDS} must be at least 60",
                e.message,
            )
        }
        assertFailsWith<IllegalStateException> { UnpricedAutoReleaseSettings.fromEnv(mapOf(UnpricedAutoReleaseSettings.ENV_ENABLED to "yes")) }
    }
}
