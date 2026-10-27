package net.kigawa.kivra.db

import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderRefusal
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.OrderTransitionDecision
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.core.autopilot.STOP_REQUEST_TTL_HOURS
import net.kigawa.kivra.core.autopilot.checkAutomaticStop
import net.kigawa.kivra.core.autopilot.checkOrderTransition
import net.kigawa.kivra.core.autopilot.checkSubtreeStop
import net.kigawa.kivra.core.autopilot.orderStopDigest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Persistence of self-development orders (ADR 0015 stage 1; V23). Raw SQL through [execUpdate] and [execQuery], as in
 * [SelfDevGoalRepository]: every write is a conditional UPDATE (compare and set on `version` or on a row state), and a write that is
 * refused after some other write has already run is rolled back by throwing [Rollback], so a refusal never leaves a partial change.
 *
 * The state rule is [checkOrderTransition] (kivra-core). A stop is approved in one transaction: the request moves PENDING -> CONSUMED and
 * the order moves STOP_REQUESTED -> STOPPED, or neither does (ADR 0015 section 4.2).
 *
 * Nothing here logs or audits a title, a policy or a free text. The event rows hold fixed codes, IDs and the first 12 hex digits of the
 * SHA-256 of an owner key.
 */

/** The order row as the repositories read it. [policy] and [title] are the operator's text; they are not logged. */
data class SelfDevOrder(
    val orderId: String,
    val parentOrderId: String?,
    val depth: Int,
    val title: String,
    val policy: String,
    val targetRepo: String,
    val maxCycles: Int,
    val cyclesStarted: Int,
    val currentGoalId: String?,
    val consecutiveFailedCycles: Int,
    val state: OrderState,
    val stateReason: OrderReason?,
    val expiresAt: LocalDateTime,
    val createdBy: String,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    /** The order's budget in whole yen (ADR 0015 section 2.7). */
    val orderBudgetJpy: Long,
    /** The part of the budget given to children. Never above [orderBudgetJpy]. */
    val allocatedJpy: Long,
    /** The number of children. */
    val childCount: Int,
    /** The cost counted against this order and its descendants (ADR 0015 section 2.7). Nothing writes it yet (PR 3c feeds it). */
    val spentJpy: Long,
    /** The most one cycle of this order may cost, in whole yen (ADR 0015 section 3.2). */
    val cycleBudgetJpy: Long,
)

enum class StopRequestState { PENDING, CONSUMED, REJECTED, CANCELLED, EXPIRED }

data class SelfDevOrderStopRequest(
    val requestId: String,
    val orderId: String,
    val requestedBy: String,
    val requesterKind: OrderActor,
    val reasonCode: StopRequestReason,
    /** The order's version after the request moved it to STOP_REQUESTED. An approval is valid only at this version. */
    val orderVersion: Long,
    val digest: String,
    val state: StopRequestState,
    val decidedBy: String?,
    val expiresAt: LocalDateTime,
    val createdAt: LocalDateTime,
    /**
     * True for a request to stop the root (the requester and the decider must differ). False for a child: its direct parent decides it,
     * which is the root approver, so the requester may be the decider (ADR 0015 section 4.2 (a)).
     */
    val targetIsRoot: Boolean,
)

/** One goal of an order: the cycle number, the goal, and its state. */
data class OrderCycle(val cycleNo: Int, val goalId: String, val goalState: GoalState, val stateReason: String?, val createdAt: LocalDateTime)

/** The fixed reason a write was refused. Each name is a [OrderRefusal] or a code of this repository. */
enum class OrderConflict {
    TERMINAL, NO_EDGE, ACTOR_NOT_ALLOWED, REASON_NOT_ALLOWED, RESUME_NOT_ALLOWED,
    NOT_ACTIVE, CYCLE_IN_PROGRESS, CYCLE_LIMIT_REACHED, ORDER_EXPIRED, AUTOPILOT_PAUSED,
    STOP_ALREADY_PENDING, STOP_NOT_PENDING, STOP_EXPIRED, SAME_PRINCIPAL, DIGEST_MISMATCH, ORDER_CHANGED,
    /** A cycle start: some order on the path to the root has less left than its cycle budget. [OrderResult.Refused.orderId] names it. */
    ORDER_BUDGET,
    /**
     * A cycle start or a resume: an ancestor is not ACTIVE (PAUSED, STOP_REQUESTED or STOPPED), so the order's effective state is not
     * ACTIVE (ADR 0015 section 2.4). [OrderResult.Refused.orderId] names the nearest such ancestor (a fixed ID, no text).
     */
    ANCESTOR_NOT_ACTIVE,
    /**
     * A cycle start: some order on the path to the root (itself and its ancestors) has a cycle that is not final and holds an unconfirmed
     * UNPRICED row, zero yen included (ADR 0015 addendum 2026-10-23, C.4 and U-35). Nothing is written; the order's state is not changed.
     * [OrderResult.Refused.orderId] is null for this reason.
     */
    ORDER_UNPRICED,
}

sealed interface OrderResult<out T> {
    data class Moved<out T>(val value: T) : OrderResult<T>
    data object NotFound : OrderResult<Nothing>
    /** The expected version is not the current one. Nothing changed. */
    data object StaleVersion : OrderResult<Nothing>
    /** [orderId] is set only for [OrderConflict.ORDER_BUDGET] and [OrderConflict.ANCESTOR_NOT_ACTIVE]: a fixed ID of an order, no text. */
    data class Refused(val reason: OrderConflict, val orderId: String? = null) : OrderResult<Nothing>
}

sealed interface CreateRootResult {
    data class Created(val order: SelfDevOrder) : CreateRootResult
    /** The tree already has its root. Nothing changed. */
    data object RootExists : CreateRootResult
}

/**
 * Why the automatic start did not start a cycle in this pass. Each name is a fixed code. The names that are also [OrderConflict] names
 * are the same checks as the operator's start; the others are only for the automatic start.
 */
enum class AutoCycleSkip {
    NOT_ACTIVE, ANCESTOR_NOT_ACTIVE, CYCLE_IN_PROGRESS, CYCLE_LIMIT_REACHED, ORDER_EXPIRED, AUTOPILOT_PAUSED, ORDER_BUDGET, ORDER_UNPRICED,
    BREAKER_OPEN, CYCLE_FAILURES, TREE_BUSY, TOO_SOON, DAILY_LIMIT, STALE,
}

sealed interface AutoCycleResult {
    data class Started(val started: CycleStarted) : AutoCycleResult
    data class Skipped(val reason: AutoCycleSkip, val orderId: String? = null) : AutoCycleResult
    /** The order had started its [cap][SelfDevOrderRepository.startCycleAutomatically] of cycles, so it was paused with LIMIT_REACHED. */
    data class LimitPaused(val order: SelfDevOrder) : AutoCycleResult
    data object NotFound : AutoCycleResult
}

/** The outcome of a successful cycle start: the order and the new goal. */
data class CycleStarted(val order: SelfDevOrder, val goalId: String, val cycleNo: Int)

/** The fixed refusal codes of a child creation (ADR 0015 section 2.7). When one holds, nothing is written. */
enum class ChildRefusal { PARENT_NOT_ACTIVE, DEPTH_LIMIT, CHILD_LIMIT, TREE_LIMIT, BUDGET_EXCEEDS_PARENT }

sealed interface ChildCreateResult {
    data class Created(val order: SelfDevOrder) : ChildCreateResult
    /** The same key with the same content: the child made before. Nothing is written. */
    data class Replayed(val order: SelfDevOrder) : ChildCreateResult
    data class Refused(val reason: ChildRefusal) : ChildCreateResult
    /** The key was already used for a different request. Nothing is written. */
    data object KeyConflict : ChildCreateResult
}

/** The limits of a child creation, from the settings (`KIVRA_SELFDEV_ORDER_MAX_*`). */
data class ChildLimits(val maxDepth: Int, val maxChildren: Int, val maxTree: Int)

/** A stop request is ended by a rejection (by the approver) or a cancellation (by the requester or the approver). */
enum class StopEnd { REJECTED, CANCELLED }

/** Why the automatic child stop does not decide (ADR 0015 section 4.4). Fixed codes; nothing is written for them. */
enum class AutoStopSkip {
    NOT_FOUND, IS_ROOT, CHILD_NOT_STOPPABLE, PARENT_NOT_ACTIVE, CYCLE_FAILURES_BELOW_LIMIT, PENDING_REQUEST,
    /** CHILD_BUDGET_EXHAUSTED: the facts ([BudgetExhaustedFacts.exhausted]) do not hold under the lock. */
    BUDGET_NOT_EXHAUSTED,
}

/**
 * The facts of CHILD_BUDGET_EXHAUSTED (ADR 0015 section 4.4 (2), addendum K.1) for one order X: the cost counted against X (`spent_jpy`,
 * which includes the RESERVED amounts, K.1 (A)), X's budget, the cycles of X's part (X and its descendants) that are not final yet (a cycle
 * with a goal and no spend row counts as not final), and the Runs of the part that are not terminal. Read only; nothing is written.
 */
data class BudgetExhaustedFacts(
    val spentJpy: Long,
    val orderBudgetJpy: Long,
    val unfinishedCycles: Int,
    val nonTerminalRuns: Int,
) {
    /** The reason holds when the budget is reached (>=), every cycle of the part is final, and no Run of the part is open. */
    val exhausted: Boolean get() = spentJpy >= orderBudgetJpy && unfinishedCycles == 0 && nonTerminalRuns == 0
}

sealed interface AutoStopResult {
    /** The child and its descendants are STOPPED, in one transaction. [order] is the child as it is now. */
    data class Stopped(val order: SelfDevOrder) : AutoStopResult
    data class NotDecided(val reason: AutoStopSkip) : AutoStopResult
    /**
     * The frequency limit (section 4.4 (4)) held the decision back. Nothing stopped. [recorded] is true when the AUTO_STOP_RATE_LIMITED event
     * was written, which happens once per child per UTC day, so a tick does not write one every minute.
     */
    data class RateLimited(val parentOrderId: String, val recorded: Boolean) : AutoStopResult
}

/** The requester and the decider of an automatic stop: a fixed system principal, never a person's owner key (ADR 0015 section 4.4). */
const val AUTO_STOP_PRINCIPAL = "system:order-auto-stop"

/** The most cycles an order may have (the schema's CHECK, V23). The configured cap is at most this. */
const val MAX_CYCLES_LIMIT = 10

/** The reason code of the audit event of an automatic cycle start (actor_kind SYSTEM). A fixed code, never text. */
const val AUTO_CYCLE_STARTED = "AUTO_CYCLE_STARTED"

/** The shortest time between two cycle starts of one order, for the automatic start (ADR 0015 section 3.2). */
val AUTO_CYCLE_MIN_INTERVAL: Duration = Duration.ofMinutes(60)

/** The most cycles the automatic start runs for one order in a UTC day (ADR 0015 section 3.2). */
const val AUTO_CYCLE_DAILY_MAX = 3

/** Cycles that stop being counted as failures after this many in a row (ADR 0015 section 3.2). */
const val CYCLE_FAILURE_LIMIT = 2

/** The default cost limit of one cycle, in whole yen (ADR 0015 section 3.2). The column default in V26 is the same value. */
const val DEFAULT_CYCLE_BUDGET_JPY = 1_000L

/**
 * Told after a stop request has been created and committed (ADR 0021 L4, the shadow judgement). It must return at once and never fail the
 * request: the repository swallows what it throws.
 */
fun interface StopRequestListener {
    fun created(requestId: String)
}

class SelfDevOrderRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val orderTtl: Duration = Duration.ofDays(14),
    private val stopTtl: Duration = Duration.ofHours(STOP_REQUEST_TTL_HOURS),
) {

    @Volatile
    private var stopRequestListener: StopRequestListener? = null

    /** Installs (or, with null, removes) the listener told after a stop request is committed. Nothing is told while none is installed. */
    fun setStopRequestListener(listener: StopRequestListener?) {
        stopRequestListener = listener
    }

    // ---- reads -------------------------------------------------------------------------------------------------------------------

    fun get(orderId: String): SelfDevOrder? = transaction(database) { getIn(orderId) }

    fun list(limit: Int): List<SelfDevOrder> = transaction(database) {
        execQuery("SELECT $ORDER_COLUMNS FROM selfdev_orders ORDER BY created_at, order_id LIMIT ?", limit) { it.toOrder() }
    }

    /** ACTIVE orders, for the safety guards. */
    fun activeOrders(limit: Int): List<SelfDevOrder> = transaction(database) {
        execQuery("SELECT $ORDER_COLUMNS FROM selfdev_orders WHERE state = 'ACTIVE' ORDER BY created_at, order_id LIMIT ?", limit) { it.toOrder() }
    }

    fun stopRequest(requestId: String): SelfDevOrderStopRequest? = transaction(database) { getRequestIn(requestId) }

    /** The PENDING stop request of the order, if any. */
    fun pendingStopRequestOf(orderId: String): SelfDevOrderStopRequest? = transaction(database) {
        execQuery("SELECT $REQUEST_COLUMNS FROM selfdev_order_stop_requests WHERE order_id = ? AND state = 'PENDING'", orderId) { it.toRequest() }
            .firstOrNull()
    }

    fun pendingStopRequests(limit: Int): List<SelfDevOrderStopRequest> = transaction(database) {
        execQuery(
            "SELECT $REQUEST_COLUMNS FROM selfdev_order_stop_requests WHERE state = 'PENDING' ORDER BY created_at, request_id LIMIT ?",
            limit,
        ) { it.toRequest() }
    }

    fun cyclesOf(orderId: String): List<OrderCycle> = transaction(database) {
        execQuery(
            "SELECT cycle_no, goal_id, state, state_reason, created_at FROM selfdev_goals WHERE order_id = ? ORDER BY cycle_no",
            orderId,
        ) { rs ->
            OrderCycle(
                cycleNo = rs.getInt(1),
                goalId = rs.getString(2),
                goalState = GoalState.valueOf(rs.getString(3)),
                stateReason = rs.getString(4),
                createdAt = rs.getObject(5, LocalDateTime::class.java),
            )
        }
    }

    /**
     * True when the goal may be dispatched from the order's point of view: the goal has no order (the existing flow), or its order is
     * ACTIVE. Read by the dispatcher before a Run is created for the goal's tasks (ADR 0015 section 5.4).
     */
    fun dispatchAllowed(goalId: String): Boolean = transaction(database) {
        val state = execQuery(
            "SELECT o.state FROM selfdev_goals g JOIN selfdev_orders o ON o.order_id = g.order_id WHERE g.goal_id = ?",
            goalId,
        ) { it.getString(1) }.firstOrNull()
        state == null || state == OrderState.ACTIVE.name
    }

    /** True when a Run of a task of the goal is UNKNOWN (the safety guard of ADR 0015 section 5.2, row 8). */
    fun hasUnknownRun(goalId: String): Boolean = transaction(database) {
        execQuery(
            """
            SELECT 1 FROM selfdev_plans p
              JOIN selfdev_plan_tasks t ON t.plan_id = p.plan_id
              JOIN development_runs r ON r.run_id = t.run_id
             WHERE p.goal_id = ? AND r.state = 'UNKNOWN'
            """.trimIndent(),
            goalId,
        ) { it.getInt(1) }.isNotEmpty()
    }

    // ---- creation ----------------------------------------------------------------------------------------------------------------

    /**
     * Creates the root order, ACTIVE, with no cycle yet. The tree has one root: the UNIQUE `root_slot` refuses a second one, and that
     * refusal is returned as [CreateRootResult.RootExists].
     */
    fun createRoot(createdBy: String, title: String, policy: String, targetRepo: String, maxCycles: Int = 1): CreateRootResult = try {
        require(maxCycles in 1..MAX_CYCLES_LIMIT) { "maxCycles must be 1 to $MAX_CYCLES_LIMIT" }
        transaction(database) {
            val now = utcNow(clock)
            val orderId = Ulid.generate(clock)
            execUpdate(
                """
                INSERT INTO selfdev_orders (order_id, parent_order_id, depth, root_slot, title, policy, target_repo, max_cycles,
                    cycles_started, consecutive_failed_cycles, state, expires_at, created_by, version, created_at, updated_at)
                VALUES (?, NULL, 0, 1, ?, ?, ?, ?, 0, 0, 'ACTIVE', ?, ?, 0, ?, ?)
                """.trimIndent(),
                orderId, title, policy, targetRepo, maxCycles, now.plus(orderTtl), createdBy, now, now,
            )
            appendEvent(orderId, null, "ORDER_CREATED", null, OrderState.ACTIVE, null, OrderActor.HUMAN, createdBy, null, now)
            CreateRootResult.Created(getIn(orderId) ?: error("order vanished after insert"))
        }
    } catch (e: Exception) {
        if (!e.hasUniqueViolation()) throw e
        CreateRootResult.RootExists
    }

    /**
     * Creates a child of [parentId] (ADR 0015 section 2.7), ACTIVE, with no cycle yet. The checks and the writes are one transaction. The
     * root row is locked first, then the path down to the parent ([lockPath]): every child creation takes the locks in that order, so two creations in one tree wait for
     * each other. That keeps the tree size (TREE_LIMIT) and the parent's budget (BUDGET_EXCEEDS_PARENT) exact under concurrency.
     *
     * A key that is already used under this parent returns the child when the title, the policy and the budget are the same
     * ([ChildCreateResult.Replayed]), and [ChildCreateResult.KeyConflict] otherwise. The child takes the parent's target repo. Its lifetime
     * is 14 days, and never longer than the parent's.
     */
    fun createChild(
        parentId: String,
        title: String,
        policy: String,
        orderBudgetJpy: Long,
        idempotencyKey: String,
        createdBy: String,
        limits: ChildLimits,
    ): ChildCreateResult = try {
        transaction(database) {
            val now = utcNow(clock)
            execQuery("SELECT order_id FROM selfdev_orders WHERE root_slot = 1 FOR UPDATE") { it.getString(1) }
            val path = lockPath(parentId) ?: throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.PARENT_NOT_ACTIVE))
            val parent = path.first()
            findChildByKey(parentId, idempotencyKey)?.let { existing ->
                return@transaction if (existing.title == title && existing.policy == policy && existing.orderBudgetJpy == orderBudgetJpy) {
                    ChildCreateResult.Replayed(existing)
                } else {
                    ChildCreateResult.KeyConflict
                }
            }
            if (parent.state != OrderState.ACTIVE || !parent.expiresAt.isAfter(now) || firstStoppingAncestor(path.drop(1)) != null) {
                throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.PARENT_NOT_ACTIVE))
            }
            if (parent.depth + 1 > limits.maxDepth) throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.DEPTH_LIMIT))
            if (parent.childCount >= limits.maxChildren) throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.CHILD_LIMIT))
            val treeSize = execQuery("SELECT COUNT(*) FROM selfdev_orders") { it.getInt(1) }.single()
            if (treeSize >= limits.maxTree) throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.TREE_LIMIT))
            if (orderBudgetJpy > parent.orderBudgetJpy - parent.allocatedJpy) {
                throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.BUDGET_EXCEEDS_PARENT))
            }

            val childId = Ulid.generate(clock)
            val lifetimeEnd = minOf(now.plus(orderTtl), parent.expiresAt)
            execUpdate(
                """
                INSERT INTO selfdev_orders (order_id, parent_order_id, depth, root_slot, title, policy, target_repo, max_cycles,
                    cycles_started, consecutive_failed_cycles, state, expires_at, created_by, version, created_at, updated_at,
                    order_budget_jpy, allocated_jpy, child_count, idempotency_key)
                VALUES (?, ?, ?, NULL, ?, ?, ?, 1, 0, 0, 'ACTIVE', ?, ?, 0, ?, ?, ?, 0, 0, ?)
                """.trimIndent(),
                childId, parentId, parent.depth + 1, title, policy, parent.targetRepo, lifetimeEnd, createdBy, now, now,
                orderBudgetJpy, idempotencyKey,
            )
            val changed = execUpdate(
                """
                UPDATE selfdev_orders SET allocated_jpy = allocated_jpy + ?, child_count = child_count + 1, version = version + 1,
                    updated_at = ? WHERE order_id = ? AND version = ?
                """.trimIndent(),
                orderBudgetJpy, now, parentId, parent.version,
            )
            check(changed == 1) { "parent order changed under its row lock" }
            execUpdate(
                """
                INSERT INTO selfdev_order_events (event_id, order_id, cycle_no, kind, from_state, to_state, reason_code, actor_kind, actor_ref,
                    request_id, occurred_at, parent_order_id, depth, amount_jpy)
                VALUES (?, ?, NULL, 'CHILD_CREATED', NULL, 'ACTIVE', NULL, ?, ?, NULL, ?, ?, ?, ?)
                """.trimIndent(),
                Ulid.generate(clock), childId, OrderActor.HUMAN.name, actorRef(createdBy), now, parentId, parent.depth + 1, orderBudgetJpy,
            )
            ChildCreateResult.Created(getIn(childId) ?: error("child order vanished after insert"))
        }
    } catch (r: ChildRollback) {
        r.outcome
    } catch (e: Exception) {
        if (!e.hasUniqueViolation()) throw e
        ChildCreateResult.KeyConflict
    }

    // ---- pause and resume --------------------------------------------------------------------------------------------------------

    /** ACTIVE -> PAUSED by an operator ([OrderReason.OPERATOR_PAUSED]). */
    fun pauseByOperator(orderId: String, expectedVersion: Long, operatorKey: String): OrderResult<SelfDevOrder> =
        move(orderId, expectedVersion, OrderState.PAUSED, OrderReason.OPERATOR_PAUSED, OrderActor.HUMAN, operatorKey, null)

    /** PAUSED -> ACTIVE by an operator. Refused for a limit or an expiry (a new order is needed). */
    fun resumeByOperator(orderId: String, expectedVersion: Long, operatorKey: String): OrderResult<SelfDevOrder> =
        move(orderId, expectedVersion, OrderState.ACTIVE, OrderReason.OPERATOR_RESUMED, OrderActor.HUMAN, operatorKey, null)

    /**
     * ACTIVE -> PAUSED by a safety guard (ADR 0015 section 5.2). The order is checked under its row lock, so a guard that is slower than
     * an operator never overwrites the operator's pause. Returns [OrderResult.Refused] with [OrderConflict.NOT_ACTIVE] when the order is
     * not ACTIVE any more, and nothing is written.
     */
    fun pauseBySystem(orderId: String, reason: OrderReason): OrderResult<SelfDevOrder> = try {
        transaction(database) {
            val current = lockOrder(orderId) ?: return@transaction OrderResult.NotFound
            if (current.state != OrderState.ACTIVE) return@transaction OrderResult.Refused(OrderConflict.NOT_ACTIVE)
            val decision = checkOrderTransition(current.state, OrderState.PAUSED, reason, OrderActor.SYSTEM, current.stateReason)
            if (decision is OrderTransitionDecision.Refused) throw Rollback(OrderResult.Refused(OrderConflict.valueOf(decision.reason.name)))
            val now = utcNow(clock)
            if (!updateState(orderId, current.version, OrderState.PAUSED, reason, now)) throw Rollback(OrderResult.StaleVersion)
            appendEvent(orderId, current.cyclesStarted.takeIf { it > 0 }, "PAUSED", current.state, OrderState.PAUSED, reason.name, OrderActor.SYSTEM, null, null, now)
            OrderResult.Moved(getIn(orderId) ?: error("order vanished after pause"))
        }
    } catch (r: Rollback) {
        r.outcome
    }

    /** Pauses every ACTIVE order at startup after the feature is enabled (ADR 0015 U-14). Returns the number paused. */
    fun holdActiveOnStartup(): Int {
        val ids = transaction(database) {
            execQuery("SELECT order_id FROM selfdev_orders WHERE state = 'ACTIVE'") { it.getString(1) }
        }
        var held = 0
        for (id in ids) {
            if (pauseBySystem(id, OrderReason.STARTUP_HOLD) is OrderResult.Moved) held++
        }
        return held
    }

    /** ACTIVE orders whose lifetime is over are paused with EXPIRED (ADR 0015 section 3.2). Returns the number paused. */
    fun expireDueOrders(limit: Int): Int {
        val now = utcNow(clock)
        val ids = transaction(database) {
            execQuery(
                "SELECT order_id FROM selfdev_orders WHERE state = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at LIMIT ?",
                now, limit,
            ) { it.getString(1) }
        }
        return ids.count { pauseBySystem(it, OrderReason.EXPIRED) is OrderResult.Moved }
    }

    // ---- cycles ------------------------------------------------------------------------------------------------------------------

    /**
     * Starts the next cycle: creates the goal (`order_id`, `cycle_no`) in GOAL_SUBMITTED, so the existing Planner and Dispatcher take it from
     * there, and records the cycle on the order. The order must be ACTIVE, have no cycle in progress, be under its cycle limit and its
     * lifetime, and the autopilot must not be paused for everything. The goal's text is the order's policy and title, copied as typed.
     *
     * The budget check (ADR 0015 section 2.7): every order on the path from this one to the root must have `order_budget_jpy - spent_jpy`
     * at least its own `cycle_budget_jpy`. The first one that does not refuses the start with [OrderConflict.ORDER_BUDGET] and its ID; nothing
     * is written. The path is locked from the root down (see [lockPath]).
     *
     * The effective state (ADR 0015 section 2.4): every ancestor must be ACTIVE too. The first one that is not refuses the start with
     * [OrderConflict.ANCESTOR_NOT_ACTIVE] and its ID; nothing is written. Checked under the same locks, so a pause of an ancestor and this start
     * cannot both win.
     */
    fun startCycle(orderId: String, expectedVersion: Long, operatorKey: String): OrderResult<CycleStarted> = try {
        transaction(database) {
            val path = lockPath(orderId) ?: return@transaction OrderResult.NotFound
            startCycleIn(path, expectedVersion, CycleStarter(OrderActor.HUMAN, operatorKey, operatorKey, null, auto = false))
        }
    } catch (r: Rollback) {
        r.outcome
    }

    /**
     * The automatic start of a cycle by the tick (ADR 0015 section 3.1 step 1 and 8, addendum 2026-10-24, behind
     * `KIVRA_SELFDEV_ORDER_AUTO_CYCLE_ENABLED`). The checks and the writes are those of [startCycle], in one shared function ([startCycleIn]),
     * under the same locks (the path from the root down), plus the rules that only the automatic start has: the breaker, the failure limit,
     * the minimum interval since the previous cycle's start, the daily cycle count, and one cycle at a time in a tree. [cap] is
     * `KIVRA_SELFDEV_ORDER_MAX_CYCLES_CAP`: an ACTIVE order that has started [cap] cycles (and has more in `max_cycles`) is paused with
     * LIMIT_REACHED, as it would be at `max_cycles`. The audit event has actor_kind SYSTEM and reason code [AUTO_CYCLE_STARTED]; the goal is
     * created in the order creator's name, as an automatically approved task already runs as the goal's creator (ADR 0011 section 2.6).
     * No version is expected: the order is read under its lock, so a pause or a stop that won the lock first is seen as not ACTIVE.
     */
    fun startCycleAutomatically(orderId: String, cap: Int): AutoCycleResult = try {
        transaction(database) {
            val path = lockPath(orderId) ?: return@transaction AutoCycleResult.NotFound
            val current = path.first()
            if (current.state == OrderState.ACTIVE && current.currentGoalId == null && current.cyclesStarted >= cap && current.cyclesStarted < current.maxCycles) {
                val now = utcNow(clock)
                if (!updateState(orderId, current.version, OrderState.PAUSED, OrderReason.LIMIT_REACHED, now)) throw AutoSkip(AutoCycleSkip.STALE)
                appendEvent(orderId, current.cyclesStarted, "PAUSED", OrderState.ACTIVE, OrderState.PAUSED, OrderReason.LIMIT_REACHED.name, OrderActor.SYSTEM, null, null, now)
                return@transaction AutoCycleResult.LimitPaused(getIn(orderId) ?: error("order vanished after the cap pause"))
            }
            val by = CycleStarter(OrderActor.SYSTEM, null, current.createdBy, AUTO_CYCLE_STARTED, auto = true)
            when (val r = startCycleIn(path, null, by)) {
                is OrderResult.Moved -> AutoCycleResult.Started(r.value)
                is OrderResult.NotFound -> AutoCycleResult.NotFound
                is OrderResult.StaleVersion -> AutoCycleResult.Skipped(AutoCycleSkip.STALE)
                is OrderResult.Refused -> AutoCycleResult.Skipped(AutoCycleSkip.valueOf(r.reason.name), r.orderId)
            }
        }
    } catch (r: Rollback) {
        when (val o = r.outcome) {
            is OrderResult.Refused -> AutoCycleResult.Skipped(AutoCycleSkip.valueOf(o.reason.name), o.orderId)
            else -> AutoCycleResult.Skipped(AutoCycleSkip.STALE)
        }
    } catch (a: AutoSkip) {
        AutoCycleResult.Skipped(a.skip, a.orderId)
    }

    /**
     * ACTIVE orders that the automatic start may look at: no cycle in progress, under the failure limit and under `max_cycles`. The other
     * conditions are checked under the locks in [startCycleAutomatically].
     */
    fun autoCycleCandidates(limit: Int): List<String> = transaction(database) {
        execQuery(
            """
            SELECT order_id FROM selfdev_orders
             WHERE state = 'ACTIVE' AND current_goal_id IS NULL AND consecutive_failed_cycles < ? AND cycles_started < max_cycles
             ORDER BY created_at, order_id LIMIT ?
            """.trimIndent(),
            CYCLE_FAILURE_LIMIT, limit,
        ) { it.getString(1) }
    }

    /** Who starts a cycle: the actor of the audit event, the creator of the goal, and whether the automatic-only rules apply. */
    private class CycleStarter(val actor: OrderActor, val actorKey: String?, val goalCreator: String, val reasonCode: String?, val auto: Boolean)

    /**
     * The checks and the writes of a cycle start, shared by the operator's [startCycle] and the automatic [startCycleAutomatically]. [path] is
     * the locked path (the order first, then its ancestors up to the root). [expectedVersion] is null only for the automatic start.
     */
    private fun Transaction.startCycleIn(path: List<SelfDevOrder>, expectedVersion: Long?, by: CycleStarter): OrderResult<CycleStarted> {
        val current = path.first()
        val orderId = current.orderId
        if (expectedVersion != null && current.version != expectedVersion) return OrderResult.StaleVersion
        if (current.state != OrderState.ACTIVE) throw Rollback(OrderResult.Refused(OrderConflict.NOT_ACTIVE))
        firstStoppingAncestor(path.drop(1))?.let { throw Rollback(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, it.orderId)) }
        if (current.currentGoalId != null) throw Rollback(OrderResult.Refused(OrderConflict.CYCLE_IN_PROGRESS))
        if (current.cyclesStarted >= current.maxCycles) throw Rollback(OrderResult.Refused(OrderConflict.CYCLE_LIMIT_REACHED))
        val now = utcNow(clock)
        if (!current.expiresAt.isAfter(now)) throw Rollback(OrderResult.Refused(OrderConflict.ORDER_EXPIRED))
        val control = execQuery("SELECT paused, breaker_open FROM selfdev_autopilot_control WHERE control_id = 1") { it.getBoolean(1) to it.getBoolean(2) }.single()
        if (control.first) throw Rollback(OrderResult.Refused(OrderConflict.AUTOPILOT_PAUSED))
        if (by.auto) checkAutomaticRules(path, control.second, now)
        for (order in path) {
            if (order.orderBudgetJpy - order.spentJpy < order.cycleBudgetJpy) {
                throw Rollback(OrderResult.Refused(OrderConflict.ORDER_BUDGET, order.orderId))
            }
        }
        if (path.any { unpricedCycleOf(it.orderId) }) throw Rollback(OrderResult.Refused(OrderConflict.ORDER_UNPRICED))

        val cycleNo = current.cyclesStarted + 1
        val goalId = Ulid.generate(clock)
        execUpdate(
            """
            INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, body, target_repo, state, revision, version,
                created_at, updated_at, order_id, cycle_no)
            VALUES (?, ?, ?, ?, ?, ?, 'GOAL_SUBMITTED', 0, 0, ?, ?, ?, ?)
            """.trimIndent(),
            goalId, by.goalCreator, "order:$orderId:cycle:$cycleNo", current.title, current.policy, current.targetRepo, now, now,
            orderId, cycleNo,
        )
        val changed = execUpdate(
            """
            UPDATE selfdev_orders SET cycles_started = cycles_started + 1, current_goal_id = ?, version = version + 1, updated_at = ?
             WHERE order_id = ? AND version = ?
            """.trimIndent(),
            goalId, now, orderId, current.version,
        )
        if (changed != 1) throw Rollback(OrderResult.StaleVersion)
        appendEvent(orderId, cycleNo, "CYCLE_STARTED", OrderState.ACTIVE, OrderState.ACTIVE, by.reasonCode, by.actor, by.actorKey, null, now)
        return OrderResult.Moved(CycleStarted(getIn(orderId) ?: error("order vanished after cycle start"), goalId, cycleNo))
    }

    /**
     * The rules that only the automatic start has (ADR 0015 section 3.2). The previous cycle's start time is the newest goal of the order
     * (the goal is created in the same transaction as the start, so there is no separate column), and the day is the UTC day. A cycle an
     * operator started counts too: the interval and the daily count are about the order, not about who started the cycle.
     */
    private fun Transaction.checkAutomaticRules(path: List<SelfDevOrder>, breakerOpen: Boolean, now: LocalDateTime) {
        val current = path.first()
        if (breakerOpen) throw AutoSkip(AutoCycleSkip.BREAKER_OPEN)
        if (current.consecutiveFailedCycles >= CYCLE_FAILURE_LIMIT) throw AutoSkip(AutoCycleSkip.CYCLE_FAILURES)
        // One cycle at a time in a tree. Every start locks the root first, so this read cannot race with another start.
        if (subtreeIn(path.last().orderId).any { it.currentGoalId != null }) throw AutoSkip(AutoCycleSkip.TREE_BUSY)
        val last = execQuery("SELECT MAX(created_at) FROM selfdev_goals WHERE order_id = ?", current.orderId) {
            it.getObject(1, LocalDateTime::class.java)
        }.firstOrNull()
        if (last != null && now.isBefore(last.plus(AUTO_CYCLE_MIN_INTERVAL))) throw AutoSkip(AutoCycleSkip.TOO_SOON)
        val today = execQuery(
            "SELECT COUNT(*) FROM selfdev_goals WHERE order_id = ? AND created_at >= ?",
            current.orderId, now.toLocalDate().atStartOfDay(),
        ) { it.getInt(1) }.single()
        if (today >= AUTO_CYCLE_DAILY_MAX) throw AutoSkip(AutoCycleSkip.DAILY_LIMIT)
    }

    /**
     * True when a cycle of [orderId] is not final and its ledger holds an unconfirmed UNPRICED row (ADR 0015 addendum 2026-10-23, C.4 and
     * U-35). Only the cycles with a spend row are read (the tick creates the row, and only when the spend switch is on), so with the switch
     * off nothing is refused. Read only: the ledger is read under the order locks of the caller, and nothing is locked here.
     */
    private fun Transaction.unpricedCycleOf(orderId: String): Boolean {
        val cycleNos = execQuery(
            """
            SELECT g.cycle_no
              FROM selfdev_goals g
              JOIN selfdev_order_cycle_spend s ON s.order_id = g.order_id AND s.cycle_no = g.cycle_no
             WHERE g.order_id = ? AND s.final = FALSE
            """.trimIndent(),
            orderId,
        ) { it.getInt(1) }
        return cycleNos.any { cycleNo -> with(spendReader) { spendOf(orderId, cycleNo).hasUnconfirmedUnpriced } }
    }

    /**
     * Records the outcome of every cycle whose goal has ended (DONE or STOPPED), and clears the order's current goal. A success resets the
     * failure count; a failure adds one. When the cycle limit is reached, or the failures reach [CYCLE_FAILURE_LIMIT], an ACTIVE order is
     * paused with that reason. An order in STOP_REQUESTED is left alone: its version is the approval's binding. Returns the number evaluated.
     */
    fun evaluateEndedCycles(limit: Int): Int {
        val candidates = transaction(database) {
            execQuery(
                """
                SELECT o.order_id FROM selfdev_orders o JOIN selfdev_goals g ON g.goal_id = o.current_goal_id
                 WHERE o.state IN ('ACTIVE', 'PAUSED') AND g.state IN ('DONE', 'STOPPED') ORDER BY o.updated_at, o.order_id LIMIT ?
                """.trimIndent(),
                limit,
            ) { it.getString(1) }
        }
        var evaluated = 0
        for (orderId in candidates) {
            val done = try {
                evaluateOne(orderId)
            } catch (r: Rollback) {
                r.outcome
                null
            }
            if (done is OrderResult.Moved) evaluated++
        }
        return evaluated
    }

    private fun evaluateOne(orderId: String): OrderResult<SelfDevOrder> = transaction(database) {
        val current = lockOrder(orderId) ?: return@transaction OrderResult.NotFound
        val goalId = current.currentGoalId ?: return@transaction OrderResult.Refused(OrderConflict.NOT_ACTIVE)
        if (current.state != OrderState.ACTIVE && current.state != OrderState.PAUSED) {
            return@transaction OrderResult.Refused(OrderConflict.NOT_ACTIVE)
        }
        val goal = execQuery("SELECT state, state_reason FROM selfdev_goals WHERE goal_id = ?", goalId) { rs ->
            GoalState.valueOf(rs.getString(1)) to rs.getString(2)
        }.singleOrNull() ?: return@transaction OrderResult.Refused(OrderConflict.NOT_ACTIVE)
        val (goalState, goalReason) = goal
        if (goalState != GoalState.DONE && goalState != GoalState.STOPPED) return@transaction OrderResult.Refused(OrderConflict.NOT_ACTIVE)

        val now = utcNow(clock)
        val failures = if (goalState == GoalState.DONE) 0 else current.consecutiveFailedCycles + 1
        if (!updateCycleOutcome(orderId, current.version, failures, now)) throw Rollback(OrderResult.StaleVersion)
        val cycleNo = current.cyclesStarted
        appendEvent(orderId, cycleNo, "CYCLE_EVALUATED", current.state, current.state, goalReason, OrderActor.SYSTEM, null, null, now)

        if (current.state == OrderState.ACTIVE) {
            val pauseReason = when {
                current.cyclesStarted >= current.maxCycles -> OrderReason.LIMIT_REACHED
                failures >= CYCLE_FAILURE_LIMIT -> OrderReason.CYCLE_FAILURES
                else -> null
            }
            if (pauseReason != null) {
                if (!updateState(orderId, current.version + 1, OrderState.PAUSED, pauseReason, now)) throw Rollback(OrderResult.StaleVersion)
                appendEvent(orderId, cycleNo, "PAUSED", OrderState.ACTIVE, OrderState.PAUSED, pauseReason.name, OrderActor.SYSTEM, null, null, now)
            }
        }
        OrderResult.Moved(getIn(orderId) ?: error("order vanished after cycle evaluation"))
    }

    // ---- cycle spend (ADR 0015 addendum 2026-10-23, stage 3g) --------------------------------------------------------------------

    /** One cycle whose cost is still to be counted: its goal, its order and its number. */
    data class SpendCycle(val goalId: String, val orderId: String, val cycleNo: Int)

    private val spendReader = SelfDevCycleSpendReader(database)

    /**
     * The cycles to count (section F, 3g): every cycle whose spend row is not final, and every cycle that has a goal but no spend row yet
     * (its first count). Not filtered by the order's state, so the cost of a stopped order's cycle is still counted until it is final (U-38).
     */
    fun unfinishedSpendCycles(limit: Int): List<SpendCycle> = transaction(database) {
        execQuery(
            """
            SELECT g.goal_id, g.order_id, g.cycle_no
              FROM selfdev_goals g
              LEFT JOIN selfdev_order_cycle_spend s ON s.order_id = g.order_id AND s.cycle_no = g.cycle_no
             WHERE g.order_id IS NOT NULL AND (s.order_id IS NULL OR s.final = FALSE)
             ORDER BY g.created_at, g.goal_id LIMIT ?
            """.trimIndent(),
            limit,
        ) { SpendCycle(it.getString(1), it.getString(2), it.getInt(3)) }
    }

    /**
     * Counts the cost of one cycle (section C.2), in one transaction: the order path is locked from the root down, the cycle's spend row is
     * locked (or created), the ledger is read again under those locks, and the difference between the new and the counted total is added to
     * the `spent_jpy` of every order on the path. Adding the difference (not the total) makes a second run with the same ledger a no-op.
     * A spend row whose cycle is final is left alone. Returns true when something was written. Throws on a failure; nothing is written then.
     */
    fun countCycleSpend(cycle: SpendCycle): Boolean = transaction(database) {
        val path = lockPath(cycle.orderId) ?: return@transaction false
        val order = path.first()
        val now = utcNow(clock)
        val before = execQuery(
            "SELECT counted_jpy, ledger_micro_yen, final FROM selfdev_order_cycle_spend WHERE order_id = ? AND cycle_no = ? FOR UPDATE",
            cycle.orderId, cycle.cycleNo,
        ) { rs -> SpendRow(rs.getLong(1), rs.getLong(2), rs.getBoolean(3)) }.firstOrNull()
        if (before?.final == true) return@transaction false
        if (before == null) {
            execUpdate(
                """
                INSERT INTO selfdev_order_cycle_spend (order_id, cycle_no, goal_id, ledger_micro_yen, counted_jpy, final, updated_at)
                VALUES (?, ?, ?, 0, 0, FALSE, ?)
                """.trimIndent(),
                cycle.orderId, cycle.cycleNo, cycle.goalId, now,
            )
        }
        val oldCounted = before?.counted ?: 0L
        val oldLedger = before?.ledger ?: 0L
        val spend = with(spendReader) { spendOf(cycle.orderId, cycle.cycleNo) }
        val total = spend.committedMicroYen + spend.lapsedMicroYen + spend.reservedMicroYen + spend.unpricedMicroYen
        val counted = ceilYen(total)
        val delta = counted - oldCounted
        val final = isSpendFinal(cycle.goalId, spend)
        if (delta == 0L && total == oldLedger && !final) return@transaction before == null
        if (delta != 0L) {
            for (row in path) {
                // Never below zero: a fall larger than the total counted so far is cut off at zero (section C.2).
                val next = if (row.spentJpy + delta < 0) 0L else row.spentJpy + delta
                execUpdate("UPDATE selfdev_orders SET spent_jpy = ? WHERE order_id = ?", next, row.orderId)
            }
        }
        execUpdate(
            "UPDATE selfdev_order_cycle_spend SET ledger_micro_yen = ?, counted_jpy = ?, final = ?, updated_at = ? WHERE order_id = ? AND cycle_no = ?",
            total, counted, final, now, cycle.orderId, cycle.cycleNo,
        )
        if (delta != 0L) {
            val child = order.parentOrderId != null
            appendEvent(
                cycle.orderId, cycle.cycleNo, SPEND_COUNTED, null, null, if (final) "FINAL" else null, OrderActor.SYSTEM, null, null, now,
                parentOrderId = order.parentOrderId, depth = order.depth.takeIf { child }, amountJpy = delta,
            )
        }
        true
    }

    /**
     * A cycle is final when its goal has ended, no attempt of it is active, and the ledger holds no RESERVED or LAPSED row that can still
     * change, and no UNPRICED row waiting for an operator (section C.2). The rows are counted, not the amounts: a zero-yen RESERVED or LAPSED
     * row can gain an amount later (true-up), so it keeps the cycle open (ADR 0015 addendum 2026-10-23, K.2). Until then the cycle is
     * counted again on every tick.
     */
    private fun Transaction.isSpendFinal(goalId: String, spend: CycleSpend): Boolean {
        val state = execQuery("SELECT state FROM selfdev_goals WHERE goal_id = ?", goalId) { GoalState.valueOf(it.getString(1)) }.firstOrNull()
        return (state == GoalState.DONE || state == GoalState.STOPPED) &&
            spend.attemptsActive == 0 && spend.reservedRows == 0 && spend.lapsedRows == 0 && !spend.hasUnconfirmedUnpriced
    }

    /** The spend row as locked: the counted yen, the last ledger total (micro-yen) and the final flag. */
    private class SpendRow(val counted: Long, val ledger: Long, val final: Boolean)

    // ---- stop requests -----------------------------------------------------------------------------------------------------------

    /**
     * Asks to stop the order. The order moves to STOP_REQUESTED at once, so no new cycle starts while the request waits. The request binds to
     * the order's new version and to a digest of the request. At most one request is PENDING per tree: the UNIQUE `active_key` refuses a
     * second one, returned as [OrderConflict.STOP_ALREADY_PENDING].
     */
    fun requestStop(
        orderId: String,
        expectedVersion: Long,
        requestedBy: String,
        requesterKind: OrderActor,
        reason: StopRequestReason,
    ): OrderResult<SelfDevOrderStopRequest> {
        val result = requestStopInTransaction(orderId, expectedVersion, requestedBy, requesterKind, reason)
        // After the commit, and contained: a listener (the shadow judgement of ADR 0021) never delays or fails the request.
        if (result is OrderResult.Moved) {
            try {
                stopRequestListener?.created(result.value.requestId)
            } catch (_: Exception) {
                // Nothing is logged here: the periodic sweep of the judgement picks the request up.
            }
        }
        return result
    }

    private fun requestStopInTransaction(
        orderId: String,
        expectedVersion: Long,
        requestedBy: String,
        requesterKind: OrderActor,
        reason: StopRequestReason,
    ): OrderResult<SelfDevOrderStopRequest> = try {
        // The automatic reason is decided by the parent's loop only (autoStopChild), never requested.
        require(reason !in StopRequestReason.AUTOMATIC) { "the automatic stop reason is not requestable" }
        transaction(database) {
            // The path is locked from the root down, and then the descendants: the same order as a cycle start and a child creation.
            val path = lockPath(orderId) ?: return@transaction OrderResult.NotFound
            val current = path.first()
            if (current.version != expectedVersion) return@transaction OrderResult.StaleVersion
            // A request is already waiting on this tree: that is the answer, not a generic transition refusal.
            if (current.state == OrderState.STOP_REQUESTED) throw Rollback(OrderResult.Refused(OrderConflict.STOP_ALREADY_PENDING))
            val decision = checkOrderTransition(current.state, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED, requesterKind, current.stateReason)
            if (decision is OrderTransitionDecision.Refused) {
                throw Rollback(OrderResult.Refused(OrderConflict.valueOf(decision.reason.name)))
            }
            val now = utcNow(clock)
            if (!updateState(orderId, expectedVersion, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED, now)) {
                throw Rollback(OrderResult.StaleVersion)
            }
            val newVersion = expectedVersion + 1
            // The stopped part at the request's time: the target at its new version, and each descendant at its current version.
            val descendants = lockDescendants(orderId)
            val subtree = listOf(orderId to newVersion) + descendants.map { it.orderId to it.version }
            val requestId = Ulid.generate(clock)
            execUpdate(
                """
                INSERT INTO selfdev_order_stop_requests (request_id, order_id, requested_by, requester_kind, reason_code, order_version,
                    digest, state, active_key, decided_by, decider_kind, decided_at, expires_at, created_at, target_is_root)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, NULL, NULL, NULL, ?, ?, ?)
                """.trimIndent(),
                requestId, orderId, requestedBy, requesterKind.name, reason.name, newVersion,
                orderStopDigest(requestId, orderId, subtree, reason), now.plus(stopTtl), now, if (current.parentOrderId == null) 1 else 0,
            )
            appendTreeEvent(current, "STOP_REQUESTED", current.state, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED.name, requesterKind, requestedBy, requestId, now)
            OrderResult.Moved(getRequestIn(requestId) ?: error("stop request vanished after insert"))
        }
    } catch (r: Rollback) {
        r.outcome
    } catch (e: Exception) {
        if (!e.hasUniqueViolation()) throw e
        OrderResult.Refused(OrderConflict.STOP_ALREADY_PENDING)
    }

    /**
     * Approves a PENDING stop request: the request is CONSUMED and the stopped part of the tree is STOPPED, in one transaction. The target
     * goes to STOPPED with OPERATOR_STOPPED (the root) or PARENT_STOPPED (a child), and each descendant goes to STOPPED with ANCESTOR_STOPPED
     * (ADR 0015 section 4.2 (a), (c) 2). The caller has checked that [approverKey] is the root approver.
     *
     * For the root the requester must differ from the approver (`target_is_root`, also checked by the CHECK in the schema). A child's stop is
     * the parent's decision, so the requester may decide it. Refused, with nothing written, when the digest does not match, the request is not
     * pending, the target or any descendant has changed since the request (the digest of the locked part is recomputed), or the request has
     * expired (the expiry itself is then recorded). Only one of two concurrent approvals of the same request can succeed: the conditional
     * UPDATE of the request decides it.
     */
    fun approveStop(requestId: String, approverKey: String, digest: String): OrderResult<SelfDevOrder> = try {
        transaction(database) {
            val request = lockRequest(requestId) ?: return@transaction OrderResult.NotFound
            if (request.state != StopRequestState.PENDING) return@transaction OrderResult.Refused(OrderConflict.STOP_NOT_PENDING)
            val now = utcNow(clock)
            if (!now.isBefore(request.expiresAt)) {
                expireLocked(request, now)
                return@transaction OrderResult.Refused(OrderConflict.STOP_EXPIRED)
            }
            if (request.targetIsRoot && request.requestedBy == approverKey) return@transaction OrderResult.Refused(OrderConflict.SAME_PRINCIPAL)
            if (request.digest != digest) return@transaction OrderResult.Refused(OrderConflict.DIGEST_MISMATCH)
            val path = lockPath(request.orderId) ?: return@transaction OrderResult.NotFound
            val order = path.first()
            if (order.state != OrderState.STOP_REQUESTED || order.version != request.orderVersion) {
                return@transaction OrderResult.Refused(OrderConflict.ORDER_CHANGED)
            }
            val descendants = lockDescendants(order.orderId)
            // The stored digest binds the stopped part as it was at the request. Any change in it since then (a descendant's version) refuses.
            val subtree = listOf(order.orderId to order.version) + descendants.map { it.orderId to it.version }
            if (orderStopDigest(request.requestId, order.orderId, subtree, request.reasonCode) != request.digest) {
                return@transaction OrderResult.Refused(OrderConflict.ORDER_CHANGED)
            }
            val reason = if (request.targetIsRoot) OrderReason.OPERATOR_STOPPED else OrderReason.PARENT_STOPPED
            val decision = checkOrderTransition(order.state, OrderState.STOPPED, reason, OrderActor.HUMAN, order.stateReason)
            if (decision is OrderTransitionDecision.Refused) {
                throw Rollback(OrderResult.Refused(OrderConflict.valueOf(decision.reason.name)))
            }
            // A descendant already STOPPED is final and is skipped, as autoStopChild skips it: the digest above still covers it.
            val live = descendants.filter { it.state != OrderState.STOPPED }
            for (descendant in live) {
                val sub = checkSubtreeStop(descendant.state, OrderActor.HUMAN)
                if (sub is OrderTransitionDecision.Refused) throw Rollback(OrderResult.Refused(OrderConflict.valueOf(sub.reason.name)))
            }

            val consumed = execUpdate(
                """
                UPDATE selfdev_order_stop_requests SET state = 'CONSUMED', active_key = NULL, decided_by = ?, decider_kind = 'HUMAN', decided_at = ?
                 WHERE request_id = ? AND state = 'PENDING'
                """.trimIndent(),
                approverKey, now, requestId,
            )
            if (consumed != 1) throw Rollback(OrderResult.Refused(OrderConflict.STOP_NOT_PENDING))
            if (!updateState(order.orderId, order.version, OrderState.STOPPED, reason, now)) {
                throw Rollback(OrderResult.StaleVersion)
            }
            appendTreeEvent(order, "STOP_APPROVED", OrderState.STOP_REQUESTED, OrderState.STOP_REQUESTED, null, OrderActor.HUMAN, approverKey, requestId, now)
            appendTreeEvent(order, "STOPPED", OrderState.STOP_REQUESTED, OrderState.STOPPED, reason.name, OrderActor.HUMAN, approverKey, requestId, now)
            // The descendants, level by level (the order of the locks). Their version was checked above and is current under the locks.
            for (descendant in live) {
                if (!updateState(descendant.orderId, descendant.version, OrderState.STOPPED, OrderReason.ANCESTOR_STOPPED, now)) {
                    throw Rollback(OrderResult.StaleVersion)
                }
                appendTreeEvent(descendant, "STOPPED", descendant.state, OrderState.STOPPED, OrderReason.ANCESTOR_STOPPED.name, OrderActor.HUMAN, approverKey, requestId, now)
            }
            OrderResult.Moved(getIn(order.orderId) ?: error("order vanished after stop"))
        }
    } catch (r: Rollback) {
        r.outcome
    }

    /** The order and its descendants, read without locks (the order first, then level by level). Read after an approval, when the stopped part is final. */
    fun subtreeOf(orderId: String): List<SelfDevOrder> = transaction(database) { subtreeIn(orderId) }

    /** The order and its descendants, in the caller's transaction. Empty when the order does not exist. */
    private fun Transaction.subtreeIn(orderId: String): List<SelfDevOrder> {
        val root = getIn(orderId) ?: return emptyList()
        val out = mutableListOf(root)
        var level = listOf(orderId)
        while (level.isNotEmpty()) {
            val children = level.flatMap { parent ->
                execQuery("SELECT $ORDER_COLUMNS FROM selfdev_orders WHERE parent_order_id = ? ORDER BY order_id", parent) { it.toOrder() }
            }
            out += children
            level = children.map { it.orderId }
        }
        return out
    }

    /**
     * Ends a PENDING stop request without stopping the order: a rejection by the approver, or a cancellation. The order goes back to PAUSED
     * with STOP_NOT_APPROVED, and is never resumed by itself. A rejection must come from someone other than the requester, and the caller
     * has checked the role. A cancellation keeps `decided_by` NULL; the canceller is in the event row.
     */
    fun endStopRequest(requestId: String, end: StopEnd, actorKey: String, actorKind: OrderActor): OrderResult<SelfDevOrder> = try {
        transaction(database) {
            val request = lockRequest(requestId) ?: return@transaction OrderResult.NotFound
            if (request.state != StopRequestState.PENDING) return@transaction OrderResult.Refused(OrderConflict.STOP_NOT_PENDING)
            val now = utcNow(clock)
            if (!now.isBefore(request.expiresAt)) {
                expireLocked(request, now)
                return@transaction OrderResult.Refused(OrderConflict.STOP_EXPIRED)
            }
            if (end == StopEnd.REJECTED && request.targetIsRoot && request.requestedBy == actorKey) {
                return@transaction OrderResult.Refused(OrderConflict.SAME_PRINCIPAL)
            }
            val order = lockOrder(request.orderId) ?: return@transaction OrderResult.NotFound
            if (order.state != OrderState.STOP_REQUESTED || order.version != request.orderVersion) {
                return@transaction OrderResult.Refused(OrderConflict.ORDER_CHANGED)
            }
            val newState = if (end == StopEnd.REJECTED) "REJECTED" else "CANCELLED"
            val decidedBy = if (end == StopEnd.REJECTED) actorKey else null
            val decider = if (end == StopEnd.REJECTED) "HUMAN" else null
            val ended = execUpdate(
                """
                UPDATE selfdev_order_stop_requests SET state = ?, active_key = NULL, decided_by = ?, decider_kind = ?, decided_at = ?
                 WHERE request_id = ? AND state = 'PENDING'
                """.trimIndent(),
                newState, decidedBy, decider, now, requestId,
            )
            if (ended != 1) throw Rollback(OrderResult.Refused(OrderConflict.STOP_NOT_PENDING))
            if (!updateState(order.orderId, order.version, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED, now)) {
                throw Rollback(OrderResult.StaleVersion)
            }
            val kind = if (end == StopEnd.REJECTED) "STOP_REJECTED" else "STOP_CANCELLED"
            appendEvent(order.orderId, order.cyclesStarted.takeIf { it > 0 }, kind, OrderState.STOP_REQUESTED, OrderState.STOP_REQUESTED, null, actorKind, actorKey, requestId, now)
            appendEvent(order.orderId, order.cyclesStarted.takeIf { it > 0 }, "PAUSED", OrderState.STOP_REQUESTED, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED.name, OrderActor.SYSTEM, null, requestId, now)
            OrderResult.Moved(getIn(order.orderId) ?: error("order vanished after stop request end"))
        }
    } catch (r: Rollback) {
        r.outcome
    }

    /**
     * Expires the PENDING requests whose 24 hours are over: each goes EXPIRED, and its order (if still STOP_REQUESTED at the request's version)
     * goes PAUSED with STOP_NOT_APPROVED. Returns the number expired.
     */
    fun expireDueStopRequests(limit: Int): Int {
        val now = utcNow(clock)
        val ids = transaction(database) {
            execQuery(
                "SELECT request_id FROM selfdev_order_stop_requests WHERE state = 'PENDING' AND expires_at <= ? ORDER BY expires_at LIMIT ?",
                now, limit,
            ) { it.getString(1) }
        }
        var expired = 0
        for (id in ids) {
            val done = transaction(database) {
                val request = lockRequest(id) ?: return@transaction false
                if (request.state != StopRequestState.PENDING || now.isBefore(request.expiresAt)) return@transaction false
                expireLocked(request, now)
                true
            }
            if (done) expired++
        }
        return expired
    }

    /** Expires one request under its lock. Caller holds the transaction. */
    private fun Transaction.expireLocked(request: SelfDevOrderStopRequest, now: LocalDateTime) {
        execUpdate("UPDATE selfdev_order_stop_requests SET state = 'EXPIRED', active_key = NULL WHERE request_id = ? AND state = 'PENDING'", request.requestId)
        appendEvent(request.orderId, null, "STOP_EXPIRED", OrderState.STOP_REQUESTED, OrderState.STOP_REQUESTED, null, OrderActor.SYSTEM, null, request.requestId, now)
        val order = lockOrder(request.orderId) ?: return
        if (order.state == OrderState.STOP_REQUESTED && order.version == request.orderVersion) {
            updateState(order.orderId, order.version, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED, now)
            appendEvent(order.orderId, null, "PAUSED", OrderState.STOP_REQUESTED, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED.name, OrderActor.SYSTEM, null, request.requestId, now)
        }
    }

    // ---- automatic child stop (ADR 0015 section 4.4) -----------------------------------------------------------------------------

    /**
     * The facts of CHILD_BUDGET_EXHAUSTED for [orderId] (ADR 0015 addendum K.1). Read only: no lock, no write. Null when the order does not exist.
     * The automatic decision reads the same facts under the tree's locks ([autoStopChild]).
     */
    fun budgetExhaustedFacts(orderId: String): BudgetExhaustedFacts? = transaction(database) {
        val order = getIn(orderId) ?: return@transaction null
        val part = subtreeIn(orderId).map { it.orderId }
        budgetFactsIn(order, part)
    }

    /**
     * The children whose cost reached their budget, the cheap first filter of CHILD_BUDGET_EXHAUSTED. The facts are checked under the lock.
     * The parent must be ACTIVE: [autoStopChild] answers PARENT_NOT_ACTIVE for any other parent without writing, so such a child would only
     * hold a place in the LIMIT, ahead of the candidates behind it.
     */
    fun budgetExhaustedCandidates(limit: Int): List<String> = transaction(database) {
        execQuery(
            """
            SELECT o.order_id FROM selfdev_orders o JOIN selfdev_orders p ON p.order_id = o.parent_order_id
             WHERE o.parent_order_id IS NOT NULL AND o.state IN ('ACTIVE', 'PAUSED') AND o.spent_jpy >= o.order_budget_jpy
               AND p.state = 'ACTIVE'
             ORDER BY o.updated_at, o.order_id LIMIT ?
            """.trimIndent(),
            limit,
        ) { it.getString(1) }
    }

    /**
     * The children that may be stopped automatically now: not root, ACTIVE or PAUSED, failures at the limit, and a direct parent that is ACTIVE
     * (the same first filter as [budgetExhaustedCandidates]). [autoStopChild] checks each again.
     */
    fun autoStopCandidates(limit: Int): List<String> = transaction(database) {
        execQuery(
            """
            SELECT o.order_id FROM selfdev_orders o JOIN selfdev_orders p ON p.order_id = o.parent_order_id
             WHERE o.parent_order_id IS NOT NULL AND o.state IN ('ACTIVE', 'PAUSED') AND o.consecutive_failed_cycles >= ?
               AND p.state = 'ACTIVE'
             ORDER BY o.updated_at, o.order_id LIMIT ?
            """.trimIndent(),
            CYCLE_FAILURE_LIMIT, limit,
        ) { it.getString(1) }
    }

    /**
     * Decides, for one child, the automatic stop of ADR 0015 section 4.4, under the tree's locks (the path from the root, then every order below
     * the root). The child and its descendants are STOPPED only when every condition holds: the child is not the root, it is ACTIVE or PAUSED,
     * its direct parent is effectively ACTIVE (the parent and its ancestors), its failures are at [CYCLE_FAILURE_LIMIT], no stop request of the
     * tree is PENDING (a person's request is not taken over), and the frequency limit allows it (one per parent per UTC day, two per tree).
     * A condition that does not hold writes nothing. The decision is one transaction: the request row (CONSUMED, decided by the parent), the
     * events, and the stop of the part. Its events name the parent and the depth.
     */
    fun autoStopChild(childId: String, reason: StopRequestReason = StopRequestReason.CHILD_CYCLE_FAILURES): AutoStopResult = transaction(database) {
        require(reason in StopRequestReason.AUTOMATIC) { "the reason is not an automatic child stop reason" }
        val path = lockPath(childId) ?: return@transaction AutoStopResult.NotDecided(AutoStopSkip.NOT_FOUND)
        val child = path.first()
        val parent = path.getOrNull(1) ?: return@transaction AutoStopResult.NotDecided(AutoStopSkip.IS_ROOT)
        val root = path.last()
        if (child.state != OrderState.ACTIVE && child.state != OrderState.PAUSED) {
            return@transaction AutoStopResult.NotDecided(AutoStopSkip.CHILD_NOT_STOPPABLE)
        }
        if (firstStoppingAncestor(path.drop(1)) != null) return@transaction AutoStopResult.NotDecided(AutoStopSkip.PARENT_NOT_ACTIVE)
        if (reason == StopRequestReason.CHILD_CYCLE_FAILURES && child.consecutiveFailedCycles < CYCLE_FAILURE_LIMIT) {
            return@transaction AutoStopResult.NotDecided(AutoStopSkip.CYCLE_FAILURES_BELOW_LIMIT)
        }

        val tree = lockDescendants(root.orderId)
        val treeIds = listOf(root.orderId) + tree.map { it.orderId }
        val inTree = treeIds.joinToString(",") { "?" }
        val pending = execQuery(
            "SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE state = 'PENDING' AND order_id IN ($inTree)",
            *treeIds.toTypedArray(),
        ) { it.getInt(1) }.single()
        if (pending > 0) return@transaction AutoStopResult.NotDecided(AutoStopSkip.PENDING_REQUEST)

        // The part to stop: the child and its descendants that are not STOPPED yet (a descendant stopped by an earlier approval is final).
        val below = ordersBelow(child.orderId, tree)
        if (reason == StopRequestReason.CHILD_BUDGET_EXHAUSTED) {
            val facts = budgetFactsIn(child, listOf(child.orderId) + below.map { it.orderId })
            if (!facts.exhausted) return@transaction AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED)
        }
        val toStop = below.filter { it.state != OrderState.STOPPED }
        val targetDecision = checkAutomaticStop(child.state, OrderReason.PARENT_STOPPED)
        if (targetDecision is OrderTransitionDecision.Refused ||
            toStop.any { checkAutomaticStop(it.state, OrderReason.ANCESTOR_STOPPED) is OrderTransitionDecision.Refused }
        ) {
            return@transaction AutoStopResult.NotDecided(AutoStopSkip.CHILD_NOT_STOPPABLE)
        }

        val now = utcNow(clock)
        val dayStart = now.toLocalDate().atStartOfDay()
        val dayEnd = dayStart.plusDays(1)
        val byParent = execQuery(
            "SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE decider_kind = 'ORDER' AND decided_at >= ? AND decided_at < ? " +
                "AND decider_order_id = ?",
            dayStart, dayEnd, parent.orderId,
        ) { it.getInt(1) }.single()
        val byTree = execQuery(
            "SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE decider_kind = 'ORDER' AND decided_at >= ? AND decided_at < ? " +
                "AND order_id IN ($inTree)",
            dayStart, dayEnd, *treeIds.toTypedArray(),
        ) { it.getInt(1) }.single()
        if (byParent >= 1 || byTree >= 2) {
            // The operator is told once per child per day (the tick runs every minute). Nothing is stopped.
            val recorded = execQuery(
                "SELECT COUNT(*) FROM selfdev_order_events WHERE order_id = ? AND kind = 'AUTO_STOP_RATE_LIMITED' AND occurred_at >= ?",
                child.orderId, dayStart,
            ) { it.getInt(1) }.single() == 0
            if (recorded) {
                appendEvent(
                    child.orderId, null, "AUTO_STOP_RATE_LIMITED", child.state, child.state, reason.name,
                    OrderActor.SYSTEM, null, null, now, parentOrderId = parent.orderId, depth = child.depth,
                )
            }
            return@transaction AutoStopResult.RateLimited(parent.orderId, recorded)
        }

        val requestId = Ulid.generate(clock)
        // The stopped part as it is before this change: the child and the descendants that will stop, at their current versions.
        val subtree = listOf(child.orderId to child.version) + toStop.map { it.orderId to it.version }
        execUpdate(
            """
            INSERT INTO selfdev_order_stop_requests (request_id, order_id, requested_by, requester_kind, reason_code, order_version,
                digest, state, active_key, decided_by, decider_kind, decider_order_id, decided_at, expires_at, created_at, target_is_root)
            VALUES (?, ?, ?, 'SYSTEM', ?, ?, ?, 'CONSUMED', NULL, ?, 'ORDER', ?, ?, ?, ?, 0)
            """.trimIndent(),
            requestId, child.orderId, AUTO_STOP_PRINCIPAL, reason.name, child.version,
            orderStopDigest(requestId, child.orderId, subtree, reason),
            AUTO_STOP_PRINCIPAL, parent.orderId, now, now.plus(stopTtl), now,
        )
        // The versions were read under the locks, so a conditional UPDATE that misses means a bug: the check throws and the whole decision rolls back.
        check(updateState(child.orderId, child.version, OrderState.STOPPED, OrderReason.PARENT_STOPPED, now)) { "child moved under its lock" }
        appendEvent(
            child.orderId, child.cyclesStarted.takeIf { it > 0 }, "STOP_APPROVED", child.state, OrderState.STOPPED,
            reason.name, OrderActor.SYSTEM, null, requestId, now, parentOrderId = parent.orderId, depth = child.depth,
        )
        appendEvent(
            child.orderId, child.cyclesStarted.takeIf { it > 0 }, "STOPPED", child.state, OrderState.STOPPED,
            reason.name, OrderActor.SYSTEM, null, requestId, now, parentOrderId = parent.orderId, depth = child.depth,
        )
        // The descendants, level by level, as an approved stop stops them (ANCESTOR_STOPPED).
        for (descendant in toStop) {
            check(updateState(descendant.orderId, descendant.version, OrderState.STOPPED, OrderReason.ANCESTOR_STOPPED, now)) {
                "descendant moved under its lock"
            }
            appendTreeEvent(descendant, "STOPPED", descendant.state, OrderState.STOPPED, OrderReason.ANCESTOR_STOPPED.name, OrderActor.SYSTEM, null, requestId, now)
        }
        AutoStopResult.Stopped(getIn(child.orderId) ?: error("child vanished after the automatic stop"))
    }

    /** The facts of CHILD_BUDGET_EXHAUSTED for [order], whose part is [partIds] (the order and its descendants). Read only. */
    private fun Transaction.budgetFactsIn(order: SelfDevOrder, partIds: List<String>): BudgetExhaustedFacts {
        val inList = partIds.joinToString(",") { "?" }
        val unfinished = execQuery(
            """
            SELECT COUNT(*) FROM selfdev_goals g
              LEFT JOIN selfdev_order_cycle_spend s ON s.order_id = g.order_id AND s.cycle_no = g.cycle_no
             WHERE g.order_id IN ($inList) AND (s.order_id IS NULL OR s.final = FALSE)
            """.trimIndent(),
            *partIds.toTypedArray(),
        ) { it.getInt(1) }.single()
        // A Run is open when its state is not terminal, or when one of its CLI attempts is active (coding_attempts.active_flag).
        val openRuns = execQuery(
            """
            SELECT COUNT(*) FROM development_runs r
             WHERE r.run_id IN (
                   SELECT t.run_id FROM selfdev_goals g
                     JOIN selfdev_plans p ON p.goal_id = g.goal_id
                     JOIN selfdev_plan_tasks t ON t.plan_id = p.plan_id
                    WHERE g.order_id IN ($inList) AND t.run_id IS NOT NULL)
               AND (r.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                    OR EXISTS (SELECT 1 FROM coding_attempts a WHERE a.run_id = r.run_id AND a.active_flag = 1))
            """.trimIndent(),
            *partIds.toTypedArray(),
        ) { it.getInt(1) }.single()
        return BudgetExhaustedFacts(order.spentJpy, order.orderBudgetJpy, unfinished, openRuns)
    }

    // ---- shared helpers ----------------------------------------------------------------------------------------------------------

    /** A generic guarded move for operator transitions (pause, resume). The state rule is checked before the write. */
    private fun move(
        orderId: String,
        expectedVersion: Long,
        to: OrderState,
        reason: OrderReason,
        actor: OrderActor,
        actorKey: String?,
        requestId: String?,
    ): OrderResult<SelfDevOrder> = try {
        transaction(database) {
            // A resume is checked against the ancestors (transition 5), so it locks the path, as a cycle start does. A pause locks the order.
            val path = (if (to == OrderState.ACTIVE) lockPath(orderId) else lockOrder(orderId)?.let { listOf(it) })
                ?: return@transaction OrderResult.NotFound
            val current = path.first()
            if (current.version != expectedVersion) return@transaction OrderResult.StaleVersion
            val decision = checkOrderTransition(current.state, to, reason, actor, current.stateReason)
            if (decision is OrderTransitionDecision.Refused) {
                throw Rollback(OrderResult.Refused(OrderConflict.valueOf(decision.reason.name)))
            }
            if (to == OrderState.ACTIVE) {
                firstStoppingAncestor(path.drop(1))?.let { throw Rollback(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, it.orderId)) }
            }
            val now = utcNow(clock)
            if (!updateState(orderId, expectedVersion, to, reason, now)) throw Rollback(OrderResult.StaleVersion)
            val kind = if (to == OrderState.ACTIVE) "RESUMED" else "PAUSED"
            appendEvent(orderId, current.cyclesStarted.takeIf { it > 0 }, kind, current.state, to, reason.name, actor, actorKey, requestId, now)
            OrderResult.Moved(getIn(orderId) ?: error("order vanished after move"))
        }
    } catch (r: Rollback) {
        r.outcome
    }

    /** Sets the state and the reason, and bumps the version. The reason is NULL for ACTIVE. Returns false unless exactly one row changed. */
    private fun Transaction.updateState(orderId: String, expectedVersion: Long, to: OrderState, reason: OrderReason?, now: LocalDateTime): Boolean =
        execUpdate(
            """
            UPDATE selfdev_orders SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
             WHERE order_id = ? AND version = ?
            """.trimIndent(),
            to.name, if (to == OrderState.ACTIVE) null else reason?.name, now, orderId, expectedVersion,
        ) == 1

    private fun Transaction.updateCycleOutcome(orderId: String, expectedVersion: Long, failures: Int, now: LocalDateTime): Boolean =
        execUpdate(
            """
            UPDATE selfdev_orders SET current_goal_id = NULL, consecutive_failed_cycles = ?, version = version + 1, updated_at = ?
             WHERE order_id = ? AND version = ?
            """.trimIndent(),
            failures, now, orderId, expectedVersion,
        ) == 1

    private fun Transaction.appendEvent(
        orderId: String,
        cycleNo: Int?,
        kind: String,
        from: OrderState?,
        to: OrderState?,
        reason: String?,
        actor: OrderActor,
        actorKey: String?,
        requestId: String?,
        now: LocalDateTime,
        parentOrderId: String? = null,
        depth: Int? = null,
        amountJpy: Long? = null,
    ) {
        execUpdate(
            """
            INSERT INTO selfdev_order_events (event_id, order_id, cycle_no, kind, from_state, to_state, reason_code, actor_kind, actor_ref,
                request_id, occurred_at, parent_order_id, depth, amount_jpy)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Ulid.generate(clock), orderId, cycleNo, kind, from?.name, to?.name, reason, actor.name, actorKey?.let(::actorRef), requestId, now,
            parentOrderId, depth, amountJpy,
        )
    }

    /**
     * An event of an order in a tree. A child's event names its parent and depth (ADR 0015 section 4.3: the parent who decided, the depth);
     * a root's event leaves both NULL, as before.
     */
    private fun Transaction.appendTreeEvent(
        order: SelfDevOrder,
        kind: String,
        from: OrderState?,
        to: OrderState?,
        reason: String?,
        actor: OrderActor,
        actorKey: String?,
        requestId: String?,
        now: LocalDateTime,
    ) {
        val child = order.parentOrderId != null
        appendEvent(
            order.orderId, order.cyclesStarted.takeIf { it > 0 }, kind, from, to, reason, actor, actorKey, requestId, now,
            parentOrderId = order.parentOrderId.takeIf { child }, depth = order.depth.takeIf { child },
        )
    }

    private fun Transaction.lockOrder(orderId: String): SelfDevOrder? =
        execQuery("SELECT $ORDER_COLUMNS FROM selfdev_orders WHERE order_id = ? FOR UPDATE", orderId) { it.toOrder() }.firstOrNull()

    /**
     * Locks the order and every ancestor, and returns the rows from the order up to the root, or null when the order does not exist. The
     * path is read without locks first; a parent never changes (ADR 0015 section 2.7), so the path is the same when it is locked. The rows
     * are then locked from the root down, the order every child creation uses too: two transactions in one tree take the same rows in the
     * same order, so they wait for each other and cannot deadlock.
     */
    private fun Transaction.lockPath(orderId: String): List<SelfDevOrder>? {
        val ids = mutableListOf<String>()
        var next: String? = orderId
        while (next != null) {
            ids += next
            next = getIn(next)?.parentOrderId
        }
        val locked = ids.asReversed().map { lockOrder(it) ?: return null }
        return locked.asReversed()
    }

    /**
     * Locks every descendant of [rootId] (not the root itself), level by level, and each level in ID order. The caller holds the root's path
     * already ([lockPath]), so the tree's child set cannot change under these locks (a child creation locks the root first).
     */
    private fun Transaction.lockDescendants(rootId: String): List<SelfDevOrder> {
        val locked = mutableListOf<SelfDevOrder>()
        var level = listOf(rootId)
        while (level.isNotEmpty()) {
            val children = level.flatMap { parent ->
                execQuery("SELECT order_id FROM selfdev_orders WHERE parent_order_id = ?", parent) { it.getString(1) }
            }.sorted()
            val next = children.map { id -> lockOrder(id) ?: error("child order vanished under its row lock") }
            locked += next
            level = next.map { it.orderId }
        }
        return locked
    }

    /** The orders below [rootId] in [tree] (the locked descendants of the root, parents before children). */
    private fun ordersBelow(rootId: String, tree: List<SelfDevOrder>): List<SelfDevOrder> {
        val inside = hashSetOf(rootId)
        return tree.filter { it.parentOrderId in inside && inside.add(it.orderId) }
    }

    private fun Transaction.lockRequest(requestId: String): SelfDevOrderStopRequest? =
        execQuery("SELECT $REQUEST_COLUMNS FROM selfdev_order_stop_requests WHERE request_id = ? FOR UPDATE", requestId) { it.toRequest() }
            .firstOrNull()

    private fun Transaction.getIn(orderId: String): SelfDevOrder? =
        execQuery("SELECT $ORDER_COLUMNS FROM selfdev_orders WHERE order_id = ?", orderId) { it.toOrder() }.firstOrNull()

    private fun Transaction.getRequestIn(requestId: String): SelfDevOrderStopRequest? =
        execQuery("SELECT $REQUEST_COLUMNS FROM selfdev_order_stop_requests WHERE request_id = ?", requestId) { it.toRequest() }.firstOrNull()

    private fun Transaction.findChildByKey(parentId: String, idempotencyKey: String): SelfDevOrder? =
        execQuery(
            "SELECT $ORDER_COLUMNS FROM selfdev_orders WHERE parent_order_id = ? AND idempotency_key = ?",
            parentId, idempotencyKey,
        ) { it.toOrder() }.firstOrNull()

    /**
     * The nearest ancestor that is not ACTIVE, or null when all are (ADR 0015 section 2.4: a PAUSED, STOP_REQUESTED or STOPPED ancestor stops
     * the order's effective state). [ancestors] is the path from the parent up to the root, as [lockPath] returns it without its first row.
     */
    private fun firstStoppingAncestor(ancestors: List<SelfDevOrder>): SelfDevOrder? = ancestors.firstOrNull { it.state != OrderState.ACTIVE }

    /**
     * Carries a refusal out of a transaction. The transaction rolls back, so no write made before the throw survives. The caller turns the
     * result back into the return value with [result].
     */
    /** A refusal that only the automatic start has. Thrown inside the transaction, so nothing is written. */
    private class AutoSkip(val skip: AutoCycleSkip, val orderId: String? = null) : RuntimeException(null, null, false, false)

    private class Rollback(val outcome: OrderResult<Nothing>) : RuntimeException(null, null, false, false)

    /** The same, for a child creation. */
    private class ChildRollback(val outcome: ChildCreateResult) : RuntimeException(null, null, false, false)

    private companion object {
        const val ORDER_COLUMNS = "order_id, parent_order_id, depth, title, policy, target_repo, max_cycles, cycles_started, " +
            "current_goal_id, consecutive_failed_cycles, state, state_reason, expires_at, created_by, version, created_at, updated_at, " +
            "order_budget_jpy, allocated_jpy, child_count, spent_jpy, cycle_budget_jpy"
        const val REQUEST_COLUMNS = "request_id, order_id, requested_by, requester_kind, reason_code, order_version, digest, state, " +
            "decided_by, expires_at, created_at, target_is_root"

        /** The audit kind of a cost count (stage 3g). Its `amount_jpy` is the change of the counted yen. */
        const val SPEND_COUNTED = "SPEND_COUNTED"
        const val MICRO_YEN_PER_YEN = 1_000_000L

        /** The whole yen of a micro-yen total, rounded up once per cycle (section C.2, U-33). */
        fun ceilYen(micro: Long): Long = if (micro <= 0) 0L else (micro + MICRO_YEN_PER_YEN - 1) / MICRO_YEN_PER_YEN

        fun actorRef(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }
}

/** True when the cause chain holds a unique or primary key violation (MariaDB 1062, H2 23505). Raw JDBC throws SQLException, not Exposed's. */
private fun Throwable.hasUniqueViolation(): Boolean = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any {
    it is SQLException && (it.sqlState == "23505" || it.errorCode == 1062 || it.errorCode == 23505)
}

private const val MAX_CAUSE_DEPTH = 5

private fun java.sql.ResultSet.toOrder() = SelfDevOrder(
    orderId = getString(1),
    parentOrderId = getString(2),
    depth = getInt(3),
    title = getString(4),
    policy = getString(5),
    targetRepo = getString(6),
    maxCycles = getInt(7),
    cyclesStarted = getInt(8),
    currentGoalId = getString(9),
    consecutiveFailedCycles = getInt(10),
    state = OrderState.valueOf(getString(11)),
    stateReason = getString(12)?.let { OrderReason.valueOf(it) },
    expiresAt = getObject(13, LocalDateTime::class.java),
    createdBy = getString(14),
    version = getLong(15),
    createdAt = getObject(16, LocalDateTime::class.java),
    updatedAt = getObject(17, LocalDateTime::class.java),
    orderBudgetJpy = getLong(18),
    allocatedJpy = getLong(19),
    childCount = getInt(20),
    spentJpy = getLong(21),
    cycleBudgetJpy = getLong(22),
)

private fun java.sql.ResultSet.toRequest() = SelfDevOrderStopRequest(
    requestId = getString(1),
    orderId = getString(2),
    requestedBy = getString(3),
    requesterKind = OrderActor.valueOf(getString(4)),
    reasonCode = StopRequestReason.valueOf(getString(5)),
    orderVersion = getLong(6),
    digest = getString(7),
    state = StopRequestState.valueOf(getString(8)),
    decidedBy = getString(9),
    expiresAt = getObject(10, LocalDateTime::class.java),
    createdAt = getObject(11, LocalDateTime::class.java),
    targetIsRoot = getInt(12) == 1,
)
