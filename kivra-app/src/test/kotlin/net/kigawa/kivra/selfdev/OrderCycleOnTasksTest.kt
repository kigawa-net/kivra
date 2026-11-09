package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.AutoCycleResult
import net.kigawa.kivra.db.AutoCycleSkip
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.CycleStarted
import net.kigawa.kivra.db.OrderConflict
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.SelfDevSubmitResult
import net.kigawa.kivra.db.TaskTransition
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

/** A clock the test moves by hand, so the intervals and the UTC day are exact. */
private class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

/**
 * The orders' cycles on the Task model (V49, ADR 0015 and ADR 0011): a cycle is a root Task, the Planner plans it, the Dispatcher submits its
 * planned tasks as Runs, and the cycle's outcome moves the order. The database is the real one (TestDb) and the Runs are the real Run
 * repository; a Run's result is set in the table (a fake result), so no model or GitHub call is made.
 */
class OrderCycleOnTasksTest {

    private inner class Harness(val db: TestDb, val clock: MutableClock = MutableClock(START)) {
        val orders = SelfDevOrderRepository(db.database, clock)
        val ops = SelfDevOperatorRepository(db.database, clock)

        fun dispatcher() = AutopilotDispatcher(
            database = db.database,
            runs = db.runs,
            config = AutopilotConfig(
                approval = AutopilotApproval.AUTO, maxPendingPrs = 3, maxConsecutiveFailures = 5, maxRunsPerDay = 50, budgetSharePercent = 50,
            ),
            maxActiveSelfDev = 5,
            budgetAllows = { true },
            orderGate = { orders.dispatchAllowed(it) },
        )

        /** Every order and task of these tests belongs to one project (ADR 0022 PR-F: a cycle of an order with no project is refused). */
        val project: String = net.kigawa.kivra.db.ProjectRepository(db.database, clock).create("Cycles", null).projectId

        fun newOrder(maxCycles: Int = 3): SelfDevOrder =
            (orders.createRoot("operator", "Order title", "Improve the docs", "kigawa-net/kivra", maxCycles, project) as CreateRootResult.Created).order

        fun versionOf(orderId: String): Long = assertNotNull(orders.get(orderId)).version

        fun startCycle(orderId: String): CycleStarted =
            (orders.startCycle(orderId, versionOf(orderId), "operator") as OrderResult.Moved).value

        /** Plans the cycle's root with one task that starts QUEUED (the stage rules approved it). */
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

        fun childOf(rootId: String): net.kigawa.kivra.db.SelfDevTask =
            ops.listTasks(500).single { it.parentTaskId == rootId }

        /** The operator stops the cycle's root task (no order stop): the cycle ends, and [SelfDevOrderRepository.evaluateEndedCycles] records it. */
        fun stopCycleTask(taskId: String) {
            val task = assertNotNull(ops.task(taskId))
            assertIs<TaskTransition.Moved>(ops.stopTask(taskId, task.version))
        }

        fun setRunState(runId: String, state: String) = transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", state, runId)
        }

        /** Plans and submits the cycle's task, then sets its Run to [runState] and lets the cycle be recorded and evaluated. */
        fun finishCycle(taskId: String, runState: String) {
            planOneTask(taskId)
            dispatcher().tick()
            val child = childOf(taskId)
            setRunState(assertNotNull(child.runId), runState)
            dispatcher().tick()
            orders.evaluateEndedCycles(10)
        }
    }

    @Test
    fun startingACycleCreatesARootTaskOnTheTaskModel() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val started = h.startCycle(order.orderId)

        assertEquals(1, started.cycleNo)
        val task = assertNotNull(h.ops.task(started.goalId))
        assertEquals(TaskState.TASK_SUBMITTED, task.state)
        assertNull(task.parentTaskId)
        assertEquals("order:${order.orderId}:cycle:1", task.idempotencyKey)
        assertEquals("operator", task.createdBy)
        assertEquals("Order title", task.title)
        assertEquals("Improve the docs", task.body)
        assertEquals(1, db.count("selfdev_tasks", "order_id = '${order.orderId}' AND cycle_no = 1"))

        val stored = assertNotNull(h.orders.get(order.orderId))
        assertEquals(started.goalId, stored.currentGoalId)
        assertEquals(1, stored.cyclesStarted)
        assertEquals(OrderState.ACTIVE, stored.state)

        val cycles = h.orders.cyclesOf(order.orderId)
        assertEquals(listOf(1), cycles.map { it.cycleNo })
        assertEquals(listOf(started.goalId), cycles.map { it.goalId })
        assertEquals(listOf("TASK_SUBMITTED"), cycles.map { it.goalState })
    }

    @Test
    fun aSecondStartWhileACycleRunsIsRefused() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        h.startCycle(order.orderId)

        assertEquals(
            OrderResult.Refused(OrderConflict.CYCLE_IN_PROGRESS),
            h.orders.startCycle(order.orderId, h.versionOf(order.orderId), "operator"),
        )
        assertEquals(1, db.count("selfdev_tasks", "order_id = '${order.orderId}'"))
    }

    @Test
    fun aDuplicateCycleNumberIsRefusedByTheUniqueKey() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        h.startCycle(order.orderId)
        // The order forgets its cycle (as after a lost write): the next start would be cycle 1 again. The unique key refuses it.
        transaction(db.database) {
            execUpdate("UPDATE selfdev_orders SET current_goal_id = NULL, cycles_started = 0 WHERE order_id = ?", order.orderId)
        }
        assertEquals(
            OrderResult.Refused(OrderConflict.CYCLE_IN_PROGRESS),
            h.orders.startCycle(order.orderId, h.versionOf(order.orderId), "operator"),
        )
        assertEquals(1, db.count("selfdev_tasks", "order_id = '${order.orderId}'"))
    }

    @Test
    fun aCompletedCycleCountsAsASuccessAndTheNextCycleCanStart() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val first = h.startCycle(order.orderId)
        h.finishCycle(first.goalId, "COMPLETED")

        assertEquals(TaskState.DONE, h.ops.task(first.goalId)!!.state)
        val after = assertNotNull(h.orders.get(order.orderId))
        assertNull(after.currentGoalId)
        assertEquals(0, after.consecutiveFailedCycles)
        assertEquals(OrderState.ACTIVE, after.state)
        assertEquals("DONE", h.orders.cyclesOf(order.orderId).single().goalState)

        assertEquals(2, h.startCycle(order.orderId).cycleNo)
    }

    @Test
    fun twoFailedCyclesInARowPauseTheOrder() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val first = h.startCycle(order.orderId)
        h.finishCycle(first.goalId, "FAILED")
        assertEquals(1, h.orders.get(order.orderId)!!.consecutiveFailedCycles)
        assertEquals(OrderState.ACTIVE, h.orders.get(order.orderId)!!.state)

        val second = h.startCycle(order.orderId)
        h.finishCycle(second.goalId, "FAILED")
        val paused = assertNotNull(h.orders.get(order.orderId))
        assertEquals(2, paused.consecutiveFailedCycles)
        assertEquals(OrderState.PAUSED, paused.state)
        assertEquals(OrderReason.CYCLE_FAILURES, paused.stateReason)
        assertEquals(TaskState.STOPPED, h.ops.task(second.goalId)!!.state)
    }

    @Test
    fun aPausedOrderDoesNotSubmitItsTasksUntilItRuns() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val started = h.startCycle(order.orderId)
        h.planOneTask(started.goalId)

        assertIs<OrderResult.Moved<*>>(h.orders.pauseByOperator(order.orderId, h.versionOf(order.orderId), "operator"))
        h.dispatcher().tick()
        assertEquals(TaskState.QUEUED, h.childOf(started.goalId).state)
        assertNull(h.childOf(started.goalId).runId)

        assertIs<OrderResult.Moved<*>>(h.orders.resumeByOperator(order.orderId, h.versionOf(order.orderId), "operator"))
        h.dispatcher().tick()
        assertEquals(TaskState.SUBMITTED, h.childOf(started.goalId).state)
    }

    @Test
    fun aTaskOutsideAnyOrderIsNotHeldByTheGate() = TestDb().use { db ->
        val h = Harness(db)
        val operatorTask = (h.ops.submitTask("operator", "plain-key", "Plain", "body", "kigawa-net/kivra", h.project) as SelfDevSubmitResult.Created).task
        assertTrue(h.orders.dispatchAllowed(operatorTask.taskId))
        assertFalse(h.orders.dispatchAllowed("01UNKNOWNTASKIDXXXXXXXXXX"))
    }

    @Test
    fun anApprovedStopStopsTheCycleTaskAndItsUnstartedPlannedTasks() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val started = h.startCycle(order.orderId)
        h.planOneTask(started.goalId)

        val request = assertIs<OrderResult.Moved<net.kigawa.kivra.db.SelfDevOrderStopRequest>>(
            h.orders.requestStop(order.orderId, h.versionOf(order.orderId), "operator", OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        ).value
        val stopped = assertIs<OrderResult.Moved<SelfDevOrder>>(h.orders.approveStop(request.requestId, "approver", request.digest)).value
        assertEquals(OrderState.STOPPED, stopped.state)

        assertEquals(true, stopCycleGoalOf(stopped, h.ops))
        assertEquals(TaskState.STOPPED, h.ops.task(started.goalId)!!.state)
        assertEquals(TaskState.SKIPPED, h.childOf(started.goalId).state)

        // A stopped order's cycle is recorded as a failure and no longer held as in progress.
        h.orders.evaluateEndedCycles(10)
        val after = assertNotNull(h.orders.get(order.orderId))
        assertNull(after.currentGoalId)
        assertEquals(1, after.consecutiveFailedCycles)
        assertEquals(OrderState.STOPPED, after.state)
        assertNull(stopCycleGoalOf(after, h.ops))
    }

    @Test
    fun theAutomaticStartKeepsItsIntervalAndItsDailyLimit() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder(maxCycles = 10)
        fun auto(): AutoCycleResult = h.orders.startCycleAutomatically(order.orderId, cap = 10)

        val first = assertIs<AutoCycleResult.Started>(auto())
        assertEquals(1, first.started.cycleNo)
        h.finishCycle(first.started.goalId, "COMPLETED")

        h.clock.advance(Duration.ofMinutes(30))
        assertEquals(AutoCycleResult.Skipped(AutoCycleSkip.TOO_SOON), auto())

        h.clock.advance(Duration.ofMinutes(30))
        val second = assertIs<AutoCycleResult.Started>(auto())
        h.finishCycle(second.started.goalId, "COMPLETED")

        h.clock.advance(Duration.ofMinutes(60))
        val third = assertIs<AutoCycleResult.Started>(auto())
        h.finishCycle(third.started.goalId, "COMPLETED")

        // The fourth cycle of the UTC day (03:00 is still the same day) is refused by the daily limit.
        h.clock.advance(Duration.ofMinutes(60))
        assertEquals(AutoCycleResult.Skipped(AutoCycleSkip.DAILY_LIMIT), auto())

        // The next UTC day allows the cycle again.
        h.clock.advance(Duration.ofHours(21))
        assertIs<AutoCycleResult.Started>(auto())
    }

    @Test
    fun aRunThatIsUnknownPausesTheOrderThroughTheGuards() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val started = h.startCycle(order.orderId)
        h.planOneTask(started.goalId)
        h.dispatcher().tick()
        setRunStateOf(h, started.goalId, "UNKNOWN")

        assertTrue(h.orders.hasUnknownRun(started.goalId))
        OrderWatch(orders = h.orders, budgetAllows = { true }, fxExpired = { false }).tick()

        val paused = assertNotNull(h.orders.get(order.orderId))
        assertEquals(OrderState.PAUSED, paused.state)
        assertEquals(OrderReason.UNKNOWN_RUN, paused.stateReason)
    }

    @Test
    fun theCycleCostCountsIntoTheOrderAndClosesTheBudgetGate() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val started = h.startCycle(order.orderId)
        // 4,500 yen of committed planner cost, keyed to the cycle's root task (the planner's ledger key prefix).
        insertLedgerRow(db, "planner-${started.goalId}-test-1", 4_500_000_000L, "COMMITTED")

        OrderWatch(orders = h.orders, budgetAllows = { true }, fxExpired = { false }, countSpend = true).tick()

        assertEquals(4_500L, assertNotNull(h.orders.get(order.orderId)).spentJpy)
        // The cycle ends (stopped by the operator), then 5,000 - 4,500 = 500 is less than the cycle budget (1,000): the next start is refused.
        h.stopCycleTask(started.goalId)
        h.orders.evaluateEndedCycles(10)
        assertEquals(
            OrderResult.Refused(OrderConflict.ORDER_BUDGET, order.orderId),
            h.orders.startCycle(order.orderId, h.versionOf(order.orderId), "operator"),
        )
    }

    @Test
    fun anUnpricedRowHoldsTheNextCycle() = TestDb().use { db ->
        val h = Harness(db)
        val order = h.newOrder()
        val started = h.startCycle(order.orderId)
        h.stopCycleTask(started.goalId)
        h.orders.evaluateEndedCycles(10)
        insertLedgerRow(db, "planner-${started.goalId}-unpriced-1", 0L, "UNPRICED")

        OrderWatch(orders = h.orders, budgetAllows = { true }, fxExpired = { false }, countSpend = true).tick()

        assertEquals(
            OrderResult.Refused(OrderConflict.ORDER_UNPRICED),
            h.orders.startCycle(order.orderId, h.versionOf(order.orderId), "operator"),
        )
    }

    private fun setRunStateOf(h: Harness, taskId: String, state: String) {
        h.setRunState(assertNotNull(h.childOf(taskId).runId), state)
    }

    private fun insertLedgerRow(db: TestDb, key: String, amountMicroYen: Long, state: String) = transaction(db.database) {
        val now = LocalDateTime.of(2026, 11, 8, 0, 0)
        execUpdate(
            "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
            key, amountMicroYen, state, now.plusDays(1), now,
        )
    }

    private companion object {
        val START: Instant = Instant.parse("2026-11-08T00:00:00Z")
    }
}
