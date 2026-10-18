package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.ClaimResult
import net.kigawa.kivra.db.CommitResult
import net.kigawa.kivra.db.GoalClaim
import net.kigawa.kivra.db.NewPlan
import net.kigawa.kivra.db.NewTask
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevGoal
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevPlanRepository
import net.kigawa.kivra.db.SelfDevPlanTask
import net.kigawa.kivra.db.SubmitResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.Transition
import net.kigawa.kivra.db.Ulid
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private const val OPERATOR = "op-a"
private const val REPO = "kigawa-net/kivra"
private const val MARKER = "MARKER"
private val TTL = java.time.Duration.ofSeconds(60)
private val START: Instant = Instant.parse("2026-10-18T10:00:00Z")

private fun config(
approval: AutopilotApproval = AutopilotApproval.REQUIRED,
maxPendingPrs: Int = 3,
maxFailures: Int = 3,
maxRunsPerDay: Int = 5,
share: Int = 50,
) = AutopilotConfig(approval, maxPendingPrs, maxFailures, maxRunsPerDay, share)

/** A clock the test sets. The daily cap is keyed on it, so a UTC day boundary is exact. */
private class FixedClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The autopilot dispatcher (ADR 0011 section 5). H2 by default. Runs are real rows created by [DevelopmentRunRepository.submitTask];
 * a Run's outcome is set by a direct update, which is what the runner would have written.
 */
class AutopilotDispatcherTest {

    private class Env(val db: TestDb, val cfg: AutopilotConfig = config(), val maxActive: Int = 10) {
        val clock = FixedClock(START)
        var budgetOpen = true
        val goals = SelfDevGoalRepository(db.database, clock)
        val plans = SelfDevPlanRepository(db.database, clock)
        val control = SelfDevAutopilotControlRepository(db.database, clock)

        /** A new dispatcher each time, so each tick is a fresh instance, as after a restart. */
        fun tick() = AutopilotDispatcher(db.database, db.runs, cfg, maxActive, { _ -> budgetOpen }, clock).tick()

        fun goal(goalId: String): SelfDevGoal = goals.get(goalId)!!
        fun task(planId: String, ordinal: Int): SelfDevPlanTask = plans.task(planId, ordinal)!!

        /**
         * A goal taken through claim, commit and the operator's first approval, as the planner and the operator would. [approve] lists the
         * ordinals approved by the operator. [queue] false leaves the goal in PLAN_PROPOSED, which the dispatcher must not touch.
         */
        fun plannedGoal(
            key: String,
            tasks: List<NewTask>,
            approve: List<Int>,
            createdBy: String = OPERATOR,
            queue: Boolean = true,
        ): Pair<String, String> {
            val created = assertIs<SubmitResult.Created>(goals.submit(createdBy, key, "$MARKER-goal-$key", "$MARKER-goal-body", REPO)).goal
            val claimed = assertIs<ClaimResult.Claimed>(goals.claim(created.goalId, TTL)).goal
            val committed = assertIs<CommitResult.Committed>(
                goals.commitPlan(
                    GoalClaim(created.goalId, claimed.version, claimed.claimToken!!),
                    NewPlan(summary = "summary", plannerModel = "claude-haiku-5-5", outputSha256 = "a".repeat(64), requestLedgerId = null),
                    tasks,
                ),
            )
            val planId = committed.plan.planId
            if (queue) movedGoal(goals.transition(created.goalId, goal(created.goalId).version, GoalState.TASKS_QUEUED, null))
            for (ordinal in approve) {
                val t = task(planId, ordinal)
                movedTask(plans.transitionTask(planId, ordinal, t.version, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
            }
            return created.goalId to planId
        }

        fun movedTask(result: Transition<SelfDevPlanTask>): SelfDevPlanTask =
            assertIs<Transition.Moved<SelfDevPlanTask>>(result).row

        fun movedGoal(result: Transition<SelfDevGoal>): SelfDevGoal =
            assertIs<Transition.Moved<SelfDevGoal>>(result).row
    }

    @Test
    fun `a disabled autopilot has no config, so no dispatcher is built`() {
        val off = AutopilotSettings.fromEnv(emptyMap(), SelfDevSettings.fromEnv(emptyMap()))
        assertFalse(off.enabled)
        assertNull(off.config)
    }

    @Test
    fun `a task is submitted only after its dependency's Run is COMPLETED, in dependency order`() = TestDb().use { db ->
        val env = Env(db)
        val (goalId, planId) = env.plannedGoal("dep", listOf(task("first"), task("second", dependsOn = listOf(1))), approve = listOf(1, 2))

        env.tick()
        val first = env.task(planId, 1)
        assertEquals(TaskState.SUBMITTED, first.state)
        assertEquals(TaskState.QUEUED, env.task(planId, 2).state)
        assertEquals(1, db.count("development_runs"))
        assertEquals(GoalState.RUNNING, env.goal(goalId).state)
        assertEquals(OPERATOR, db.runs.get(RunId(first.runId!!))!!.principal)

        env.tick()
        assertEquals(1, db.count("development_runs"))

        db.setRunState(first.runId!!, DevelopmentRunState.PULL_REQUEST)
        env.tick()
        assertEquals(TaskState.QUEUED, env.task(planId, 2).state, "an open pull request is not a merge")
        assertEquals(1, db.count("development_runs"))

        db.setRunState(first.runId!!, DevelopmentRunState.COMPLETED, "PR_MERGED")
        env.tick()
        assertEquals(TaskState.COMPLETED, env.task(planId, 1).state)
        assertEquals(TaskState.SUBMITTED, env.task(planId, 2).state)
        assertEquals(2, db.count("development_runs"))

        db.setRunState(env.task(planId, 2).runId!!, DevelopmentRunState.COMPLETED, "PR_MERGED")
        env.tick()
        assertEquals(TaskState.COMPLETED, env.task(planId, 2).state)
        assertEquals(GoalState.DONE, env.goal(goalId).state)
    }

    @Test
    fun `a repeated tick never submits a task twice`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("twice", listOf(task("only")), approve = listOf(1))
        repeat(3) { env.tick() }
        assertEquals(1, db.count("development_runs"))
        assertEquals(1, env.task(planId, 1).attempts)
    }

    @Test
    fun `a submission interrupted after SUBMITTING resumes with the same key and creates no second Run`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("crash-after-run", listOf(task("only")), approve = listOf(1))
        val queued = env.task(planId, 1)
        val submitting = env.movedTask(env.plans.transitionTask(planId, 1, queued.version, TaskState.SUBMITTING, null))
        // The Run was created, and the process stopped before it wrote SUBMITTED.
        val run = assertIs<TaskSubmitResult.Created>(
            db.runs.submitTask(OPERATOR, submitting.idempotencyKey, submitting.title, selfDev = true, maxActiveSelfDev = 10, body = submitting.body),
        ).run

        env.tick()

        val after = env.task(planId, 1)
        assertEquals(TaskState.SUBMITTED, after.state)
        assertEquals(run.runId.value, after.runId)
        assertEquals(1, db.count("development_runs"))
    }

    @Test
    fun `a task left in SUBMITTING with no Run gets its one Run on the next tick`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("crash-before-run", listOf(task("only")), approve = listOf(1))
        val queued = env.task(planId, 1)
        env.movedTask(env.plans.transitionTask(planId, 1, queued.version, TaskState.SUBMITTING, null))

        env.tick()
        env.tick()

        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
        assertEquals(1, db.count("development_runs"))
    }

    @Test
    fun `two dispatchers ticking at once submit a task once and take one daily slot`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("race", listOf(task("only")), approve = listOf(1))
        val pool = Executors.newFixedThreadPool(2)
        try {
            (1..2).map { pool.submit(Callable { env.tick() }) }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
        assertEquals(1, db.count("development_runs"))
        assertEquals(1, env.control.read().runsToday)
    }

    @Test
    fun `a paused autopilot submits nothing and leaves the goal as it was`() = TestDb().use { db ->
        val env = Env(db)
        val (goalId, planId) = env.plannedGoal("paused", listOf(task("only")), approve = listOf(1))
        env.control.setPaused(true, "op-pauser")

        env.tick()
        assertEquals(GoalState.TASKS_QUEUED, env.goal(goalId).state)
        assertEquals(TaskState.QUEUED, env.task(planId, 1).state)
        assertEquals(0, db.count("development_runs"))

        env.control.setPaused(false, "op-pauser")
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
    }

    @Test
    fun `a goal that the operator has not queued is not dispatched`() = TestDb().use { db ->
        val env = Env(db)
        val (goalId, planId) = env.plannedGoal("proposed", listOf(task("only")), approve = listOf(1), queue = false)
        env.tick()
        assertEquals(GoalState.PLAN_PROPOSED, env.goal(goalId).state)
        assertEquals(TaskState.QUEUED, env.task(planId, 1).state)
        assertEquals(0, db.count("development_runs"))
    }

    @Test
    fun `the pending pull request limit counts manual runs, and the goal waits until a slot frees`() = TestDb().use { db ->
        val env = Env(db, cfg = config(maxPendingPrs = 1))
        val manual = db.insertRunRow("selfdev:manual-${Ulid.generate()}", DevelopmentRunState.PULL_REQUEST)
        val (goalId, planId) = env.plannedGoal("pr-limit", listOf(task("only")), approve = listOf(1))

        env.tick()
        assertEquals(GoalState.BLOCKED, env.goal(goalId).state)
        assertEquals(AutopilotReason.PENDING_PR_LIMIT, env.goal(goalId).stateReason)
        assertEquals(TaskState.QUEUED, env.task(planId, 1).state)
        assertEquals(1, db.count("development_runs"))

        db.setRunState(manual, DevelopmentRunState.CANCELLED, "PR_ABANDONED")
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
        assertEquals(GoalState.RUNNING, env.goal(goalId).state)
    }

    @Test
    fun `three consecutive failures open the breaker across goals, and only a manual resume lets a goal go on`() = TestDb().use { db ->
        val env = Env(db)
        val failing = (1..3).map { env.plannedGoal("breaker-$it", listOf(task("t$it")), approve = listOf(1)) }
        env.tick()
        failing.forEach { (_, planId) -> db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.FAILED, "RUN_FAILED") }
        env.tick()
        assertEquals(3, env.control.read().consecutiveFailures)
        assertTrue(env.control.read().breakerOpen)

        val (fourth, fourthPlan) = env.plannedGoal("breaker-4", listOf(task("t4")), approve = listOf(1))
        env.tick()
        assertEquals(GoalState.BLOCKED, env.goal(fourth).state)
        assertEquals(AutopilotReason.CIRCUIT_OPEN, env.goal(fourth).stateReason)
        assertEquals(TaskState.QUEUED, env.task(fourthPlan, 1).state)
        assertEquals(3, db.count("development_runs"))

        // The operator resets the breaker and resumes the goal. The UI is a later PR; this is the repository path.
        env.control.resetBreaker()
        env.movedGoal(env.goals.transition(fourth, env.goal(fourth).version, GoalState.RUNNING, AutopilotReason.OPERATOR_RESUMED))
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(fourthPlan, 1).state)
    }

    @Test
    fun `a manual resume is needed even when the cause is gone, so a CIRCUIT_OPEN goal does not restart by itself`() = TestDb().use { db ->
        val env = Env(db)
        val failing = (1..3).map { env.plannedGoal("auto-$it", listOf(task("t$it")), approve = listOf(1)) }
        env.tick()
        failing.forEach { (_, planId) -> db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.FAILED, "RUN_FAILED") }
        env.tick()
        val (goalId, planId) = env.plannedGoal("blocked", listOf(task("t")), approve = listOf(1))
        env.tick()
        env.control.resetBreaker()
        env.tick()
        assertEquals(GoalState.BLOCKED, env.goal(goalId).state)
        assertEquals(TaskState.QUEUED, env.task(planId, 1).state)
    }

    @Test
    fun `a success resets the failure count`() = TestDb().use { db ->
        val env = Env(db)
        val (_, failPlan) = env.plannedGoal("reset-fail", listOf(task("f")), approve = listOf(1))
        val (_, okPlan) = env.plannedGoal("reset-ok", listOf(task("o")), approve = listOf(1))
        env.tick()
        db.setRunState(env.task(failPlan, 1).runId!!, DevelopmentRunState.FAILED, "RUN_FAILED")
        env.tick()
        assertEquals(1, env.control.read().consecutiveFailures)
        db.setRunState(env.task(okPlan, 1).runId!!, DevelopmentRunState.COMPLETED, "PR_MERGED")
        env.tick()
        assertEquals(TaskState.FAILED, env.task(failPlan, 1).state)
        assertEquals(TaskState.COMPLETED, env.task(okPlan, 1).state)
        assertEquals(0, env.control.read().consecutiveFailures)
        assertFalse(env.control.read().breakerOpen)
    }

    @Test
    fun `the daily Run cap stops at the limit and starts again on the next UTC day`() = TestDb().use { db ->
        val env = Env(db, cfg = config(maxRunsPerDay = 1))
        val goals = listOf(env.plannedGoal("day-a", listOf(task("a")), approve = listOf(1)), env.plannedGoal("day-b", listOf(task("b")), approve = listOf(1)))

        env.tick()
        assertEquals(1, db.count("development_runs"))
        val (waiting, waitingPlan) = goals.single { env.goal(it.first).state == GoalState.BLOCKED }
        assertEquals(AutopilotReason.DAILY_RUN_CAP, env.goal(waiting).stateReason)

        env.clock.now = Instant.parse("2026-10-19T00:01:00Z")
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(waitingPlan, 1).state)
        assertEquals(GoalState.RUNNING, env.goal(waiting).state)
        assertEquals(1, env.control.read().runsToday)
    }

    @Test
    fun `the monthly budget share holds the goals, and the comparison is the remaining budget against the share`() = TestDb().use { db ->
        val env = Env(db)
        env.budgetOpen = false
        val (goalId, planId) = env.plannedGoal("budget", listOf(task("only")), approve = listOf(1))
        env.tick()
        assertEquals(GoalState.BLOCKED, env.goal(goalId).state)
        assertEquals(AutopilotReason.BUDGET_HOLD, env.goal(goalId).stateReason)
        assertEquals(0, db.count("development_runs"))

        env.budgetOpen = true
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
    }

    @Test
    fun `the budget check compares the remaining month, with reserved money counted, against the share`() {
        val limit = BigDecimal("10000")
        assertTrue(autopilotBudgetAllows(BigDecimal("5000"), BigDecimal.ZERO, limit, 50))
        assertFalse(autopilotBudgetAllows(BigDecimal("5000.01"), BigDecimal.ZERO, limit, 50))
        assertFalse(autopilotBudgetAllows(BigDecimal("4000"), BigDecimal("1000.5"), limit, 50))
        assertTrue(autopilotBudgetAllows(BigDecimal.ZERO, BigDecimal.ZERO, limit, 100))
        assertFalse(autopilotBudgetAllows(BigDecimal("0.01"), BigDecimal.ZERO, limit, 100))
    }

    @Test
    fun `the active-run limit waits without a state change and without counting a failure`() = TestDb().use { db ->
        val env = Env(db, maxActive = 1)
        val manual = assertIs<TaskSubmitResult.Created>(
            db.runs.submitTask("alice", "manual-1", "manual", selfDev = true, maxActiveSelfDev = 10),
        ).run
        val (goalId, planId) = env.plannedGoal("active", listOf(task("only")), approve = listOf(1))

        env.tick()
        assertEquals(TaskState.QUEUED, env.task(planId, 1).state)
        assertEquals(GoalState.TASKS_QUEUED, env.goal(goalId).state)
        assertEquals(0, env.control.read().consecutiveFailures)
        assertEquals(1, db.count("development_runs"))

        db.setRunState(manual.runId.value, DevelopmentRunState.COMPLETED, "PR_MERGED")
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
    }

    @Test
    fun `a failed Run fails its task, skips the rest of the plan, and is counted once`() = TestDb().use { db ->
        val env = Env(db)
        val (goalId, planId) = env.plannedGoal(
            "fail",
            listOf(task("a"), task("b", dependsOn = listOf(1)), task("c")),
            approve = listOf(1, 2, 3),
        )
        env.tick()
        assertEquals(TaskState.SUBMITTED, env.task(planId, 1).state)
        db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.FAILED, "DEVELOPER_TIMEOUT")

        env.tick()
        assertEquals(TaskState.FAILED, env.task(planId, 1).state)
        assertEquals(AutopilotReason.RUN_FAILED, env.task(planId, 1).stateReason)
        assertEquals(TaskState.SKIPPED, env.task(planId, 2).state)
        assertEquals(TaskState.SKIPPED, env.task(planId, 3).state)
        assertEquals(1, env.control.read().consecutiveFailures)

        env.tick()
        assertEquals(1, env.control.read().consecutiveFailures)
        assertEquals(1, db.count("development_runs"))
        assertEquals(GoalState.RUNNING, env.goal(goalId).state)
    }

    @Test
    fun `a Run that failed on policy fails its task with the policy reason`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("policy", listOf(task("only")), approve = listOf(1))
        env.tick()
        db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.FAILED, "POLICY_REJECTED")
        env.tick()
        assertEquals(TaskState.FAILED, env.task(planId, 1).state)
        assertEquals(AutopilotReason.POLICY_REJECTED, env.task(planId, 1).stateReason)
    }

    @Test
    fun `a closed pull request fails its task with PR_ABANDONED and is counted`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("abandoned", listOf(task("only")), approve = listOf(1))
        env.tick()
        db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.CANCELLED, "PR_ABANDONED")
        env.tick()
        assertEquals(TaskState.FAILED, env.task(planId, 1).state)
        assertEquals(AutopilotReason.PR_ABANDONED, env.task(planId, 1).stateReason)
        assertEquals(1, env.control.read().consecutiveFailures)
    }

    @Test
    fun `a Run released by the operator cancels its task and is not counted`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("released", listOf(task("a"), task("c")), approve = listOf(1, 2))
        env.tick()
        db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.CANCELLED, "OPERATOR_RELEASED")
        env.tick()
        assertEquals(TaskState.CANCELLED, env.task(planId, 1).state)
        assertEquals(TaskState.SUBMITTED, env.task(planId, 2).state, "a release does not skip the rest of the plan")
        assertEquals(0, env.control.read().consecutiveFailures)
    }

    @Test
    fun `an UNKNOWN Run fails its task once, and the failure is not counted again`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("unknown", listOf(task("only")), approve = listOf(1))
        env.tick()
        db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.UNKNOWN, null)
        env.tick()
        env.tick()
        assertEquals(TaskState.FAILED, env.task(planId, 1).state)
        assertEquals(1, env.control.read().consecutiveFailures)
    }

    @Test
    fun `a denylisted task waits for the operator under auto, and a denylisted task the operator approved runs as the operator`() = TestDb().use { db ->
        val auto = Env(db, cfg = config(approval = AutopilotApproval.AUTO))
        val (_, autoPlan) = auto.plannedGoal("deny-auto", listOf(task("deny", paths = listOf("docs/x.md"), requiresApproval = true)), approve = emptyList())
        auto.tick()
        assertEquals(TaskState.PENDING_APPROVAL, auto.task(autoPlan, 1).state)
        assertEquals(0, db.count("development_runs"))

        val required = Env(db)
        val (_, requiredPlan) = required.plannedGoal("deny-approved", listOf(task("deny", requiresApproval = true)), approve = listOf(1))
        required.tick()
        val submitted = required.task(requiredPlan, 1)
        assertEquals(TaskState.SUBMITTED, submitted.state)
        assertEquals(OPERATOR, db.runs.get(RunId(submitted.runId!!))!!.principal)
    }

    @Test
    fun `under required nothing is approved automatically`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("required", listOf(task("low", paths = listOf("docs/a.md"))), approve = emptyList())
        env.tick()
        assertEquals(TaskState.PENDING_APPROVAL, env.task(planId, 1).state)
        assertEquals(0, db.count("development_runs"))
    }

    @Test
    fun `under auto, a low-risk task is approved and its Run belongs to the goal creator, and the rest wait for the operator`() = TestDb().use { db ->
        val env = Env(db, cfg = config(approval = AutopilotApproval.AUTO))
        val (goalId, planId) = env.plannedGoal(
            "auto",
            listOf(
                task("low", paths = listOf("docs/a.md", "README.md"), size = "S"),
                task("medium", paths = listOf("docs/b.md"), size = "M"),
                task("code", paths = listOf("kivra-core/src/main/kotlin/A.kt"), size = "S"),
                task("test", paths = listOf("kivra-core/src/test/kotlin/ATest.kt"), size = "S"),
            ),
            approve = emptyList(),
        )
        env.tick()
        val low = env.task(planId, 1)
        assertEquals(TaskState.SUBMITTED, low.state)
        assertEquals(OPERATOR, db.runs.get(RunId(low.runId!!))!!.principal)
        assertEquals(TaskState.PENDING_APPROVAL, env.task(planId, 2).state)
        assertEquals(TaskState.PENDING_APPROVAL, env.task(planId, 3).state)
        // The test-source task is low risk too, but the one-run-per-goal rule keeps it behind the first.
        assertEquals(GoalState.RUNNING, env.goal(goalId).state)
    }

    @Test
    fun `a dependent task of a failed one is skipped, not left queued`() = TestDb().use { db ->
        val env = Env(db)
        val (_, planId) = env.plannedGoal("skip", listOf(task("a"), task("b", dependsOn = listOf(1))), approve = listOf(1, 2))
        env.tick()
        db.setRunState(env.task(planId, 1).runId!!, DevelopmentRunState.FAILED, "RUN_FAILED")
        env.tick()
        assertEquals(TaskState.SKIPPED, env.task(planId, 2).state)
        assertIs<Transition.Refused>(env.plans.transitionTask(planId, 2, env.task(planId, 2).version, TaskState.QUEUED, AutopilotReason.OPERATOR_REQUEUED))
    }

    @Test
    fun `the logs carry ids and fixed codes only, never goal, task, or path text`() = TestDb().use { db ->
        val env = Env(db, cfg = config(maxRunsPerDay = 1))
        val logs = captureLogs {
            val (_, firstPlan) = env.plannedGoal(
                "log-1",
                listOf(task("$MARKER-task", paths = listOf("docs/$MARKER-path.md"), body = "$MARKER-task-body")),
                approve = listOf(1),
            )
            env.tick()
            db.setRunState(env.task(firstPlan, 1).runId!!, DevelopmentRunState.FAILED, "POLICY_REJECTED")
            env.plannedGoal("log-2", listOf(task("$MARKER-second")), approve = listOf(1))
            env.tick()
        }
        assertTrue(logs.any { it.contains("submitted goalId=") }, "the submission is logged")
        assertTrue(logs.any { it.contains("code=DAILY_RUN_CAP") }, "the guard is logged with its code")
        logs.forEach { assertFalse(it.contains(MARKER), "a log line carries no goal, task, or path text: $it") }
    }

    @Test
    fun `releasing a slot gives back one run of the current day and never goes below zero`() = TestDb().use { db ->
        val control = SelfDevAutopilotControlRepository(db.database, FixedClock(START))
        assertTrue(control.tryReserveRun(dailyLimit = 2))
        control.releaseRunSlot()
        assertEquals(0, control.read().runsToday)
        control.releaseRunSlot()
        assertEquals(0, control.read().runsToday)
    }

    @Test
    fun `the pending pull request count is self-development only and reads by state`() = TestDb().use { db ->
        db.insertRunRow("selfdev:pr-1-${Ulid.generate()}", DevelopmentRunState.PULL_REQUEST)
        db.insertRunRow("selfdev:pr-2-${Ulid.generate()}", DevelopmentRunState.PULL_REQUEST)
        db.insertRunRow("selfdev:done-${Ulid.generate()}", DevelopmentRunState.COMPLETED)
        db.insertRunRow("api:pr-${Ulid.generate()}", DevelopmentRunState.PULL_REQUEST)
        assertEquals(2L, db.runs.countSelfDevInState(DevelopmentRunState.PULL_REQUEST))
    }

    private fun task(
        title: String,
        paths: List<String> = listOf("docs/a.md"),
        size: String = "S",
        dependsOn: List<Int> = emptyList(),
        requiresApproval: Boolean = false,
        body: String? = "$MARKER-task-body",
    ) = NewTask(title, body, paths, size, dependsOn, requiresApproval)

    private fun TestDb.setRunState(runId: String, state: DevelopmentRunState, reason: String? = null) = transaction(database) {
        execUpdate("UPDATE development_runs SET state = ?, state_reason = ?, version = version + 1 WHERE run_id = ?", state.name, reason, runId)
    }

    /** A run row in [state] for [taskId], written as the runner would have written it (the Run is not started). */
    private fun TestDb.insertRunRow(taskId: String, state: DevelopmentRunState): String {
        val runId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO development_runs
                    (run_id, task_id, state, completion_mode, attempt, plan_revision, lease_generation, cancel_requested, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, 0, 0, FALSE, 0, NOW(6), NOW(6))
                """.trimIndent(),
                runId, taskId, state.name, RunCompletionMode.PR_ONLY.name,
            )
        }
        return runId
    }

    /** Every line the application logs during [block], at TRACE. Exposed's own SQL logger is outside the application's package. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val appLogger = LoggerFactory.getLogger("net.kigawa.kivra") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); appLogger.addAppender(it) }
        val oldLevel = appLogger.level
        appLogger.level = Level.TRACE
        try {
            block()
        } finally {
            appLogger.detachAppender(appender)
            appLogger.level = oldLevel
        }
        return appender.list.map { it.formattedMessage }
    }
}
