package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.ReserveResult
import net.kigawa.kivra.modelgateway.UnknownCostReason

/*
 * The project scope of the budget (ADR 0022, PR-E). The tenant limit is 10,000 yen a month; each project's limit comes from
 * project_budgets. Real DbBudgetStore on H2 (or MariaDB, when configured).
 */

private const val TENANT = "tenant-a"
private val NOW: Instant = Instant.parse("2026-10-18T03:00:00Z") // JST 2026-10-18 12:00
private val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

private fun yenAmount(v: String) = BudgetAmount(BigDecimal(v), "JPY", Cost.UNIT_CURRENCY_UNITS)

private fun microYenOf(yen: String): Long = BigDecimal(yen).movePointRight(6).toLong()

private class ProjectBudgetFixture(val db: TestDb) {
    val store = DbBudgetStore(db.database, TENANT, BudgetLimit(BigDecimal("10000"), currency = "JPY"), clock = CLOCK)
    private val projects = ProjectRepository(db.database, CLOCK)

    fun project(name: String): String = projects.create(name, null).projectId

    fun scope(projectId: String): String = DbBudgetStore.projectScope(projectId)
}

class ProjectBudgetStoreTest {
    @Test
    fun `a project without a limit row is not in the admission path, and a project with one is`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        assertEquals(listOf(TENANT), f.store.scopesFor(p))
        assertEquals(listOf(TENANT), f.store.scopesFor(null))

        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))

        assertEquals(listOf(TENANT, f.scope(p)), f.store.scopesFor(p))
    }

    @Test
    fun `a reservation on both scopes is held in both and settled in both`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))

        assertEquals(ReserveResult.Reserved, f.store.reserve("k1", listOf(TENANT, f.scope(p)), yenAmount("300")))
        val during = f.store.projectBudget(p)
        assertEquals(microYenOf("300"), during.reservedMicroYen)
        assertEquals(0L, during.committedMicroYen)
        assertEquals(microYenOf("700"), during.remainingMicroYen)
        assertEquals(0, BigDecimal("300").compareTo(f.store.usage(TENANT).reserved))

        f.store.commit("k1", yenAmount("250"))
        val after = f.store.projectBudget(p)
        assertEquals(microYenOf("250"), after.committedMicroYen)
        assertEquals(0L, after.reservedMicroYen)
        assertEquals(microYenOf("750"), after.remainingMicroYen)
        assertEquals(0, BigDecimal("250").compareTo(f.store.usage(TENANT).committed))
    }

    @Test
    fun `a release returns the reserved amount of the project`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))
        f.store.reserve("k1", listOf(TENANT, f.scope(p)), yenAmount("300"))

        f.store.release("k1")

        val b = f.store.projectBudget(p)
        assertEquals(0L, b.reservedMicroYen)
        assertEquals(0L, b.committedMicroYen)
    }

    @Test
    fun `a project over its limit refuses only its own reservations, and the tenant and other projects are not affected`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val one = f.project("one")
        val two = f.project("two")
        val three = f.project("three")
        f.store.setProjectBudget(one, BigDecimal("1000"), BigDecimal("0.8"))
        f.store.setProjectBudget(two, BigDecimal("5000"), BigDecimal("0.8"))
        assertEquals(ReserveResult.Reserved, f.store.reserve("a", listOf(TENANT, f.scope(one)), yenAmount("900")))

        val denied = assertIs<ReserveResult.Denied>(f.store.reserve("b", listOf(TENANT, f.scope(one)), yenAmount("200")))
        assertEquals(BudgetDenial.BUDGET, denied.code)
        assertEquals(f.scope(one), denied.scopeId)
        // The same project's smaller cost still fits in what is left (100 yen).
        assertEquals(ReserveResult.Reserved, f.store.reserve("b2", listOf(TENANT, f.scope(one)), yenAmount("100")))

        assertEquals(ReserveResult.Reserved, f.store.reserve("c", listOf(TENANT, f.scope(two)), yenAmount("200")))
        assertEquals(ReserveResult.Reserved, f.store.reserve("d", f.store.scopesFor(three), yenAmount("500")))
        // The tenant total: 900 + 100 + 200 + 500. The refused 200 is not in it.
        assertEquals(0, BigDecimal("1700").compareTo(f.store.usage(TENANT).reserved))
    }

    @Test
    fun `a project without a limit is limited by the tenant only, as before`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("free")
        // Spend of a project with no limit is still booked (for the dashboard), and it gates nothing.
        assertEquals(SpendResult.RECORDED, f.store.recordSpend("settled", yenAmount("9000"), unpriced = false, projectId = p).result)

        assertEquals(ReserveResult.Reserved, f.store.reserve("r", f.store.scopesFor(p), yenAmount("900")))
        assertEquals(ReserveResult.Reserved, f.store.reserve("r2", f.store.scopesFor(p), yenAmount("50")))
        assertNull(f.store.projectBudget(p).monthlyLimitJpy)
        assertNull(f.store.projectBudget(p).remainingMicroYen)
    }

    @Test
    fun `a cost without a project is booked to the tenant only`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))

        assertEquals(SpendResult.RECORDED, f.store.recordSpend("k", yenAmount("100"), unpriced = false).result)

        assertEquals(0, BigDecimal.ZERO.compareTo(f.store.usage(f.scope(p)).committed))
        assertEquals(0, BigDecimal("100").compareTo(f.store.usage(TENANT).committed))
    }

    @Test
    fun `a settled cost of a project is booked to both scopes once, and it counts even without a limit`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")

        assertEquals(SpendResult.RECORDED, f.store.recordSpend("k", yenAmount("120"), unpriced = false, projectId = p).result)
        assertEquals(SpendResult.ALREADY_RECORDED, f.store.recordSpend("k", yenAmount("120"), unpriced = false, projectId = p).result)

        val b = f.store.projectBudget(p)
        assertEquals(microYenOf("120"), b.committedMicroYen)
        assertNull(b.monthlyLimitJpy)
        assertEquals(0, BigDecimal("120").compareTo(f.store.usage(TENANT).committed))
    }

    @Test
    fun `an unpriced project cost blocks that project until an operator acknowledges it`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))
        f.store.recordSpend("coding:a1:1", yenAmount("50"), unpriced = true, projectId = p)

        val b = f.store.projectBudget(p)
        assertEquals(1L, b.unpricedCalls)
        val denied = assertIs<ReserveResult.Denied>(f.store.admission(listOf(TENANT, f.scope(p)), yenAmount("10")))
        assertEquals(BudgetDenial.UNKNOWN_COST, denied.code)
        assertEquals(UnknownCostReason.UNPRICED_IN_SCOPE, denied.costReason)

        val ack = f.store.acknowledgeUnpriced(
            AcknowledgeUnpriced(
                scopeId = f.scope(p), period = b.period, reservationKey = "coding:a1:1", actualAmount = null,
                evidence = "checked", operatorId = "op", reason = "test",
            ),
        )
        assertIs<AcknowledgeResult.Acknowledged>(ack)
        assertEquals(0L, f.store.projectBudget(p).unpricedCalls)
        assertNull(f.store.admission(listOf(TENANT, f.scope(p)), yenAmount("10")))
    }

    @Test
    fun `a reservation keeps its scopes when the limit is removed or changed, and a new path on the removed limit fails closed`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))
        assertEquals(ReserveResult.Reserved, f.store.reserve("inflight", listOf(TENANT, f.scope(p)), yenAmount("300")))
        assertEquals(ReserveResult.Reserved, f.store.reserve("cancel", listOf(TENANT, f.scope(p)), yenAmount("50")))

        // The limit goes away while two reservations are in flight. Both still settle in the project scope they were made in.
        f.store.clearProjectBudget(p)
        f.store.commit("inflight", yenAmount("280"))
        f.store.release("cancel")

        val b = f.store.projectBudget(p)
        assertEquals(microYenOf("280"), b.committedMicroYen)
        assertEquals(0L, b.reservedMicroYen)
        assertNull(b.monthlyLimitJpy)
        // A path built before the removal, used after it, is refused (no limit to check it against): fail closed.
        val denied = assertIs<ReserveResult.Denied>(f.store.reserve("late", listOf(TENANT, f.scope(p)), yenAmount("10")))
        assertEquals(BudgetDenial.BUDGET_POLICY_MISSING, denied.code)

        // A limit set again governs new admissions, with the 280 yen already spent this month.
        f.store.setProjectBudget(p, BigDecimal("300"), BigDecimal("0.8"))
        val over = assertIs<ReserveResult.Denied>(f.store.reserve("again", listOf(TENANT, f.scope(p)), yenAmount("30")))
        assertEquals(BudgetDenial.BUDGET, over.code)
        assertEquals(ReserveResult.Reserved, f.store.reserve("fits", listOf(TENANT, f.scope(p)), yenAmount("20")))
    }

    @Test
    fun `a limit lowered below the reservations in flight keeps them and refuses only new ones`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))
        assertEquals(ReserveResult.Reserved, f.store.reserve("k", listOf(TENANT, f.scope(p)), yenAmount("500")))

        f.store.setProjectBudget(p, BigDecimal("100"), BigDecimal("0.8"))

        assertIs<ReserveResult.Denied>(f.store.reserve("n", listOf(TENANT, f.scope(p)), yenAmount("1")))
        f.store.commit("k", yenAmount("500"))
        assertEquals(microYenOf("500"), f.store.projectBudget(p).committedMicroYen)
    }

    @Test
    fun `the soft threshold is a warning that is reported, and nothing is refused for it`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        f.store.setProjectBudget(p, BigDecimal("1000"), BigDecimal("0.8"))
        assertEquals(ReserveResult.Reserved, f.store.reserve("k", listOf(TENANT, f.scope(p)), yenAmount("850")))

        val b = f.store.projectBudget(p)
        assertTrue(b.softExceeded)
        assertEquals(microYenOf("150"), b.remainingMicroYen)
        assertEquals(ReserveResult.Reserved, f.store.reserve("k2", listOf(TENANT, f.scope(p)), yenAmount("150")))
    }

    @Test
    fun `project limits are whole yen, the soft ratio is above 0 and at most 1 with 3 decimals, and a missing project is null`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val p = f.project("alpha")
        listOf(
            "-1" to "0.8",
            "1000.5" to "0.8",
            "1000" to "0",
            "1000" to "1.5",
            "1000" to "0.8123",
            "1000000000000" to "0.8",
        ).forEach { (limit, ratio) ->
            assertFailsWith<IllegalArgumentException>("$limit / $ratio") {
                f.store.setProjectBudget(p, BigDecimal(limit), BigDecimal(ratio))
            }
        }
        assertNull(f.store.projectBudget(p).monthlyLimitJpy)

        assertNull(f.store.setProjectBudget("01MISSINGPROJECTAAAAAAAAA", BigDecimal("1000"), BigDecimal("0.8")))
        assertNull(f.store.clearProjectBudget("01MISSINGPROJECTAAAAAAAAA"))
        // A zero limit is a real limit: nothing with a cost is admitted on it.
        f.store.setProjectBudget(p, BigDecimal("0"), BigDecimal("1"))
        assertIs<ReserveResult.Denied>(f.store.reserve("z", listOf(TENANT, f.scope(p)), yenAmount("1")))
    }

    @Test
    fun `the tenant scope cannot start with the project or run prefix`() = TestDb().use { db ->
        assertFailsWith<IllegalArgumentException> {
            DbBudgetStore(db.database, "project:x", BudgetLimit(BigDecimal("10000"), currency = "JPY"), clock = CLOCK)
        }
        assertFailsWith<IllegalArgumentException> {
            DbBudgetStore(db.database, "run:x", BudgetLimit(BigDecimal("10000"), currency = "JPY"), clock = CLOCK)
        }
    }

    @Test
    fun `concurrent reservations and bookings on different projects finish without a lock cycle`() = TestDb().use { db ->
        val f = ProjectBudgetFixture(db)
        val projects = (1..4).map { f.project("p$it") }
        projects.forEach { f.store.setProjectBudget(it, BigDecimal("1000000"), BigDecimal("0.8")) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = projects.mapIndexed { i, p ->
                pool.submit<List<ReserveResult>> {
                    (0 until 20).map { k ->
                        val key = "t$i-$k"
                        val r = f.store.reserve(key, listOf(TENANT, f.scope(p)), yenAmount("1"))
                        f.store.commit(key, yenAmount("1"))
                        f.store.recordSpend("s$i-$k", yenAmount("1"), unpriced = false, projectId = p)
                        r
                    }
                }
            }
            val results = futures.flatMap { it.get(120, TimeUnit.SECONDS) }
            assertEquals(80, results.size)
            // Every result is a decision, never a store failure (which would mean a timeout or a lock cycle).
            assertTrue(results.all { it == ReserveResult.Reserved }, "a store failure or a refusal: $results")
            projects.forEach { assertEquals(microYenOf("40"), f.store.projectBudget(it).committedMicroYen) }
        } finally {
            pool.shutdownNow()
        }
    }
}

class BudgetLockOrderTest {
    @Test
    fun `project rows come before run rows, each sorted by scope and period, and the tenant rows are left out`() {
        val pairs = listOf(
            "run:b" to "ALL",
            "project:z" to "2026-10",
            TENANT to "2026-10",
            "run:a" to "ALL",
            "project:a" to "2026-11",
            "project:a" to "2026-10",
            "project:z" to "2026-10",
        )
        assertEquals(
            listOf(
                "project:a" to "2026-10",
                "project:a" to "2026-11",
                "project:z" to "2026-10",
                "run:a" to "ALL",
                "run:b" to "ALL",
            ),
            lockOrder(TENANT, pairs),
        )
    }

    @Test
    fun `the order does not depend on the order the path is given in`() {
        val pairs = listOf("project:b" to "2026-10", "run:a" to "ALL", "project:a" to "2026-10", "run:b" to "ALL")
        val expected = lockOrder(TENANT, pairs)
        assertEquals(expected, lockOrder(TENANT, pairs.reversed()))
        assertEquals(expected, lockOrder(TENANT, listOf(pairs[2], pairs[0], pairs[3], pairs[1])))
    }
}
