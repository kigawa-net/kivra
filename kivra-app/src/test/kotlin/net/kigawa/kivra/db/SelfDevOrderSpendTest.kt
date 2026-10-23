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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.coding.DbCodingCostLedger
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.developer.LlmDeveloper
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.selfdev.OrderWatch
import net.kigawa.kivra.selfdev.plannerRequestId
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "1:i:kigawa"
private const val TARGET = "kigawa-net/kivra"
private val START: Instant = Instant.parse("2026-10-23T10:00:00Z")
private val CHILD_LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)

private class MovingClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** The cycle counting of the order cost (ADR 0015 addendum 2026-10-23, stage 3g), on H2. */
class SelfDevOrderSpendTest {

    private class Fixture(val db: TestDb) {
        val clock = MovingClock(START)
        val orders = SelfDevOrderRepository(db.database, clock)
        val root: SelfDevOrder = (orders.createRoot(OPERATOR, "root title", "root policy", TARGET) as CreateRootResult.Created).order

        fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

        fun current(order: SelfDevOrder): SelfDevOrder = assertNotNull(orders.get(order.orderId))

        fun startCycle(order: SelfDevOrder): CycleStarted =
            assertIs<OrderResult.Moved<CycleStarted>>(orders.startCycle(order.orderId, current(order).version, OPERATOR)).value

        fun child(parent: SelfDevOrder, budget: Long = 2_000): SelfDevOrder = (
            orders.createChild(parent.orderId, "child", "child policy", budget, "key-${Ulid.generate()}", OPERATOR, CHILD_LIMITS) as
                ChildCreateResult.Created
            ).order

        fun spentOf(orderId: String): Long = assertNotNull(orders.get(orderId)).spentJpy

        private var nextAttemptNo = 1

        fun newRun(): String = db.runs.create("task-${Ulid.generate()}", "test", RunCompletionMode.PR_ONLY).runId.value

        /** A plan of [goalId] with one task submitted as [runId]. */
        fun task(goalId: String, runId: String) {
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
        }

        /** A CLI attempt of [runId]. Its state decides whether it is active (active_flag is generated from the state). */
        fun attempt(runId: String, state: String): String {
            val attemptId = Ulid.generate()
            exec(
                "INSERT INTO coding_attempts (attempt_id, run_id, attempt_no, state, claimed_by, lease_generation, lease_expires_at," +
                    " queued_expires_at, base_sha, result_digest, exit_status, error_code, session_id, version, created_at, updated_at)" +
                    " VALUES (?, ?, ?, ?, NULL, 1, NULL, TIMESTAMPADD(SECOND, 600, NOW(6)), ?, NULL, NULL, NULL, NULL, 0, ?, ?)",
                attemptId, runId, nextAttemptNo++, state, "a".repeat(40), utcNow(clock), utcNow(clock),
            )
            return attemptId
        }

        fun ledgerKeyOf(attemptId: String): String = DbCodingCostLedger.reservationKey(attemptId, 1)

        fun ledger(key: String, state: String, micro: Long) {
            exec(
                "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at, settled_at)" +
                    " VALUES (?, ?, ?, ?, ?, ?)",
                key, micro, state, utcNow(clock), utcNow(clock), utcNow(clock),
            )
        }

        fun setLedgerState(key: String, state: String) = exec("UPDATE budget_reservations SET state = ? WHERE reservation_key = ?", state, key)

        /** Starts a cycle of [order] and gives it one submitted Run. Returns the started cycle and the Run. */
        fun cycleWithRun(order: SelfDevOrder): Pair<CycleStarted, String> {
            val cycle = startCycle(order)
            val run = newRun()
            task(cycle.goalId, run)
            return cycle to run
        }

        fun endGoal(goalId: String, state: GoalState = GoalState.DONE) =
            exec("UPDATE selfdev_goals SET state = ?, state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", state.name, goalId)

        fun spendRow(orderId: String, cycleNo: Int): Map<String, Any?>? = transaction(db.database) {
            execQuery(
                "SELECT counted_jpy, ledger_micro_yen, final, updated_at FROM selfdev_order_cycle_spend WHERE order_id = ? AND cycle_no = ?",
                orderId, cycleNo,
            ) { rs ->
                mapOf("counted" to rs.getLong(1), "ledger" to rs.getLong(2), "final" to rs.getBoolean(3), "updated" to rs.getTimestamp(4))
            }.firstOrNull()
        }

        fun spendRowCount(): Int = db.count("selfdev_order_cycle_spend")

        fun watch(countSpend: Boolean = true): OrderWatch =
            OrderWatch(orders, budgetAllows = { true }, fxExpired = { false }, countSpend = countSpend)

        fun spendEvents(orderId: String): List<Pair<Long?, String?>> = transaction(db.database) {
            execQuery(
                "SELECT amount_jpy, reason_code FROM selfdev_order_events WHERE order_id = ? AND kind = 'SPEND_COUNTED' ORDER BY event_id",
                orderId,
            ) { rs -> rs.getLong(1).takeUnless { rs.wasNull() } to rs.getString(2) }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db)) }

    @Test
    fun `the cost is added once, rounded up per cycle, and a second tick on the same ledger adds nothing`() = withFixture { f ->
        val (cycle, run) = f.cycleWithRun(f.root)
        val a1 = f.attempt(run, "RESULT_RECEIVED")
        f.ledger(f.ledgerKeyOf(a1), DbBudgetStore.COMMITTED, 1_500_000)

        f.watch().tick()
        assertEquals(2L, f.spentOf(f.root.orderId))
        assertEquals(2L, f.spendRow(f.root.orderId, cycle.cycleNo)!!["counted"])

        f.watch().tick()
        assertEquals(2L, f.spentOf(f.root.orderId))

        // The ledger grows to 3,100,000: the difference (4 - 2) is added, not the whole.
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_600_000)
        f.watch().tick()
        assertEquals(4L, f.spentOf(f.root.orderId))
        assertEquals(4L, f.spendRow(f.root.orderId, cycle.cycleNo)!!["counted"])
    }

    @Test
    fun `the cost of a child's cycle is added to the child, its ancestors and not to a sibling`() = withFixture { f ->
        val child = f.child(f.root, budget = 2_000)
        val grand = f.child(child, budget = 1_000)
        val sibling = f.child(f.root, budget = 2_000)
        val (cycle, run) = f.cycleWithRun(grand)
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_200_000)
        f.startCycle(sibling)

        f.watch().tick()

        assertEquals(2L, f.spentOf(grand.orderId))
        assertEquals(2L, f.spentOf(child.orderId))
        assertEquals(2L, f.spentOf(f.root.orderId))
        assertEquals(0L, f.spentOf(sibling.orderId))
        assertEquals(2L, f.spendRow(grand.orderId, cycle.cycleNo)!!["counted"])
    }

    @Test
    fun `when the ledger total falls, the counted yen falls with it, and spent never goes below zero`() = withFixture { f ->
        val (cycle, run) = f.cycleWithRun(f.root)
        val committed = f.attempt(run, "RESULT_RECEIVED")
        f.ledger(f.ledgerKeyOf(committed), DbBudgetStore.COMMITTED, 1_100_000)
        val reservedAttempt = f.attempt(run, "QUEUED")
        val reservedKey = f.ledgerKeyOf(reservedAttempt)
        f.ledger(reservedKey, DbBudgetStore.RESERVED, 2_000_000)
        f.watch().tick()
        assertEquals(4L, f.spentOf(f.root.orderId))

        // The reservation is released: the total goes from 3,100,000 to 1,100,000, so the counted yen goes from 4 to 2.
        f.setLedgerState(reservedKey, DbBudgetStore.RELEASED)
        f.watch().tick()
        assertEquals(2L, f.spentOf(f.root.orderId))
        assertEquals(2L, f.spendRow(f.root.orderId, cycle.cycleNo)!!["counted"])

        // The order's spent is lowered by hand below the fall that is due: the result is cut off at zero.
        f.exec("UPDATE selfdev_orders SET spent_jpy = 1 WHERE order_id = ?", f.root.orderId)
        f.setLedgerState(f.ledgerKeyOf(committed), DbBudgetStore.RELEASED)
        f.watch().tick()
        assertEquals(0L, f.spentOf(f.root.orderId))
        assertEquals(0L, f.spendRow(f.root.orderId, cycle.cycleNo)!!["counted"])
    }

    @Test
    fun `a cycle is final only when the goal has ended, no attempt is active and nothing is RESERVED, LAPSED or unconfirmed UNPRICED`() = withFixture { f ->
        val (cycle, run) = f.cycleWithRun(f.root)
        f.endGoal(cycle.goalId)
        val reserved = f.attempt(run, "QUEUED")
        f.ledger(f.ledgerKeyOf(reserved), DbBudgetStore.RESERVED, 1_000_000)
        f.watch().tick()
        assertEquals(false, f.spendRow(f.root.orderId, cycle.cycleNo)!!["final"], "a RESERVED row keeps the cycle open")

        f.setLedgerState(f.ledgerKeyOf(reserved), DbBudgetStore.COMMITTED)
        f.exec("UPDATE coding_attempts SET state = 'RUNNING' WHERE attempt_id = ?", reserved)
        f.watch().tick()
        assertEquals(false, f.spendRow(f.root.orderId, cycle.cycleNo)!!["final"], "an active attempt keeps the cycle open")

        f.exec("UPDATE coding_attempts SET state = 'FAILED' WHERE attempt_id = ?", reserved)
        val lapsed = f.attempt(run, "FAILED")
        f.ledger(f.ledgerKeyOf(lapsed), DbBudgetStore.LAPSED, 500_000)
        f.watch().tick()
        assertEquals(false, f.spendRow(f.root.orderId, cycle.cycleNo)!!["final"], "a LAPSED row keeps the cycle open")

        f.setLedgerState(f.ledgerKeyOf(lapsed), DbBudgetStore.UNPRICED)
        f.watch().tick()
        assertEquals(false, f.spendRow(f.root.orderId, cycle.cycleNo)!!["final"], "an unconfirmed UNPRICED row keeps the cycle open")

        f.setLedgerState(f.ledgerKeyOf(lapsed), DbBudgetStore.ACKNOWLEDGED)
        f.watch().tick()
        assertEquals(true, f.spendRow(f.root.orderId, cycle.cycleNo)!!["final"])
        assertEquals(2L, f.spentOf(f.root.orderId), "the total is 1,000,000 + 500,000 micro-yen, rounded up once to 2 yen")

        // A final cycle is not touched again: a later change of the ledger is not counted, and the row is not written.
        val before = f.spendRow(f.root.orderId, cycle.cycleNo)!!["updated"]
        f.clock.now = START.plus(Duration.ofHours(1))
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 9_000_000)
        f.watch().tick()
        assertEquals(2L, f.spentOf(f.root.orderId))
        assertEquals(before, f.spendRow(f.root.orderId, cycle.cycleNo)!!["updated"])
    }

    @Test
    fun `a STOPPED goal is final too, and a stopped order's cycle is still counted (U-38)`() = withFixture { f ->
        val child = f.child(f.root)
        val (cycle, run) = f.cycleWithRun(child)
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_000_000)
        f.exec("UPDATE selfdev_orders SET state = 'STOPPED', state_reason = 'PARENT_STOPPED' WHERE order_id = ?", child.orderId)
        f.endGoal(cycle.goalId, GoalState.STOPPED)

        f.watch().tick()

        assertEquals(1L, f.spentOf(child.orderId))
        assertEquals(1L, f.spentOf(f.root.orderId))
        assertEquals(true, f.spendRow(child.orderId, cycle.cycleNo)!!["final"])
    }

    @Test
    fun `with the switch off, the tick writes no spend and no spend row`() = withFixture { f ->
        val (_, run) = f.cycleWithRun(f.root)
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_500_000)

        f.watch(countSpend = false).tick()

        assertEquals(0L, f.spentOf(f.root.orderId))
        assertEquals(0, f.spendRowCount())
    }

    @Test
    fun `a cycle counted before the start of the next one makes the 3b budget check refuse it`() = withFixture { f ->
        val child = f.child(f.root, budget = 1_000)
        f.exec("UPDATE selfdev_orders SET max_cycles = 3 WHERE order_id = ?", child.orderId)
        val (first, run) = f.cycleWithRun(child)
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1)
        f.endGoal(first.goalId)
        f.watch().tick()
        assertEquals(1L, f.spentOf(child.orderId))

        // 1,000 - 1 < 1,000: the next cycle is refused with the child's own ID, nothing is written.
        val refused = f.orders.startCycle(child.orderId, f.current(child).version, OPERATOR)
        assertEquals(OrderResult.Refused(OrderConflict.ORDER_BUDGET, child.orderId), refused)
    }

    @Test
    fun `two ticks that count the same cycle at the same time add its cost once`() = withFixture { f ->
        val (cycle, run) = f.cycleWithRun(f.root)
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_500_000)
        val cycleRef = SelfDevOrderRepository.SpendCycle(cycle.goalId, f.root.orderId, cycle.cycleNo)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val tasks = (1..2).map {
                pool.submit<Boolean> {
                    barrier.await(10, TimeUnit.SECONDS)
                    f.orders.countCycleSpend(cycleRef)
                }
            }
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2L, f.spentOf(f.root.orderId))
        assertEquals(2L, f.spendRow(f.root.orderId, cycle.cycleNo)!!["counted"])
    }

    @Test
    fun `counting two cycles of the same tree at the same time does not deadlock, and each is counted`() = withFixture { f ->
        val left = f.child(f.root, budget = 2_000)
        val right = f.child(f.root, budget = 2_000)
        val (cl, rl) = f.cycleWithRun(left)
        val (cr, rr) = f.cycleWithRun(right)
        f.ledger(f.ledgerKeyOf(f.attempt(rl, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_000_001)
        f.ledger(f.ledgerKeyOf(f.attempt(rr, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 2_000_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val tasks = listOf(
                SelfDevOrderRepository.SpendCycle(cl.goalId, left.orderId, cl.cycleNo),
                SelfDevOrderRepository.SpendCycle(cr.goalId, right.orderId, cr.cycleNo),
            ).map { cycle ->
                pool.submit<Boolean> {
                    barrier.await(10, TimeUnit.SECONDS)
                    f.orders.countCycleSpend(cycle)
                }
            }
            tasks.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2L, f.spentOf(left.orderId))
        assertEquals(2L, f.spentOf(right.orderId))
        assertEquals(4L, f.spentOf(f.root.orderId))
    }

    @Test
    fun `a cycle that fails is skipped with the others counted in the same tick`() = withFixture { f ->
        val bad = f.child(f.root, budget = 2_000)
        val good = f.child(f.root, budget = 2_000)
        val (_, rb) = f.cycleWithRun(bad)
        val (_, rg) = f.cycleWithRun(good)
        // An unknown state makes the reader fail for the bad cycle only.
        f.ledger(f.ledgerKeyOf(f.attempt(rb, "RESULT_RECEIVED")), "BOGUS_STATE", 1_000)
        f.ledger(f.ledgerKeyOf(f.attempt(rg, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_000_000)

        f.watch().tick()

        assertEquals(0L, f.spentOf(bad.orderId))
        assertEquals(1L, f.spentOf(good.orderId))
        assertEquals(1L, f.spentOf(f.root.orderId))
    }

    @Test
    fun `each change writes one SPEND_COUNTED event with the change in yen, and an unchanged cycle writes none`() = withFixture { f ->
        val child = f.child(f.root)
        val (cycle, run) = f.cycleWithRun(child)
        val attempt = f.attempt(run, "RESULT_RECEIVED")
        f.ledger(f.ledgerKeyOf(attempt), DbBudgetStore.COMMITTED, 1_500_000)

        f.watch().tick()
        f.watch().tick()
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_600_000)
        f.watch().tick()

        // One event per change, on the cycle's own order. The root gets none (the ancestors are counted, not audited).
        assertEquals(listOf(2L to null, 2L to null), f.spendEvents(child.orderId))
        assertEquals(emptyList(), f.spendEvents(f.root.orderId))
        assertEquals(1, cycle.cycleNo)
    }

    @Test
    fun `a cycle whose goal ended with nothing open is final at once, and its audit row says FINAL`() = withFixture { f ->
        val (cycle, run) = f.cycleWithRun(f.root)
        f.ledger(f.ledgerKeyOf(f.attempt(run, "RESULT_RECEIVED")), DbBudgetStore.COMMITTED, 1_000_000)
        f.endGoal(cycle.goalId)
        f.watch().tick()
        assertEquals(listOf(1L to "FINAL"), f.spendEvents(f.root.orderId))
        assertEquals(true, f.spendRow(f.root.orderId, cycle.cycleNo)!!["final"])
    }

    @Test
    fun `the planner key and the developer key are counted into the cycle the same way`() = withFixture { f ->
        val (cycle, run) = f.cycleWithRun(f.root)
        f.ledger(ModelGateway.ledgerKey(plannerRequestId(cycle.goalId, 1L, 1), 1), DbBudgetStore.COMMITTED, 1_000_000)
        f.ledger(ModelGateway.ledgerKey(LlmDeveloper.requestIdOf(run, 1), 1), DbBudgetStore.COMMITTED, 1)
        f.watch().tick()
        assertEquals(2L, f.spentOf(f.root.orderId))
    }
}
