package net.kigawa.kivra.coding

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.kivra.db.CodingAttemptUsageRepository
import net.kigawa.kivra.db.DbBudgetStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CostConverter
import net.kigawa.kivra.modelgateway.FxConfig

/** Shared by the cost tests: a fixed clock in JST October 2026, and one tenant scope. */
internal const val COST_TENANT = "default"
internal val COST_NOW: Instant = Instant.parse("2026-10-18T03:00:00Z")
internal val COST_CLOCK: Clock = Clock.fixed(COST_NOW, ZoneOffset.UTC)

/** The FX rate every cost test uses: 150 JPY per USD, as of [COST_NOW]. */
internal fun costFx(asOf: Instant = COST_NOW) = FxConfig(usdJpyRate = BigDecimal("150"), fxAsOf = asOf)

internal class CostFixture(val budget: DbBudgetStore, val ledger: DbCodingCostLedger, val usageRows: CodingAttemptUsageRepository)

/**
 * A real budget store and ledger over [db]. The default reserve of 0.50 USD at 150 JPY/USD with 10% FX and 10% tax margins is
 * 90.750000 yen. [hard] is the monthly limit in yen.
 */
internal fun costFixture(
    db: TestDb,
    hard: String = "10000",
    fx: FxConfig? = costFx(),
    reserveUsd: String = "0.50",
    priced: Boolean = true,
): CostFixture {
    val budget = DbBudgetStore(
        database = db.database,
        tenantScope = COST_TENANT,
        tenantLimit = BudgetLimit(BigDecimal(hard), currency = "JPY"),
        clock = COST_CLOCK,
    )
    val usageRows = CodingAttemptUsageRepository(db.database, COST_CLOCK)
    val ledger = DbCodingCostLedger(
        budget = budget,
        usage = usageRows,
        converter = CostConverter(fx ?: FxConfig(), COST_CLOCK),
        reserveUsd = BigDecimal(reserveUsd),
        tenantScope = COST_TENANT,
        modelPriced = { _, _ -> priced },
        clock = COST_CLOCK,
    )
    return CostFixture(budget, ledger, usageRows)
}
