package net.kigawa.kivra.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostPortTest {
    private val run = CostScope(CostScopeKind.RUN, "run-1")

    @Test
    fun `usage accumulates and unpriced calls are counted separately`() {
        val ledger = TestOnlyInMemoryCostLedger()
        ledger.add(run, UsageAmount(100, 20, 500))
        ledger.add(run, UsageAmount(10, 5, null))
        ledger.add(run, UsageAmount(1, 1, 250))

        assertEquals(CostTotal(111, 26, 750, 1), ledger.total(run))
        assertEquals(CostTotal(), ledger.total(CostScope(CostScopeKind.AGENT, "none")))
    }

    @Test
    fun `knownCostExceeded is true only above the known-cost limit`() {
        val ledger = TestOnlyInMemoryCostLedger()
        val policy = BudgetPolicy { if (it.kind == CostScopeKind.RUN) 1000 else null }
        ledger.add(run, UsageAmount(0, 0, 1000))
        assertFalse(BudgetCheck.knownCostExceeded(ledger, policy, run))
        ledger.add(run, UsageAmount(0, 0, 1))
        assertTrue(BudgetCheck.knownCostExceeded(ledger, policy, run))
        assertFalse(BudgetCheck.knownCostExceeded(ledger, policy, CostScope(CostScopeKind.USER, "u")))
    }

    @Test
    fun `unpriced calls make verdict UNKNOWN_COST even when knownCostExceeded is false`() {
        val ledger = TestOnlyInMemoryCostLedger()
        val policy = BudgetPolicy { 1000 }
        ledger.add(run, UsageAmount(10, 10, 500))
        ledger.add(run, UsageAmount(10, 10, null))
        assertFalse(BudgetCheck.knownCostExceeded(ledger, policy, run))
        assertEquals(BudgetVerdict.UNKNOWN_COST, BudgetCheck.verdict(ledger, policy, run))
    }

    @Test
    fun `known cost above the limit is EXCEEDED`() {
        val ledger = TestOnlyInMemoryCostLedger()
        val policy = BudgetPolicy { 1000 }
        ledger.add(run, UsageAmount(0, 0, 1001))
        assertEquals(BudgetVerdict.EXCEEDED, BudgetCheck.verdict(ledger, policy, run))
        ledger.add(run, UsageAmount(0, 0, null))
        assertEquals(BudgetVerdict.EXCEEDED, BudgetCheck.verdict(ledger, policy, run))
    }

    @Test
    fun `priced usage within the limit is WITHIN_KNOWN_LIMIT`() {
        val ledger = TestOnlyInMemoryCostLedger()
        val policy = BudgetPolicy { 1000 }
        ledger.add(run, UsageAmount(1, 1, 1000))
        assertEquals(BudgetVerdict.WITHIN_KNOWN_LIMIT, BudgetCheck.verdict(ledger, policy, run))
    }

    @Test
    fun `missing limit or no usage fails closed as UNKNOWN_COST`() {
        val ledger = TestOnlyInMemoryCostLedger()
        val noLimit = BudgetPolicy { null }
        ledger.add(run, UsageAmount(1, 1, 1))
        assertEquals(BudgetVerdict.UNKNOWN_COST, BudgetCheck.verdict(ledger, noLimit, run))
        assertFalse(BudgetCheck.knownCostExceeded(ledger, noLimit, run))
    }

    @Test
    fun `negative token counts and costs are rejected`() {
        val failures = listOf(
            runCatching { UsageAmount(-1, 0, null) },
            runCatching { UsageAmount(0, 0, -1) },
        )
        assertTrue(failures.all { it.isFailure })
    }
}
