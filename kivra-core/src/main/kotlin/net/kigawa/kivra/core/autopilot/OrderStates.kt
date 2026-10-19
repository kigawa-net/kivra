package net.kigawa.kivra.core.autopilot

import java.security.MessageDigest

/*
 * The order state machine (ADR 0015 sections 2.4 and 2.5, stage 1). Pure: no I/O. The repository asks [checkOrderTransition] before every
 * write, and writes only a change this file allows. The table is the rule; the database does not know it.
 *
 * Stage 1 has the root order only, so there is no effective-state walk over ancestors here (stage 2).
 */

/** The state of an order. [STOPPED] is terminal. */
enum class OrderState { ACTIVE, PAUSED, STOP_REQUESTED, STOPPED }

/** Who asks for a change. [SYSTEM] is a safety guard or a timer; [HUMAN] is an operator who is not the AI principal; [AI] is the AI principal. */
enum class OrderActor { HUMAN, AI, SYSTEM }

/** Fixed reason codes (ADR 0015 sections 4.6 and 5.2). A reason is never free text. */
enum class OrderReason {
    /** ACTIVE -> PAUSED by an operator. */
    OPERATOR_PAUSED,
    /** PAUSED -> ACTIVE by an operator. */
    OPERATOR_RESUMED,
    /** ACTIVE -> PAUSED at startup after the feature is enabled (U-14). */
    STARTUP_HOLD,
    /** ACTIVE -> PAUSED when the monthly budget share is gone. */
    BUDGET_HOLD,
    /** ACTIVE -> PAUSED when the order has failed cycles in a row. */
    CYCLE_FAILURES,
    /** ACTIVE -> PAUSED when a Run of the current cycle is UNKNOWN. */
    UNKNOWN_RUN,
    /** ACTIVE -> PAUSED when the FX rate is too old to convert a cost. */
    FX_EXPIRED,
    /** ACTIVE -> PAUSED when the cycle limit is reached. An operator cannot resume it; a new order is needed (U-11). */
    LIMIT_REACHED,
    /** ACTIVE -> PAUSED when the order's lifetime is over. An operator cannot resume it (U-11). */
    EXPIRED,
    /** ACTIVE or PAUSED -> STOP_REQUESTED. */
    STOP_REQUESTED,
    /** STOP_REQUESTED -> PAUSED after a rejection, a cancellation or an expiry. Resumable by an operator. */
    STOP_NOT_APPROVED,
    /** STOP_REQUESTED -> STOPPED after the root approver's approval. */
    OPERATOR_STOPPED,
}

/** The reason codes a stop request may carry (U-31). Free text is never accepted. */
enum class StopRequestReason { OPERATOR_REQUEST, RUNAWAY_SUSPECTED, BUDGET_CONCERN, SCOPE_CONCERN }

/** Why a transition is refused. Fixed codes. */
enum class OrderRefusal { TERMINAL, NO_EDGE, ACTOR_NOT_ALLOWED, REASON_NOT_ALLOWED, RESUME_NOT_ALLOWED }

sealed interface OrderTransitionDecision {
    data object Allowed : OrderTransitionDecision
    data class Refused(val reason: OrderRefusal) : OrderTransitionDecision
}

/** The reasons a system guard may pause an ACTIVE order with. Operators resume them; nothing resumes them by itself. */
private val SYSTEM_PAUSE_REASONS = setOf(
    OrderReason.STARTUP_HOLD,
    OrderReason.BUDGET_HOLD,
    OrderReason.CYCLE_FAILURES,
    OrderReason.UNKNOWN_RUN,
    OrderReason.FX_EXPIRED,
    OrderReason.LIMIT_REACHED,
    OrderReason.EXPIRED,
)

/** Reasons after which an operator may not resume the order: a new order is needed instead (U-11). */
private val NOT_RESUMABLE = setOf(OrderReason.LIMIT_REACHED, OrderReason.EXPIRED)

/**
 * Decides one transition. [from] is the current state, [fromReason] its state reason (used for resume), [to] the target state, [reason] the
 * fixed code, and [actor] who asks. Every pair not listed is refused.
 *
 * - ACTIVE -> PAUSED: an operator's pause, or a system guard's pause (see [SYSTEM_PAUSE_REASONS]).
 * - PAUSED -> ACTIVE: an operator's resume only, and not after a limit or an expiry.
 * - ACTIVE or PAUSED -> STOP_REQUESTED: a stop request, by an operator or by the AI principal.
 * - STOP_REQUESTED -> STOPPED: the approval, by an operator only (the approver's identity is checked by the caller).
 * - STOP_REQUESTED -> PAUSED: a rejection, a cancellation or an expiry (STOP_NOT_APPROVED), by an operator or the system.
 * - STOPPED is terminal. There is no edge from ACTIVE or PAUSED to STOPPED: a stop always goes through a request.
 */
fun checkOrderTransition(
    from: OrderState,
    to: OrderState,
    reason: OrderReason,
    actor: OrderActor,
    fromReason: OrderReason?,
): OrderTransitionDecision {
    if (from == OrderState.STOPPED) return OrderTransitionDecision.Refused(OrderRefusal.TERMINAL)
    return when {
        from == OrderState.ACTIVE && to == OrderState.PAUSED -> when {
            reason == OrderReason.OPERATOR_PAUSED ->
                if (actor == OrderActor.HUMAN) allowed() else refused(OrderRefusal.ACTOR_NOT_ALLOWED)
            reason in SYSTEM_PAUSE_REASONS ->
                if (actor == OrderActor.SYSTEM) allowed() else refused(OrderRefusal.ACTOR_NOT_ALLOWED)
            else -> refused(OrderRefusal.REASON_NOT_ALLOWED)
        }
        from == OrderState.PAUSED && to == OrderState.ACTIVE -> when {
            reason != OrderReason.OPERATOR_RESUMED -> refused(OrderRefusal.REASON_NOT_ALLOWED)
            actor != OrderActor.HUMAN -> refused(OrderRefusal.ACTOR_NOT_ALLOWED)
            fromReason in NOT_RESUMABLE -> refused(OrderRefusal.RESUME_NOT_ALLOWED)
            else -> allowed()
        }
        (from == OrderState.ACTIVE || from == OrderState.PAUSED) && to == OrderState.STOP_REQUESTED -> when {
            reason != OrderReason.STOP_REQUESTED -> refused(OrderRefusal.REASON_NOT_ALLOWED)
            actor == OrderActor.SYSTEM -> refused(OrderRefusal.ACTOR_NOT_ALLOWED)
            else -> allowed()
        }
        from == OrderState.STOP_REQUESTED && to == OrderState.STOPPED -> when {
            reason != OrderReason.OPERATOR_STOPPED -> refused(OrderRefusal.REASON_NOT_ALLOWED)
            actor != OrderActor.HUMAN -> refused(OrderRefusal.ACTOR_NOT_ALLOWED)
            else -> allowed()
        }
        from == OrderState.STOP_REQUESTED && to == OrderState.PAUSED -> when {
            reason != OrderReason.STOP_NOT_APPROVED -> refused(OrderRefusal.REASON_NOT_ALLOWED)
            actor == OrderActor.AI -> refused(OrderRefusal.ACTOR_NOT_ALLOWED)
            else -> allowed()
        }
        else -> refused(OrderRefusal.NO_EDGE)
    }
}

private fun allowed() = OrderTransitionDecision.Allowed
private fun refused(reason: OrderRefusal) = OrderTransitionDecision.Refused(reason)

/** The lifetime of a stop request: 24 hours from its creation (ADR 0015 section 4.2). */
const val STOP_REQUEST_TTL_HOURS = 24L

/**
 * The digest of a stop request (ADR 0015 section 4.2): SHA-256 of the canonical JSON
 * `{"action":"ORDER_STOP","target_order_id","subtree","request_id","reason_code"}`, where `subtree` lists each order in the stopped tree with its
 * version. Stage 1 has one order, so the subtree is the target itself. The fields are IDs and fixed codes, so no escaping is needed.
 */
fun orderStopDigest(requestId: String, targetOrderId: String, orderVersion: Long, reason: StopRequestReason): String {
    val canonical = buildString {
        append("{\"action\":\"ORDER_STOP\",\"target_order_id\":\"").append(targetOrderId).append('"')
        append(",\"subtree\":[{\"order_id\":\"").append(targetOrderId).append("\",\"version\":").append(orderVersion).append("}]")
        append(",\"request_id\":\"").append(requestId).append('"')
        append(",\"reason_code\":\"").append(reason.name).append("\"}")
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
