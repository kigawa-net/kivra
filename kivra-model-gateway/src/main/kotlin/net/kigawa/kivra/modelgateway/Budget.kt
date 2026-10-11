package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class BudgetLimit(val hard: BigDecimal, val soft: BigDecimal? = null)

data class BudgetUsage(val committed: BigDecimal, val reserved: BigDecimal)

sealed interface ReserveResult {
    data object Reserved : ReserveResult
    data class Denied(val scopeId: String, val reason: String) : ReserveResult
}

/**
 * Reserve / commit / release budget control over a scope hierarchy (design 05 section 7). In-memory;
 * persistence and multi-instance sharing are out of scope, so this is NOT a production hard limit. Limits are fixed at construction so a caller
 * (agent) has no way to raise its own limit. Unknown scopes are denied (fail closed).
 */
class BudgetController(
    private val limits: Map<String, BudgetLimit>,
    private val clock: Clock = Clock.systemUTC(),
    private val reservationTtl: Duration = Duration.ofMinutes(5),
) {
    private class Reservation(val scopes: List<String>, val amount: BigDecimal, val expiresAt: Instant)

    private val committed = HashMap<String, BigDecimal>()
    private val reservations = HashMap<String, Reservation>()
    private val settledKeys = HashSet<String>()

    /**
     * Reservations whose TTL elapsed before commit/release. The provider call may still be running and may
     * be billed, so the reserved amount is conservatively charged to `committed` (never silently dropped)
     * and the key stays here so a late [commit] can true it up to the actual cost.
     */
    private val lapsed = HashMap<String, Reservation>()

    @Synchronized
    fun usage(scopeId: String): BudgetUsage {
        purgeExpired()
        return BudgetUsage(committed[scopeId] ?: BigDecimal.ZERO, reservedFor(scopeId))
    }

    @Synchronized
    fun canReserve(scopes: List<String>, amount: BigDecimal): Boolean {
        purgeExpired()
        return denial(scopes, amount) == null
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

    /** Atomically reserve [amount] in every scope. [key] identifies requestId/attemptId. */
    @Synchronized
    fun reserve(key: String, scopes: List<String>, amount: BigDecimal): ReserveResult {
        purgeExpired()
        // A key that is already reserved, lapsed or settled has been (or is being) spent: never hand out a second
        // reservation for it, otherwise a repeated key could reach the provider with no budget accounting.
        if (key in reservations || key in lapsed || key in settledKeys) return ReserveResult.Denied("", "duplicate reservation key")
        denial(scopes, amount)?.let { return it }
        reservations[key] = Reservation(scopes, amount, clock.instant().plus(reservationTtl))
        return ReserveResult.Reserved
    }

    /** Settle with the actual cost, or with the reserved amount when [actual] is null (estimated). Idempotent. */
    @Synchronized
    fun commit(key: String, actual: BigDecimal?) {
        lapsed.remove(key)?.let { r ->
            settledKeys.add(key)
            if (actual != null) for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) - r.amount + actual
            return
        }
        val r = reservations.remove(key) ?: return
        if (!settledKeys.add(key)) return
        val amount = actual ?: r.amount
        for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) + amount
    }

    @Synchronized
    fun release(key: String) {
        lapsed.remove(key)?.let { r ->
            settledKeys.add(key)
            for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) - r.amount // definitively not billed
            return
        }
        if (reservations.remove(key) != null) settledKeys.add(key)
    }

    private fun reservedFor(scopeId: String): BigDecimal =
        reservations.values.filter { scopeId in it.scopes }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }

    private fun denial(scopes: List<String>, amount: BigDecimal): ReserveResult.Denied? {
        if (scopes.isEmpty()) return ReserveResult.Denied("", "no budget scope given")
        for (id in scopes) {
            val limit = limits[id] ?: return ReserveResult.Denied(id, "unknown budget scope")
            val projected = (committed[id] ?: BigDecimal.ZERO) + reservedFor(id) + amount
            if (projected > limit.hard) return ReserveResult.Denied(id, "hard limit exceeded")
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
                for (s in r.scopes) committed[s] = (committed[s] ?: BigDecimal.ZERO) + r.amount
            }
        }
    }
}
