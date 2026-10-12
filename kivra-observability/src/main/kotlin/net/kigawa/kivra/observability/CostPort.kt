package net.kigawa.kivra.observability

enum class CostScopeKind { RUN, AGENT, USER }

/** A cost accumulation key. [id] is for the ledger only and is never used as a metric label. */
data class CostScope(val kind: CostScopeKind, val id: String)

/** One model call's usage. [costMicros] is null when the provider price is unknown. */
data class UsageAmount(val inputTokens: Long, val outputTokens: Long, val costMicros: Long?) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0) { "token counts must not be negative" }
        require(costMicros == null || costMicros >= 0) { "cost must not be negative" }
    }
}

/** Accumulated usage for one scope. [unpricedCalls] counts calls whose cost is unknown. */
data class CostTotal(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val knownCostMicros: Long = 0,
    val unpricedCalls: Long = 0,
) {
    operator fun plus(u: UsageAmount): CostTotal = CostTotal(
        inputTokens + u.inputTokens,
        outputTokens + u.outputTokens,
        knownCostMicros + (u.costMicros ?: 0),
        unpricedCalls + if (u.costMicros == null) 1 else 0,
    )
}

/**
 * Port for usage aggregation (Run / Agent / user). Persistence is a later issue; implementations
 * must not drop accumulated values silently.
 */
interface CostLedger {
    fun add(scope: CostScope, usage: UsageAmount)
    fun total(scope: CostScope): CostTotal
}

/** Per-scope limit in micro-currency units; null means no limit configured. */
fun interface BudgetPolicy {
    fun limitMicros(scope: CostScope): Long?
}

object BudgetCheck {
    /**
     * True when the known cost of [scope] exceeds its limit. Unpriced calls are not included, so
     * callers should also look at [CostTotal.unpricedCalls] before trusting a "not exceeded" result.
     */
    fun exceeded(ledger: CostLedger, policy: BudgetPolicy, scope: CostScope): Boolean {
        val limit = policy.limitMicros(scope) ?: return false
        return ledger.total(scope).knownCostMicros > limit
    }
}

/** Non-persistent ledger for tests and local development only. */
class InMemoryCostLedger : CostLedger {
    private val totals = HashMap<CostScope, CostTotal>()

    @Synchronized
    override fun add(scope: CostScope, usage: UsageAmount) {
        totals[scope] = (totals[scope] ?: CostTotal()) + usage
    }

    @Synchronized
    override fun total(scope: CostScope): CostTotal = totals[scope] ?: CostTotal()
}
