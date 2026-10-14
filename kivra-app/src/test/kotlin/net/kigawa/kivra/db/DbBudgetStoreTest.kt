package net.kigawa.kivra.db

import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.config.DatabaseConfig
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.modelgateway.ReserveResult
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** A store over [db] with the production limits (hard 10,000 JPY, soft 80%) and a run limit of [runHard] (null: none). */
internal fun newDbStore(
    db: TestDb,
    clock: Clock,
    runHard: String? = "1000",
    hard: String = "10000",
    database: Database = db.database,
): DbBudgetStore {
    val h = BigDecimal(hard)
    return DbBudgetStore(
        database = database,
        tenantScope = BUDGET_TENANT,
        tenantLimit = BudgetLimit(h, soft = h.multiply(BigDecimal("0.8")), currency = "JPY"),
        runLimit = runHard?.let { BudgetLimit(BigDecimal(it), currency = "JPY") },
        clock = clock,
    )
}

/** A second connection pool on the same database: a separate replica for the cross-replica tests. */
internal fun TestDb.replicaDataSource(): DataSource {
    val h = dataSource as HikariDataSource
    return createDataSource(DatabaseConfig(h.jdbcUrl, h.username, h.password))
}

/** Runs the shared contract over [DbBudgetStore], one fresh migrated database per test. */
class DbBudgetStoreContractTest : BudgetPortContract() {
    override fun withBudget(tenantHard: String, runHard: String?, replicas: Int, clock: MutableClock, block: (BudgetFixture) -> Unit) {
        TestDb().use { db ->
            val extra = mutableListOf<DataSource>()
            try {
                val ports: List<BudgetPort> = List(replicas) { i ->
                    if (i == 0) {
                        newDbStore(db, clock, runHard, tenantHard)
                    } else {
                        val ds = db.replicaDataSource().also { extra.add(it) }
                        // Same limits as replica 0, over a separate connection pool (a separate replica).
                        newDbStore(db, clock, runHard, tenantHard, database = connectDatabase(ds))
                    }
                }
                block(BudgetFixture(clock, ports))
            } finally {
                extra.forEach { closeDataSource(it) }
            }
        }
    }
}

/** DB-only behaviour: periods, rounding, rollback, restart. */
class DbBudgetStoreTest {
    private val t = BUDGET_TENANT

    @Test
    fun `a new JST month starts at zero, carries nothing over, and a month-crossing commit settles in its own month`() {
        TestDb().use { db ->
            // 2026-10-31 23:59:59 JST
            val clock = MutableClock(Instant.parse("2026-10-31T14:59:59Z"))
            val store = newDbStore(db, clock)
            assertEquals(ReserveResult.Reserved, store.reserve("oct", listOf(t, BUDGET_RUN), jpy("500")))
            assertEquals(ReserveResult.Reserved, store.reserve("oct-2", listOf(t), jpy("3000")))

            // 2026-11-01 00:00:00 JST
            clock.now = Instant.parse("2026-10-31T15:00:00Z")
            val nov = store.usage(t)
            assertTrue(nov.committed.signum() == 0 && nov.reserved.signum() == 0 && nov.unpricedCalls == 0L)
            assertEquals(ReserveResult.Reserved, store.reserve("nov", listOf(t), jpy("9000")))
            assertTrue(store.reserve("nov-over", listOf(t), jpy("1001")) is ReserveResult.Denied)

            // The October reservations are settled in October.
            store.commit("oct", jpy("400"))
            store.commit("oct-2", jpy("3000"))
            assertSame("3400", micro(db, t, "2026-10"))
            assertSame("0", micro(db, t, "2026-11"))
            assertSame("400", micro(db, BUDGET_RUN, "ALL"))

            // Run limits are lifetime: the run's 400 JPY still counts after the rollover.
            assertEquals(BudgetDenial.BUDGET, (store.reserve("run-nov", listOf(t, BUDGET_RUN), jpy("601")) as ReserveResult.Denied).code)
            assertEquals(ReserveResult.Reserved, store.reserve("run-nov-ok", listOf(t, BUDGET_RUN), jpy("600")))
        }
    }

    @Test
    fun `usage survives a restart of the store`() {
        TestDb().use { db ->
            val clock = MutableClock()
            assertEquals(ReserveResult.Reserved, newDbStore(db, clock).reserve("rs", listOf(t), jpy("2500")))
            newDbStore(db, clock).commit("rs", jpy("2400"))
            val restarted = DbBudgetStore(connectDatabase(db.dataSource), t, BudgetLimit(BigDecimal("10000"), currency = "JPY"), clock = clock)
            assertSame("2400", restarted.usage(t).committed)
        }
    }

    @Test
    fun `costs are rounded up to whole micro-yen and overflow is unknown cost`() {
        TestDb().use { db ->
            val store = newDbStore(db, MutableClock())
            assertEquals(ReserveResult.Reserved, store.reserve("tiny", listOf(t), jpy("0.0000001")))
            assertSame("0.000001", store.usage(t).reserved)
            store.commit("tiny", jpy("0.0000004"))
            assertSame("0.000001", store.usage(t).committed)
            assertEquals(BudgetDenial.UNKNOWN_COST, (store.reserve("huge", listOf(t), jpy("1e30")) as ReserveResult.Denied).code)
        }
    }

    @Test
    fun `an exception inside reserve rolls back the whole reservation`() {
        TestDb().use { db ->
            val store = newDbStore(db, MutableClock())
            store.afterReservationWrite = { error("injected failure") }
            val denied = store.reserve("rb", listOf(t, BUDGET_RUN), jpy("300")) as ReserveResult.Denied
            assertEquals(BudgetDenial.STORE_UNAVAILABLE, denied.code)
            store.afterReservationWrite = null

            assertEquals(0, db.count("budget_reservations", "reservation_key = 'rb'"))
            assertEquals(0, db.count("budget_reservation_scopes", "reservation_key = 'rb'"))
            assertSame("0", store.usage(t).reserved)
            assertSame("0", store.usage(BUDGET_RUN).reserved)
            // The key was never reserved, so it can be reserved now.
            assertEquals(ReserveResult.Reserved, store.reserve("rb", listOf(t, BUDGET_RUN), jpy("300")))
        }
    }

    @Test
    fun `the tenant period row is created on first use of a month and is unique per scope and period`() {
        TestDb().use { db ->
            val store = newDbStore(db, MutableClock())
            assertEquals(0, db.count("budget_periods", "scope_id = '$t'"))
            store.reserve("p1", listOf(t), jpy("1"))
            assertEquals(1, db.count("budget_periods", "scope_id = '$t' AND period = '2026-10'"))
            assertNull(store.admission(listOf(t), jpy("1")))
        }
    }

    private fun assertSame(expected: String, actual: BigDecimal) =
        assertTrue(BigDecimal(expected).compareTo(actual) == 0, "expected $expected but was $actual")

    private fun micro(db: TestDb, scope: String, period: String): BigDecimal = transaction(db.database) {
        execQuery("SELECT committed_micro_yen FROM budget_periods WHERE scope_id = ? AND period = ?", scope, period) { it.getLong(1) }
            .single()
            .let { BigDecimal.valueOf(it, 6) }
    }
}
