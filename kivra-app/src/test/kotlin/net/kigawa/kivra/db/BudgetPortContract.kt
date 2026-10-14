package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.modelgateway.ReserveResult

const val BUDGET_TENANT = "tenant"
const val BUDGET_RUN = "run:r1"

/** A clock whose time is set by the test. Starts at 2026-10-14 12:00 JST. */
class MutableClock(var now: Instant = Instant.parse("2026-10-14T03:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

fun jpy(s: String) = BudgetAmount(BigDecimal(s), "JPY")

/** [replicas] ports over one shared budget state (in-memory: the same instance; DB: separate connection pools). */
class BudgetFixture(val clock: MutableClock, private val replicas: List<BudgetPort>) {
    val port: BudgetPort get() = replicas.first()
    fun replica(i: Int): BudgetPort = replicas[i % replicas.size]
}

/**
 * The contract every [BudgetPort] implementation must pass (Issue #109, B1). Tenant limit is [BUDGET_TENANT] (JPY, soft 80%),
 * run limit is [BUDGET_RUN] (JPY, null = no run policy).
 */
abstract class BudgetPortContract {
    /** Builds a fresh budget and passes it to [block]. Any resources are released when [block] returns. */
    protected abstract fun withBudget(
        tenantHard: String = "10000",
        runHard: String? = "1000",
        replicas: Int = 1,
        clock: MutableClock = MutableClock(),
        block: (BudgetFixture) -> Unit,
    )

    private fun assertBd(expected: String, actual: BigDecimal) =
        assertTrue(BigDecimal(expected).compareTo(actual) == 0, "expected $expected but was $actual")

    private fun denialCode(r: ReserveResult): String? = (r as? ReserveResult.Denied)?.code

    @Test
    fun `parallel reservations from several replicas cannot overspend the cap`() {
        withBudget(replicas = 2) { f ->
            val pool = Executors.newFixedThreadPool(20)
            try {
                val start = CountDownLatch(1)
                val futures = (0 until 20).map { i ->
                    pool.submit<ReserveResult> {
                        start.await(20, TimeUnit.SECONDS)
                        f.replica(i).reserve("par-$i", listOf(BUDGET_TENANT), jpy("3000"))
                    }
                }
                start.countDown()
                val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                assertEquals(3, results.count { it == ReserveResult.Reserved })
                assertTrue(results.all { it == ReserveResult.Reserved || denialCode(it) == BudgetDenial.BUDGET })
                assertBd("9000", f.port.usage(BUDGET_TENANT).reserved)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `a reservation landing exactly on the cap is admitted and the next one is denied`() {
        withBudget { f ->
            val p = f.port
            assertEquals(ReserveResult.Reserved, p.reserve("edge-1", listOf(BUDGET_TENANT), jpy("9999.999999")))
            assertEquals(ReserveResult.Reserved, p.reserve("edge-2", listOf(BUDGET_TENANT), jpy("0.000001")))
            assertEquals(BudgetDenial.BUDGET, denialCode(p.reserve("edge-3", listOf(BUDGET_TENANT), jpy("0.000001"))))
            assertEquals(BudgetDenial.BUDGET, denialCode(p.reserve("edge-4", listOf(BUDGET_TENANT), jpy("10001"))))
        }
    }

    @Test
    fun `unknown price and currency or unit mismatch fail closed as UNKNOWN_COST`() {
        withBudget { f ->
            val p = f.port
            assertEquals(BudgetDenial.UNKNOWN_COST, denialCode(p.reserve("u1", listOf(BUDGET_TENANT), null)))
            assertEquals(BudgetDenial.UNKNOWN_COST, denialCode(p.reserve("u2", listOf(BUDGET_TENANT), BudgetAmount(BigDecimal("1"), "USD"))))
            assertEquals(
                BudgetDenial.UNKNOWN_COST,
                denialCode(p.reserve("u3", listOf(BUDGET_TENANT), BudgetAmount(BigDecimal("1"), "JPY", unit = "tokens"))),
            )
            assertBd("0", p.usage(BUDGET_TENANT).reserved)
            assertNull(p.admission(listOf(BUDGET_TENANT), jpy("1")))
        }
    }

    @Test
    fun `a duplicate key is refused and commit and release are idempotent`() {
        withBudget { f ->
            val p = f.port
            assertEquals(ReserveResult.Reserved, p.reserve("d", listOf(BUDGET_TENANT), jpy("3000")))
            assertTrue(p.reserve("d", listOf(BUDGET_TENANT), jpy("1")) is ReserveResult.Denied)
            p.commit("d", jpy("2000"))
            p.commit("d", jpy("5000"))
            p.release("d")
            assertBd("2000", p.usage(BUDGET_TENANT).committed)
            // A settled key is never reserved again.
            assertTrue(p.reserve("d", listOf(BUDGET_TENANT), jpy("1")) is ReserveResult.Denied)

            assertEquals(ReserveResult.Reserved, p.reserve("r", listOf(BUDGET_TENANT), jpy("1000")))
            p.release("r")
            p.release("r")
            assertBd("0", p.usage(BUDGET_TENANT).reserved)
            assertBd("2000", p.usage(BUDGET_TENANT).committed)
        }
    }

    @Test
    fun `an estimated commit counts as unpriced and blocks later admission`() {
        withBudget { f ->
            val p = f.port
            p.reserve("e", listOf(BUDGET_TENANT), jpy("1200"))
            p.commit("e", null)
            val usage = p.usage(BUDGET_TENANT)
            assertBd("1200", usage.committed)
            assertEquals(1L, usage.unpricedCalls)
            assertEquals(BudgetDenial.UNKNOWN_COST, p.admission(listOf(BUDGET_TENANT), jpy("1"))?.code)
        }
    }

    @Test
    fun `an expired reservation is charged and unpriced, and a late commit trues it up`() {
        withBudget { f ->
            val p = f.port
            p.reserve("l", listOf(BUDGET_TENANT), jpy("3000"))
            f.clock.advance(Duration.ofMinutes(6))
            val lapsed = p.usage(BUDGET_TENANT)
            assertBd("3000", lapsed.committed)
            assertBd("0", lapsed.reserved)
            assertEquals(1L, lapsed.unpricedCalls)
            assertEquals(BudgetDenial.UNKNOWN_COST, p.admission(listOf(BUDGET_TENANT), jpy("1"))?.code)

            p.commit("l", jpy("2500"))
            assertBd("2500", p.usage(BUDGET_TENANT).committed)
            assertEquals(1L, p.usage(BUDGET_TENANT).unpricedCalls)
        }
    }

    @Test
    fun `releasing a lapsed reservation returns its charge and unpriced count`() {
        withBudget { f ->
            val p = f.port
            p.reserve("lr", listOf(BUDGET_TENANT), jpy("3000"))
            f.clock.advance(Duration.ofMinutes(6))
            assertEquals(1L, p.usage(BUDGET_TENANT).unpricedCalls)
            p.release("lr")
            val u = p.usage(BUDGET_TENANT)
            assertBd("0", u.committed)
            assertEquals(0L, u.unpricedCalls)
            assertNull(p.admission(listOf(BUDGET_TENANT), jpy("1")))
        }
    }

    @Test
    fun `run and tenant scopes form a hierarchy and the first exceeded scope is reported`() {
        withBudget { f ->
            val p = f.port
            assertEquals(ReserveResult.Reserved, p.reserve("h1", listOf(BUDGET_TENANT, BUDGET_RUN), jpy("800")))
            val runDenied = p.reserve("h2", listOf(BUDGET_TENANT, BUDGET_RUN), jpy("300")) as ReserveResult.Denied
            assertEquals(BUDGET_RUN, runDenied.scopeId)
            assertEquals(BudgetDenial.BUDGET, runDenied.code)
            assertEquals(ReserveResult.Reserved, p.reserve("h3", listOf(BUDGET_TENANT), jpy("9000")))
            val tenantDenied = p.reserve("h4", listOf(BUDGET_TENANT), jpy("300")) as ReserveResult.Denied
            assertEquals(BUDGET_TENANT, tenantDenied.scopeId)
            assertEquals(BudgetDenial.BUDGET, tenantDenied.code)
        }
    }

    @Test
    fun `a run scope without a policy and an unknown scope are denied as policy missing`() {
        withBudget(runHard = null) { f ->
            assertEquals(
                BudgetDenial.BUDGET_POLICY_MISSING,
                denialCode(f.port.reserve("np1", listOf(BUDGET_TENANT, BUDGET_RUN), jpy("1"))),
            )
        }
        withBudget { f ->
            assertEquals(BudgetDenial.BUDGET_POLICY_MISSING, denialCode(f.port.reserve("np2", listOf(BUDGET_TENANT, "other"), jpy("1"))))
        }
    }

    @Test
    fun `soft limit is reported at 80 percent of the tenant hard limit`() {
        withBudget { f ->
            val p = f.port
            p.reserve("s1", listOf(BUDGET_TENANT), jpy("7999.999999"))
            assertTrue(!p.softExceeded(listOf(BUDGET_TENANT)))
            p.reserve("s2", listOf(BUDGET_TENANT), jpy("0.000001"))
            assertTrue(p.softExceeded(listOf(BUDGET_TENANT)))
        }
    }
}
