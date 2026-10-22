package net.kigawa.kivra.db

import java.security.MessageDigest
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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
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
private val CHILD_LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)

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
    fun `hasUnknownRun is true only while a task of the goal holds a Run that is UNKNOWN`() = withFixture { f ->
        val root = f.root()
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, OPERATOR)).value
        assertFalse(f.repo.hasUnknownRun(started.goalId))
        val run = assertIs<TaskSubmitResult.Created>(f.db.runs.submitTask(OPERATOR, "unknown-run-repo", "a task")).run
        f.db.linkRunToGoal(started.goalId, run.runId.value)
        assertFalse(f.repo.hasUnknownRun(started.goalId), "a CREATED Run is not UNKNOWN")
        f.db.forceState(run.runId.value, DevelopmentRunState.UNKNOWN)
        assertTrue(f.repo.hasUnknownRun(started.goalId))
        assertFalse(f.repo.hasUnknownRun("01ZZZZZZZZZZZZZZZZZZZZZZZZ"), "another goal has no task with that Run")
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

    // ---- child orders (ADR 0015 section 2.7, stage 2 PR 3a) ----------------------------------------------------------------

    private fun Fixture.child(
        parentId: String,
        budget: Long = 1_000,
        key: String = "key-$parentId-${budget}-${Ulid.generate()}",
        title: String = "child",
        policy: String = "child policy",
        lim: ChildLimits = CHILD_LIMITS,
    ): ChildCreateResult = repo.createChild(parentId, title, policy, budget, key, OPERATOR, lim)

    private fun Fixture.created(result: ChildCreateResult): SelfDevOrder = assertIs<ChildCreateResult.Created>(result).order

    private fun Fixture.refusal(result: ChildCreateResult): ChildRefusal = assertIs<ChildCreateResult.Refused>(result).reason

    @Test
    fun `a child is ACTIVE under an ACTIVE parent, with the budget, the parent link and the depth`() = withFixture { f ->
        val root = f.root()
        val child = f.created(f.child(root.orderId, budget = 3_000, key = "first"))
        assertEquals(root.orderId, child.parentOrderId)
        assertEquals(1, child.depth)
        assertEquals(OrderState.ACTIVE, child.state)
        assertEquals(3_000, child.orderBudgetJpy)
        assertEquals(0, child.allocatedJpy)
        assertEquals(0, child.childCount)
        assertEquals(TARGET, child.targetRepo)
        val parent = f.repo.get(root.orderId)!!
        assertEquals(3_000, parent.allocatedJpy)
        assertEquals(1, parent.childCount)
        assertEquals(root.version + 1, parent.version)
    }

    @Test
    fun `a child needs an ACTIVE parent and ACTIVE ancestors, and a refusal writes nothing`() = withFixture { f ->
        val root = f.root()
        val c1 = f.created(f.child(root.orderId, key = "c1"))
        val c2 = f.created(f.child(root.orderId, key = "c2"))
        val c3 = f.created(f.child(root.orderId, key = "c3"))
        val c4 = f.created(f.child(root.orderId, key = "c4"))
        // c2 is STOPPED (approved), c3 is waiting for a stop, c4 is PAUSED, and the root is PAUSED (so c1 has a paused ancestor).
        val request2 = requestByAi(f, f.repo.get(c2.orderId)!!)
        assertIs<OrderResult.Moved<*>>(f.repo.approveStop(request2.requestId, OPERATOR, request2.digest))
        requestByAi(f, f.repo.get(c3.orderId)!!)
        f.repo.pauseByOperator(c4.orderId, f.repo.get(c4.orderId)!!.version, OPERATOR)
        f.repo.pauseByOperator(root.orderId, f.repo.get(root.orderId)!!.version, OPERATOR)

        val ordersBefore = f.count("SELECT COUNT(*) FROM selfdev_orders")
        val eventsBefore = f.count("SELECT COUNT(*) FROM selfdev_order_events")
        val rootBefore = f.repo.get(root.orderId)!!

        assertEquals(ChildRefusal.PARENT_NOT_ACTIVE, f.refusal(f.child("01ZZZZZZZZZZZZZZZZZZZZZZZZ", key = "missing")))
        assertEquals(ChildRefusal.PARENT_NOT_ACTIVE, f.refusal(f.child(c1.orderId, key = "under-paused-ancestor")))
        assertEquals(ChildRefusal.PARENT_NOT_ACTIVE, f.refusal(f.child(c2.orderId, key = "under-stopped")))
        assertEquals(ChildRefusal.PARENT_NOT_ACTIVE, f.refusal(f.child(c3.orderId, key = "under-waiting")))
        assertEquals(ChildRefusal.PARENT_NOT_ACTIVE, f.refusal(f.child(c4.orderId, key = "under-paused")))

        assertEquals(ordersBefore, f.count("SELECT COUNT(*) FROM selfdev_orders"))
        assertEquals(eventsBefore, f.count("SELECT COUNT(*) FROM selfdev_order_events"))
        val rootAfter = f.repo.get(root.orderId)!!
        assertEquals(rootBefore.allocatedJpy, rootAfter.allocatedJpy)
        assertEquals(rootBefore.childCount, rootAfter.childCount)
        assertEquals(rootBefore.version, rootAfter.version)
    }

    @Test
    fun `depth 2 is the limit, so a grandchild is made and a great-grandchild is refused`() = withFixture { f ->
        val root = f.root()
        val child = f.created(f.child(root.orderId, key = "c"))
        val grand = f.created(f.child(child.orderId, key = "g"))
        assertEquals(2, grand.depth)
        assertEquals(ChildRefusal.DEPTH_LIMIT, f.refusal(f.child(grand.orderId, key = "gg")))
    }

    @Test
    fun `a parent takes five children and a sixth is refused`() = withFixture { f ->
        val root = f.root()
        repeat(5) { i -> f.created(f.child(root.orderId, budget = 1, key = "k$i")) }
        assertEquals(5, f.repo.get(root.orderId)!!.childCount)
        assertEquals(ChildRefusal.CHILD_LIMIT, f.refusal(f.child(root.orderId, budget = 1, key = "k5")))
    }

    @Test
    fun `a tree holds fifteen orders with the root, and the sixteenth is refused`() = withFixture { f ->
        val root = f.root()
        val wide = ChildLimits(maxDepth = 2, maxChildren = 20, maxTree = 15)
        repeat(14) { i -> f.created(f.child(root.orderId, budget = 1, key = "w$i", lim = wide)) }
        assertEquals(15, f.count("SELECT COUNT(*) FROM selfdev_orders"))
        assertEquals(ChildRefusal.TREE_LIMIT, f.refusal(f.child(root.orderId, budget = 1, key = "w14", lim = wide)))
    }

    @Test
    fun `the children of a parent never allocate more than its budget`() = withFixture { f ->
        val root = f.root()
        f.created(f.child(root.orderId, budget = 3_000, key = "a"))
        assertEquals(ChildRefusal.BUDGET_EXCEEDS_PARENT, f.refusal(f.child(root.orderId, budget = 2_001, key = "b")))
        f.created(f.child(root.orderId, budget = 2_000, key = "c"))
        val parent = f.repo.get(root.orderId)!!
        assertEquals(5_000, parent.allocatedJpy)
        assertEquals(2, parent.childCount)
    }

    @Test
    fun `two children created at the same time never allocate more than the parent's budget`() = withFixture { f ->
        val root = f.root()
        val results = concurrently(2) {
            f.repo.createChild(root.orderId, "race", "race policy", 3_000, "race-${Thread.currentThread().id}", OPERATOR, CHILD_LIMITS)
        }
        assertEquals(1, results.count { it is ChildCreateResult.Created }, "results=$results")
        assertEquals(listOf(ChildRefusal.BUDGET_EXCEEDS_PARENT), results.filterIsInstance<ChildCreateResult.Refused>().map { it.reason })
        val parent = f.repo.get(root.orderId)!!
        assertEquals(3_000, parent.allocatedJpy)
        assertEquals(1, parent.childCount)
    }

    @Test
    fun `the same key with the same content replays the child, and another content under the key is a conflict`() = withFixture { f ->
        val root = f.root()
        val first = f.created(f.child(root.orderId, budget = 1_000, key = "same"))
        val replay = assertIs<ChildCreateResult.Replayed>(f.child(root.orderId, budget = 1_000, key = "same")).order
        assertEquals(first.orderId, replay.orderId)
        assertIs<ChildCreateResult.KeyConflict>(f.child(root.orderId, budget = 1_000, key = "same", title = "other"))
        assertIs<ChildCreateResult.KeyConflict>(f.child(root.orderId, budget = 2_000, key = "same"))
        val parent = f.repo.get(root.orderId)!!
        assertEquals(1_000, parent.allocatedJpy)
        assertEquals(1, parent.childCount)
    }

    @Test
    fun `a child creation is audited with the parent, the depth and the amount, and never with the title or the policy`() = withFixture { f ->
        val root = f.root()
        val child = f.created(f.child(root.orderId, budget = 1_000, title = TITLE_MARKER, policy = POLICY_MARKER))
        val rows = transaction(f.db.database) {
            execQuery(
                "SELECT order_id, parent_order_id, depth, amount_jpy, actor_kind, actor_ref FROM selfdev_order_events WHERE kind = 'CHILD_CREATED'",
            ) { rs ->
                listOf(rs.getString(1), rs.getString(2), rs.getInt(3).toString(), rs.getLong(4).toString(), rs.getString(5), rs.getString(6))
            }
        }
        val expectedRef = MessageDigest.getInstance("SHA-256").digest(OPERATOR.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        assertEquals(listOf(child.orderId, root.orderId, "1", "1000", "HUMAN", expectedRef), rows.single())
        assertEquals(0, f.count("SELECT COUNT(*) FROM selfdev_order_events WHERE reason_code LIKE '%$TITLE_MARKER%' OR reason_code LIKE '%$POLICY_MARKER%'"))
    }

    // ---- the cycle budget check (ADR 0015 section 2.7, stage 2 PR 3b) -------------------------------------------------------------

    /** Sets the cost of an order by SQL: nothing in the application writes spent_jpy yet (PR 3c feeds it). */
    private fun Fixture.setSpent(orderId: String, spent: Long) = exec("UPDATE selfdev_orders SET spent_jpy = ? WHERE order_id = ?", spent, orderId)

    private fun Fixture.goalsOf(orderId: String): Int = count("SELECT COUNT(*) FROM selfdev_goals WHERE order_id = '$orderId'")

    @Test
    fun `a new order has no spend and a 1,000 yen cycle budget, and a cycle needs the budget left, at the limit too`() = withFixture { f ->
        val root = f.root()
        assertEquals(0, root.spentJpy)
        assertEquals(1_000, root.cycleBudgetJpy)
        f.setSpent(root.orderId, 4_001) // 999 left, below the 1,000 yen of one cycle
        assertEquals(OrderResult.Refused(OrderConflict.ORDER_BUDGET, root.orderId), f.repo.startCycle(root.orderId, root.version, OPERATOR))
        assertEquals(0, f.repo.get(root.orderId)!!.cyclesStarted)
        assertEquals(0, f.goalsOf(root.orderId), "a refused start writes no goal")
        f.setSpent(root.orderId, 4_000) // exactly 1,000 left: allowed
        assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(root.orderId, root.version, OPERATOR))
    }

    @Test
    fun `a child's cycle is refused by the child's own budget, then by an ancestor's, and each refusal names that order`() = withFixture { f ->
        val root = f.root()
        val child = f.created(f.child(root.orderId, budget = 3_000, key = "budget-child"))
        f.setSpent(child.orderId, 2_001) // the child has 999 left
        assertEquals(OrderResult.Refused(OrderConflict.ORDER_BUDGET, child.orderId), f.repo.startCycle(child.orderId, child.version, OPERATOR))
        f.setSpent(child.orderId, 0)
        f.setSpent(root.orderId, 4_500) // the child has enough, the root has 500 left
        assertEquals(OrderResult.Refused(OrderConflict.ORDER_BUDGET, root.orderId), f.repo.startCycle(child.orderId, child.version, OPERATOR))
        assertEquals(0, f.repo.get(child.orderId)!!.cyclesStarted)
        assertEquals(0, f.goalsOf(child.orderId), "a refused start writes no goal")
        f.setSpent(root.orderId, 0)
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(child.orderId, child.version, OPERATOR)).value
        assertEquals(1, started.order.cyclesStarted)
        assertEquals(1, f.goalsOf(child.orderId))
        assertEquals(0, f.repo.get(root.orderId)!!.cyclesStarted, "the child's cycle is not counted on the root")
    }

    @Test
    fun `two cycle starts on two children of one tree at the same time both start, without a deadlock`() = withFixture { f ->
        val root = f.root()
        val children = listOf(
            f.created(f.child(root.orderId, budget = 2_000, key = "cycle-a")),
            f.created(f.child(root.orderId, budget = 2_000, key = "cycle-b")),
        )
        val next = java.util.concurrent.atomic.AtomicInteger()
        val results = concurrently(2) {
            val child = children[next.getAndIncrement()]
            f.repo.startCycle(child.orderId, child.version, OPERATOR)
        }
        results.forEach { assertIs<OrderResult.Moved<CycleStarted>>(it) }
        assertEquals(2, f.count("SELECT COUNT(*) FROM selfdev_goals WHERE order_id IN (SELECT order_id FROM selfdev_orders WHERE parent_order_id = '${root.orderId}')"))
    }

    // ---- the effective state (ADR 0015 section 2.4, stage 2 PR 3c) ---------------------------------------------------------------

    /** A root, a child and a grandchild: the grandchild's cycle and resume are checked against both ancestors. */
    private fun Fixture.tree(): Triple<SelfDevOrder, SelfDevOrder, SelfDevOrder> {
        val root = root()
        val child = created(child(root.orderId, budget = 3_000, key = "eff-child"))
        val grand = created(child(child.orderId, budget = 1_000, key = "eff-grand"))
        // Creating a child bumps its parent's version, so the versions are read again after the whole tree exists.
        return Triple(repo.get(root.orderId)!!, repo.get(child.orderId)!!, repo.get(grand.orderId)!!)
    }

    @Test
    fun `a child's cycle starts only under ACTIVE ancestors, and a refused start writes nothing`() = withFixture { f ->
        val (root, child, grand) = f.tree()
        val childVersion = f.repo.get(child.orderId)!!.version
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(root.orderId, root.version, OPERATOR))
        val rootPaused = f.repo.get(root.orderId)!!
        val goalsBefore = f.count("SELECT COUNT(*) FROM selfdev_goals")
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.startCycle(child.orderId, child.version, OPERATOR))
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.startCycle(grand.orderId, grand.version, OPERATOR))
        assertEquals(goalsBefore, f.count("SELECT COUNT(*) FROM selfdev_goals"), "a refused start writes no goal")
        val childAfter = f.repo.get(child.orderId)!!
        assertEquals(0, childAfter.cyclesStarted)
        assertEquals(childVersion, childAfter.version, "the child's row is not changed by its parent's pause")
        assertEquals(rootPaused.cyclesStarted, f.repo.get(root.orderId)!!.cyclesStarted)
    }

    @Test
    fun `a parent in STOP_REQUESTED stops a child's cycle, and a stopped ancestor stops it too`() = withFixture { f ->
        val (root, child, grand) = f.tree()
        val request = requestByAi(f, root)
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.startCycle(child.orderId, child.version, OPERATOR))
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.startCycle(grand.orderId, grand.version, OPERATOR))
        // A cancelled request leaves the root PAUSED (STOP_NOT_APPROVED); the operator resumes it.
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.endStopRequest(request.requestId, StopEnd.CANCELLED, OPERATOR, OrderActor.HUMAN))
        assertEquals(OrderState.PAUSED, f.repo.get(root.orderId)!!.state)
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.resumeByOperator(root.orderId, f.repo.get(root.orderId)!!.version, OPERATOR))
        assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(child.orderId, child.version, OPERATOR))

        // The child is stopped (approved): its ACTIVE grandchild is blocked by the nearest ancestor that is not ACTIVE.
        val childRequest = requestByAi(f, f.repo.get(child.orderId)!!)
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.approveStop(childRequest.requestId, OPERATOR, childRequest.digest))
        assertEquals(OrderState.STOPPED, f.repo.get(child.orderId)!!.state)
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, child.orderId), f.repo.startCycle(grand.orderId, grand.version, OPERATOR))
    }

    @Test
    fun `a grandchild's cycle is refused by a PAUSED grandparent, and a child under a paused parent is refused too`() = withFixture { f ->
        val (root, child, grand) = f.tree()
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(root.orderId, root.version, OPERATOR))
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.startCycle(grand.orderId, grand.version, OPERATOR))
        // The middle one is ACTIVE, the root is paused: the grandchild is blocked by the root, the nearest one that is not ACTIVE.
        assertEquals(OrderState.ACTIVE, f.repo.get(child.orderId)!!.state)
        assertEquals(0, f.repo.get(grand.orderId)!!.cyclesStarted)
    }

    @Test
    fun `a child's resume is refused while an ancestor is held, and goes through once the ancestor is ACTIVE again`() = withFixture { f ->
        val (root, child, grand) = f.tree()
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(child.orderId, child.version, OPERATOR))
        val pausedChild = f.repo.get(child.orderId)!!
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(root.orderId, root.version, OPERATOR))
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.resumeByOperator(child.orderId, pausedChild.version, OPERATOR))
        val afterRefusal = f.repo.get(child.orderId)!!
        assertEquals(OrderState.PAUSED, afterRefusal.state, "the refused resume does not write the child")
        assertEquals(pausedChild.version, afterRefusal.version)

        val rootPaused = f.repo.get(root.orderId)!!
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.resumeByOperator(root.orderId, rootPaused.version, OPERATOR))
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.resumeByOperator(child.orderId, pausedChild.version, OPERATOR))
        assertEquals(OrderState.ACTIVE, f.repo.get(child.orderId)!!.state)
        assertIs<OrderResult.Moved<CycleStarted>>(f.repo.startCycle(grand.orderId, grand.version, OPERATOR))
    }

    @Test
    fun `a child's resume is refused while the parent is in STOP_REQUESTED, and the request is not approved by it`() = withFixture { f ->
        val (root, child, _) = f.tree()
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(child.orderId, child.version, OPERATOR))
        val pausedChild = f.repo.get(child.orderId)!!
        val request = requestByAi(f, f.repo.get(root.orderId)!!)
        assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), f.repo.resumeByOperator(child.orderId, pausedChild.version, OPERATOR))
        assertEquals(OrderState.STOP_REQUESTED, f.repo.get(root.orderId)!!.state)
        assertEquals(StopRequestState.PENDING, f.repo.stopRequest(request.requestId)!!.state)
    }

    @Test
    fun `a pause under a held ancestor is allowed and does not reach the parent`() = withFixture { f ->
        val (root, child, _) = f.tree()
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(root.orderId, root.version, OPERATOR))
        val rootPaused = f.repo.get(root.orderId)!!
        assertIs<OrderResult.Moved<SelfDevOrder>>(f.repo.pauseByOperator(child.orderId, child.version, OPERATOR))
        assertEquals(OrderState.PAUSED, f.repo.get(child.orderId)!!.state)
        val rootAfter = f.repo.get(root.orderId)!!
        assertEquals(rootPaused.version, rootAfter.version, "the child's pause writes nothing to the parent")
        assertEquals(OrderReason.OPERATOR_PAUSED, rootAfter.stateReason)
    }

    @Test
    fun `a parent's pause and a child's cycle start at the same time both finish, without a deadlock`() = withFixture { f ->
        val (root, child, grand) = f.tree()
        val next = java.util.concurrent.atomic.AtomicInteger()
        val startSeen = java.util.concurrent.atomic.AtomicReference<OrderResult<CycleStarted>>()
        concurrently(2) {
            if (next.getAndIncrement() == 0) {
                f.repo.pauseByOperator(root.orderId, root.version, OPERATOR)
            } else {
                startSeen.set(f.repo.startCycle(grand.orderId, grand.version, OPERATOR))
            }
        }
        // Either the start came first (its cycle is written, and the pause then moves the root) or the pause came first (the start is refused
        // with the root's ID). In both cases the goal count matches the start's result, and nothing else is left half-written.
        val start = startSeen.get()
        assertEquals(OrderState.PAUSED, f.repo.get(root.orderId)!!.state)
        assertEquals(OrderState.ACTIVE, f.repo.get(child.orderId)!!.state)
        when (start) {
            is OrderResult.Moved -> assertEquals(1, f.goalsOf(grand.orderId))
            else -> {
                assertEquals(OrderResult.Refused(OrderConflict.ANCESTOR_NOT_ACTIVE, root.orderId), start)
                assertEquals(0, f.goalsOf(grand.orderId))
            }
        }
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

/**
 * Links a Run to a goal the way a Plan does (ADR 0011 section 5): one PROPOSED plan of the goal, with one task that holds the Run. The
 * Run's state is the caller's to set. Used by the order tests that need an UNKNOWN Run of the current cycle.
 */
internal fun TestDb.linkRunToGoal(goalId: String, runId: String) {
    val planId = Ulid.generate()
    transaction(database) {
        execUpdate(
            """
            INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, summary, planner_model, output_sha256, request_ledger_id, created_at)
            VALUES (?, ?, 1, 'PROPOSED', NULL, 'test-model', ?, NULL, NOW(6))
            """.trimIndent(),
            planId, goalId, "0".repeat(64),
        )
        execUpdate(
            """
            INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, target_paths, size_estimate, depends_on, requires_approval, state,
                idempotency_key, run_id, attempts, version, created_at, updated_at)
            VALUES (?, 1, 'a task', '[]', 'S', '[]', FALSE, 'SUBMITTED', ?, ?, 1, 0, NOW(6), NOW(6))
            """.trimIndent(),
            planId, "ap:$planId:1", runId,
        )
    }
}
