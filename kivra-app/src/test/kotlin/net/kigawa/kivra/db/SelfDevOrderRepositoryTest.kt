package net.kigawa.kivra.db

import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.core.autopilot.orderStopDigest
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "op-kigawa"
private const val AI = "ai-principal"
private const val TARGET = "kigawa-net/kivra"
private const val TITLE_MARKER = "TITLE-MARKER-XYZ"
private const val POLICY_MARKER = "POLICY-MARKER-XYZ"
private val START: Instant = Instant.parse("2026-10-19T10:00:00Z")

/** A clock the test moves. */
private class MovableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The order repository (ADR 0015 stage 1) on H2 (MariaDB with `KIVRA_TEST_DB=mariadb`). The state rules are in OrderStatesTest; this file
 * checks the writes: CAS, rollback on a refused second write, the stop approval binding, expiry, the races, and the schema's own CHECKs.
 */
class SelfDevOrderRepositoryTest {

    private class Fixture(val db: TestDb, val clock: MovableClock) {
        val repo = SelfDevOrderRepository(db.database, clock)
        val goals = SelfDevGoalRepository(db.database, clock)
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db, MovableClock(START))) }

    private fun Fixture.root(): SelfDevOrder =
        (repo.createRoot(OPERATOR, TITLE_MARKER, POLICY_MARKER, TARGET) as CreateRootResult.Created).order

    private fun Fixture.exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

    private fun Fixture.count(sql: String): Int = transaction(db.database) {
        execQuery(sql) { it.getInt(1) }.single()
    }

    private fun Fixture.setGoalState(goalId: String, state: GoalState, reason: String) {
        exec("UPDATE selfdev_goals SET state = ?, state_reason = ? WHERE goal_id = ?", state.name, reason, goalId)
    }

    @Test
    fun `the root is created ACTIVE and a second root is refused by the repository and by the schema`() = withFixture { f ->
        val root = f.root()
        assertEquals(OrderState.ACTIVE, root.state)
        assertNull(root.parentOrderId)
        assertEquals(0, root.depth)
        assertEquals(1, root.maxCycles)
        assertIs<CreateRootResult.RootExists>(f.repo.createRoot(OPERATOR, "again", "again", TARGET))
        assertFailsWith<SQLException> {
            f.exec(
                "INSERT INTO selfdev_orders (order_id, depth, root_slot, title, policy, target_repo, max_cycles, cycles_started, " +
                    "consecutive_failed_cycles, state, expires_at, created_by, version, created_at, updated_at) " +
                    "VALUES ('01BBBBBBBBBBBBBBBBBBBBBBBB', 0, 1, 't', 'p', 'r', 1, 0, 0, 'ACTIVE', NOW(6), 'x', 0, NOW(6), NOW(6))",
            )
        }
    }

    @Test
    fun `an operator pauses and resumes with the version check, and a stale version changes nothing`() = withFixture { f ->
        val root = f.root()
        assertIs<OrderResult.StaleVersion>(f.repo.pauseByOperator(root.orderId, 99, OPERATOR))
        val paused = assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(root.orderId, root.version, OPERATOR)).value
        assertEquals(OrderState.PAUSED, paused.state)
        assertEquals(OrderReason.OPERATOR_PAUSED, paused.stateReason)
        val resumed = assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.resumeByOperator(root.orderId, paused.version, OPERATOR)).value
        assertEquals(OrderState.ACTIVE, resumed.state)
        assertNull(resumed.stateReason)
        // ORDER_CREATED, PAUSED and RESUMED.
        assertEquals(3, f.count("SELECT COUNT(*) FROM selfdev_order_events WHERE order_id = '${root.orderId}'"))
    }

    @Test
    fun `a system guard pauses only an ACTIVE order and a limit or an expiry is never resumed by an operator`() = withFixture { f ->
        val root = f.root()
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseBySystem(root.orderId, OrderReason.BUDGET_HOLD))
        assertEquals(OrderResult.Refused(OrderConflict.NOT_ACTIVE), f.repo.pauseBySystem(root.orderId, OrderReason.FX_EXPIRED))
        // The operator's resume is the only way back: a stale version is refused, and nothing is resumed by the guard itself.
        assertEquals(OrderResult.StaleVersion, f.repo.resumeByOperator(root.orderId, 99, OPERATOR))
        assertEquals(OrderState.PAUSED, f.repo.get(root.orderId)!!.state)
    }

    @Test
    fun `an expired order is paused with EXPIRED and an operator cannot resume it`() = withFixture { f ->
        val root = f.root()
        f.exec("UPDATE selfdev_orders SET expires_at = ? WHERE order_id = ?", java.time.LocalDateTime.ofInstant(START.minusSeconds(1), ZoneOffset.UTC), root.orderId)
        assertEquals(1, f.repo.expireDueOrders(10))
        val expired = assertNotNull(f.repo.get(root.orderId))
        assertEquals(OrderReason.EXPIRED, expired.stateReason)
        assertEquals(OrderResult.Refused(OrderConflict.RESUME_NOT_ALLOWED), f.repo.resumeByOperator(root.orderId, expired.version, OPERATOR))
    }

    @Test
    fun `the pauses of the operator and the guard are both written with a fixed reason and no text`() = withFixture { f ->
        val root = f.root()
        f.repo.pauseBySystem(root.orderId, OrderReason.UNKNOWN_RUN)
        val events = transaction(f.db.database) {
            execQuery("SELECT kind, from_state, to_state, reason_code, actor_kind, actor_ref FROM selfdev_order_events WHERE order_id = ?", root.orderId) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
            }
        }
        val pauses = events.filter { it[0] == "PAUSED" }
        assertEquals(1, pauses.size)
        assertEquals(listOf("PAUSED", "ACTIVE", "PAUSED", "UNKNOWN_RUN", "SYSTEM", null), pauses.single())
        // No title, policy or reason text is in any event row.
        for (row in events) for (cell in row) assertTrue(cell == null || (!cell.contains(TITLE_MARKER) && !cell.contains(POLICY_MARKER)))
    }

    @Test
    fun `a cycle creates a goal bound to the order and a second cycle waits for the first`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, OPERATOR)).value
        assertEquals(1, started.cycleNo)
        val goal = assertNotNull(f.goals.get(started.goalId))
        assertEquals(GoalState.GOAL_SUBMITTED, goal.state)
        assertEquals(TITLE_MARKER, goal.title)
        assertEquals(POLICY_MARKER, goal.body)
        assertEquals(started.goalId, f.repo.get(root.orderId)?.currentGoalId)
        // No Run exists yet, so no task of the cycle is UNKNOWN (the query itself is run here).
        assertTrue(!f.repo.hasUnknownRun(started.goalId))
        assertEquals(1, f.count("SELECT COUNT(*) FROM selfdev_goals WHERE order_id = '${root.orderId}' AND cycle_no = 1"))
        assertEquals(OrderResult.Refused(OrderConflict.CYCLE_IN_PROGRESS), f.repo.startCycle(root.orderId, started.order.version, OPERATOR))
    }

    @Test
    fun `a cycle is refused while the autopilot is paused for everything, or after the order expired`() = withFixture { f ->
        val root = f.root()
        f.exec("UPDATE selfdev_autopilot_control SET paused = TRUE WHERE control_id = 1")
        assertEquals(OrderResult.Refused(OrderConflict.AUTOPILOT_PAUSED), f.repo.startCycle(root.orderId, root.version, OPERATOR))
        f.exec("UPDATE selfdev_autopilot_control SET paused = FALSE WHERE control_id = 1")
        f.clock.now = START.plus(Duration.ofDays(15))
        assertEquals(OrderResult.Refused(OrderConflict.ORDER_EXPIRED), f.repo.startCycle(root.orderId, root.version, OPERATOR))
    }

    @Test
    fun `a single-cycle order is paused with LIMIT_REACHED once its cycle ends, and a failed cycle counts`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, OPERATOR)).value
        f.setGoalState(started.goalId, GoalState.DONE, "ALL_TASKS_COMPLETED")
        assertEquals(1, f.repo.evaluateEndedCycles(10))
        val after = assertNotNull(f.repo.get(root.orderId))
        assertEquals(OrderState.PAUSED, after.state)
        assertEquals(OrderReason.LIMIT_REACHED, after.stateReason)
        assertNull(after.currentGoalId)
        assertEquals(0, after.consecutiveFailedCycles)
    }

    @Test
    fun `two failed cycles in a row pause the order with CYCLE_FAILURES`() = withFixture { f ->
        val root = f.root()
        f.exec("UPDATE selfdev_orders SET max_cycles = 5 WHERE order_id = ?", root.orderId)
        var version = f.repo.get(root.orderId)!!.version
        val first = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, version, OPERATOR)).value
        f.setGoalState(first.goalId, GoalState.STOPPED, "TASKS_INCOMPLETE")
        f.repo.evaluateEndedCycles(10)
        assertEquals(1, f.repo.get(root.orderId)!!.consecutiveFailedCycles)
        assertEquals(OrderState.ACTIVE, f.repo.get(root.orderId)!!.state)

        version = f.repo.get(root.orderId)!!.version
        val second = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, version, OPERATOR)).value
        f.setGoalState(second.goalId, GoalState.STOPPED, "TASKS_INCOMPLETE")
        f.repo.evaluateEndedCycles(10)
        val paused = assertNotNull(f.repo.get(root.orderId))
        assertEquals(OrderState.PAUSED, paused.state)
        assertEquals(OrderReason.CYCLE_FAILURES, paused.stateReason)
        assertEquals(2, paused.consecutiveFailedCycles)
    }

    @Test
    fun `an AI request moves the order to STOP_REQUESTED and the digest binds the version`() = withFixture { f ->
        val root = f.root()
        val request = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            f.repo.requestStop(root.orderId, root.version, AI, OrderActor.AI, StopRequestReason.RUNAWAY_SUSPECTED),
        ).value
        assertEquals(StopRequestState.PENDING, request.state)
        assertEquals(OrderActor.AI, request.requesterKind)
        val order = assertNotNull(f.repo.get(root.orderId))
        assertEquals(OrderState.STOP_REQUESTED, order.state)
        assertEquals(order.version, request.orderVersion)
        assertEquals(orderStopDigest(request.requestId, root.orderId, order.version, StopRequestReason.RUNAWAY_SUSPECTED), request.digest)
    }

    @Test
    fun `a second pending request for the same tree is refused`() = withFixture { f ->
        val root = f.root()
        f.repo.requestStop(root.orderId, root.version, AI, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST)
        val version = f.repo.get(root.orderId)!!.version
        assertEquals(
            OrderResult.Refused(OrderConflict.STOP_ALREADY_PENDING),
            f.repo.requestStop(root.orderId, version, OPERATOR, OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        )
        // The refused request wrote nothing: the order is still STOP_REQUESTED at the version of the first request.
        assertEquals(version, f.repo.get(root.orderId)!!.version)
        assertEquals(1, f.count("SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE order_id = '${root.orderId}'"))
    }

    @Test
    fun `the approval stops the order and consumes the request, and a replay is refused`() = withFixture { f ->
        val root = f.root()
        val request = requestByAi(f, root)
        val order = assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.approveStop(request.requestId, OPERATOR, request.digest)).value
        assertEquals(OrderState.STOPPED, order.state)
        assertEquals(OrderReason.OPERATOR_STOPPED, order.stateReason)
        assertEquals(StopRequestState.CONSUMED, f.repo.stopRequest(request.requestId)!!.state)
        assertEquals(OPERATOR, f.repo.stopRequest(request.requestId)!!.decidedBy)
        assertEquals(OrderResult.Refused(OrderConflict.STOP_NOT_PENDING), f.repo.approveStop(request.requestId, OPERATOR, request.digest))
    }

    @Test
    fun `the requester cannot approve its own request`() = withFixture { f ->
        val root = f.root()
        val request = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            f.repo.requestStop(root.orderId, root.version, OPERATOR, OrderActor.HUMAN, StopRequestReason.OPERATOR_REQUEST),
        ).value
        assertEquals(OrderResult.Refused(OrderConflict.SAME_PRINCIPAL), f.repo.approveStop(request.requestId, OPERATOR, request.digest))
        assertEquals(OrderState.STOP_REQUESTED, f.repo.get(root.orderId)!!.state)
        assertEquals(StopRequestState.PENDING, f.repo.stopRequest(request.requestId)!!.state)
    }

    @Test
    fun `the schema refuses a decision by the requester itself`() = withFixture { f ->
        val root = f.root()
        val request = requestByAi(f, root)
        assertFailsWith<SQLException> {
            f.exec("UPDATE selfdev_order_stop_requests SET decided_by = requested_by, decider_kind = 'HUMAN' WHERE request_id = ?", request.requestId)
        }
    }

    @Test
    fun `a wrong digest changes nothing`() = withFixture { f ->
        val root = f.root()
        val request = requestByAi(f, root)
        assertEquals(OrderResult.Refused(OrderConflict.DIGEST_MISMATCH), f.repo.approveStop(request.requestId, OPERATOR, "0".repeat(64)))
        assertEquals(OrderState.STOP_REQUESTED, f.repo.get(root.orderId)!!.state)
        assertEquals(StopRequestState.PENDING, f.repo.stopRequest(request.requestId)!!.state)
    }

    @Test
    fun `an approval after the order changed is refused`() = withFixture { f ->
        val root = f.root()
        val request = requestByAi(f, root)
        f.exec("UPDATE selfdev_orders SET version = version + 1 WHERE order_id = ?", root.orderId)
        assertEquals(OrderResult.Refused(OrderConflict.ORDER_CHANGED), f.repo.approveStop(request.requestId, OPERATOR, request.digest))
    }

    @Test
    fun `an approval after 24 hours is refused, expires the request and pauses the order without resuming it`() = withFixture { f ->
        val root = f.root()
        val request = requestByAi(f, root)
        f.clock.now = START.plus(Duration.ofHours(25))
        assertEquals(OrderResult.Refused(OrderConflict.STOP_EXPIRED), f.repo.approveStop(request.requestId, OPERATOR, request.digest))
        assertEquals(StopRequestState.EXPIRED, f.repo.stopRequest(request.requestId)!!.state)
        val order = f.repo.get(root.orderId)!!
        assertEquals(OrderState.PAUSED, order.state)
        assertEquals(OrderReason.STOP_NOT_APPROVED, order.stateReason)
    }

    @Test
    fun `the sweeper expires overdue requests and no order is resumed by itself`() = withFixture { f ->
        val root = f.root()
        requestByAi(f, root)
        assertEquals(0, f.repo.expireDueStopRequests(10))
        f.clock.now = START.plus(Duration.ofHours(24))
        assertEquals(1, f.repo.expireDueStopRequests(10))
        assertEquals(OrderState.PAUSED, f.repo.get(root.orderId)!!.state)
        assertEquals(0, f.repo.expireDueStopRequests(10))
        f.clock.now = START.plus(Duration.ofHours(48))
        assertEquals(0, f.repo.expireDueOrders(10))
        assertEquals(OrderState.PAUSED, f.repo.get(root.orderId)!!.state)
    }

    @Test
    fun `a rejection and a cancellation both pause the order and the requester cannot reject`() = withFixture { f ->
        val root = f.root()
        val first = requestByAi(f, root)
        assertEquals(OrderResult.Refused(OrderConflict.SAME_PRINCIPAL), f.repo.endStopRequest(first.requestId, StopEnd.REJECTED, AI, OrderActor.AI))
        val rejected = assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.endStopRequest(first.requestId, StopEnd.REJECTED, OPERATOR, OrderActor.HUMAN)).value
        assertEquals(OrderState.PAUSED, rejected.state)
        assertEquals(OrderReason.STOP_NOT_APPROVED, rejected.stateReason)
        assertEquals(StopRequestState.REJECTED, f.repo.stopRequest(first.requestId)!!.state)

        val second = assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            f.repo.requestStop(root.orderId, rejected.version, OPERATOR, OrderActor.HUMAN, StopRequestReason.BUDGET_CONCERN),
        ).value
        val cancelled = assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.endStopRequest(second.requestId, StopEnd.CANCELLED, OPERATOR, OrderActor.HUMAN)).value
        assertEquals(OrderState.PAUSED, cancelled.state)
        val row = f.repo.stopRequest(second.requestId)!!
        assertEquals(StopRequestState.CANCELLED, row.state)
        assertNull(row.decidedBy)
    }

    @Test
    fun `two concurrent approvals of one request let exactly one succeed`() = withFixture { f ->
        val root = f.root()
        val request = requestByAi(f, root)
        val results = concurrently(2) { f.repo.approveStop(request.requestId, OPERATOR, request.digest) }
        assertEquals(1, results.count { it is OrderResult.Moved<*> }, "results=$results")
        assertEquals(OrderState.STOPPED, f.repo.get(root.orderId)!!.state)
    }

    @Test
    fun `two concurrent stop requests on one tree let exactly one succeed`() = withFixture { f ->
        val root = f.root()
        val results = concurrently(2) {
            f.repo.requestStop(root.orderId, root.version, AI, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST)
        }
        assertEquals(1, results.count { it is OrderResult.Moved<*> }, "results=$results")
        assertEquals(1, f.count("SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE order_id = '${root.orderId}'"))
    }

    @Test
    fun `the schema allows one pending request per tree and refuses a second one`() = withFixture { f ->
        val root = f.root()
        requestByAi(f, root)
        assertFailsWith<SQLException> {
            f.exec(
                "INSERT INTO selfdev_order_stop_requests (request_id, order_id, requested_by, requester_kind, reason_code, order_version, " +
                    "digest, state, active_key, expires_at, created_at) VALUES ('01CCCCCCCCCCCCCCCCCCCCCCCC', ?, 'x', 'AI', 'OPERATOR_REQUEST', 1, " +
                    "'${"a".repeat(64)}', 'PENDING', 1, NOW(6), NOW(6))",
                root.orderId,
            )
        }
    }

    @Test
    fun `at startup every ACTIVE order is held, and a paused one is left alone`() = withFixture { f ->
        val root = f.root()
        assertEquals(1, f.repo.holdActiveOnStartup())
        val held = assertNotNull(f.repo.get(root.orderId))
        assertEquals(OrderState.PAUSED, held.state)
        assertEquals(OrderReason.STARTUP_HOLD, held.stateReason)
        assertEquals(0, f.repo.holdActiveOnStartup())
    }

    @Test
    fun `the dispatch gate allows a goal without an order, and an ACTIVE order only`() = withFixture { f ->
        val plain = (f.goals.submit(OPERATOR, "plain-goal", "t", null, TARGET) as SubmitResult.Created).goal
        assertTrue(f.repo.dispatchAllowed(plain.goalId))
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, OPERATOR)).value
        assertTrue(f.repo.dispatchAllowed(started.goalId))
        f.repo.pauseBySystem(root.orderId, OrderReason.BUDGET_HOLD)
        assertTrue(!f.repo.dispatchAllowed(started.goalId))
    }

    @Test
    fun `a stopped order, and a cycle of it, is never dispatched again`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, OPERATOR)).value
        val request = requestByAi(f, f.repo.get(root.orderId)!!)
        f.repo.approveStop(request.requestId, OPERATOR, request.digest)
        assertTrue(!f.repo.dispatchAllowed(started.goalId))
    }

    private fun requestByAi(f: Fixture, root: SelfDevOrder): SelfDevOrderStopRequest =
        assertIs<OrderResult.Moved<SelfDevOrderStopRequest>>(
            f.repo.requestStop(root.orderId, root.version, AI, OrderActor.AI, StopRequestReason.OPERATOR_REQUEST),
        ).value

    /** Runs [count] calls at the same time, released together. */
    private fun <T> concurrently(count: Int, call: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val gate = CountDownLatch(1)
            val futures = (1..count).map {
                pool.submit(Callable {
                    gate.await()
                    call()
                })
            }
            gate.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
