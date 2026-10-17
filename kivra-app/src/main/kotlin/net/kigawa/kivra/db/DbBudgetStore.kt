package net.kigawa.kivra.db

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_MAX_MULTIPLE
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_NOT_COMPARABLE
import net.kigawa.kivra.modelgateway.ACKNOWLEDGE_TOO_LARGE
import net.kigawa.kivra.modelgateway.AcknowledgeResult
import net.kigawa.kivra.modelgateway.AcknowledgeUnpriced
import net.kigawa.kivra.modelgateway.BudgetAmount
import net.kigawa.kivra.modelgateway.BudgetDenial
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.BudgetPort
import net.kigawa.kivra.modelgateway.BudgetUsage
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.UnknownCostReason
import net.kigawa.kivra.modelgateway.ReserveResult
import net.kigawa.kivra.modelgateway.validateAcknowledgement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Durable budget ledger in Kivra MariaDB (Issue #109, B1). Same semantics as the in-memory [net.kigawa.kivra.modelgateway.BudgetController],
 * but the state is shared by every replica that uses the same database.
 *
 * Scopes: [tenantScope] (monthly hard limit, JST calendar month) and any `run:<id>` scope (lifetime limit [runLimit], optional).
 * A reservation path must contain [tenantScope]. Amounts are stored as BIGINT micro-yen; costs are rounded up.
 *
 * Locking and atomicity:
 * - Every operation first takes `SELECT ... FOR UPDATE` on all `budget_periods` rows of [tenantScope], in one transaction.
 *   Only the tenant scope and this order are used for locking across replicas, so no two operations can wait on each other
 *   in a cycle. Run rows are locked after the tenant rows, sorted by scope id.
 * - Period rows are created in their own short transaction before the locked transaction, so an insert never waits on
 *   the locked transaction. A concurrent creation of the same row is a duplicate key and is ignored.
 * - A reservation is inserted in the same transaction as the check that admitted it. An exception rolls back the
 *   whole operation: no partial reservation remains. Any failure of reserve / admission / softExceeded is a denial
 *   with [BudgetDenial.STORE_UNAVAILABLE] (fail closed).
 *
 * Reservation TTL: an expired RESERVED row is moved to LAPSED at the start of each operation (lazy, no background thread).
 * LAPSED charges the estimate to the scope and counts it as unpriced. A later commit trues up the amount; a release
 * returns it. Usage always reads the current JST month for the tenant scope; a reservation keeps the period it was made in.
 *
 * UNPRICED: a reservation whose cost was never established (committed without an actual, or lapsed). Its unpriced count stays
 * on every scope of its path until an operator releases it with [acknowledgeUnpriced] (ADR 0003). The charged amount is kept
 * in `budget_reservations.amount_micro_yen`, so a release computes the correction from what is actually charged.
 *
 * Limits are fixed at construction. The database user only needs DML: nothing here issues DDL.
 */
class DbBudgetStore(
    private val database: Database,
    private val tenantScope: String,
    private val tenantLimit: BudgetLimit,
    private val runLimit: BudgetLimit? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val reservationTtl: Duration = Duration.ofMinutes(5),
) : BudgetPort {
    init {
        require(tenantScope.isNotBlank() && !tenantScope.startsWith(RUN_PREFIX)) { "tenantScope must not be blank or start with run:" }
        requireJpy(tenantLimit, "tenant")
        runLimit?.let { requireJpy(it, "run") }
        require(reservationTtl.isPositive) { "reservationTtl must be positive" }
    }

    private val logger = LoggerFactory.getLogger(DbBudgetStore::class.java)

    /** Test seam: runs inside the reserve transaction after the reservation rows are written. A throw must roll all of it back. */
    internal var afterReservationWrite: (() -> Unit)? = null

    /** Test seam: runs inside the acknowledgement transaction after the rows are written. A throw must roll all of it back. */
    internal var afterAcknowledgeWrite: (() -> Unit)? = null

    override fun usage(scopeId: String): BudgetUsage = transaction(database) {
        val now = clock.instant()
        lockTenant()
        expireDue(now)
        val row = periodRow(scopeId, periodFor(scopeId, now))
        BudgetUsage(
            committed = micro(row?.committed ?: 0L),
            reserved = micro(reservedFor(scopeId, periodFor(scopeId, now))),
            unpricedCalls = row?.unpriced ?: 0L,
        )
    }

    override fun admission(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied? {
        preDenial(scopes, amount)?.let { return it }
        val micro = microOf(amount!!)
        return try {
            transaction<ReserveResult.Denied?>(database) {
                val now = clock.instant()
                lockTenant()
                expireDue(now)
                admissionDenial(scopes, micro, now)
            }
        } catch (e: Exception) {
            storeUnavailable(scopes)
        }
    }

    override fun softExceeded(scopes: List<String>): Boolean = try {
        transaction(database) {
            val now = clock.instant()
            lockTenant()
            expireDue(now)
            var exceeded = false
            for (id in scopes) {
                val soft = limitFor(id)?.soft ?: continue
                val used = Math.addExact(periodRow(id, periodFor(id, now))?.committed ?: 0L, reservedFor(id, periodFor(id, now)))
                if (used >= microFloor(soft)) exceeded = true
            }
            exceeded
        }
    } catch (e: Exception) {
        true // unknown state: report soft exceeded (conservative)
    }

    override fun reserve(key: String, scopes: List<String>, amount: BudgetAmount?): ReserveResult {
        preDenial(scopes, amount)?.let { return it }
        val micro = microOf(amount!!)
        return try {
            val now = clock.instant()
            ensurePeriodRows(scopes, now)
            transaction<ReserveResult>(database) {
                lockTenant()
                expireDue(clock.instant())
                if (reservationExists(key)) return@transaction ReserveResult.Denied("", "duplicate reservation key", BudgetDenial.BUDGET)
                val denial = admissionDenial(scopes, micro, now)
                if (denial != null) return@transaction denial
                val createdAt = utcNow(clock)
                execUpdate(
                    "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                    key, micro, RESERVED, createdAt.plusNanos(reservationTtl.toNanos()), createdAt,
                )
                for (id in scopes) {
                    execUpdate(
                        "INSERT INTO budget_reservation_scopes (reservation_key, scope_id, period) VALUES (?, ?, ?)",
                        key, id, periodFor(id, now),
                    )
                }
                afterReservationWrite?.invoke()
                ReserveResult.Reserved
            }
        } catch (e: Exception) {
            storeUnavailable(scopes)
        }
    }

    override fun commit(key: String, actual: BudgetAmount?) {
        transaction(database) {
            lockTenant()
            expireDue(clock.instant())
            val r = loadReservation(key) ?: return@transaction
            val known = actual?.let { knownMicro(it) }
            when (r.state) {
                RESERVED -> {
                    lockRunRows(r.scopes)
                    for ((id, period) in r.scopes) {
                        addToPeriod(id, period, known ?: r.amount, unpriced = if (known == null) 1L else 0L)
                    }
                    settle(key, if (known == null) UNPRICED else COMMITTED)
                }
                // The estimate was charged and counted as unpriced at lapse. A known actual trues up the amount only; the
                // unpriced count stays (a late actual does not clear it), so the reservation remains UNPRICED until released.
                LAPSED -> {
                    lockRunRows(r.scopes)
                    if (known != null) {
                        for ((id, period) in r.scopes) addToPeriod(id, period, known - r.amount, unpriced = 0L)
                        execUpdate("UPDATE budget_reservations SET amount_micro_yen = ? WHERE reservation_key = ?", known, key)
                    }
                    settle(key, UNPRICED)
                }
                else -> Unit // COMMITTED / RELEASED: idempotent
            }
        }
    }

    override fun release(key: String) {
        transaction(database) {
            lockTenant()
            expireDue(clock.instant())
            val r = loadReservation(key) ?: return@transaction
            when (r.state) {
                RESERVED -> settle(key, RELEASED)
                LAPSED -> {
                    lockRunRows(r.scopes)
                    // Definitively not billed: undo the lapse charge and its unpriced count.
                    for ((id, period) in r.scopes) addToPeriod(id, period, -r.amount, unpriced = -1L)
                    settle(key, RELEASED)
                }
                else -> Unit
            }
        }
    }

    /**
     * Books spend that has already happened, outside the reserve / commit cycle (ADR 0010 addendum 4: the CLI runtime's cost). The
     * row is a settled reservation under [key]: COMMITTED, or UNPRICED when [unpriced] (the cost is not verified and the scope is
     * UNKNOWN_COST until an operator acknowledges it). Only the tenant scope is charged, in its current JST month.
     *
     * There is no hard-limit check here: the money is already spent, so refusing to record it would hide it. Admission is
     * checked separately, before a claim ([admission]). Keyed by [key]: a second call with the same key changes nothing and
     * returns [SpendResult.ALREADY_RECORDED]. Any failure is [SpendResult.FAILURE] with nothing written.
     */
    fun recordSpend(key: String, amount: BudgetAmount, unpriced: Boolean): SpendResult {
        val micro = (if (amount.currency == tenantLimit.currency && amount.unit == tenantLimit.unit) microOrNull(amount) else null)
            ?: return SpendResult.FAILURE
        ensurePeriodRows(listOf(tenantScope), clock.instant())
        return try {
            transaction<SpendResult>(database) {
                lockTenant()
                expireDue(clock.instant())
                if (reservationExists(key)) return@transaction SpendResult.ALREADY_RECORDED
                val now = clock.instant()
                val period = periodFor(tenantScope, now)
                val at = utcNow(clock)
                execUpdate(
                    "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at, settled_at) VALUES (?, ?, ?, ?, ?, ?)",
                    key, micro, if (unpriced) UNPRICED else COMMITTED, at, at, at,
                )
                execUpdate(
                    "INSERT INTO budget_reservation_scopes (reservation_key, scope_id, period) VALUES (?, ?, ?)",
                    key, tenantScope, period,
                )
                addToPeriod(tenantScope, period, micro, unpriced = if (unpriced) 1L else 0L)
                SpendResult.RECORDED
            }
        } catch (e: Exception) {
            if (e.isDuplicateKey()) SpendResult.ALREADY_RECORDED else SpendResult.FAILURE
        }
    }

    /**
     * Releases one UNPRICED (or LAPSED) reservation after an operator's audited decision (ADR 0003). In one transaction, under the
     * tenant lock: the charge moves to the verified actual (or stays at the charged amount when no actual is known), the unpriced
     * count of each scope in the path drops by one, and the audit row is inserted. Any failure rolls all of it back and returns
     * [AcknowledgeResult.Failure]. The audit row's evidence is never logged; the log has only the operator, a key prefix, and the result kind.
     */
    override fun acknowledgeUnpriced(request: AcknowledgeUnpriced): AcknowledgeResult {
        validateAcknowledgement(request)?.let { return AcknowledgeResult.Rejected(it) }
        val result = try {
            transaction<AcknowledgeResult>(database) {
                lockTenant()
                expireDue(clock.instant())
                val r = loadReservation(request.reservationKey) ?: return@transaction AcknowledgeResult.NotFound
                if (r.scopes.none { it.first == request.scopeId && it.second == request.period }) return@transaction AcknowledgeResult.NotFound
                if (r.state == ACKNOWLEDGED) return@transaction AcknowledgeResult.AlreadyAcknowledged
                if (r.state != LAPSED && r.state != UNPRICED) return@transaction AcknowledgeResult.NotUnpriced
                val actualMicro: Long? = request.actualAmount?.let { a ->
                    val m = knownMicro(a) ?: return@transaction AcknowledgeResult.Rejected(ACKNOWLEDGE_NOT_COMPARABLE)
                    if (m > Math.multiplyExact(r.amount, ACKNOWLEDGE_MAX_MULTIPLE)) return@transaction AcknowledgeResult.Rejected(ACKNOWLEDGE_TOO_LARGE)
                    m
                }
                val settled = actualMicro ?: r.amount
                lockRunRows(r.scopes)
                for ((id, period) in r.scopes) {
                    // An unpriced count must exist for every scope of an UNPRICED or LAPSED reservation. Otherwise the data is
                    // inconsistent: refuse (rolls back) rather than drive a counter negative.
                    check((periodRow(id, period)?.unpriced ?: 0L) >= 1L) { "unpriced count is inconsistent" }
                    addToPeriod(id, period, settled - r.amount, unpriced = -1L)
                }
                execUpdate(
                    "UPDATE budget_reservations SET state = ?, amount_micro_yen = ?, settled_at = ? WHERE reservation_key = ?",
                    ACKNOWLEDGED, settled, utcNow(clock), request.reservationKey,
                )
                execUpdate(
                    """
                    INSERT INTO budget_acknowledgements
                        (reservation_key, scope_id, period, operator_id, reason, evidence, charged_before_micro_yen, actual_micro_yen, actual_known, acknowledged_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    request.reservationKey, request.scopeId, request.period, request.operatorId, request.reason, request.evidence,
                    r.amount, settled, actualMicro != null, utcNow(clock),
                )
                afterAcknowledgeWrite?.invoke()
                AcknowledgeResult.Acknowledged(request.reservationKey, actualKnown = actualMicro != null)
            }
        } catch (e: Exception) {
            if (e.isDuplicateKey()) AcknowledgeResult.AlreadyAcknowledged else AcknowledgeResult.Failure
        }
        logger.info(
            "budget acknowledgement: operator={} reservation={} result={}",
            request.operatorId, request.reservationKey.take(8), result::class.simpleName,
        )
        return result
    }

    // ---- admission --------------------------------------------------------------------------------------------------

    /** Checks that do not need the database: scope presence, price known, currency/unit, limits configured. */
    private fun preDenial(scopes: List<String>, amount: BudgetAmount?): ReserveResult.Denied? {
        if (scopes.isEmpty()) return ReserveResult.Denied("", "no budget scope given", BudgetDenial.BUDGET_POLICY_MISSING)
        if (scopes.distinct().size != scopes.size || tenantScope !in scopes) {
            return ReserveResult.Denied(scopes.first(), "path must contain the tenant scope once", BudgetDenial.BUDGET_POLICY_MISSING)
        }
        val a = amount ?: return ReserveResult.Denied(scopes.first(), "price unknown", BudgetDenial.UNKNOWN_COST, UnknownCostReason.NO_AMOUNT)
        if (microOrNull(a) == null) {
            return ReserveResult.Denied(scopes.first(), "cost is negative or too large", BudgetDenial.UNKNOWN_COST, UnknownCostReason.AMOUNT_INVALID)
        }
        for (id in scopes) {
            val limit = limitFor(id) ?: return ReserveResult.Denied(id, "unknown budget scope", BudgetDenial.BUDGET_POLICY_MISSING)
            if (a.currency != limit.currency || a.unit != limit.unit) {
                return ReserveResult.Denied(
                    id, "cost currency/unit does not match the budget", BudgetDenial.UNKNOWN_COST, UnknownCostReason.CURRENCY_MISMATCH,
                )
            }
        }
        return null
    }

    /** Must be called inside a transaction that holds the tenant lock. Missing period rows count as zero. */
    private fun Transaction.admissionDenial(scopes: List<String>, micro: Long, now: Instant): ReserveResult.Denied? {
        for (id in scopes) {
            val row = periodRow(id, periodFor(id, now))
            if ((row?.unpriced ?: 0L) > 0L) {
                return ReserveResult.Denied(id, "unpriced usage recorded in scope", BudgetDenial.UNKNOWN_COST, UnknownCostReason.UNPRICED_IN_SCOPE)
            }
        }
        for (id in scopes) {
            val hard = microFloor(limitFor(id)!!.hard)
            val period = periodFor(id, now)
            val projected = Math.addExact(
                Math.addExact(periodRow(id, period)?.committed ?: 0L, reservedFor(id, period)),
                micro,
            )
            if (projected > hard) return ReserveResult.Denied(id, "hard limit exceeded", BudgetDenial.BUDGET)
        }
        return null
    }

    private fun storeUnavailable(scopes: List<String>) =
        ReserveResult.Denied(scopes.firstOrNull().orEmpty(), "budget store unavailable", BudgetDenial.STORE_UNAVAILABLE)

    // ---- period rows and locks ---------------------------------------------------------------------------------------

    /** Creates missing period rows in their own transactions, so the locked transaction never waits on an insert. */
    private fun ensurePeriodRows(scopes: List<String>, now: Instant) {
        for (id in scopes) {
            val period = periodFor(id, now)
            val hard = microFloor(limitFor(id)!!.hard)
            try {
                transaction(database) {
                    execUpdate(
                        "INSERT INTO budget_periods (scope_id, period, hard_micro_yen, committed_micro_yen, unpriced_calls, created_at) VALUES (?, ?, ?, 0, 0, ?)",
                        id, period, hard, utcNow(clock),
                    )
                }
            } catch (e: Exception) {
                if (!e.isDuplicateKey()) throw e // a concurrent creation of the same row is fine
            }
        }
    }

    /** Locks every tenant period row (all months) in period order. Every operation takes this lock first. */
    private fun Transaction.lockTenant() {
        execQuery("SELECT period FROM budget_periods WHERE scope_id = ? ORDER BY period FOR UPDATE", tenantScope) { it.getString(1) }
    }

    /** Locks the run period rows of [pairs] in (scope, period) order. The tenant rows must already be locked. */
    private fun Transaction.lockRunRows(pairs: List<Pair<String, String>>) {
        pairs.filter { it.first != tenantScope }.distinct().sortedWith(compareBy({ it.first }, { it.second })).forEach { (id, period) ->
            execQuery("SELECT committed_micro_yen FROM budget_periods WHERE scope_id = ? AND period = ? FOR UPDATE", id, period) { it.getLong(1) }
        }
    }

    /**
     * Moves expired RESERVED rows to LAPSED, charging them conservatively (committed += estimate, unpriced += 1). Called
     * with the tenant lock held, so no other operation can change these rows meanwhile.
     */
    private fun Transaction.expireDue(now: Instant) {
        val due = execQuery(
            """
            SELECT r.reservation_key, r.amount_micro_yen, s.scope_id, s.period
              FROM budget_reservations r
              JOIN budget_reservation_scopes s ON s.reservation_key = r.reservation_key
             WHERE r.state = ? AND r.expires_at <= ?
            """.trimIndent(),
            RESERVED, LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
        ) { Triple(it.getString(1), it.getLong(2), it.getString(3) to it.getString(4)) }
        if (due.isEmpty()) return
        lockRunRows(due.map { it.third })
        for ((key, amount, pair) in due) {
            addToPeriod(pair.first, pair.second, amount, unpriced = 1L)
            execUpdate("UPDATE budget_reservations SET state = ? WHERE reservation_key = ? AND state = ?", LAPSED, key, RESERVED)
        }
    }

    // ---- row access (inside a transaction holding the tenant lock) ----------------------------------------------------

    private data class PeriodRow(val committed: Long, val unpriced: Long)

    private fun Transaction.periodRow(scopeId: String, period: String): PeriodRow? = execQuery(
        "SELECT committed_micro_yen, unpriced_calls FROM budget_periods WHERE scope_id = ? AND period = ?",
        scopeId, period,
    ) { PeriodRow(it.getLong(1), it.getLong(2)) }.singleOrNull()

    private fun Transaction.reservedFor(scopeId: String, period: String): Long = execQuery(
        """
        SELECT COALESCE(SUM(r.amount_micro_yen), 0)
          FROM budget_reservation_scopes s
          JOIN budget_reservations r ON r.reservation_key = s.reservation_key
         WHERE s.scope_id = ? AND s.period = ? AND r.state = ?
        """.trimIndent(),
        scopeId, period, RESERVED,
    ) { it.getLong(1) }.single()

    private fun Transaction.addToPeriod(scopeId: String, period: String, micro: Long, unpriced: Long) {
        execUpdate(
            "UPDATE budget_periods SET committed_micro_yen = committed_micro_yen + ?, unpriced_calls = unpriced_calls + ? WHERE scope_id = ? AND period = ?",
            micro, unpriced, scopeId, period,
        )
    }

    private data class StoredReservation(val state: String, val amount: Long, val scopes: List<Pair<String, String>>)

    private fun Transaction.loadReservation(key: String): StoredReservation? {
        val head = execQuery(
            "SELECT state, amount_micro_yen FROM budget_reservations WHERE reservation_key = ? FOR UPDATE", key,
        ) { it.getString(1) to it.getLong(2) }.singleOrNull() ?: return null
        val scopes = execQuery(
            "SELECT scope_id, period FROM budget_reservation_scopes WHERE reservation_key = ?", key,
        ) { it.getString(1) to it.getString(2) }
        return StoredReservation(head.first, head.second, scopes)
    }

    private fun Transaction.reservationExists(key: String): Boolean =
        execQuery("SELECT 1 FROM budget_reservations WHERE reservation_key = ?", key) { it.getInt(1) }.isNotEmpty()

    private fun Transaction.settle(key: String, state: String) {
        execUpdate("UPDATE budget_reservations SET state = ?, settled_at = ? WHERE reservation_key = ?", state, utcNow(clock), key)
    }

    // ---- scopes, periods, money -----------------------------------------------------------------------------------

    private fun limitFor(scopeId: String): BudgetLimit? = when {
        scopeId == tenantScope -> tenantLimit
        scopeId.startsWith(RUN_PREFIX) -> runLimit
        else -> null
    }

    /** The period a scope is charged to at [now]: the JST calendar month for the tenant, lifetime for runs. */
    private fun periodFor(scopeId: String, now: Instant): String =
        if (scopeId == tenantScope) YearMonth.from(now.atZone(JST)).toString() else PERIOD_ALL

    /** A known cost in the limit's currency and unit, or null when it cannot be compared. */
    private fun knownMicro(a: BudgetAmount): Long? {
        if (a.currency != tenantLimit.currency || a.unit != tenantLimit.unit) return null
        return microOrNull(a)
    }

    private fun microOf(a: BudgetAmount): Long = microOrNull(a)!!

    private fun microOrNull(a: BudgetAmount): Long? = try {
        if (a.amount.signum() < 0) null else microCeil(a.amount)
    } catch (e: ArithmeticException) {
        null
    }

    private fun micro(v: Long): BigDecimal = BigDecimal.valueOf(v, MICRO_SCALE)

    companion object {
        const val RUN_PREFIX = "run:"
        const val PERIOD_ALL = "ALL"
        const val RESERVED = "RESERVED"
        const val LAPSED = "LAPSED"
        const val COMMITTED = "COMMITTED"
        /** Settled without an established actual cost. Still counted as unpriced until acknowledged. */
        const val UNPRICED = "UNPRICED"
        /** Released by an operator through [acknowledgeUnpriced]. Terminal. */
        const val ACKNOWLEDGED = "ACKNOWLEDGED"
        const val RELEASED = "RELEASED"
        private const val MICRO_SCALE = 6
        private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

        /** Rounds up to whole micro-yen (conservative for costs). Throws on overflow of BIGINT. */
        internal fun microCeil(amount: BigDecimal): Long =
            amount.movePointRight(MICRO_SCALE).setScale(0, RoundingMode.CEILING).longValueExact()

        /** Rounds down to whole micro-yen (conservative for limits). */
        internal fun microFloor(amount: BigDecimal): Long =
            amount.movePointRight(MICRO_SCALE).setScale(0, RoundingMode.FLOOR).longValueExact()

        private fun requireJpy(limit: BudgetLimit, which: String) {
            require(limit.currency == "JPY" && limit.unit == Cost.UNIT_CURRENCY_UNITS) { "$which budget limit must be in JPY (currency_units)" }
            require(limit.hard.signum() > 0) { "$which budget hard limit must be positive" }
            microFloor(limit.hard)
            limit.soft?.let { microFloor(it) }
        }

        private fun Throwable.isDuplicateKey(): Boolean = generateSequence(this) { it.cause }.any {
            it is SQLException && (it.sqlState == "23505" || it.errorCode == 1062 || it.errorCode == 23505)
        }
    }
}

/** Outcome of [DbBudgetStore.recordSpend]. Fixed values only. */
enum class SpendResult { RECORDED, ALREADY_RECORDED, FAILURE }
