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
import net.kigawa.kivra.core.autopilot.checkOrderTransition
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
}

sealed interface OrderResult<out T> {
    data class Moved<out T>(val value: T) : OrderResult<T>
    data object NotFound : OrderResult<Nothing>
    /** The expected version is not the current one. Nothing changed. */
    data object StaleVersion : OrderResult<Nothing>
    /** [orderId] is set only for [OrderConflict.ORDER_BUDGET]: the ID of the order that does not have the budget (a fixed ID, no text). */
    data class Refused(val reason: OrderConflict, val orderId: String? = null) : OrderResult<Nothing>
}

sealed interface CreateRootResult {
    data class Created(val order: SelfDevOrder) : CreateRootResult
    /** The tree already has its root. Nothing changed. */
    data object RootExists : CreateRootResult
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

/** Cycles that stop being counted as failures after this many in a row (ADR 0015 section 3.2). */
const val CYCLE_FAILURE_LIMIT = 2

/** The default cost limit of one cycle, in whole yen (ADR 0015 section 3.2). The column default in V26 is the same value. */
const val DEFAULT_CYCLE_BUDGET_JPY = 1_000L

class SelfDevOrderRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val orderTtl: Duration = Duration.ofDays(14),
    private val stopTtl: Duration = Duration.ofHours(STOP_REQUEST_TTL_HOURS),
) {

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
    fun createRoot(createdBy: String, title: String, policy: String, targetRepo: String): CreateRootResult = try {
        transaction(database) {
            val now = utcNow(clock)
            val orderId = Ulid.generate(clock)
            execUpdate(
                """
                INSERT INTO selfdev_orders (order_id, parent_order_id, depth, root_slot, title, policy, target_repo, max_cycles,
                    cycles_started, consecutive_failed_cycles, state, expires_at, created_by, version, created_at, updated_at)
                VALUES (?, NULL, 0, 1, ?, ?, ?, 1, 0, 0, 'ACTIVE', ?, ?, 0, ?, ?)
                """.trimIndent(),
                orderId, title, policy, targetRepo, now.plus(orderTtl), createdBy, now, now,
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
     * root row is locked first, then the parent: every child creation takes the locks in that order, so two creations in one tree wait for
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
            val parent = lockOrder(parentId) ?: throw ChildRollback(ChildCreateResult.Refused(ChildRefusal.PARENT_NOT_ACTIVE))
            findChildByKey(parentId, idempotencyKey)?.let { existing ->
                return@transaction if (existing.title == title && existing.policy == policy && existing.orderBudgetJpy == orderBudgetJpy) {
                    ChildCreateResult.Replayed(existing)
                } else {
                    ChildCreateResult.KeyConflict
                }
            }
            if (parent.state != OrderState.ACTIVE || !parent.expiresAt.isAfter(now) || !ancestorsActive(parent)) {
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
     */
    fun startCycle(orderId: String, expectedVersion: Long, operatorKey: String): OrderResult<CycleStarted> = try {
        transaction(database) {
            val path = lockPath(orderId) ?: return@transaction OrderResult.NotFound
            val current = path.first()
            if (current.version != expectedVersion) return@transaction OrderResult.StaleVersion
            if (current.state != OrderState.ACTIVE) throw Rollback(OrderResult.Refused(OrderConflict.NOT_ACTIVE))
            if (current.currentGoalId != null) throw Rollback(OrderResult.Refused(OrderConflict.CYCLE_IN_PROGRESS))
            if (current.cyclesStarted >= current.maxCycles) throw Rollback(OrderResult.Refused(OrderConflict.CYCLE_LIMIT_REACHED))
            val now = utcNow(clock)
            if (!current.expiresAt.isAfter(now)) throw Rollback(OrderResult.Refused(OrderConflict.ORDER_EXPIRED))
            val paused = execQuery("SELECT paused FROM selfdev_autopilot_control WHERE control_id = 1") { it.getBoolean(1) }.single()
            if (paused) throw Rollback(OrderResult.Refused(OrderConflict.AUTOPILOT_PAUSED))
            for (order in path) {
                if (order.orderBudgetJpy - order.spentJpy < order.cycleBudgetJpy) {
                    throw Rollback(OrderResult.Refused(OrderConflict.ORDER_BUDGET, order.orderId))
                }
            }

            val cycleNo = current.cyclesStarted + 1
            val goalId = Ulid.generate(clock)
            execUpdate(
                """
                INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, body, target_repo, state, revision, version,
                    created_at, updated_at, order_id, cycle_no)
                VALUES (?, ?, ?, ?, ?, ?, 'GOAL_SUBMITTED', 0, 0, ?, ?, ?, ?)
                """.trimIndent(),
                goalId, operatorKey, "order:$orderId:cycle:$cycleNo", current.title, current.policy, current.targetRepo, now, now,
                orderId, cycleNo,
            )
            val changed = execUpdate(
                """
                UPDATE selfdev_orders SET cycles_started = cycles_started + 1, current_goal_id = ?, version = version + 1, updated_at = ?
                 WHERE order_id = ? AND version = ?
                """.trimIndent(),
                goalId, now, orderId, expectedVersion,
            )
            if (changed != 1) throw Rollback(OrderResult.StaleVersion)
            appendEvent(orderId, cycleNo, "CYCLE_STARTED", OrderState.ACTIVE, OrderState.ACTIVE, null, OrderActor.HUMAN, operatorKey, null, now)
            OrderResult.Moved(CycleStarted(getIn(orderId) ?: error("order vanished after cycle start"), goalId, cycleNo))
        }
    } catch (r: Rollback) {
        r.outcome
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
    ): OrderResult<SelfDevOrderStopRequest> = try {
        transaction(database) {
            val current = lockOrder(orderId) ?: return@transaction OrderResult.NotFound
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
            val requestId = Ulid.generate(clock)
            execUpdate(
                """
                INSERT INTO selfdev_order_stop_requests (request_id, order_id, requested_by, requester_kind, reason_code, order_version,
                    digest, state, active_key, decided_by, decider_kind, decided_at, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, NULL, NULL, NULL, ?, ?)
                """.trimIndent(),
                requestId, orderId, requestedBy, requesterKind.name, reason.name, newVersion,
                orderStopDigest(requestId, orderId, newVersion, reason), now.plus(stopTtl), now,
            )
            appendEvent(orderId, current.cyclesStarted.takeIf { it > 0 }, "STOP_REQUESTED", current.state, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED.name, requesterKind, requestedBy, requestId, now)
            OrderResult.Moved(getRequestIn(requestId) ?: error("stop request vanished after insert"))
        }
    } catch (r: Rollback) {
        r.outcome
    } catch (e: Exception) {
        if (!e.hasUniqueViolation()) throw e
        OrderResult.Refused(OrderConflict.STOP_ALREADY_PENDING)
    }

    /**
     * Approves a PENDING stop request: the request is CONSUMED and the order is STOPPED, in one transaction. The caller has checked that
     * [approverKey] is the root approver. Refused, with nothing written, when the approver is the requester, the digest does not match, the
     * request is not pending, the order has changed since the request, or the request has expired (the expiry itself is then recorded).
     * Only one of two concurrent approvals of the same request can succeed: the conditional UPDATE of the request decides it.
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
            if (request.requestedBy == approverKey) return@transaction OrderResult.Refused(OrderConflict.SAME_PRINCIPAL)
            if (request.digest != digest) return@transaction OrderResult.Refused(OrderConflict.DIGEST_MISMATCH)
            val order = lockOrder(request.orderId) ?: return@transaction OrderResult.NotFound
            if (order.state != OrderState.STOP_REQUESTED || order.version != request.orderVersion) {
                return@transaction OrderResult.Refused(OrderConflict.ORDER_CHANGED)
            }
            val decision = checkOrderTransition(order.state, OrderState.STOPPED, OrderReason.OPERATOR_STOPPED, OrderActor.HUMAN, order.stateReason)
            if (decision is OrderTransitionDecision.Refused) {
                throw Rollback(OrderResult.Refused(OrderConflict.valueOf(decision.reason.name)))
            }

            val consumed = execUpdate(
                """
                UPDATE selfdev_order_stop_requests SET state = 'CONSUMED', active_key = NULL, decided_by = ?, decider_kind = 'HUMAN', decided_at = ?
                 WHERE request_id = ? AND state = 'PENDING'
                """.trimIndent(),
                approverKey, now, requestId,
            )
            if (consumed != 1) throw Rollback(OrderResult.Refused(OrderConflict.STOP_NOT_PENDING))
            if (!updateState(order.orderId, order.version, OrderState.STOPPED, OrderReason.OPERATOR_STOPPED, now)) {
                throw Rollback(OrderResult.StaleVersion)
            }
            appendEvent(order.orderId, order.cyclesStarted.takeIf { it > 0 }, "STOP_APPROVED", OrderState.STOP_REQUESTED, OrderState.STOP_REQUESTED, null, OrderActor.HUMAN, approverKey, requestId, now)
            appendEvent(order.orderId, order.cyclesStarted.takeIf { it > 0 }, "STOPPED", OrderState.STOP_REQUESTED, OrderState.STOPPED, OrderReason.OPERATOR_STOPPED.name, OrderActor.HUMAN, approverKey, requestId, now)
            OrderResult.Moved(getIn(order.orderId) ?: error("order vanished after stop"))
        }
    } catch (r: Rollback) {
        r.outcome
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
            if (end == StopEnd.REJECTED && request.requestedBy == actorKey) return@transaction OrderResult.Refused(OrderConflict.SAME_PRINCIPAL)
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
            val current = lockOrder(orderId) ?: return@transaction OrderResult.NotFound
            if (current.version != expectedVersion) return@transaction OrderResult.StaleVersion
            val decision = checkOrderTransition(current.state, to, reason, actor, current.stateReason)
            if (decision is OrderTransitionDecision.Refused) {
                throw Rollback(OrderResult.Refused(OrderConflict.valueOf(decision.reason.name)))
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
    ) {
        execUpdate(
            """
            INSERT INTO selfdev_order_events (event_id, order_id, cycle_no, kind, from_state, to_state, reason_code, actor_kind, actor_ref,
                request_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Ulid.generate(clock), orderId, cycleNo, kind, from?.name, to?.name, reason, actor.name, actorKey?.let(::actorRef), requestId, now,
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

    /** True when every ancestor of [order] is ACTIVE (its effective state, ADR 0015 section 2.4). Reads the rows without locking them. */
    private fun Transaction.ancestorsActive(order: SelfDevOrder): Boolean {
        var next = order.parentOrderId
        while (next != null) {
            val ancestor = getIn(next) ?: return false
            if (ancestor.state != OrderState.ACTIVE) return false
            next = ancestor.parentOrderId
        }
        return true
    }

    /**
     * Carries a refusal out of a transaction. The transaction rolls back, so no write made before the throw survives. The caller turns the
     * result back into the return value with [result].
     */
    private class Rollback(val outcome: OrderResult<Nothing>) : RuntimeException(null, null, false, false)

    /** The same, for a child creation. */
    private class ChildRollback(val outcome: ChildCreateResult) : RuntimeException(null, null, false, false)

    private companion object {
        const val ORDER_COLUMNS = "order_id, parent_order_id, depth, title, policy, target_repo, max_cycles, cycles_started, " +
            "current_goal_id, consecutive_failed_cycles, state, state_reason, expires_at, created_by, version, created_at, updated_at, " +
            "order_budget_jpy, allocated_jpy, child_count, spent_jpy, cycle_budget_jpy"
        const val REQUEST_COLUMNS = "request_id, order_id, requested_by, requester_kind, reason_code, order_version, digest, state, " +
            "decided_by, expires_at, created_at"

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
)
