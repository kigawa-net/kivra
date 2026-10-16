package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

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
    /** The budget store could not decide (I/O or database error). Fail closed: the call is not admitted. */
    const val STORE_UNAVAILABLE = "BUDGET_STORE_UNAVAILABLE"
}

/**
 * Why a cost is UNKNOWN_COST. Fixed codes only (no values), so they can be logged. Set where the cost is determined or refused.
 * - [NO_AMOUNT]: no estimate was given to the budget.
 * - [PRICE_MISSING]: no price version is in force for the model at the time.
 * - [FX_UNUSABLE]: the cost cannot be converted to the budget currency (rate absent, out of range, future, or older than the max age).
 * - [CACHE_WRITE_UNPRICED]: cache write tokens without a registry rate for their TTL.
 * - [CURRENCY_MISMATCH]: the cost's currency or unit is not the budget's.
 * - [AMOUNT_INVALID]: negative or too large for the budget's micro-unit.
 * - [UNPRICED_IN_SCOPE]: an earlier call on the scope was recorded as unpriced and not yet acknowledged (ADR 0003).
 * - [UNSPECIFIED]: the budget port denied without a reason.
 */
enum class UnknownCostReason {
    NO_AMOUNT, PRICE_MISSING, FX_UNUSABLE, CACHE_WRITE_UNPRICED, CURRENCY_MISMATCH, AMOUNT_INVALID, UNPRICED_IN_SCOPE, UNSPECIFIED,
}

sealed interface ReserveResult {
    data object Reserved : ReserveResult
    data class Denied(
        val scopeId: String,
        val reason: String,
        val code: String = BudgetDenial.BUDGET,
        /** Set when [code] is UNKNOWN_COST. */
        val costReason: UnknownCostReason? = null,
    ) : ReserveResult
}

/**
 * An operator's release of one unpriced reservation (ADR 0003, Issue #109). It names one reservation by [reservationKey]:
 * there is no release of a whole scope. [actualAmount] is the cost the operator verified; null means the actual cost is not
 * known, and the charged estimate stays as the settled cost. [evidence] is a short, secret-free description of how the
 * cost was checked; it is kept in the audit record and never logged. [operatorId] must come from the authenticated caller,
 * never from a request body.
 */
data class AcknowledgeUnpriced(
    val scopeId: String,
    val period: String,
    val reservationKey: String,
    val actualAmount: BudgetAmount?,
    val evidence: String,
    val operatorId: String,
    val reason: String,
)

sealed interface AcknowledgeResult {
    /** The reservation was released. [actualKnown] is false when the charged estimate was kept as the settled cost. */
    data class Acknowledged(val reservationKey: String, val actualKnown: Boolean) : AcknowledgeResult

    /** The same key was already released. Nothing changed. */
    data object AlreadyAcknowledged : AcknowledgeResult

    /** No such reservation, or it is not in the scope and period the request names. */
    data object NotFound : AcknowledgeResult

    /** The reservation is not unpriced (still reserved, settled with a known cost, or released). */
    data object NotUnpriced : AcknowledgeResult

    /** The request was refused. [reason] is fixed text that never echoes the input. Nothing changed. */
    data class Rejected(val reason: String) : AcknowledgeResult

    /** The store could not decide (I/O, lock or database error). Nothing changed. */
    data object Failure : AcknowledgeResult
}

/** An acknowledged actual may be at most this many times the cost charged for the reservation (rejects absurd values). */
const val ACKNOWLEDGE_MAX_MULTIPLE = 100L

/** Refusal text for a value the store cannot compare with the budget. Fixed, so it never echoes the input. */
const val ACKNOWLEDGE_NOT_COMPARABLE = "actual amount is not comparable with the budget"

/** Refusal text for an actual cost far above the charged cost. Fixed, so it never echoes the input. */
const val ACKNOWLEDGE_TOO_LARGE = "actual amount is too large for the charged cost"

/**
 * The rules every [BudgetPort.acknowledgeUnpriced] implementation applies before it touches state. Returns the fixed refusal
 * text, or null when the request is well formed. Text fields must be non-blank, within their column size (code points), and
 * free of control characters.
 */
fun validateAcknowledgement(r: AcknowledgeUnpriced): String? {
    if (!plainText(r.operatorId, 256)) return "operatorId is invalid"
    if (!plainText(r.reason, 500)) return "reason is invalid"
    if (!plainText(r.evidence, 500)) return "evidence is invalid"
    if (!plainText(r.reservationKey, 128)) return "reservationKey is invalid"
    if (!plainText(r.scopeId, 128)) return "scopeId is invalid"
    if (!plainText(r.period, 16)) return "period is invalid"
    r.actualAmount?.let { if (it.amount.signum() < 0) return "actual amount is negative" }
    return null
}

private fun plainText(value: String, maxCodePoints: Int): Boolean =
    value.isNotBlank() && value.codePointCount(0, value.length) <= maxCodePoints && value.none { it.isISOControl() }

/**
 * The budget operations the Model Gateway needs (design 05 section 7). Implementations: [BudgetController] (in-memory,
 * dev/test only) and the durable store in kivra-app (`DbBudgetStore`). Both must satisfy the same contract.
 */
interface BudgetPort {
    /** Committed spend, open reservations and unpriced call count of [scopeId]. */
    fun usage(scopeId: String): BudgetUsage

    /** The admission decision without reserving. Null means admitted. */
    fun admission(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied?

    /** True when any scope in the path is at or beyond its soft limit. */
    fun softExceeded(scopes: List<String>): Boolean

    /** Atomically reserve [amount] in every scope under [key]. A null [amount] (unknown price) is denied. */
    fun reserve(key: String, scopes: List<String>, amount: BudgetAmount?): ReserveResult

    /** Settle the reservation under [key]. Null [actual] means the actual cost is not established. Idempotent. */
    fun commit(key: String, actual: BudgetAmount?)

    /** Return the reservation under [key] to the budget. Idempotent. */
    fun release(key: String)

    /**
     * Releases one unpriced reservation after an audited operator decision (ADR 0003). The charge is trued up to the verified
     * actual cost (or kept at the estimate when no actual is known) and the unpriced count of each scope in its path drops by
     * one. Per key: the second call returns [AcknowledgeResult.AlreadyAcknowledged] and changes nothing.
     */
    fun acknowledgeUnpriced(request: AcknowledgeUnpriced): AcknowledgeResult
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
) : BudgetPort {
    private class Reservation(
        val scopes: List<String>,
        /** Period each scope is charged to (JST month, or ALL for run scopes), fixed at reserve time. */
        val periods: Map<String, String>,
        val amount: BudgetAmount,
        val expiresAt: Instant,
    )

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

    /** Every key ever reserved. Kept so [acknowledgeUnpriced] can name a settled reservation. Dev/test only: never pruned. */
    private val history = HashMap<String, Reservation>()

    /** Keys that currently count as unpriced, with the cost charged for them: the estimate, or a late known actual. */
    private val unpricedCharged = HashMap<String, BigDecimal>()

    /** Keys released by [acknowledgeUnpriced]. */
    private val acknowledged = HashSet<String>()

    @Synchronized
    override fun usage(scopeId: String): BudgetUsage {
        purgeExpired()
        return BudgetUsage(committed[scopeId] ?: BigDecimal.ZERO, reservedFor(scopeId), unpriced[scopeId] ?: 0L)
    }

    @Synchronized
    fun canReserve(scopes: List<String>, amount: BudgetAmount?): Boolean = admission(scopes, amount) == null

    /** The admission decision without reserving. Null means admitted. */
    @Synchronized
    override fun admission(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied? {
        purgeExpired()
        return denial(scopes, amount)
    }

    /** True when any scope in the path is at or beyond its soft limit. */
    @Synchronized
    override fun softExceeded(scopes: List<String>): Boolean {
        purgeExpired()
        return scopes.any { id ->
            val soft = limits[id]?.soft ?: return@any false
            (committed[id] ?: BigDecimal.ZERO) + reservedFor(id) >= soft
        }
    }

    /** Atomically reserve [amount] in every scope. [key] identifies requestId/attemptId. A null [amount] (unknown price) is denied. */
    @Synchronized
    override fun reserve(key: String, scopes: List<String>, amount: BudgetAmount?): ReserveResult {
        purgeExpired()
        // A key that is already reserved, lapsed or settled has been (or is being) spent: never hand out a second
        // reservation for it, otherwise a repeated key could reach the provider with no budget accounting.
        if (key in reservations || key in lapsed || key in settledKeys) return ReserveResult.Denied("", "duplicate reservation key")
        denial(scopes, amount)?.let { return it }
        val a = checkNotNull(amount) { "denial() admits only a known amount" }
        val now = clock.instant()
        val r = Reservation(scopes, periodsAt(scopes, now), a, now.plus(reservationTtl))
        reservations[key] = r
        history[key] = r
        return ReserveResult.Reserved
    }

    /**
     * Settle a reservation. [actual] is the actual cost. Null, or an amount in another currency or unit, means the
     * actual cost is not established: the reserved estimate is charged and the scopes record an unpriced call.
     * Idempotent: a second commit for the same key is ignored.
     */
    @Synchronized
    override fun commit(key: String, actual: BudgetAmount?) {
        // Expire first, as every operation does (the durable store does the same): a commit after the TTL is a late commit.
        purgeExpired()
        lapsed.remove(key)?.let { r ->
            settledKeys.add(key)
            // The estimate was already charged and counted as unpriced at lapse; only a known actual trues it up.
            // The key stays unpriced (the count is not reduced here): an operator must acknowledge it.
            actual?.takeIf { r.amount.sameUnitAs(it) }?.let { a ->
                for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) - r.amount.amount + a.amount
                unpricedCharged[key] = a.amount
            }
            return
        }
        val r = reservations.remove(key) ?: return
        if (!settledKeys.add(key)) return
        val known = actual?.takeIf { r.amount.sameUnitAs(it) }
        val charge = known?.amount ?: r.amount.amount
        if (known == null) {
            for (s in r.scopes) unpriced[s] = (unpriced[s] ?: 0L) + 1
            unpricedCharged[key] = charge
        }
        for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) + charge
    }

    @Synchronized
    override fun release(key: String) {
        purgeExpired()
        lapsed.remove(key)?.let { r ->
            settledKeys.add(key)
            for (s in r.scopes) {
                committed[s] = (committed[s] ?: BigDecimal.ZERO) - r.amount.amount // definitively not billed
                unpriced[s]?.let { n -> if (n > 1) unpriced[s] = n - 1 else unpriced.remove(s) }
            }
            unpricedCharged.remove(key)
            return
        }
        if (reservations.remove(key) != null) settledKeys.add(key)
    }

    @Synchronized
    override fun acknowledgeUnpriced(request: AcknowledgeUnpriced): AcknowledgeResult {
        purgeExpired()
        validateAcknowledgement(request)?.let { return AcknowledgeResult.Rejected(it) }
        val key = request.reservationKey
        val r = history[key] ?: return AcknowledgeResult.NotFound
        if (request.scopeId !in r.scopes || r.periods[request.scopeId] != request.period) return AcknowledgeResult.NotFound
        if (key in acknowledged) return AcknowledgeResult.AlreadyAcknowledged
        val charged = unpricedCharged[key] ?: return AcknowledgeResult.NotUnpriced
        val actual = request.actualAmount
        if (actual != null) {
            if (!r.amount.sameUnitAs(actual)) return AcknowledgeResult.Rejected(ACKNOWLEDGE_NOT_COMPARABLE)
            if (actual.amount > charged * BigDecimal(ACKNOWLEDGE_MAX_MULTIPLE)) return AcknowledgeResult.Rejected(ACKNOWLEDGE_TOO_LARGE)
        }
        val settled = actual?.amount ?: charged
        for (s in r.scopes) {
            committed[s] = (committed[s] ?: BigDecimal.ZERO) + settled - charged
            unpriced[s]?.let { n -> if (n > 1) unpriced[s] = n - 1 else unpriced.remove(s) }
        }
        unpricedCharged.remove(key)
        acknowledged.add(key)
        return AcknowledgeResult.Acknowledged(key, actualKnown = actual != null)
    }

    private fun reservedFor(scopeId: String): BigDecimal =
        reservations.values.filter { scopeId in it.scopes }.fold(BigDecimal.ZERO) { a, r -> a + r.amount.amount }

    private fun denial(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied? {
        if (scopes.isEmpty()) return ReserveResult.Denied("", "no budget scope given", BudgetDenial.BUDGET_POLICY_MISSING)
        val a = amount ?: return ReserveResult.Denied(scopes.first(), "price unknown", BudgetDenial.UNKNOWN_COST, UnknownCostReason.NO_AMOUNT)
        for (id in scopes) {
            val limit = limits[id] ?: return ReserveResult.Denied(id, "unknown budget scope", BudgetDenial.BUDGET_POLICY_MISSING)
            if (a.currency != limit.currency || a.unit != limit.unit) {
                return ReserveResult.Denied(id, "cost currency/unit does not match the budget", BudgetDenial.UNKNOWN_COST, UnknownCostReason.CURRENCY_MISMATCH)
            }
            if ((unpriced[id] ?: 0L) > 0L) {
                return ReserveResult.Denied(id, "unpriced usage recorded in scope", BudgetDenial.UNKNOWN_COST, UnknownCostReason.UNPRICED_IN_SCOPE)
            }
        }
        for (id in scopes) {
            val limit = limits.getValue(id)
            val projected = (committed[id] ?: BigDecimal.ZERO) + reservedFor(id) + a.amount
            if (projected > limit.hard) return ReserveResult.Denied(id, "hard limit exceeded", BudgetDenial.BUDGET)
        }
        return null
    }

    private fun periodsAt(scopes: List<String>, now: Instant): Map<String, String> {
        val month = YearMonth.from(now.atZone(JST)).toString()
        return scopes.associateWith { if (it.startsWith(RUN_PREFIX)) PERIOD_ALL else month }
    }

    private fun purgeExpired() {
        val now = clock.instant()
        val it = reservations.entries.iterator()
        while (it.hasNext()) {
            val (key, r) = it.next()
            if (r.expiresAt <= now) {
                it.remove()
                lapsed[key] = r
                unpricedCharged[key] = r.amount.amount
                for (s in r.scopes) {
                    committed[s] = (committed[s] ?: BigDecimal.ZERO) + r.amount.amount
                    unpriced[s] = (unpriced[s] ?: 0L) + 1
                }
            }
        }
    }

    private companion object {
        const val RUN_PREFIX = "run:"
        const val PERIOD_ALL = "ALL"
        val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    }
}
