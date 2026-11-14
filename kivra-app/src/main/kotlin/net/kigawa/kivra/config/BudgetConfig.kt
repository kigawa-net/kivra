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
    /** The tenant's daily hard limit (JST calendar day), or null for none. It applies together with the monthly limit. */
    val dailyLimitJpy: BigDecimal? = null,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= 64) { "budget tenantId must be a non-blank identifier of at most 64 characters" }
        require(monthlyLimitJpy.signum() > 0) { "budget monthlyLimitJpy must be positive" }
        require(softRatio.signum() > 0 && softRatio <= BigDecimal.ONE) { "budget softRatio must be in (0, 1]" }
        require(dailyLimitJpy == null || dailyLimitJpy.signum() > 0) { "budget dailyLimitJpy must be positive" }
    }

    /** The daily limit as a budget: hard = [dailyLimitJpy], soft = hard * [softRatio]. Null when no daily limit is configured. */
    fun dailyLimit(): BudgetLimit? = dailyLimitJpy?.let {
        BudgetLimit(hard = it, soft = it.multiply(softRatio), currency = JPY, unit = Cost.UNIT_CURRENCY_UNITS)
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
            dailyLimitJpy = dailyLimitFrom(config),
        )

        /** Unset or blank means no daily limit. A value that is not a positive decimal fails the startup; the message names no value. */
        private fun dailyLimitFrom(config: ApplicationConfig): BigDecimal? {
            val raw = config.propertyOrNull("kivra.budget.dailyLimitJpy")?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val value = try { BigDecimal(raw) } catch (e: NumberFormatException) {
                throw IllegalStateException("KIVRA_BUDGET_TENANT_DAILY_LIMIT_JPY must be a positive decimal")
            }
            check(value.signum() > 0) { "KIVRA_BUDGET_TENANT_DAILY_LIMIT_JPY must be a positive decimal" }
            return value
        }
    }
}
