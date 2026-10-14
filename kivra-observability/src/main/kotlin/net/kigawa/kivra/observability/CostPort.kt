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

/**
 * Result of [BudgetCheck.verdict]. This is a classification for observation and for callers that
 * must fail closed; none of its values is an approval.
 */
enum class BudgetVerdict {
    /**
     * Known cost is within the limit, no unpriced calls and no incomplete usage. This is NOT an
     * approval to spend. The authoritative admission gate belongs to #35 / #69 (see #73).
     */
    WITHIN_KNOWN_LIMIT,

    /** Known cost already exceeds the limit. Spending must not be authorized. */
    EXCEEDED,

    /**
     * Cost cannot be determined: unpriced calls are present, usage is incomplete, or no limit is
     * configured. Must be treated as not authorized (fail closed), even when known cost is below the limit.
     */
    UNKNOWN_COST,
}

/**
 * 既知コストの観測結果。[complete] が false のとき、[knownMicros] は実際のコストより過小である。
 * [unpricedCalls] は価格不明の呼び出し数で、0 円として扱ってはならない。
 */
data class KnownCostObservation(
    val knownMicros: Long,
    val unpricedCalls: Long,
    val complete: Boolean,
)

object BudgetCheck {
    /**
     * 観測専用。`unpricedCalls > 0` や価格不明の呼び出しを 0 円として扱う。支出の可否の判断に使ってはならない。
     * 可否は `kivra-model-gateway` の `BudgetPort`（ALLOW / DENY / UNKNOWN_COST、fail-closed）が決める。
     *
     * 既知コストのみの比較で、未価格呼出し・部分ストリーム・応答喪失・会計 commit 失敗は考慮しない。
     * 未価格呼出しの有無は [observeKnownCost] の `complete` で確認できる。
     *
     * True when the known cost of [scope] exceeds its limit. Returns false when no limit is configured.
     */
    fun knownCostExceeded(ledger: CostLedger, policy: BudgetPolicy, scope: CostScope): Boolean {
        val limit = policy.limitMicros(scope) ?: return false
        return ledger.total(scope).knownCostMicros > limit
    }

    /**
     * 観測専用。既知コストと未価格呼出し数を返す。[KnownCostObservation.complete] が false の場合、
     * 観測結果は完全ではない（価格不明の呼び出しを含む）。支出の可否の判断に使ってはならない（上記 [knownCostExceeded] と同じ）。
     */
    fun observeKnownCost(ledger: CostLedger, scope: CostScope): KnownCostObservation {
        val total = ledger.total(scope)
        return KnownCostObservation(
            knownMicros = total.knownCostMicros,
            unpricedCalls = total.unpricedCalls,
            complete = total.unpricedCalls == 0L,
        )
    }

    /**
     * 3 値の判定。順序: 上限未設定 → [BudgetVerdict.UNKNOWN_COST] (fail closed)、既知コストが上限超 → [BudgetVerdict.EXCEEDED]、
     * 未価格呼出し (`unpricedCalls > 0`) がある → [BudgetVerdict.UNKNOWN_COST]、それ以外 → [BudgetVerdict.WITHIN_KNOWN_LIMIT]。
     *
     * [BudgetVerdict.WITHIN_KNOWN_LIMIT] は承認ではない。承認(admission)の判定に使うのは #35 / #69 の durable gate であり、
     * 本関数はその代替にならない (#73)。
     */
    fun verdict(ledger: CostLedger, policy: BudgetPolicy, scope: CostScope): BudgetVerdict {
        val limit = policy.limitMicros(scope) ?: return BudgetVerdict.UNKNOWN_COST
        val total = ledger.total(scope)
        return when {
            total.knownCostMicros > limit -> BudgetVerdict.EXCEEDED
            total.unpricedCalls > 0 -> BudgetVerdict.UNKNOWN_COST
            else -> BudgetVerdict.WITHIN_KNOWN_LIMIT
        }
    }
}

/**
 * 開発・テスト専用。本番の台帳ではない（非永続）。本番の admission / 集計には使わない (#35 / #69 / #70 の durable reserve/commit/release の範囲)。
 * モジュール外から参照できないよう internal にしている。
 */
internal class TestOnlyInMemoryCostLedger : CostLedger {
    private val totals = HashMap<CostScope, CostTotal>()

    @Synchronized
    override fun add(scope: CostScope, usage: UsageAmount) {
        totals[scope] = (totals[scope] ?: CostTotal()) + usage
    }

    @Synchronized
    override fun total(scope: CostScope): CostTotal = totals[scope] ?: CostTotal()
}
