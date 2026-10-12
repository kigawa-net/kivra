package net.kigawa.kivra.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostPortTest {
    private val run = CostScope(CostScopeKind.RUN, "run-1")

    @Test
    fun `usage accumulates and unpriced calls are counted separately`() {
        val ledger = InMemoryCostLedger()
        ledger.add(run, UsageAmount(100, 20, 500))
        ledger.add(run, UsageAmount(10, 5, null))
        ledger.add(run, UsageAmount(1, 1, 250))

        assertEquals(CostTotal(111, 26, 750, 1), ledger.total(run))
        assertEquals(CostTotal(), ledger.total(CostScope(CostScopeKind.AGENT, "none")))
    }

    @Test
    fun `budget is exceeded only above the known-cost limit`() {
        val ledger = InMemoryCostLedger()
        val policy = BudgetPolicy { if (it.kind == CostScopeKind.RUN) 1000 else null }
        ledger.add(run, UsageAmount(0, 0, 1000))
        assertFalse(BudgetCheck.exceeded(ledger, policy, run))
        ledger.add(run, UsageAmount(0, 0, 1))
        assertTrue(BudgetCheck.exceeded(ledger, policy, run))
        assertFalse(BudgetCheck.exceeded(ledger, policy, CostScope(CostScopeKind.USER, "u")))
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
