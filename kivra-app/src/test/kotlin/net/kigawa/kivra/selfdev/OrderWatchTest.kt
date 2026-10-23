package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.ChildCreateResult
import net.kigawa.kivra.db.ChildLimits
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.CycleStarted
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.db.linkRunToGoal
import org.jetbrains.exposed.sql.transactions.transaction

private val START: Instant = Instant.parse("2026-10-19T10:00:00Z")

private class MovableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The order guards (ADR 0015 section 5.2) on H2. A guard pauses an ACTIVE order with its own reason, touches nothing else, and never
 * resumes an order: the operator does that.
 */
class OrderWatchTest {

    private class Fixture(val db: TestDb, val clock: MovableClock) {
        val repo = SelfDevOrderRepository(db.database, clock)
        val goals = SelfDevGoalRepository(db.database, clock)
        var budgetOk = true
        var fxExpired = false
        val watch = OrderWatch(repo, budgetAllows = { budgetOk }, fxExpired = { fxExpired })

        fun root(): SelfDevOrder = (repo.createRoot("1:i:kigawa", "title", "policy", "kigawa-net/kivra") as CreateRootResult.Created).order

        fun state(id: String): SelfDevOrder = assertNotNull(repo.get(id))

        fun childOf(parentId: String, key: String, budget: Long = 1_000): SelfDevOrder =
            (repo.createChild(parentId, "child", "child policy", budget, key, "1:i:kigawa", ChildLimits(2, 5, 15)) as ChildCreateResult.Created).order
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db, MovableClock(START))) }

    @Test
    fun `a lost budget share pauses the ACTIVE order with BUDGET_HOLD and the order is not resumed when the budget returns`() = withFixture { f ->
        val root = f.root()
        f.budgetOk = false
        f.watch.tick()
        val paused = f.state(root.orderId)
        assertEquals(OrderState.PAUSED, paused.state)
        assertEquals(OrderReason.BUDGET_HOLD, paused.stateReason)
        f.budgetOk = true
        f.watch.tick()
        assertEquals(OrderState.PAUSED, f.state(root.orderId).state)
    }

    @Test
    fun `an expired FX rate pauses the ACTIVE order with FX_EXPIRED`() = withFixture { f ->
        val root = f.root()
        f.fxExpired = true
        f.watch.tick()
        assertEquals(OrderReason.FX_EXPIRED, f.state(root.orderId).stateReason)
    }

    @Test
    fun `a stop waiting for approval is not touched by the guards`() = withFixture { f ->
        val root = f.root()
        assertIs<OrderResult.Moved<*>>(f.repo.requestStop(root.orderId, root.version, "1:i:ai", OrderActor.AI, StopRequestReason.OPERATOR_REQUEST))
        f.budgetOk = false
        f.fxExpired = true
        f.watch.tick()
        assertEquals(OrderState.STOP_REQUESTED, f.state(root.orderId).state)
    }

    @Test
    fun `an UNKNOWN Run of the current cycle pauses the ACTIVE order with UNKNOWN_RUN`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, "1:i:kigawa")).value
        val run = assertIs<TaskSubmitResult.Created>(f.db.runs.submitTask("1:i:kigawa", "watch-unknown-run", "a task")).run
        f.db.linkRunToGoal(started.goalId, run.runId.value)
        f.db.forceState(run.runId.value, DevelopmentRunState.UNKNOWN)
        f.watch.tick()
        val paused = f.state(root.orderId)
        assertEquals(OrderState.PAUSED, paused.state)
        assertEquals(OrderReason.UNKNOWN_RUN, paused.stateReason)
    }

    @Test
    fun `a Run of the current cycle that is not UNKNOWN leaves the ACTIVE order alone`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, "1:i:kigawa")).value
        val run = assertIs<TaskSubmitResult.Created>(f.db.runs.submitTask("1:i:kigawa", "watch-created-run", "a task")).run
        f.db.linkRunToGoal(started.goalId, run.runId.value)
        f.watch.tick()
        assertEquals(OrderState.ACTIVE, f.state(root.orderId).state)
        assertFalse(f.repo.hasUnknownRun(started.goalId))
    }

    @Test
    fun `an overdue stop request is expired by the tick and the order is paused, not stopped`() = withFixture { f ->
        val root = f.root()
        assertIs<OrderResult.Moved<*>>(f.repo.requestStop(root.orderId, root.version, "1:i:ai", OrderActor.AI, StopRequestReason.OPERATOR_REQUEST))
        f.clock.now = START.plus(Duration.ofHours(24))
        f.watch.tick()
        val order = f.state(root.orderId)
        assertEquals(OrderState.PAUSED, order.state)
        assertEquals(OrderReason.STOP_NOT_APPROVED, order.stateReason)
    }

    @Test
    fun `an order past its lifetime is paused with EXPIRED by the tick`() = withFixture { f ->
        val root = f.root()
        f.clock.now = START.plus(Duration.ofDays(15))
        f.watch.tick()
        assertEquals(OrderReason.EXPIRED, f.state(root.orderId).stateReason)
    }

    @Test
    fun `a finished single cycle pauses the order with LIMIT_REACHED, and the tick records the cycle`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, "1:i:kigawa")).value
        // The goal ends (the Planner and the Dispatcher are not run here).
        transaction(f.db.database) {
            execUpdate("UPDATE selfdev_goals SET state = 'DONE', state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", started.goalId)
        }
        f.watch.tick()
        val order = f.state(root.orderId)
        assertEquals(OrderState.PAUSED, order.state)
        assertEquals(OrderReason.LIMIT_REACHED, order.stateReason)
        assertNull(order.currentGoalId)
        assertEquals(GoalState.DONE, f.goals.get(started.goalId)!!.state)
    }

    @Test
    fun `a paused order stays paused through a tick with nothing to do`() = withFixture { f ->
        val root = f.root()
        assertIs<OrderResult.Moved<*>>(f.repo.pauseByOperator(root.orderId, root.version, "1:i:kigawa"))
        f.watch.tick()
        assertEquals(OrderState.PAUSED, f.state(root.orderId).state)
        assertEquals(OrderReason.OPERATOR_PAUSED, f.state(root.orderId).stateReason)
    }

    /** The same watch with the automatic child stop on (the switch is on). */
    private fun Fixture.autoStopWatch(): OrderWatch =
        OrderWatch(repo, budgetAllows = { budgetOk }, fxExpired = { fxExpired }, autoStop = AutoStopDeps(goals, SelfDevOperatorRepository(db.database, clock)))

    @Test
    fun `with the automatic child stop switch off, a tick decides nothing, even for a child with two failed cycles`() = withFixture { f ->
        val root = f.root()
        val child = f.childOf(root.orderId, "off-child")
        transaction(f.db.database) { execUpdate("UPDATE selfdev_orders SET consecutive_failed_cycles = 2 WHERE order_id = ?", child.orderId) }
        f.watch.tick()
        assertEquals(OrderState.ACTIVE, f.state(child.orderId).state)
    }

    @Test
    fun `with the switch on, a tick stops a child with two failed cycles, its grandchild, and the goal of its cycle, never the root`() = withFixture { f ->
        val root = f.root()
        val child = f.childOf(root.orderId, "on-child", budget = 3_000)
        val grand = f.childOf(child.orderId, "on-grand", budget = 500)
        val started = assertIs<OrderResult.Moved<CycleStarted>>(
            f.repo.startCycle(child.orderId, f.state(child.orderId).version, "1:i:kigawa"),
        ).value
        transaction(f.db.database) {
            execUpdate("UPDATE selfdev_orders SET consecutive_failed_cycles = 2 WHERE order_id = ?", child.orderId)
            execUpdate("UPDATE selfdev_orders SET consecutive_failed_cycles = 2 WHERE order_id = ?", root.orderId)
        }
        f.autoStopWatch().tick()
        assertEquals(OrderState.STOPPED, f.state(child.orderId).state)
        assertEquals(OrderReason.PARENT_STOPPED, f.state(child.orderId).stateReason)
        assertEquals(OrderState.STOPPED, f.state(grand.orderId).state)
        assertEquals(OrderReason.ANCESTOR_STOPPED, f.state(grand.orderId).stateReason)
        assertEquals(GoalState.STOPPED, assertNotNull(f.goals.get(started.goalId)).state)
        assertEquals(OrderState.ACTIVE, f.state(root.orderId).state)
    }
}
