package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Limit for one scope, denominated in [currency] and [unit]. Spend in any other currency or unit cannot be added to it. */
data class BudgetLimit(
    val hard: BigDecimal,
    val soft: BigDecimal? = null,
    val currency: String = "USD",
    val unit: String = Cost.UNIT_CURRENCY_UNITS,
)

/**
 * A cost in a known currency and unit, with the price evidence it was computed from. [amount] is an upper-bound
 * estimate when reserving and the actual cost when committing.
 */
data class BudgetAmount(
    val amount: BigDecimal,
    val currency: String = "USD",
    val unit: String = Cost.UNIT_CURRENCY_UNITS,
    val priceRevision: String? = null,
    val model: ModelRef? = null,
) {
    fun sameUnitAs(other: BudgetAmount): Boolean = currency == other.currency && unit == other.unit
}

/**
 * [unpricedCalls] counts calls charged at the estimate because their actual cost was not established (no usage,
 * partial stream, lost response, lapsed reservation, currency/unit mismatch). Any non-zero count makes later
 * admission on the scope UNKNOWN_COST.
 */
data class BudgetUsage(val committed: BigDecimal, val reserved: BigDecimal, val unpricedCalls: Long = 0)

/** Machine-readable denial codes. */
object BudgetDenial {
    const val BUDGET = "BUDGET"
    /** No scope, or a scope without a budget policy. */
    const val BUDGET_POLICY_MISSING = "BUDGET_POLICY_MISSING"
    /** Price unknown, currency/unit not comparable, or unpriced usage already recorded. Never admitted. */
    const val UNKNOWN_COST = "UNKNOWN_COST"
}

sealed interface ReserveResult {
    data object Reserved : ReserveResult
    data class Denied(val scopeId: String, val reason: String, val code: String = BudgetDenial.BUDGET) : ReserveResult
}

/**
 * Reserve / commit / release budget control over a scope hierarchy (design 05 section 7). In-memory: dev/test only,
 * not a production hard limit (no persistence, no multi-instance sharing). Limits are fixed at construction so a caller
 * (agent) has no way to raise its own limit.
 *
 * Admission is fail-closed: a reservation needs a known price in the limit's currency and unit, and no unpriced usage
 * may be recorded on any scope in the path. Unknown scopes are denied.
 */
class BudgetController(
    private val limits: Map<String, BudgetLimit>,
    private val clock: Clock = Clock.systemUTC(),
    private val reservationTtl: Duration = Duration.ofMinutes(5),
) {
    private class Reservation(val scopes: List<String>, val amount: BudgetAmount, val expiresAt: Instant)

    private val committed = HashMap<String, BigDecimal>()
    private val unpriced = HashMap<String, Long>()
    private val reservations = HashMap<String, Reservation>()
    private val settledKeys = HashSet<String>()

    /**
     * Reservations whose TTL elapsed before commit/release. The provider call may still be running and may
     * be billed, so the reserved amount is conservatively charged to `committed` and counted as unpriced (never silently
     * dropped). The key stays here so a late [commit] can true it up when the actual cost is known.
     */
    private val lapsed = HashMap<String, Reservation>()

    @Synchronized
    fun usage(scopeId: String): BudgetUsage {
        purgeExpired()
        return BudgetUsage(committed[scopeId] ?: BigDecimal.ZERO, reservedFor(scopeId), unpriced[scopeId] ?: 0L)
    }

    @Synchronized
    fun canReserve(scopes: List<String>, amount: BudgetAmount?): Boolean = admission(scopes, amount) == null

    /** The admission decision without reserving. Null means admitted. */
    @Synchronized
    fun admission(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied? {
        purgeExpired()
        return denial(scopes, amount)
    }

    /** True when any scope in the path is at or beyond its soft limit. */
    @Synchronized
    fun softExceeded(scopes: List<String>): Boolean {
        purgeExpired()
        return scopes.any { id ->
            val soft = limits[id]?.soft ?: return@any false
            (committed[id] ?: BigDecimal.ZERO) + reservedFor(id) >= soft
        }
    }

    /** Atomically reserve [amount] in every scope. [key] identifies requestId/attemptId. A null [amount] (unknown price) is denied. */
    @Synchronized
    fun reserve(key: String, scopes: List<String>, amount: BudgetAmount?): ReserveResult {
        purgeExpired()
        // A key that is already reserved, lapsed or settled has been (or is being) spent: never hand out a second
        // reservation for it, otherwise a repeated key could reach the provider with no budget accounting.
        if (key in reservations || key in lapsed || key in settledKeys) return ReserveResult.Denied("", "duplicate reservation key")
        denial(scopes, amount)?.let { return it }
        val a = checkNotNull(amount) { "denial() admits only a known amount" }
        reservations[key] = Reservation(scopes, a, clock.instant().plus(reservationTtl))
        return ReserveResult.Reserved
    }

    /**
     * Settle a reservation. [actual] is the actual cost. Null, or an amount in another currency or unit, means the
     * actual cost is not established: the reserved estimate is charged and the scopes record an unpriced call.
     * Idempotent: a second commit for the same key is ignored.
     */
    @Synchronized
    fun commit(key: String, actual: BudgetAmount?) {
        lapsed.remove(key)?.let { r ->
            settledKeys.add(key)
            // The estimate was already charged and counted as unpriced at lapse; only a known actual trues it up.
            actual?.takeIf { r.amount.sameUnitAs(it) }?.let { a ->
                for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) - r.amount.amount + a.amount
            }
            return
        }
        val r = reservations.remove(key) ?: return
        if (!settledKeys.add(key)) return
        val known = actual?.takeIf { r.amount.sameUnitAs(it) }
        if (known == null) for (s in r.scopes) unpriced[s] = (unpriced[s] ?: 0L) + 1
        val charge = known?.amount ?: r.amount.amount
        for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) + charge
    }

    @Synchronized
    fun release(key: String) {
        lapsed.remove(key)?.let { r ->
            settledKeys.add(key)
            for (s in r.scopes) {
                committed[s] = (committed[s] ?: BigDecimal.ZERO) - r.amount.amount // definitively not billed
                unpriced[s]?.let { n -> if (n > 1) unpriced[s] = n - 1 else unpriced.remove(s) }
            }
            return
        }
        if (reservations.remove(key) != null) settledKeys.add(key)
    }

    private fun reservedFor(scopeId: String): BigDecimal =
        reservations.values.filter { scopeId in it.scopes }.fold(BigDecimal.ZERO) { a, r -> a + r.amount.amount }

    private fun denial(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied? {
        if (scopes.isEmpty()) return ReserveResult.Denied("", "no budget scope given", BudgetDenial.BUDGET_POLICY_MISSING)
        val a = amount ?: return ReserveResult.Denied(scopes.first(), "price unknown", BudgetDenial.UNKNOWN_COST)
        for (id in scopes) {
            val limit = limits[id] ?: return ReserveResult.Denied(id, "unknown budget scope", BudgetDenial.BUDGET_POLICY_MISSING)
            if (a.currency != limit.currency || a.unit != limit.unit) {
                return ReserveResult.Denied(id, "cost currency/unit does not match the budget", BudgetDenial.UNKNOWN_COST)
            }
            if ((unpriced[id] ?: 0L) > 0L) return ReserveResult.Denied(id, "unpriced usage recorded in scope", BudgetDenial.UNKNOWN_COST)
        }
        for (id in scopes) {
            val limit = limits.getValue(id)
            val projected = (committed[id] ?: BigDecimal.ZERO) + reservedFor(id) + a.amount
            if (projected > limit.hard) return ReserveResult.Denied(id, "hard limit exceeded", BudgetDenial.BUDGET)
        }
        return null
    }

    private fun purgeExpired() {
        val now = clock.instant()
        val it = reservations.entries.iterator()
        while (it.hasNext()) {
            val (key, r) = it.next()
            if (r.expiresAt <= now) {
                it.remove()
                lapsed[key] = r
                for (s in r.scopes) {
                    committed[s] = (committed[s] ?: BigDecimal.ZERO) + r.amount.amount
                    unpriced[s] = (unpriced[s] ?: 0L) + 1
                }
            }
        }
    }
}
