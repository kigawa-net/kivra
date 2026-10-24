package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.coding.DbCodingCostLedger
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderReason
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import net.kigawa.kivra.selfdev.OrderWatch
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "1:i:kigawa"
private const val OTHER = "1:i:other"
private const val TARGET = "kigawa-net/kivra"
private val START: Instant = Instant.parse("2026-10-23T10:00:00Z")
private val CHILD_LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)

private class CycleTestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

/**
 * The automatic cycle start (ADR 0015 addendum 2026-10-24, stage 3 early implementation, dev only) on H2 (and MariaDB with
 * KIVRA_TEST_DB=mariadb). It shares the checks of the operator's start; here every condition of section 3.1 step 1 and 3.2 is shown to
 * hold a cycle back, and the audit row of a start is shown to carry the fixed code.
 */
class SelfDevOrderAutoCycleTest {

    private class Fixture(val db: TestDb) {
        val clock = CycleTestClock(START)
        val orders = SelfDevOrderRepository(db.database, clock)
        var maxCycles = 10
        val root: SelfDevOrder = (orders.createRoot(OPERATOR, "root title", "root policy", TARGET, maxCycles) as CreateRootResult.Created).order

        fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

        fun current(order: SelfDevOrder = root): SelfDevOrder = assertNotNull(orders.get(order.orderId))

        fun watch(cap: Int? = 10, countSpend: Boolean = false): OrderWatch =
            OrderWatch(orders, budgetAllows = { true }, fxExpired = { false }, countSpend = countSpend, autoCycleCap = cap)

        fun child(parent: SelfDevOrder, budget: Long = 2_000): SelfDevOrder = (
            orders.createChild(parent.orderId, "child", "child policy", budget, "key-${Ulid.generate()}", OPERATOR, CHILD_LIMITS) as
                ChildCreateResult.Created
            ).order

        fun goalIds(order: SelfDevOrder = root): List<String> = transaction(db.database) {
            execQuery("SELECT goal_id FROM selfdev_goals WHERE order_id = ? ORDER BY cycle_no", order.orderId) { it.getString(1) }
        }

        fun endGoal(goalId: String, state: GoalState = GoalState.DONE) =
            exec("UPDATE selfdev_goals SET state = ?, state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", state.name, goalId)

        /** Ends the order's running goal, so that the next tick records it and the order may have its next cycle. */
        fun endCurrent(order: SelfDevOrder = root, state: GoalState = GoalState.DONE) = endGoal(assertNotNull(current(order).currentGoalId), state)

        fun events(order: SelfDevOrder = root, kind: String = "CYCLE_STARTED"): List<List<String?>> = transaction(db.database) {
            execQuery(
                "SELECT kind, actor_kind, actor_ref, reason_code, from_state, to_state FROM selfdev_order_events WHERE order_id = ? AND kind = ? ORDER BY event_id",
                order.orderId, kind,
            ) { rs -> (1..6).map { rs.getString(it) } }
        }

        fun auto(order: SelfDevOrder = root, cap: Int = 10): AutoCycleResult = orders.startCycleAutomatically(order.orderId, cap)

        private var nextAttemptNo = 1

        fun cycleRun(goalId: String): String {
            val runId = db.runs.create("task-${Ulid.generate()}", "test", RunCompletionMode.PR_ONLY).runId.value
            val planId = Ulid.generate()
            exec(
                "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, summary, planner_model, output_sha256, request_ledger_id, created_at)" +
                    " VALUES (?, ?, 1, 'PROPOSED', NULL, 'claude-haiku-5-5', ?, NULL, ?)",
                planId, goalId, "0".repeat(64), utcNow(clock),
            )
            exec(
                "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval," +
                    " state, state_reason, idempotency_key, run_id, attempts, version, created_at, updated_at)" +
                    " VALUES (?, 1, 'task', NULL, '[]', 'S', '[]', FALSE, 'SUBMITTED', NULL, ?, ?, 0, 0, ?, ?)",
                planId, "ap:$planId:1", runId, utcNow(clock), utcNow(clock),
            )
            return runId
        }

        fun unpricedAttempt(runId: String) {
            val attemptId = Ulid.generate()
            exec(
                "INSERT INTO coding_attempts (attempt_id, run_id, attempt_no, state, claimed_by, lease_generation, lease_expires_at," +
                    " queued_expires_at, base_sha, result_digest, exit_status, error_code, session_id, version, created_at, updated_at)" +
                    " VALUES (?, ?, ?, 'RESULT_RECEIVED', NULL, 1, NULL, TIMESTAMPADD(SECOND, 600, NOW(6)), ?, NULL, NULL, NULL, NULL, 0, ?, ?)",
                attemptId, runId, nextAttemptNo++, "a".repeat(40), utcNow(clock), utcNow(clock),
            )
            exec(
                "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at, settled_at)" +
                    " VALUES (?, 0, ?, ?, ?, ?)",
                DbCodingCostLedger.reservationKey(attemptId, 1), DbBudgetStore.UNPRICED, utcNow(clock), utcNow(clock), utcNow(clock),
            )
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db)) }

    private fun assertSkipped(reason: AutoCycleSkip, result: AutoCycleResult) =
        assertEquals(reason, assertIs<AutoCycleResult.Skipped>(result).reason)

    // ---- the switch -----------------------------------------------------------------------------------------------------

    @Test
    fun `with the switch off a tick starts nothing`() = withFixture { f ->
        f.watch(cap = null).tick()
        f.watch(cap = null).tick()
        assertEquals(0, f.current().cyclesStarted)
        assertTrue(f.goalIds().isEmpty())
        assertTrue(f.events().isEmpty())
    }

    // ---- a start --------------------------------------------------------------------------------------------------------

    @Test
    fun `when every condition holds one tick starts one cycle, and the audit row has the fixed code and a SYSTEM actor`() = withFixture { f ->
        f.watch().tick()
        val order = f.current()
        assertEquals(1, order.cyclesStarted)
        val goalId = assertNotNull(order.currentGoalId)
        assertEquals(listOf(goalId), f.goalIds())
        val goal = SelfDevGoalRepository(f.db.database, f.clock).get(goalId)!!
        assertEquals(GoalState.GOAL_SUBMITTED, goal.state)
        assertEquals(OPERATOR, goal.createdBy)
        assertEquals("root title", goal.title)
        assertEquals("root policy", goal.body)
        assertEquals("order:${f.root.orderId}:cycle:1", goal.idempotencyKey)
        assertEquals(listOf(listOf("CYCLE_STARTED", "SYSTEM", null, AUTO_CYCLE_STARTED, "ACTIVE", "ACTIVE")), f.events())
        // The next tick starts nothing: the cycle is in progress.
        f.clock.advance(Duration.ofHours(5))
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
        assertEquals(1, f.events().size)
    }

    @Test
    fun `the operator's start is unchanged, so it has no interval, no breaker rule and a HUMAN event`() = withFixture { f ->
        f.exec("UPDATE selfdev_autopilot_control SET breaker_open = TRUE WHERE control_id = 1")
        val first = assertIs<OrderResult.Moved<CycleStarted>>(f.orders.startCycle(f.root.orderId, f.current().version, OPERATOR)).value
        f.endGoal(first.goalId)
        f.watch(cap = null).tick()
        assertIs<OrderResult.Moved<CycleStarted>>(f.orders.startCycle(f.root.orderId, f.current().version, OPERATOR))
        assertEquals(2, f.current().cyclesStarted)
        assertEquals(listOf("HUMAN", "HUMAN"), f.events().map { it[1] })
        assertEquals(listOf(null, null), f.events().map { it[3] })
    }

    // ---- the conditions -------------------------------------------------------------------------------------------------

    @Test
    fun `an order paused by an operator, or one with a pending stop request, is not started`() = withFixture { f ->
        assertIs<OrderResult.Moved<*>>(f.orders.pauseByOperator(f.root.orderId, f.current().version, OPERATOR))
        f.watch().tick()
        assertEquals(0, f.current().cyclesStarted)
        assertSkipped(AutoCycleSkip.NOT_ACTIVE, f.auto())

        assertIs<OrderResult.Moved<*>>(f.orders.resumeByOperator(f.root.orderId, f.current().version, OPERATOR))
        assertIs<OrderResult.Moved<*>>(
            f.orders.requestStop(f.root.orderId, f.current().version, "1:i:ai", OrderActor.AI, StopRequestReason.OPERATOR_REQUEST),
        )
        assertNotNull(f.orders.pendingStopRequestOf(f.root.orderId))
        f.watch().tick()
        assertEquals(0, f.current().cyclesStarted)
        assertSkipped(AutoCycleSkip.NOT_ACTIVE, f.auto())
    }

    @Test
    fun `after the startup hold nothing starts until an operator resumes the order`() = withFixture { f ->
        assertEquals(1, f.orders.holdActiveOnStartup())
        f.watch().tick()
        assertEquals(0, f.current().cyclesStarted)
        assertIs<OrderResult.Moved<*>>(f.orders.resumeByOperator(f.root.orderId, f.current().version, OPERATOR))
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
    }

    @Test
    fun `a paused ancestor holds the child back (effective state), and a tree has one cycle at a time`() = withFixture { f ->
        f.clock.advance(Duration.ofSeconds(1)) // the child is the younger row, so the root is looked at first
        val child = f.child(f.root)
        assertIs<OrderResult.Moved<*>>(f.orders.pauseByOperator(f.root.orderId, f.current().version, OPERATOR))
        f.watch().tick()
        assertEquals(0, f.current(child).cyclesStarted)
        assertEquals(AutoCycleResult.Skipped(AutoCycleSkip.ANCESTOR_NOT_ACTIVE, f.root.orderId), f.auto(child))

        assertIs<OrderResult.Moved<*>>(f.orders.resumeByOperator(f.root.orderId, f.current().version, OPERATOR))
        f.watch().tick()
        // Both are ACTIVE and ready, but only one cycle runs in the tree: the root (the older row) starts.
        assertEquals(1, f.current().cyclesStarted)
        assertEquals(0, f.current(child).cyclesStarted)
        assertSkipped(AutoCycleSkip.TREE_BUSY, f.auto(child))
    }

    @Test
    fun `the global pause and an open breaker hold the start back`() = withFixture { f ->
        f.exec("UPDATE selfdev_autopilot_control SET paused = TRUE WHERE control_id = 1")
        f.watch().tick()
        assertSkipped(AutoCycleSkip.AUTOPILOT_PAUSED, f.auto())
        f.exec("UPDATE selfdev_autopilot_control SET paused = FALSE, breaker_open = TRUE WHERE control_id = 1")
        f.watch().tick()
        assertSkipped(AutoCycleSkip.BREAKER_OPEN, f.auto())
        assertEquals(0, f.current().cyclesStarted)
        f.exec("UPDATE selfdev_autopilot_control SET breaker_open = FALSE WHERE control_id = 1")
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
    }

    @Test
    fun `an order with a cycle in progress is not started again, which is also the pending cycle waiting for an approval`() = withFixture { f ->
        val started = assertIs<OrderResult.Moved<CycleStarted>>(f.orders.startCycle(f.root.orderId, f.current().version, OPERATOR)).value
        f.clock.advance(Duration.ofHours(5))
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
        assertEquals(started.goalId, f.current().currentGoalId)
        assertSkipped(AutoCycleSkip.CYCLE_IN_PROGRESS, f.auto())
    }

    @Test
    fun `an expired order is paused by the tick and not started`() = withFixture { f ->
        f.clock.advance(Duration.ofDays(15))
        assertSkipped(AutoCycleSkip.ORDER_EXPIRED, f.auto())
        f.watch().tick()
        assertEquals(0, f.current().cyclesStarted)
        assertEquals(OrderReason.EXPIRED, f.current().stateReason)
    }

    @Test
    fun `an order stops at max_cycles with LIMIT_REACHED, and the default of one cycle is one cycle`() = withFixture { f ->
        val one = TestDb().use { db2 ->
            val repo = SelfDevOrderRepository(db2.database, CycleTestClock(START))
            val o = (repo.createRoot(OPERATOR, "t", "p", TARGET) as CreateRootResult.Created).order
            val w = OrderWatch(repo, { true }, { false }, autoCycleCap = 10)
            w.tick()
            val goal = assertNotNull(repo.get(o.orderId)!!.currentGoalId)
            transaction(db2.database) { execUpdate("UPDATE selfdev_goals SET state = 'DONE', state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", goal) }
            w.tick()
            w.tick()
            repo.get(o.orderId)!!
        }
        assertEquals(1, one.cyclesStarted)
        assertEquals(OrderState.PAUSED, one.state)
        assertEquals(OrderReason.LIMIT_REACHED, one.stateReason)
        assertNull(one.currentGoalId)
    }

    @Test
    fun `the cap below max_cycles pauses the order with LIMIT_REACHED instead of starting another cycle`() = withFixture { f ->
        f.watch(cap = 1).tick()
        assertEquals(1, f.current().cyclesStarted)
        f.endCurrent()
        f.clock.advance(Duration.ofHours(2))
        f.watch(cap = 1).tick()
        val order = f.current()
        assertEquals(1, order.cyclesStarted)
        assertEquals(OrderState.PAUSED, order.state)
        assertEquals(OrderReason.LIMIT_REACHED, order.stateReason)
        assertEquals(listOf(listOf("PAUSED", "SYSTEM", null, "LIMIT_REACHED", "ACTIVE", "PAUSED")), f.events(kind = "PAUSED"))
    }

    @Test
    fun `two failed cycles in a row pause the order with CYCLE_FAILURES and nothing resumes it`() = withFixture { f ->
        f.watch().tick()
        f.endCurrent(state = GoalState.STOPPED)
        f.clock.advance(Duration.ofMinutes(61))
        f.watch().tick() // records failure 1 and starts cycle 2 in the same pass
        assertEquals(2, f.current().cyclesStarted)
        assertEquals(1, f.current().consecutiveFailedCycles)
        f.endCurrent(state = GoalState.STOPPED)
        f.clock.advance(Duration.ofMinutes(61))
        f.watch().tick()
        val order = f.current()
        assertEquals(2, order.cyclesStarted)
        assertEquals(OrderState.PAUSED, order.state)
        assertEquals(OrderReason.CYCLE_FAILURES, order.stateReason)
        repeat(3) { f.clock.advance(Duration.ofHours(3)); f.watch().tick() }
        assertEquals(2, f.current().cyclesStarted)
        assertEquals(OrderState.PAUSED, f.current().state)
    }

    @Test
    fun `an ACTIVE order at the failure limit is not started`() = withFixture { f ->
        f.exec("UPDATE selfdev_orders SET consecutive_failed_cycles = 2 WHERE order_id = ?", f.root.orderId)
        assertTrue(f.orders.autoCycleCandidates(50).isEmpty())
        assertSkipped(AutoCycleSkip.CYCLE_FAILURES, f.auto())
        f.watch().tick()
        assertEquals(0, f.current().cyclesStarted)
    }

    @Test
    fun `the next cycle waits 60 minutes from the previous start`() = withFixture { f ->
        f.watch().tick()
        f.endCurrent()
        f.clock.advance(Duration.ofMinutes(59))
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
        assertNull(f.current().currentGoalId, "the cycle is recorded, and the next one waits")
        assertSkipped(AutoCycleSkip.TOO_SOON, f.auto())
        f.clock.advance(Duration.ofMinutes(1))
        f.watch().tick()
        assertEquals(2, f.current().cyclesStarted)
        assertEquals(2, f.events().size)
    }

    @Test
    fun `the interval counts a cycle an operator started`() = withFixture { f ->
        val first = assertIs<OrderResult.Moved<CycleStarted>>(f.orders.startCycle(f.root.orderId, f.current().version, OPERATOR)).value
        f.endGoal(first.goalId)
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
        assertSkipped(AutoCycleSkip.TOO_SOON, f.auto())
    }

    @Test
    fun `an order gets at most 3 automatic cycles in a UTC day, and starts again the next day`() = withFixture { f ->
        f.watch().tick() // 10:00, cycle 1
        repeat(2) {
            f.endCurrent()
            f.clock.advance(Duration.ofMinutes(60))
            f.watch().tick() // 11:00 and 12:00
        }
        assertEquals(3, f.current().cyclesStarted)
        f.endCurrent()
        f.clock.advance(Duration.ofMinutes(60))
        f.watch().tick() // 13:00: the interval is met, the day's count is not
        assertEquals(3, f.current().cyclesStarted)
        assertNull(f.current().currentGoalId)
        assertSkipped(AutoCycleSkip.DAILY_LIMIT, f.auto())
        f.clock.now = Instant.parse("2026-10-24T00:00:00Z")
        f.watch().tick()
        assertEquals(4, f.current().cyclesStarted)
    }

    @Test
    fun `an order whose budget path has less than one cycle left is not started, and the refused order is named`() = withFixture { f ->
        f.exec("UPDATE selfdev_orders SET spent_jpy = 4500 WHERE order_id = ?", f.root.orderId)
        f.watch().tick()
        assertEquals(0, f.current().cyclesStarted)
        assertEquals(AutoCycleResult.Skipped(AutoCycleSkip.ORDER_BUDGET, f.root.orderId), f.auto())
        assertTrue(f.goalIds().isEmpty())
        f.exec("UPDATE selfdev_orders SET spent_jpy = 4000 WHERE order_id = ?", f.root.orderId)
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
    }

    @Test
    fun `an ancestor's missing budget holds a child back`() = withFixture { f ->
        val child = f.child(f.root, budget = 2_000)
        f.exec("UPDATE selfdev_orders SET spent_jpy = 4500 WHERE order_id = ?", f.root.orderId)
        assertEquals(AutoCycleResult.Skipped(AutoCycleSkip.ORDER_BUDGET, f.root.orderId), f.auto(child))
    }

    @Test
    fun `an unconfirmed UNPRICED cycle on the path holds the start back (ORDER_UNPRICED)`() = withFixture { f ->
        f.watch(countSpend = true).tick()
        val goalId = assertNotNull(f.current().currentGoalId)
        f.unpricedAttempt(f.cycleRun(goalId))
        f.endGoal(goalId)
        f.clock.advance(Duration.ofHours(2))
        f.watch(countSpend = true).tick()
        assertEquals(1, f.current().cyclesStarted)
        assertSkipped(AutoCycleSkip.ORDER_UNPRICED, f.auto())
    }

    // ---- concurrency ----------------------------------------------------------------------------------------------------

    @Test
    fun `two simultaneous passes start the cycle once`() = withFixture { f ->
        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            val barrier = CyclicBarrier(2)
            (1..2).map {
                pool.submit<AutoCycleResult> {
                    barrier.await(10, TimeUnit.SECONDS)
                    f.orders.startCycleAutomatically(f.root.orderId, 10)
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, results.count { it is AutoCycleResult.Started })
        assertEquals(1, f.current().cyclesStarted)
        assertEquals(1, f.goalIds().size)
        assertEquals(1, f.events().size)
    }

    @Test
    fun `an automatic start and an operator's start at the same time make one cycle`() = withFixture { f ->
        val version = f.current().version
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val a = pool.submit<Any> { barrier.await(10, TimeUnit.SECONDS); f.orders.startCycleAutomatically(f.root.orderId, 10) }
            val b = pool.submit<Any> { barrier.await(10, TimeUnit.SECONDS); f.orders.startCycle(f.root.orderId, version, OTHER) }
            a.get(30, TimeUnit.SECONDS)
            b.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, f.current().cyclesStarted)
        assertEquals(1, f.goalIds().size)
    }

    @Test
    fun `a pass starts at most one cycle per order and an unknown order is not found`() = withFixture { f ->
        f.watch().tick()
        f.watch().tick()
        assertEquals(1, f.current().cyclesStarted)
        assertEquals(AutoCycleResult.NotFound, f.orders.startCycleAutomatically("0".repeat(26), 10))
    }

    @Test
    fun `createRoot refuses a max_cycles out of 1 to 10`() = withFixture { f ->
        // The fixture already made the root; the argument check comes before the insert.
        for (bad in listOf(0, 11, -1)) {
            assertFailsWith<IllegalArgumentException> { f.orders.createRoot(OPERATOR, "t", "p", TARGET, bad) }
        }
    }
}
