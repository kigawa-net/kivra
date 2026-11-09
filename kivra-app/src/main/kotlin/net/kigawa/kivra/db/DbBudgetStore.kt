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
 * Scopes: [tenantScope] (monthly hard limit, JST calendar month), `project:<id>` (ADR 0022 PR-E: monthly limit read from
 * `project_budgets` at each admission, JST calendar month) and any `run:<id>` scope (lifetime limit [runLimit], optional).
 * A reservation path must contain [tenantScope]. Amounts are stored as BIGINT micro-yen; costs are rounded up.
 *
 * A project scope without a `project_budgets` row has no limit. It is kept out of an admission path by [scopesFor], so such a
 * project behaves exactly as before. Settled spend ([recordSpend]) is still booked to its project scope, so the month's consumption
 * is visible even before a limit is set. A reservation keeps the scopes it was made with, whatever the limit does later.
 *
 * Locking and atomicity:
 * - Every operation first takes `SELECT ... FOR UPDATE` on all `budget_periods` rows of [tenantScope], in one transaction.
 *   Only the tenant scope and this order are used for locking across replicas, so no two operations can wait on each other
 *   in a cycle. The other rows are locked after the tenant rows, in the order of [lockOrder]: project rows first, then run
 *   rows, each sorted by (scope id, period). A change of a project's limit takes the tenant lock too, so it is ordered with
 *   every admission.
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
) : BudgetPort, BudgetOperatorView {
    init {
        require(tenantScope.isNotBlank() && !tenantScope.startsWith(RUN_PREFIX) && !tenantScope.startsWith(PROJECT_PREFIX)) {
            "tenantScope must not be blank or start with run: or project:"
        }
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
                    lockScopeRows(r.scopes)
                    for ((id, period) in r.scopes) {
                        addToPeriod(id, period, known ?: r.amount, unpriced = if (known == null) 1L else 0L)
                    }
                    settle(key, if (known == null) UNPRICED else COMMITTED)
                }
                // The estimate was charged and counted as unpriced at lapse. A known actual trues up the amount only; the
                // unpriced count stays (a late actual does not clear it), so the reservation remains UNPRICED until released.
                LAPSED -> {
                    lockScopeRows(r.scopes)
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
                    lockScopeRows(r.scopes)
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
     * returns [SpendResult.ALREADY_RECORDED]. Any failure is [SpendResult.FAILURE] with nothing written. When RECORDED, the outcome
     * also carries the booked period, the booked micro-yen and the month's committed total after the booking (read in the same
     * locked transaction; null if it cannot be read).
     */
    fun recordSpend(key: String, amount: BudgetAmount, unpriced: Boolean, projectId: String? = null): SpendOutcome {
        val micro = (if (amount.currency == tenantLimit.currency && amount.unit == tenantLimit.unit) microOrNull(amount) else null)
            ?: return SpendOutcome(SpendResult.FAILURE)
        // The tenant always; the project too when the Run has one (ADR 0022 PR-E). The project's limit does not gate a booking.
        val scopes = listOfNotNull(tenantScope, projectId?.let { projectScope(it) })
        ensurePeriodRows(scopes, clock.instant())
        return try {
            transaction<SpendOutcome>(database) {
                lockTenant()
                expireDue(clock.instant())
                if (reservationExists(key)) return@transaction SpendOutcome(SpendResult.ALREADY_RECORDED)
                val now = clock.instant()
                val pairs = scopes.map { it to periodFor(it, now) }
                lockScopeRows(pairs)
                val at = utcNow(clock)
                execUpdate(
                    "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at, settled_at) VALUES (?, ?, ?, ?, ?, ?)",
                    key, micro, if (unpriced) UNPRICED else COMMITTED, at, at, at,
                )
                for ((id, period) in pairs) {
                    execUpdate(
                        "INSERT INTO budget_reservation_scopes (reservation_key, scope_id, period) VALUES (?, ?, ?)",
                        key, id, period,
                    )
                }
                for ((id, period) in pairs) addToPeriod(id, period, micro, unpriced = if (unpriced) 1L else 0L)
                if (projectId != null) warnIfProjectSoftReached(projectId, now)
                val tenantPeriod = pairs.first().second
                SpendOutcome(
                    SpendResult.RECORDED,
                    period = tenantPeriod,
                    amountMicroYen = micro,
                    monthCommittedMicroYen = periodRow(tenantScope, tenantPeriod)?.committed,
                )
            }
        } catch (e: Exception) {
            SpendOutcome(if (e.isDuplicateKey()) SpendResult.ALREADY_RECORDED else SpendResult.FAILURE)
        }
    }

    /** Logs (no amounts) when a project's month has reached its soft threshold. A warning only: nothing is refused. Inside the lock. */
    private fun Transaction.warnIfProjectSoftReached(projectId: String, now: Instant) {
        val id = projectScope(projectId)
        val soft = limitFor(id)?.soft ?: return
        val period = periodFor(id, now)
        val used = Math.addExact(periodRow(id, period)?.committed ?: 0L, reservedFor(id, period))
        if (used >= microFloor(soft)) logger.warn("project budget soft limit reached: projectId={} period={}", projectId, period)
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
                lockScopeRows(r.scopes)
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

    /**
     * Read-only snapshot for the operator screen. Amounts and reservation keys only. The committed total and the reserved sum are
     * those of the tenant scope in its current JST month. [BudgetOperatorStatus.cliCommittedJpy] is the part of the charged total
     * whose key is `coding:` (the CLI path). The API path is the rest, so the two always add up to the committed total.
     * The unpriced list covers every month (an older UNPRICED still blocks until acknowledged), capped at [UNPRICED_LIST_LIMIT].
     */
    override fun operatorStatus(): BudgetOperatorStatus = transaction(database) {
        val now = clock.instant()
        lockTenant()
        expireDue(now)
        val period = periodFor(tenantScope, now)
        val committed = periodRow(tenantScope, period)?.committed ?: 0L
        val reserved = reservedFor(tenantScope, period)
        val cli = execQuery(
            """
            SELECT COALESCE(SUM(r.amount_micro_yen), 0)
              FROM budget_reservation_scopes s
              JOIN budget_reservations r ON r.reservation_key = s.reservation_key
             WHERE s.scope_id = ? AND s.period = ? AND r.state IN (?, ?, ?, ?) AND r.reservation_key LIKE ?
            """.trimIndent(),
            tenantScope, period, COMMITTED, UNPRICED, ACKNOWLEDGED, LAPSED, "$CODING_KEY_PREFIX%",
        ) { it.getLong(1) }.single()
        val rows = execQuery(
            """
            SELECT r.reservation_key, r.state, r.amount_micro_yen, s.period
              FROM budget_reservation_scopes s
              JOIN budget_reservations r ON r.reservation_key = s.reservation_key
             WHERE s.scope_id = ? AND r.state IN (?, ?)
             ORDER BY r.created_at, r.reservation_key
             LIMIT ${UNPRICED_LIST_LIMIT + 1}
            """.trimIndent(),
            tenantScope, UNPRICED, LAPSED,
        ) { UnpricedReservation(it.getString(1), tenantScope, it.getString(4), it.getString(2), micro(it.getLong(3)), it.getString(1).startsWith(CODING_KEY_PREFIX)) }
        val count = execQuery(
            """
            SELECT COUNT(*)
              FROM budget_reservation_scopes s
              JOIN budget_reservations r ON r.reservation_key = s.reservation_key
             WHERE s.scope_id = ? AND r.state IN (?, ?)
            """.trimIndent(),
            tenantScope, UNPRICED, LAPSED,
        ) { it.getLong(1) }.single()
        BudgetOperatorStatus(
            period = period,
            limitJpy = tenantLimit.hard,
            committedJpy = micro(committed),
            reservedJpy = micro(reserved),
            cliCommittedJpy = micro(cli),
            unpricedCount = count,
            unpriced = rows.take(UNPRICED_LIST_LIMIT),
        )
    }

    // ---- project budgets (ADR 0022 PR-E) -------------------------------------------------------------------------------

    /**
     * The scopes of an admission for a Run in [projectId] (null: the cost has no project, e.g. the Planner's). The tenant always; the
     * project scope only when the project has a limit row. A project without one is not limited, so it stays out of the path. A limit
     * removed after this check makes the admission fail closed (BUDGET_POLICY_MISSING), it does not pass unchecked.
     */
    fun scopesFor(projectId: String?): List<String> {
        if (projectId == null) return listOf(tenantScope)
        val limited = transaction(database) { projectBudgetRow(projectId) != null }
        return if (limited) listOf(tenantScope, projectScope(projectId)) else listOf(tenantScope)
    }

    /** The budget of [projectId] for the current JST month. Read only, under the tenant lock. */
    fun projectBudget(projectId: String): ProjectBudgetStatus = transaction(database) {
        val now = clock.instant()
        lockTenant()
        expireDue(now)
        projectBudgetStatusIn(projectId, now)
    }

    /**
     * Sets the monthly limit (whole yen) and the soft ratio of [projectId], under the tenant lock, so it is ordered with every admission.
     * Returns the budget after the change, or null when the project does not exist. Amounts are checked here too (see [requireProjectBudget]).
     */
    fun setProjectBudget(projectId: String, monthlyLimitJpy: BigDecimal, softRatio: BigDecimal): ProjectBudgetStatus? {
        requireProjectBudget(monthlyLimitJpy, softRatio)
        val monthly = monthlyLimitJpy.setScale(0)
        val ratio = softRatio.setScale(3)
        return transaction<ProjectBudgetStatus?>(database) {
            val now = clock.instant()
            lockTenant()
            expireDue(now)
            if (!projectExists(projectId)) return@transaction null
            val updated = execUpdate(
                "UPDATE project_budgets SET monthly_limit_jpy = ?, soft_ratio = ?, updated_at = ? WHERE project_id = ?",
                monthly, ratio, utcNow(clock), projectId,
            )
            if (updated == 0) {
                execUpdate(
                    "INSERT INTO project_budgets (project_id, monthly_limit_jpy, soft_ratio, updated_at) VALUES (?, ?, ?, ?)",
                    projectId, monthly, ratio, utcNow(clock),
                )
            }
            projectBudgetStatusIn(projectId, now)
        }
    }

    /**
     * Removes the monthly limit of [projectId]. Its consumption is still booked, and reservations already made keep their scopes.
     * Returns the budget after the change, or null when the project does not exist.
     */
    fun clearProjectBudget(projectId: String): ProjectBudgetStatus? = transaction<ProjectBudgetStatus?>(database) {
        val now = clock.instant()
        lockTenant()
        expireDue(now)
        if (!projectExists(projectId)) return@transaction null
        execUpdate("DELETE FROM project_budgets WHERE project_id = ?", projectId)
        projectBudgetStatusIn(projectId, now)
    }

    private fun Transaction.projectBudgetStatusIn(projectId: String, now: Instant): ProjectBudgetStatus {
        val scope = projectScope(projectId)
        val period = periodFor(scope, now)
        val row = projectBudgetRow(projectId)
        val committed = periodRow(scope, period)?.committed ?: 0L
        val reserved = reservedFor(scope, period)
        val used = Math.addExact(committed, reserved)
        val limit = row?.let { BudgetLimit(hard = it.monthlyLimitJpy, soft = it.monthlyLimitJpy.multiply(it.softRatio), currency = tenantLimit.currency, unit = tenantLimit.unit) }
        return ProjectBudgetStatus(
            projectId = projectId,
            period = period,
            monthlyLimitJpy = row?.monthlyLimitJpy,
            softRatio = row?.softRatio,
            committedMicroYen = committed,
            reservedMicroYen = reserved,
            unpricedCalls = periodRow(scope, period)?.unpriced ?: 0L,
            remainingMicroYen = limit?.let { maxOf(0L, microFloor(it.hard) - used) },
            softExceeded = limit?.soft?.let { used >= microFloor(it) } ?: false,
        )
    }

    /** The `project_budgets` row of [projectId], or null when it has none (no limit). */
    private fun Transaction.projectBudgetRow(projectId: String): ProjectBudgetRow? = execQuery(
        "SELECT monthly_limit_jpy, soft_ratio FROM project_budgets WHERE project_id = ?", projectId,
    ) { ProjectBudgetRow(it.getBigDecimal(1), it.getBigDecimal(2)) }.singleOrNull()

    private fun Transaction.projectExists(projectId: String): Boolean =
        execQuery("SELECT 1 FROM projects WHERE project_id = ?", projectId) { it.getInt(1) }.isNotEmpty()

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
            val limit = when {
                // A project limit is JPY, as the tenant's. Whether the project has a limit row is read in the locked transaction.
                isProjectScope(id) -> tenantLimit
                else -> staticLimitFor(id) ?: return ReserveResult.Denied(id, "unknown budget scope", BudgetDenial.BUDGET_POLICY_MISSING)
            }
            if (a.currency != limit.currency || a.unit != limit.unit) {
                return ReserveResult.Denied(
                    id, "cost currency/unit does not match the budget", BudgetDenial.UNKNOWN_COST, UnknownCostReason.CURRENCY_MISMATCH,
                )
            }
        }
        return null
    }

    /**
     * Must be called inside a transaction that holds the tenant lock. Missing period rows count as zero. A scope with no limit here
     * (a project whose limit row was removed after the path was chosen) is denied with BUDGET_POLICY_MISSING: fail closed.
     */
    private fun Transaction.admissionDenial(scopes: List<String>, micro: Long, now: Instant): ReserveResult.Denied? {
        val limits = scopes.associateWith { limitFor(it) }
        limits.forEach { (id, limit) ->
            if (limit == null) return ReserveResult.Denied(id, "unknown budget scope", BudgetDenial.BUDGET_POLICY_MISSING)
        }
        for (id in scopes) {
            val row = periodRow(id, periodFor(id, now))
            if ((row?.unpriced ?: 0L) > 0L) {
                return ReserveResult.Denied(id, "unpriced usage recorded in scope", BudgetDenial.UNKNOWN_COST, UnknownCostReason.UNPRICED_IN_SCOPE)
            }
        }
        for (id in scopes) {
            val hard = microFloor(limits.getValue(id)!!.hard)
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

    /**
     * Creates missing period rows in their own transactions, so the locked transaction never waits on an insert. A row that already
     * exists is detected by a plain SELECT first, so the common path issues no INSERT (and so no duplicate-key error for the
     * transaction layer to log). Only a row that is missing is inserted; if another replica creates it in between, the duplicate
     * key is ignored as before.
     */
    private fun ensurePeriodRows(scopes: List<String>, now: Instant) {
        for (id in scopes) {
            val period = periodFor(id, now)
            if (transaction(database) { periodRow(id, period) != null }) continue
            try {
                transaction(database) {
                    // The hard value is informational for a project (its limit is read from project_budgets); 0 when it has none.
                    val hard = limitFor(id)?.let { microFloor(it.hard) } ?: 0L
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

    /**
     * Locks the non-tenant period rows of [pairs] in [lockOrder]: project rows, then run rows, each sorted by (scope, period). The tenant
     * rows must already be locked (see [lockTenant]).
     */
    private fun Transaction.lockScopeRows(pairs: List<Pair<String, String>>) {
        lockOrder(tenantScope, pairs).forEach { (id, period) ->
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
        lockScopeRows(due.map { it.third })
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

    /** The limit of a scope that has one in the configuration (tenant, run). Project limits are in the database: see [limitFor]. */
    private fun staticLimitFor(scopeId: String): BudgetLimit? = when {
        scopeId == tenantScope -> tenantLimit
        scopeId.startsWith(RUN_PREFIX) -> runLimit
        else -> null
    }

    /** The limit of [scopeId] now, or null when it has none. A project's limit is read from `project_budgets` in the caller's transaction. */
    private fun Transaction.limitFor(scopeId: String): BudgetLimit? {
        if (!isProjectScope(scopeId)) return staticLimitFor(scopeId)
        val row = projectBudgetRow(scopeId.removePrefix(PROJECT_PREFIX)) ?: return null
        return BudgetLimit(hard = row.monthlyLimitJpy, soft = row.monthlyLimitJpy.multiply(row.softRatio), currency = tenantLimit.currency, unit = tenantLimit.unit)
    }

    private fun isProjectScope(scopeId: String): Boolean = scopeId.startsWith(PROJECT_PREFIX)

    /** The period a scope is charged to at [now]: the JST calendar month for the tenant and project scopes, lifetime for runs. */
    private fun periodFor(scopeId: String, now: Instant): String =
        if (scopeId == tenantScope || isProjectScope(scopeId)) YearMonth.from(now.atZone(JST)).toString() else PERIOD_ALL

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
        /** The scope of one project (ADR 0022 PR-E): `project:<projectId>`. Like `run:`, it can never be the tenant scope. */
        const val PROJECT_PREFIX = "project:"
        fun projectScope(projectId: String): String = PROJECT_PREFIX + projectId

        /** Limits of a project's monthly budget: whole yen (DECIMAL(12,0)), below 10^12, and a soft ratio in (0, 1] with at most 3 decimals. */
        internal fun requireProjectBudget(monthlyLimitJpy: BigDecimal, softRatio: BigDecimal) {
            require(monthlyLimitJpy.signum() >= 0 && monthlyLimitJpy < BigDecimal.TEN.pow(12)) { "project monthly limit must be 0 to below 10^12 yen" }
            require(monthlyLimitJpy.stripTrailingZeros().scale() <= 0) { "project monthly limit must be whole yen" }
            require(softRatio.signum() > 0 && softRatio <= BigDecimal.ONE) { "project soft ratio must be in (0, 1]" }
            require(softRatio.stripTrailingZeros().scale() <= 3) { "project soft ratio has at most 3 decimals" }
        }

        /** The reservation key prefix of the CLI coding path (`coding:<attemptId>:<generation>`, see CodingCostLedger). */
        const val CODING_KEY_PREFIX = "coding:"
        /** The most unpriced rows the operator screen lists. The count is still exact. */
        const val UNPRICED_LIST_LIMIT = 100
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

/**
 * Read-only view of the budget for the operator screen (`GET /api/admin/budget/status`). Carries amounts and reservation keys only:
 * no prompt, response, path or model name.
 */
interface BudgetOperatorView {
    fun operatorStatus(): BudgetOperatorStatus
}

/** The tenant scope's month, in yen. [unpriced] is capped; [unpricedCount] is the exact number of UNPRICED or LAPSED rows. */
data class BudgetOperatorStatus(
    val period: String,
    val limitJpy: BigDecimal,
    val committedJpy: BigDecimal,
    val reservedJpy: BigDecimal,
    val cliCommittedJpy: BigDecimal,
    val unpricedCount: Long,
    val unpriced: List<UnpricedReservation>,
)

/** One UNPRICED or LAPSED reservation on the tenant scope, as the operator can acknowledge it. [cli] is true for a `coding:` key. */
data class UnpricedReservation(
    val reservationKey: String,
    val scopeId: String,
    val period: String,
    val state: String,
    val amountJpy: BigDecimal,
    val cli: Boolean,
)

/** Outcome of [DbBudgetStore.recordSpend]. Fixed values only. */
enum class SpendResult { RECORDED, ALREADY_RECORDED, FAILURE }

/**
 * Result of [DbBudgetStore.recordSpend]. The detail fields are set only when [result] is RECORDED; [monthCommittedMicroYen] may be
 * null even then (the total could not be read). Numbers and the fixed period text only.
 */
data class SpendOutcome(
    val result: SpendResult,
    val period: String? = null,
    val amountMicroYen: Long? = null,
    val monthCommittedMicroYen: Long? = null,
)

/**
 * The lock order of the non-tenant rows of a path (ADR 0022 PR-E): the tenant rows are locked first (by every operation), then the
 * project rows, then the run rows, each group sorted by (scope id, period). Returns the distinct pairs in that order, without the tenant
 * rows. Pure: no database access, so the order is fixed by a unit test.
 */
internal fun lockOrder(tenantScope: String, pairs: List<Pair<String, String>>): List<Pair<String, String>> =
    pairs.filter { it.first != tenantScope }.distinct().sortedWith(
        compareBy<Pair<String, String>>(
            { if (it.first.startsWith(DbBudgetStore.PROJECT_PREFIX)) 0 else 1 },
            { it.first },
            { it.second },
        ),
    )

/** A project's `project_budgets` row: whole yen and the soft ratio. */
internal data class ProjectBudgetRow(val monthlyLimitJpy: BigDecimal, val softRatio: BigDecimal)

/**
 * A project's budget for the current JST month (ADR 0022 PR-E). [monthlyLimitJpy] and [softRatio] are null when the project has no limit;
 * then [remainingMicroYen] is null and [softExceeded] is false. Amounts are micro-yen (1 JPY = 1,000,000), except the two limits.
 * [softExceeded]: committed plus reserved has reached the soft threshold (a warning only; nothing is refused for it).
 */
data class ProjectBudgetStatus(
    val projectId: String,
    val period: String,
    val monthlyLimitJpy: BigDecimal?,
    val softRatio: BigDecimal?,
    val committedMicroYen: Long,
    val reservedMicroYen: Long,
    val unpricedCalls: Long,
    val remainingMicroYen: Long?,
    val softExceeded: Boolean,
)
