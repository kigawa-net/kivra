package net.kigawa.kivra.core.autopilot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The order state table (ADR 0015 sections 2.4 and 2.5). Every combination is checked, so a new edge cannot appear unnoticed. */
class OrderStatesTest {

    /** An allowed edge. [fromReasonOk] says which state reasons the edge accepts (the resume is the only one that looks at it). */
    private data class Edge(
        val from: OrderState,
        val to: OrderState,
        val reason: OrderReason,
        val actor: OrderActor,
        val fromReasonOk: (OrderReason?) -> Boolean = { true },
    )

    /** Every (from, to, reason, actor, fromReason) that is allowed. Anything else must be refused. */
    private val allowedEdges: Set<Edge> = buildSet {
        val systemPauses = listOf(
            OrderReason.STARTUP_HOLD, OrderReason.BUDGET_HOLD, OrderReason.CYCLE_FAILURES, OrderReason.UNKNOWN_RUN,
            OrderReason.FX_EXPIRED, OrderReason.LIMIT_REACHED, OrderReason.EXPIRED,
        )
        for (reason in systemPauses) add(Edge(OrderState.ACTIVE, OrderState.PAUSED, reason, OrderActor.SYSTEM))
        add(Edge(OrderState.ACTIVE, OrderState.PAUSED, OrderReason.OPERATOR_PAUSED, OrderActor.HUMAN))
        // A resume restores ACTIVE, by an operator only, and not from a limit or an expiry.
        add(
            Edge(OrderState.PAUSED, OrderState.ACTIVE, OrderReason.OPERATOR_RESUMED, OrderActor.HUMAN) {
                it != OrderReason.LIMIT_REACHED && it != OrderReason.EXPIRED
            },
        )
        for (actor in listOf(OrderActor.HUMAN, OrderActor.AI)) {
            add(Edge(OrderState.ACTIVE, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED, actor))
            add(Edge(OrderState.PAUSED, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED, actor))
        }
        add(Edge(OrderState.STOP_REQUESTED, OrderState.STOPPED, OrderReason.OPERATOR_STOPPED, OrderActor.HUMAN))
        // A child's stop, decided by its parent (the root approver's decision): the same approval, with its own reason.
        add(Edge(OrderState.STOP_REQUESTED, OrderState.STOPPED, OrderReason.PARENT_STOPPED, OrderActor.HUMAN))
        add(Edge(OrderState.STOP_REQUESTED, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED, OrderActor.HUMAN))
        add(Edge(OrderState.STOP_REQUESTED, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED, OrderActor.SYSTEM))
    }

    @Test
    fun `every combination is decided by the table and only the listed edges are allowed`() {
        val fromReasons = listOf<OrderReason?>(null) + OrderReason.entries
        var checked = 0
        for (from in OrderState.entries) for (to in OrderState.entries) for (reason in OrderReason.entries)
            for (actor in OrderActor.entries) for (fromReason in fromReasons) {
                val decision = checkOrderTransition(from, to, reason, actor, fromReason)
                val expected = allowedEdges.any {
                    it.from == from && it.to == to && it.reason == reason && it.actor == actor && it.fromReasonOk(fromReason)
                }
                val allowed = decision == OrderTransitionDecision.Allowed
                assertEquals(expected, allowed, "from=$from to=$to reason=$reason actor=$actor fromReason=$fromReason")
                checked++
            }
        assertTrue(checked > 0)
    }

    @Test
    fun `STOPPED is terminal and nothing leaves it`() {
        for (to in OrderState.entries) for (reason in OrderReason.entries) for (actor in OrderActor.entries) {
            assertEquals(
                OrderTransitionDecision.Refused(OrderRefusal.TERMINAL),
                checkOrderTransition(OrderState.STOPPED, to, reason, actor, null),
            )
        }
    }

    @Test
    fun `there is no edge from ACTIVE or PAUSED straight to STOPPED`() {
        for (from in listOf(OrderState.ACTIVE, OrderState.PAUSED)) for (reason in OrderReason.entries) for (actor in OrderActor.entries) {
            assertEquals(
                OrderTransitionDecision.Refused(OrderRefusal.NO_EDGE),
                checkOrderTransition(from, OrderState.STOPPED, reason, actor, null),
                "from=$from reason=$reason actor=$actor",
            )
        }
    }

    @Test
    fun `the AI principal may request a stop and nothing else`() {
        assertEquals(
            OrderTransitionDecision.Allowed,
            checkOrderTransition(OrderState.ACTIVE, OrderState.STOP_REQUESTED, OrderReason.STOP_REQUESTED, OrderActor.AI, null),
        )
        for (edge in allowedEdges.filter { it.actor == OrderActor.AI }) {
            assertEquals(OrderState.STOP_REQUESTED, edge.to, "AI may only enter STOP_REQUESTED: $edge")
        }
        // Approval, resume, pause and rejection are refused for the AI principal.
        assertEquals(
            OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED),
            checkOrderTransition(OrderState.STOP_REQUESTED, OrderState.STOPPED, OrderReason.OPERATOR_STOPPED, OrderActor.AI, OrderReason.STOP_REQUESTED),
        )
        assertEquals(
            OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED),
            checkOrderTransition(OrderState.PAUSED, OrderState.ACTIVE, OrderReason.OPERATOR_RESUMED, OrderActor.AI, OrderReason.OPERATOR_PAUSED),
        )
        assertEquals(
            OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED),
            checkOrderTransition(OrderState.ACTIVE, OrderState.PAUSED, OrderReason.OPERATOR_PAUSED, OrderActor.AI, null),
        )
        assertEquals(
            OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED),
            checkOrderTransition(OrderState.STOP_REQUESTED, OrderState.PAUSED, OrderReason.STOP_NOT_APPROVED, OrderActor.AI, OrderReason.STOP_REQUESTED),
        )
    }

    @Test
    fun `a limit or an expiry cannot be resumed by an operator`() {
        for (fromReason in listOf(OrderReason.LIMIT_REACHED, OrderReason.EXPIRED)) {
            assertEquals(
                OrderTransitionDecision.Refused(OrderRefusal.RESUME_NOT_ALLOWED),
                checkOrderTransition(OrderState.PAUSED, OrderState.ACTIVE, OrderReason.OPERATOR_RESUMED, OrderActor.HUMAN, fromReason),
            )
        }
    }

    @Test
    fun `nothing resumes an order by itself`() {
        for (reason in OrderReason.entries) {
            assertEquals(
                OrderTransitionDecision.Refused(
                    if (reason == OrderReason.OPERATOR_RESUMED) OrderRefusal.ACTOR_NOT_ALLOWED else OrderRefusal.REASON_NOT_ALLOWED,
                ),
                checkOrderTransition(OrderState.PAUSED, OrderState.ACTIVE, reason, OrderActor.SYSTEM, OrderReason.BUDGET_HOLD),
                "reason=$reason",
            )
        }
    }

    @Test
    fun `an operator pause is not a system pause, and a guard pause is not an operator pause`() {
        assertEquals(
            OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED),
            checkOrderTransition(OrderState.ACTIVE, OrderState.PAUSED, OrderReason.OPERATOR_PAUSED, OrderActor.SYSTEM, null),
        )
        assertEquals(
            OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED),
            checkOrderTransition(OrderState.ACTIVE, OrderState.PAUSED, OrderReason.BUDGET_HOLD, OrderActor.HUMAN, null),
        )
    }

    @Test
    fun `the digest binds the request id and the order version and the reason`() {
        val base = orderStopDigest("01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", 3, StopRequestReason.OPERATOR_REQUEST)
        assertEquals(64, base.length)
        assertEquals(base, orderStopDigest("01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", 3, StopRequestReason.OPERATOR_REQUEST))
        assertNotEquals(base, orderStopDigest("01CCCCCCCCCCCCCCCCCCCCCCCC", "01BBBBBBBBBBBBBBBBBBBBBBBB", 3, StopRequestReason.OPERATOR_REQUEST))
        assertNotEquals(base, orderStopDigest("01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", 4, StopRequestReason.OPERATOR_REQUEST))
        assertNotEquals(base, orderStopDigest("01AAAAAAAAAAAAAAAAAAAAAAAA", "01BBBBBBBBBBBBBBBBBBBBBBBB", 3, StopRequestReason.RUNAWAY_SUSPECTED))
    }

    @Test
    fun `the digest of a subtree binds every version of the part and does not depend on the order of the list`() {
        val reason = StopRequestReason.OPERATOR_REQUEST
        val target = "01BBBBBBBBBBBBBBBBBBBBBBBB"
        val child = "01CCCCCCCCCCCCCCCCCCCCCCCC"
        val grand = "01DDDDDDDDDDDDDDDDDDDDDDDD"
        val req = "01AAAAAAAAAAAAAAAAAAAAAAAA"
        val base = orderStopDigest(req, target, listOf(target to 4L, child to 2L, grand to 1L), reason)
        assertEquals(base, orderStopDigest(req, target, listOf(grand to 1L, target to 4L, child to 2L), reason))
        assertNotEquals(base, orderStopDigest(req, target, listOf(target to 4L, child to 3L, grand to 1L), reason))
        assertNotEquals(base, orderStopDigest(req, target, listOf(target to 4L, child to 2L), reason))
        // One entry is the stage 1 form: the same digest as the single-order overload.
        assertEquals(
            orderStopDigest(req, target, 4, reason),
            orderStopDigest(req, target, listOf(target to 4L), reason),
        )
    }

    @Test
    fun `a descendant stops only from ACTIVE or PAUSED, by the human approver, as part of an approval`() {
        assertEquals(OrderTransitionDecision.Allowed, checkSubtreeStop(OrderState.ACTIVE, OrderActor.HUMAN))
        assertEquals(OrderTransitionDecision.Allowed, checkSubtreeStop(OrderState.PAUSED, OrderActor.HUMAN))
        assertEquals(OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED), checkSubtreeStop(OrderState.ACTIVE, OrderActor.AI))
        assertEquals(OrderTransitionDecision.Refused(OrderRefusal.ACTOR_NOT_ALLOWED), checkSubtreeStop(OrderState.ACTIVE, OrderActor.SYSTEM))
        assertEquals(OrderTransitionDecision.Refused(OrderRefusal.NO_EDGE), checkSubtreeStop(OrderState.STOP_REQUESTED, OrderActor.HUMAN))
        assertEquals(OrderTransitionDecision.Refused(OrderRefusal.TERMINAL), checkSubtreeStop(OrderState.STOPPED, OrderActor.HUMAN))
    }
}
