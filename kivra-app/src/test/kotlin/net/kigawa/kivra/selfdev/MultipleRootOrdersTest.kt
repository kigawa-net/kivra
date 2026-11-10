package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.ChildRefusal
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.StopEnd
import net.kigawa.kivra.db.TestDb

/** A clock the test moves by hand. */
private class RootsClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * Several root orders at once (ADR 0015 addendum 2026-11-11, V43 and V54). Each root is the top of its own tree: its own stop requests, its own
 * cycles and its own children. What is shared across the trees (the Run slot, the budget, the tree size) is checked here as well.
 */
class MultipleRootOrdersTest {

    private inner class Harness(val db: TestDb, val clock: RootsClock = RootsClock(START)) {
        val orders = SelfDevOrderRepository(db.database, clock)
        val ops = SelfDevOperatorRepository(db.database, clock)
        val project: String = ProjectRepository(db.database, clock).create("Roots", null).projectId

        fun root(title: String = "Order title"): SelfDevOrder =
            (orders.createRoot("operator", title, "Improve the docs", "kigawa-net/kivra", 3, project) as CreateRootResult.Created).order

        fun version(orderId: String): Long = assertNotNull(orders.get(orderId)).version

        fun stateOf(orderId: String): OrderState = assertNotNull(orders.get(orderId)).state

        fun child(parentId: String, key: String, limits: ChildLimits = ChildLimits(2, 5, 50)): ChildCreateResult =
            orders.createChild(parentId, "Child", "Improve the docs", 1_000, key, "operator", limits)

        fun dispatcher(maxActiveSelfDev: Int) = AutopilotDispatcher(
            database = db.database,
            runs = db.runs,
            config = AutopilotConfig(
                approval = AutopilotApproval.AUTO, maxPendingPrs = 3, maxConsecutiveFailures = 5, maxRunsPerDay = 50, budgetSharePercent = 50,
            ),
            maxActiveSelfDev = maxActiveSelfDev,
            budgetAllows = { true },
            orderGate = { orders.dispatchAllowed(it) },
        )

        /** Plans a cycle's root with one task that starts QUEUED, as the OrderCycleOnTasksTest harness does. */
        fun planOneTask(taskId: String) {
            val fence = assertNotNull(ops.claimForPlanning(taskId, "token-$taskId", Duration.ofMinutes(10))).fence
            val committed = ops.commitPlan(
                fence,
                listOf(
                    NewTask(
                        title = "Docs", body = "body", targetPaths = listOf("docs/a.md"), sizeEstimate = "S", dependsOn = emptyList(),
                        requiresApproval = false, initialState = TaskState.QUEUED,
                    ),
                ),
            )
            assertIs<net.kigawa.kivra.db.PlanCommit.Committed>(committed)
        }
    }

    @Test
    fun severalRootsAreCreatedAndEachHasItsOwnTree() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("First")
        val b = h.root("Second")
        assertNull(a.parentOrderId)
        assertNull(b.parentOrderId)
        assertEquals(2, db.count("selfdev_orders", "parent_order_id IS NULL"))

        val childOfA = assertIs<ChildCreateResult.Created>(h.child(a.orderId, "child-a")).order
        assertEquals(a.orderId, childOfA.parentOrderId)
        h.root("Third")
        assertEquals(3, h.orders.list(10).count { it.parentOrderId == null })
    }

    @Test
    fun aPendingStopIsPerTreeNotPerDatabase() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("A")
        val b = h.root("B")
        val childOfA = assertIs<ChildCreateResult.Created>(h.child(a.orderId, "child-a")).order

        assertIs<OrderResult.Moved<*>>(
            h.orders.requestStop(a.orderId, h.version(a.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        )
        // Another tree's stop is not refused by the request of tree A (the database-wide UNIQUE of V23 did refuse it).
        assertIs<OrderResult.Moved<*>>(
            h.orders.requestStop(b.orderId, h.version(b.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        )
        assertEquals(2, db.count("selfdev_order_stop_requests", "state = 'PENDING'"))

        // A second request in tree A is refused: the root is STOP_REQUESTED.
        assertEquals(
            OrderResult.Refused(OrderConflict.STOP_ALREADY_PENDING),
            h.orders.requestStop(a.orderId, h.version(a.orderId), "1:i:other", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        )
        // A child of tree A is refused too, while the root's request waits: one pending request per tree, not per order.
        assertEquals(
            OrderResult.Refused(OrderConflict.STOP_ALREADY_PENDING),
            h.orders.requestStop(childOfA.orderId, h.version(childOfA.orderId), "1:i:other", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        )
    }

    @Test
    fun aRejectedStopFreesItsTreeAndLeavesTheOtherTreesRequest() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("A")
        val b = h.root("B")
        val reqA = (h.orders.requestStop(a.orderId, h.version(a.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST)
            as OrderResult.Moved).value
        val reqB = (h.orders.requestStop(b.orderId, h.version(b.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST)
            as OrderResult.Moved).value

        assertIs<OrderResult.Moved<*>>(h.orders.endStopRequest(reqA.requestId, StopEnd.REJECTED, "operator", OrderActor.HUMAN))
        assertEquals(OrderState.PAUSED, h.stateOf(a.orderId))
        assertEquals(OrderState.STOP_REQUESTED, h.stateOf(b.orderId))
        assertEquals(1, db.count("selfdev_order_stop_requests", "state = 'PENDING'"))

        // Tree A may ask again now; tree B still has its own pending request.
        assertIs<OrderResult.Moved<*>>(
            h.orders.requestStop(a.orderId, h.version(a.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        )
        assertEquals(reqB.requestId, h.orders.pendingStopRequestOf(b.orderId)?.requestId)
    }

    @Test
    fun anApprovalStopsItsOwnTreeOnly() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("A")
        val b = h.root("B")
        val reqA = (h.orders.requestStop(a.orderId, h.version(a.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST)
            as OrderResult.Moved).value
        val reqB = (h.orders.requestStop(b.orderId, h.version(b.orderId), "1:i:requester", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST)
            as OrderResult.Moved).value

        assertIs<OrderResult.Moved<*>>(h.orders.approveStop(reqA.requestId, "operator", reqA.digest))
        assertEquals(OrderState.STOPPED, h.stateOf(a.orderId))
        assertEquals(OrderState.STOP_REQUESTED, h.stateOf(b.orderId))

        assertIs<OrderResult.Moved<*>>(h.orders.approveStop(reqB.requestId, "operator", reqB.digest))
        assertEquals(OrderState.STOPPED, h.stateOf(b.orderId))
    }

    @Test
    fun theAutomaticStartRunsInEveryTree() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("A")
        val b = h.root("B")

        OrderWatch(orders = h.orders, budgetAllows = { true }, fxExpired = { false }, autoCycleCap = 10).tick()

        assertNotNull(h.orders.get(a.orderId)?.currentGoalId)
        assertNotNull(h.orders.get(b.orderId)?.currentGoalId)
        assertEquals(1, h.orders.get(a.orderId)?.cyclesStarted)
        assertEquals(1, h.orders.get(b.orderId)?.cyclesStarted)
    }

    @Test
    fun theOneRunSlotIsSharedByEveryTree() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("A")
        val b = h.root("B")
        val goalA = (h.orders.startCycle(a.orderId, h.version(a.orderId), "operator") as OrderResult.Moved).value.goalId
        val goalB = (h.orders.startCycle(b.orderId, h.version(b.orderId), "operator") as OrderResult.Moved).value.goalId
        h.planOneTask(goalA)
        h.planOneTask(goalB)

        // One self-development Run at a time, for the whole database: the second tree's task waits, whichever tree it is in.
        h.dispatcher(maxActiveSelfDev = 1).tick()

        val children = h.ops.listTasks(500).filter { it.parentTaskId == goalA || it.parentTaskId == goalB }
        assertEquals(2, children.size)
        assertEquals(1, children.count { it.runId != null }, "exactly one Run may be active across the trees")
        assertTrue(children.any { it.runId == null && it.state == TaskState.QUEUED })
    }

    @Test
    fun theTreeSizeLimitCountsTheOrdersOfEveryTree() = TestDb().use { db ->
        val h = Harness(db)
        val a = h.root("A")
        h.root("B")
        // Two orders exist (one per tree). The limit is the database's total, not a limit per tree: a third order is refused.
        assertEquals(
            ChildCreateResult.Refused(ChildRefusal.TREE_LIMIT),
            h.child(a.orderId, "child-a", ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 2)),
        )
    }

    @Test
    fun theMonthlyBudgetHoldsEveryActiveOrderNotOnlyTheFirstPage() = TestDb().use { db ->
        val h = Harness(db)
        // More roots than one guard page holds (OrderWatch.BATCH). The last ones must be paused too.
        val count = OrderWatch.BATCH + 2
        repeat(count) { h.root("Order $it") }

        OrderWatch(orders = h.orders, budgetAllows = { false }, fxExpired = { false }).tick()

        assertEquals(count, db.count("selfdev_orders", "state = 'PAUSED' AND state_reason = '${OrderReason.BUDGET_HOLD.name}'"))
        assertEquals(0, db.count("selfdev_orders", "state = 'ACTIVE'"))
    }

    private companion object {
        val START: Instant = Instant.parse("2026-11-10T00:00:00Z")
    }
}
