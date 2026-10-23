package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.core.autopilot.StopRequestReason
import org.jetbrains.exposed.sql.transactions.transaction

private const val OPERATOR = "1:i:kigawa"
private const val TARGET = "kigawa-net/kivra"
private val START: Instant = Instant.parse("2026-10-23T10:00:00Z")
private val LIMITS = ChildLimits(maxDepth = 2, maxChildren = 5, maxTree = 15)

private class BudgetClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The facts and the automatic stop of CHILD_BUDGET_EXHAUSTED (ADR 0015 section 4.4 (2), addendum 2026-10-23 K.1 and L), on H2 (MariaDB with
 * `KIVRA_TEST_DB=mariadb`). Each test has its own database, closed when the test ends.
 */
class SelfDevOrderBudgetExhaustedTest {

    private class Fixture(val db: TestDb) {
        val clock = BudgetClock(START)
        val repo = SelfDevOrderRepository(db.database, clock)
        val root: SelfDevOrder = (repo.createRoot(OPERATOR, "root title", "root policy", TARGET) as CreateRootResult.Created).order

        fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

        fun count(sql: String): Int = transaction(db.database) { execQuery(sql) { it.getInt(1) }.single() }

        fun now(orderId: String): SelfDevOrder = assertNotNull(repo.get(orderId))

        fun child(parent: SelfDevOrder, budget: Long = 1_000): SelfDevOrder = (
            repo.createChild(parent.orderId, "child title", "child policy", budget, "key-${Ulid.generate()}", OPERATOR, LIMITS) as
                ChildCreateResult.Created
            ).order

        fun startCycle(order: SelfDevOrder): CycleStarted =
            assertIs<OrderResult.Moved<CycleStarted>>(repo.startCycle(order.orderId, now(order.orderId).version, OPERATOR)).value

        fun newRun(): String = db.runs.create("task-${Ulid.generate()}", "test", RunCompletionMode.PR_ONLY).runId.value

        /** A plan of [goalId] with one task submitted as [runId]. */
        fun task(goalId: String, runId: String) {
            val planId = Ulid.generate()
            exec(
                "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, summary, planner_model, output_sha256, request_ledger_id, created_at)" +
                    " VALUES (?, ?, 1, 'PROPOSED', NULL, 'claude-haiku-5-5', ?, NULL, ?)",
                planId, goalId, "0".repeat(64), clock.now,
            )
            exec(
                "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval," +
                    " state, state_reason, idempotency_key, run_id, attempts, version, created_at, updated_at)" +
                    " VALUES (?, 1, 'task', NULL, '[]', 'S', '[]', FALSE, 'SUBMITTED', NULL, ?, ?, 0, 0, ?, ?)",
                planId, "ap:$planId:1", runId, clock.now, clock.now,
            )
        }

        /** A CLI attempt of [runId] in [state]. Its active_flag is generated from the state. */
        fun attempt(runId: String, state: String): String {
            val attemptId = Ulid.generate()
            exec(
                "INSERT INTO coding_attempts (attempt_id, run_id, attempt_no, state, claimed_by, lease_generation, lease_expires_at," +
                    " queued_expires_at, base_sha, result_digest, exit_status, error_code, session_id, version, created_at, updated_at)" +
                    " VALUES (?, ?, 1, ?, NULL, 1, NULL, TIMESTAMPADD(SECOND, 600, NOW(6)), ?, NULL, NULL, NULL, NULL, 0, ?, ?)",
                attemptId, runId, state, "a".repeat(40), clock.now, clock.now,
            )
            return attemptId
        }

        fun setAttemptState(attemptId: String, state: String) = exec("UPDATE coding_attempts SET state = ? WHERE attempt_id = ?", state, attemptId)

        fun setRunState(runId: String, state: String) = exec("UPDATE development_runs SET state = ? WHERE run_id = ?", state, runId)

        fun setSpent(orderId: String, spent: Long) = exec("UPDATE selfdev_orders SET spent_jpy = ? WHERE order_id = ?", spent, orderId)

        fun setState(orderId: String, state: OrderState) = exec("UPDATE selfdev_orders SET state = ? WHERE order_id = ?", state.name, orderId)

        fun setFailures(orderId: String, n: Int) = exec("UPDATE selfdev_orders SET consecutive_failed_cycles = ? WHERE order_id = ?", n, orderId)

        /** Ends the goal of [cycle] (DONE) and counts it once, as the stage 3g tick does. The spend row is then final when nothing is open. */
        fun finish(order: SelfDevOrder, cycle: CycleStarted) {
            exec("UPDATE selfdev_goals SET state = ?, state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", GoalState.DONE.name, cycle.goalId)
            repo.countCycleSpend(SelfDevOrderRepository.SpendCycle(cycle.goalId, order.orderId, cycle.cycleNo))
        }

        /** A child with one cycle that is final and whose run is terminal, and whose spent is [budget]: every condition holds. */
        fun exhaustedChild(parent: SelfDevOrder, budget: Long = 1_000): SelfDevOrder {
            val c = child(parent, budget)
            val cycle = startCycle(c)
            val run = newRun()
            task(cycle.goalId, run)
            setRunState(run, "COMPLETED")
            finish(c, cycle)
            setSpent(c.orderId, budget)
            return c
        }

        fun facts(orderId: String): BudgetExhaustedFacts = assertNotNull(repo.budgetExhaustedFacts(orderId))

        fun stopped(orderId: String, reason: StopRequestReason = StopRequestReason.CHILD_BUDGET_EXHAUSTED): SelfDevOrder =
            assertIs<AutoStopResult.Stopped>(repo.autoStopChild(orderId, reason)).order
    }

    private fun withFixture(block: (Fixture) -> Unit) = TestDb().use { db -> block(Fixture(db)) }

    @Test
    fun `every condition holds - the child is stopped by its parent with CHILD_BUDGET_EXHAUSTED, audited, and its part stops with it`() = withFixture { f ->
        val child = f.exhaustedChild(f.root)
        val grand = f.child(child, budget = 500)
        assertEquals(BudgetExhaustedFacts(1_000, 1_000, unfinishedCycles = 0, nonTerminalRuns = 0), f.facts(child.orderId))
        assertTrue(f.facts(child.orderId).exhausted)

        val stopped = f.stopped(child.orderId)
        assertEquals(OrderState.STOPPED, stopped.state)
        assertEquals(OrderState.STOPPED, f.now(grand.orderId).state)
        assertEquals(OrderState.ACTIVE, f.now(f.root.orderId).state)
        assertEquals(
            1,
            f.count(
                "SELECT COUNT(*) FROM selfdev_order_events WHERE order_id = '${child.orderId}' AND kind = 'STOPPED' AND actor_kind = 'SYSTEM' " +
                    "AND reason_code = 'CHILD_BUDGET_EXHAUSTED' AND parent_order_id = '${f.root.orderId}' AND depth = 1",
            ),
        )
        assertEquals(
            1,
            f.count(
                "SELECT COUNT(*) FROM selfdev_order_stop_requests WHERE order_id = '${child.orderId}' AND decider_kind = 'ORDER' " +
                    "AND decider_order_id = '${f.root.orderId}' AND target_is_root = 0 AND reason_code = 'CHILD_BUDGET_EXHAUSTED'",
            ),
        )
    }

    @Test
    fun `the cost below the budget holds the decision, and the budget reached exactly (greater or equal) decides`() = withFixture { f ->
        val child = f.exhaustedChild(f.root)
        f.setSpent(child.orderId, 999)
        assertFalse(f.facts(child.orderId).exhausted)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED), f.repo.autoStopChild(child.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(OrderState.ACTIVE, f.now(child.orderId).state)

        f.setSpent(child.orderId, 1_000)
        assertTrue(f.facts(child.orderId).exhausted)
    }

    @Test
    fun `a cycle with no spend row, or with a row that is not final, holds the decision`() = withFixture { f ->
        // The goal is DONE but the cycle has no spend row yet (its first count has not run).
        val noRow = f.child(f.root)
        val cycle = f.startCycle(noRow)
        f.exec("UPDATE selfdev_goals SET state = ?, state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", GoalState.DONE.name, cycle.goalId)
        f.setSpent(noRow.orderId, 1_000)
        assertEquals(1, f.facts(noRow.orderId).unfinishedCycles)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED), f.repo.autoStopChild(noRow.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        f.repo.countCycleSpend(SelfDevOrderRepository.SpendCycle(cycle.goalId, noRow.orderId, cycle.cycleNo))
        assertTrue(f.facts(noRow.orderId).exhausted)

        // The row exists but the goal is still running, so the row is not final.
        val running = f.child(f.root)
        val runningCycle = f.startCycle(running)
        f.repo.countCycleSpend(SelfDevOrderRepository.SpendCycle(runningCycle.goalId, running.orderId, runningCycle.cycleNo))
        f.setSpent(running.orderId, 1_000)
        assertEquals(1, f.facts(running.orderId).unfinishedCycles)
        assertFalse(f.facts(running.orderId).exhausted)
    }

    @Test
    fun `an unfinished cycle of a descendant holds the decision of its ancestor`() = withFixture { f ->
        // The child's own cycle is final first; then a grandchild starts a cycle that stays open. The child's spent is set last.
        val child = f.child(f.root)
        f.finish(child, f.startCycle(child))
        val grand = f.child(child, budget = 1_000)
        val grandCycle = f.startCycle(grand)
        f.setSpent(child.orderId, 1_000)
        assertEquals(1, f.facts(child.orderId).unfinishedCycles)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED), f.repo.autoStopChild(child.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(OrderState.ACTIVE, f.now(grand.orderId).state)

        f.finish(grand, grandCycle)
        assertTrue(f.facts(child.orderId).exhausted)
    }

    @Test
    fun `a Run that is not terminal holds the decision, and so does an active CLI attempt of a terminal Run`() = withFixture { f ->
        val child = f.child(f.root)
        val cycle = f.startCycle(child)
        val run = f.newRun()
        f.task(cycle.goalId, run)
        f.finish(child, cycle)
        f.setSpent(child.orderId, 1_000)

        f.setRunState(run, "RUNNING")
        assertEquals(1, f.facts(child.orderId).nonTerminalRuns)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED), f.repo.autoStopChild(child.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))

        // The Run is COMPLETED, but one of its attempts is still RUNNING (active_flag = 1).
        f.setRunState(run, "COMPLETED")
        val attempt = f.attempt(run, "RUNNING")
        assertEquals(1, f.facts(child.orderId).nonTerminalRuns)
        assertFalse(f.facts(child.orderId).exhausted)

        f.setAttemptState(attempt, "RESULT_RECEIVED")
        assertEquals(0, f.facts(child.orderId).nonTerminalRuns)
        assertTrue(f.facts(child.orderId).exhausted)
    }

    @Test
    fun `without the spend switch no spend row exists, so the reason never holds even when the spent is at the budget`() = withFixture { f ->
        // ADR 0015 addendum K.2 and L: with KIVRA_SELFDEV_ORDER_SPEND_ENABLED off nothing counts a cycle, so its row never becomes final.
        val child = f.child(f.root)
        val cycle = f.startCycle(child)
        f.exec("UPDATE selfdev_goals SET state = ?, state_reason = 'ALL_TASKS_COMPLETED' WHERE goal_id = ?", GoalState.DONE.name, cycle.goalId)
        f.setSpent(child.orderId, 1_000)

        assertEquals(0, f.count("SELECT COUNT(*) FROM selfdev_order_cycle_spend"))
        assertFalse(f.facts(child.orderId).exhausted)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED), f.repo.autoStopChild(child.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(OrderState.ACTIVE, f.now(child.orderId).state)
    }

    @Test
    fun `an order with no cycle at all and a spent below its budget is not decided, and the root is never decided`() = withFixture { f ->
        val idle = f.child(f.root)
        assertFalse(f.facts(idle.orderId).exhausted)
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.BUDGET_NOT_EXHAUSTED), f.repo.autoStopChild(idle.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(AutoStopResult.NotDecided(AutoStopSkip.IS_ROOT), f.repo.autoStopChild(f.root.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(null, f.repo.budgetExhaustedFacts("01ZZZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun `the reason is only for the automatic decision, and no request can carry it`() = withFixture { f ->
        val child = f.exhaustedChild(f.root)
        assertFalse(StopRequestReason.CHILD_BUDGET_EXHAUSTED in StopRequestReason.REQUESTABLE)
        assertFailsWith<IllegalArgumentException> {
            f.repo.requestStop(child.orderId, f.now(child.orderId).version, OPERATOR, OrderActor.HUMAN, StopRequestReason.CHILD_BUDGET_EXHAUSTED)
        }
        assertFailsWith<IllegalArgumentException> { f.repo.autoStopChild(child.orderId, StopRequestReason.OPERATOR_REQUEST) }
        assertEquals(OrderState.ACTIVE, f.now(child.orderId).state)
    }

    @Test
    fun `the automatic stop keeps the frequency limit - one a day per parent across both reasons, and the next day resets it`() = withFixture { f ->
        val a = f.exhaustedChild(f.root)
        val b = f.exhaustedChild(f.root)
        val c = f.child(f.root)
        f.setFailures(c.orderId, 2)

        assertEquals(setOf(a.orderId, b.orderId), f.repo.budgetExhaustedCandidates(10).toSet())
        assertEquals(listOf(c.orderId), f.repo.autoStopCandidates(10))

        f.stopped(a.orderId)
        assertEquals(AutoStopResult.RateLimited(f.root.orderId, recorded = true), f.repo.autoStopChild(b.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(AutoStopResult.RateLimited(f.root.orderId, recorded = false), f.repo.autoStopChild(b.orderId, StopRequestReason.CHILD_BUDGET_EXHAUSTED))
        assertEquals(
            AutoStopResult.RateLimited(f.root.orderId, recorded = true),
            f.repo.autoStopChild(c.orderId, StopRequestReason.CHILD_CYCLE_FAILURES),
        )
        assertEquals(OrderState.ACTIVE, f.now(b.orderId).state)
        assertEquals(setOf(b.orderId), f.repo.budgetExhaustedCandidates(10).toSet())

        f.clock.now = START.plus(Duration.ofDays(1))
        f.stopped(b.orderId)
        assertEquals(OrderState.STOPPED, f.now(b.orderId).state)
    }
}
