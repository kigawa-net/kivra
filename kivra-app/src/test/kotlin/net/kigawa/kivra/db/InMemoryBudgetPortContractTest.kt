package net.kigawa.kivra.db

import java.math.BigDecimal
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit

/** The in-memory [BudgetController] runs the same contract as the durable store. */
class InMemoryBudgetPortContractTest : BudgetPortContract() {
    override fun withBudget(tenantHard: String, runHard: String?, replicas: Int, clock: MutableClock, block: (BudgetFixture) -> Unit) {
        val hard = BigDecimal(tenantHard)
        val limits = buildMap {
            put(BUDGET_TENANT, BudgetLimit(hard, soft = hard.multiply(BigDecimal("0.8")), currency = "JPY"))
            if (runHard != null) put(BUDGET_RUN, BudgetLimit(BigDecimal(runHard), currency = "JPY"))
        }
        val controller = BudgetController(limits, clock)
        block(BudgetFixture(clock, List(replicas) { controller }))
    }
}
