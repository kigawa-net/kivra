package net.kigawa.kivra.db

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.coding.DbCodingCostLedger
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.developer.LlmDeveloper
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.selfdev.plannerRequestId
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "op-kigawa"
private const val TARGET = "kigawa-net/kivra"
private val START: Instant = Instant.parse("2026-10-23T10:00:00Z")
private val CHILD_LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)

/**
 * The cycle cost reader (ADR 0015 addendum 2026-10-23, stage 3f). The ledger rows are written with the keys the producers build
 * (DbCodingCostLedger.reservationKey, LlmDeveloper.requestIdOf, AutopilotPlanner.requestIdOf, ModelGateway.ledgerKey), so a change in
 * a key format fails here as well as in SelfDevCycleSpendContractTest.
 */
class SelfDevCycleSpendReaderTest {

    private class Fixture(val db: TestDb) {
        val clock: Clock = Clock.fixed(START, ZoneOffset.UTC)
        val orders = SelfDevOrderRepository(db.database, clock)
        val reader = SelfDevCycleSpendReader(db.database)
        val root: SelfDevOrder =
            (orders.createRoot(OPERATOR, "root", "root policy", TARGET) as CreateRootResult.Created).order

        fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

        fun current(order: SelfDevOrder): SelfDevOrder = orders.get(order.orderId)!!

        fun startCycle(order: SelfDevOrder): CycleStarted =
            assertIs<OrderResult.Moved<CycleStarted>>(orders.startCycle(order.orderId, current(order).version, OPERATOR)).value

        fun child(parent: SelfDevOrder): SelfDevOrder = (
            orders.createChild(parent.orderId, "child", "child policy", 1_000, "key-${Ulid.generate()}", OPERATOR, CHILD_LIMITS) as
                ChildCreateResult.Created
            ).order

        fun newPlan(goalId: String, revision: Int = 1): String {
            val planId = Ulid.generate()
            exec(
                "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, summary, planner_model, output_sha256, request_ledger_id, created_at)" +
                    " VALUES (?, ?, ?, 'PROPOSED', NULL, 'claude-haiku-5-5', ?, NULL, ?)",
                planId, goalId, revision, "0".repeat(64), utcNow(clock),
            )
            return planId
        }

        /** One task of the plan, submitted as [runId] (its Run). */
        fun task(planId: String, ordinal: Int, runId: String) {
            exec(
                "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval," +
                    " state, state_reason, idempotency_key, run_id, attempts, version, created_at, updated_at)" +
                    " VALUES (?, ?, 'task', NULL, '[]', 'S', '[]', FALSE, 'SUBMITTED', NULL, ?, ?, 0, 0, ?, ?)",
                planId, ordinal, "ap:$planId:$ordinal", runId, utcNow(clock), utcNow(clock),
            )
        }

        fun newRun(): String = db.runs.create("task-${Ulid.generate()}", "test", RunCompletionMode.PR_ONLY).runId.value

        fun attempt(runId: String, attemptNo: Int, state: String, generation: Long): String {
            val attemptId = Ulid.generate()
            exec(
                "INSERT INTO coding_attempts (attempt_id, run_id, attempt_no, state, claimed_by, lease_generation, lease_expires_at," +
                    " queued_expires_at, base_sha, result_digest, exit_status, error_code, session_id, version, created_at, updated_at)" +
                    " VALUES (?, ?, ?, ?, NULL, ?, NULL, TIMESTAMPADD(SECOND, 600, NOW(6)), ?, NULL, NULL, NULL, NULL, 0, ?, ?)",
                attemptId, runId, attemptNo, state, generation, "a".repeat(40), utcNow(clock), utcNow(clock),
            )
            return attemptId
        }

        fun ledger(key: String, state: String, micro: Long) {
            exec(
                "INSERT INTO budget_reservations (reservation_key, amount_micro_yen, state, expires_at, created_at, settled_at)" +
                    " VALUES (?, ?, ?, ?, ?, ?)",
                key, micro, state, utcNow(clock), utcNow(clock), utcNow(clock),
            )
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db)) }

    @Test
    fun `the cost of every Run of the cycle is summed by state, from the keys of all three producers`() = withFixture { f ->
        val cycle = f.startCycle(f.root)
        val plan = f.newPlan(cycle.goalId)
        val r1 = f.newRun()
        val r2 = f.newRun()
        f.task(plan, 1, r1)
        f.task(plan, 2, r2)

        // CLI path: one row per attempt generation (coding:<attemptId>:<generation>).
        val a1 = f.attempt(r1, 1, "RESULT_RECEIVED", 1)
        f.ledger(DbCodingCostLedger.reservationKey(a1, 1), DbBudgetStore.COMMITTED, 100)
        val a2 = f.attempt(r1, 2, "FAILED", 1)
        f.ledger(DbCodingCostLedger.reservationKey(a2, 1), DbBudgetStore.UNPRICED, 50)
        val a3 = f.attempt(r2, 1, "RUNNING", 2)
        f.ledger(DbCodingCostLedger.reservationKey(a3, 2), DbBudgetStore.RESERVED, 30)
        val a4 = f.attempt(r2, 2, "FAILED", 1)
        f.ledger(DbCodingCostLedger.reservationKey(a4, 1), DbBudgetStore.LAPSED, 20)

        // Planner path (planner-<goalId>-...), and the API Developer path (developer-<runId>-...).
        val planner1 = plannerRequestId(cycle.goalId, 1L, 1)
        f.ledger(ModelGateway.ledgerKey(planner1, 1), DbBudgetStore.COMMITTED, 11)
        f.ledger(ModelGateway.ledgerKey(plannerRequestId(cycle.goalId, 1L, 2), 2), DbBudgetStore.RELEASED, 999)
        f.ledger(ModelGateway.ledgerKey(LlmDeveloper.requestIdOf(r2, 1), 1), DbBudgetStore.ACKNOWLEDGED, 13)

        val spend = f.reader.read(cycle.order.orderId, cycle.cycleNo)

        assertEquals(
            CycleSpend(
                committedMicroYen = 100 + 11 + 13,
                lapsedMicroYen = 20,
                reservedMicroYen = 30,
                unpricedMicroYen = 50,
                hasUnconfirmedUnpriced = true,
                attemptsActive = 1,
            ),
            spend,
        )
    }

    @Test
    fun `the cost of another cycle of the same order, and of a child order, does not mix in`() = withFixture { f ->
        f.exec("UPDATE selfdev_orders SET max_cycles = 5 WHERE order_id = ?", f.root.orderId)
        val first = f.startCycle(f.root)
        val firstRun = f.newRun()
        f.task(f.newPlan(first.goalId), 1, firstRun)
        f.ledger(DbCodingCostLedger.reservationKey(f.attempt(firstRun, 1, "RESULT_RECEIVED", 1), 1), DbBudgetStore.COMMITTED, 100)
        f.ledger(ModelGateway.ledgerKey(plannerRequestId(first.goalId, 1L, 1), 1), DbBudgetStore.COMMITTED, 5)

        // The first cycle ends, and a second one starts on the same order.
        f.exec("UPDATE selfdev_goals SET state = ?, state_reason = ? WHERE goal_id = ?", GoalState.DONE.name, "ALL_TASKS_COMPLETED", first.goalId)
        assertEquals(1, f.orders.evaluateEndedCycles(10))
        val second = f.startCycle(f.root)
        val secondRun = f.newRun()
        f.task(f.newPlan(second.goalId), 1, secondRun)
        f.ledger(DbCodingCostLedger.reservationKey(f.attempt(secondRun, 1, "RESULT_RECEIVED", 1), 1), DbBudgetStore.COMMITTED, 700)

        // A child order with its own cycle: its costs belong to the child, not to the root.
        val child = f.child(f.root)
        val childCycle = f.startCycle(child)
        val childRun = f.newRun()
        f.task(f.newPlan(childCycle.goalId), 1, childRun)
        f.ledger(DbCodingCostLedger.reservationKey(f.attempt(childRun, 1, "RESULT_RECEIVED", 1), 1), DbBudgetStore.COMMITTED, 500)
        f.ledger(ModelGateway.ledgerKey(plannerRequestId(childCycle.goalId, 1L, 1), 1), DbBudgetStore.COMMITTED, 300)

        assertEquals(105, f.reader.read(f.root.orderId, 1).committedMicroYen)
        assertEquals(700, f.reader.read(f.root.orderId, 2).committedMicroYen)
        assertEquals(800, f.reader.read(child.orderId, 1).committedMicroYen)
        assertTrue(f.orders.get(child.orderId)!!.state == OrderState.ACTIVE)
    }

    @Test
    fun `an attempt with no ledger row yet counts as zero and as active`() = withFixture { f ->
        val cycle = f.startCycle(f.root)
        val run = f.newRun()
        f.task(f.newPlan(cycle.goalId), 1, run)
        f.attempt(run, 1, "QUEUED", 0)

        assertEquals(CycleSpend(0, 0, 0, 0, hasUnconfirmedUnpriced = false, attemptsActive = 1), f.reader.read(f.root.orderId, 1))
    }

    @Test
    fun `an UNPRICED row of zero yen still sets hasUnconfirmedUnpriced`() = withFixture { f ->
        val cycle = f.startCycle(f.root)
        val run = f.newRun()
        f.task(f.newPlan(cycle.goalId), 1, run)
        f.ledger(DbCodingCostLedger.reservationKey(f.attempt(run, 1, "FAILED", 1), 1), DbBudgetStore.UNPRICED, 0)

        assertEquals(CycleSpend(0, 0, 0, 0, hasUnconfirmedUnpriced = true, attemptsActive = 0), f.reader.read(f.root.orderId, 1))
    }

    @Test
    fun `a cycle without any Run, or a cycle number that does not exist, reads as all zero`() = withFixture { f ->
        val cycle = f.startCycle(f.root)
        f.newPlan(cycle.goalId)

        val zero = CycleSpend(0, 0, 0, 0, hasUnconfirmedUnpriced = false, attemptsActive = 0)
        assertEquals(zero, f.reader.read(f.root.orderId, 1))
        assertEquals(zero, f.reader.read(f.root.orderId, 9))
    }
}
