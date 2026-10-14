package net.kigawa.kivra.config

import io.ktor.server.config.ApplicationConfig
import java.math.BigDecimal
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.Cost

/**
 * Monthly external API budget for the single tenant (Issue #109). The hard limit is fixed at startup: nothing in the
 * application can raise it at runtime. Amounts are JPY.
 */
data class BudgetConfig(
    val tenantId: String,
    val monthlyLimitJpy: BigDecimal,
    val softRatio: BigDecimal,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= 64) { "budget tenantId must be a non-blank identifier of at most 64 characters" }
        require(monthlyLimitJpy.signum() > 0) { "budget monthlyLimitJpy must be positive" }
        require(softRatio.signum() > 0 && softRatio <= BigDecimal.ONE) { "budget softRatio must be in (0, 1]" }
    }

    /** The tenant limit: hard = [monthlyLimitJpy], soft = hard * [softRatio], in JPY whole currency units. */
    fun tenantLimit(): BudgetLimit = BudgetLimit(
        hard = monthlyLimitJpy,
        soft = monthlyLimitJpy.multiply(softRatio),
        currency = JPY,
        unit = Cost.UNIT_CURRENCY_UNITS,
    )

    companion object {
        const val JPY = "JPY"
        const val DEFAULT_MONTHLY_LIMIT_JPY = "10000"
        const val DEFAULT_SOFT_RATIO = "0.8"

        fun from(config: ApplicationConfig): BudgetConfig = BudgetConfig(
            tenantId = config.propertyOrNull("kivra.budget.tenantId")?.getString() ?: "default",
            monthlyLimitJpy = BigDecimal(
                config.propertyOrNull("kivra.budget.monthlyLimitJpy")?.getString() ?: DEFAULT_MONTHLY_LIMIT_JPY,
            ),
            softRatio = BigDecimal(config.propertyOrNull("kivra.budget.softRatio")?.getString() ?: DEFAULT_SOFT_RATIO),
        )
    }
}
