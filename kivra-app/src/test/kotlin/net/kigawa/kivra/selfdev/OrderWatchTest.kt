package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.db.CreateRootResult
import net.kigawa.kivra.db.CycleStarted
import net.kigawa.kivra.db.OrderResult
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
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
}
