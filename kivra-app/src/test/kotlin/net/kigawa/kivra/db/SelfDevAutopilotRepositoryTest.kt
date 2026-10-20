package net.kigawa.kivra.db

import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.PlanState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.selfdev.PromotionCounts
import java.sql.SQLException
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(60)
private val SHA = "a".repeat(64)

/**
 * Repositories of the autopilot (V18, ADR 0011 sections 2 and 5). Runs on H2 by default, and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 * Every rule here is enforced by a conditional UPDATE or a unique / check constraint, so a test that passes on H2 proves the SQL,
 * not only the Kotlin code around it.
 */
class SelfDevAutopilotRepositoryTest {

    @Test
    fun `a goal is stored once per operator and key, and a repeat with other content is refused`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val first = assertIs<SubmitResult.Created>(goals.submit("op-a", "k1", "Title", "Body", "kigawa-net/kivra")).goal
        assertEquals(GoalState.GOAL_SUBMITTED, first.state)
        assertEquals(0L, first.version)

        val replay = assertIs<SubmitResult.Replayed>(goals.submit("op-a", "k1", "Title", "Body", "kigawa-net/kivra")).goal
        assertEquals(first.goalId, replay.goalId)
        assertIs<SubmitResult.KeyConflict>(goals.submit("op-a", "k1", "Other", "Body", "kigawa-net/kivra"))
        assertIs<SubmitResult.KeyConflict>(goals.submit("op-a", "k1", "Title", null, "kigawa-net/kivra"))
        // The key is scoped to the operator: another operator's same key is a new goal.
        assertIs<SubmitResult.Created>(goals.submit("op-b", "k1", "Title", "Body", "kigawa-net/kivra"))
        assertEquals(2, db.count("selfdev_goals"))
    }

    @Test
    fun `an empty body is stored as no body, and the length limits are enforced in the application`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val goal = assertIs<SubmitResult.Created>(goals.submit("op", "k-empty", "t", "", "r/r")).goal
        assertNull(goal.body)
        assertIs<SubmitResult.Created>(goals.submit("op", "k-max", "t".repeat(200), "b".repeat(8000), "r/r"))
        assertFailsWith<IllegalArgumentException> { goals.submit("op", "k-long-title", "t".repeat(201), null, "r/r") }
        assertFailsWith<IllegalArgumentException> { goals.submit("op", "k-long-body", "t", "b".repeat(8001), "r/r") }
        // The limit counts characters, not UTF-16 units: 8000 Japanese characters are allowed.
        assertIs<SubmitResult.Created>(goals.submit("op", "k-ja-body", "t", "あ".repeat(8000), "r/r"))
    }

    @Test
    fun `of several concurrent claims exactly one wins`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val goalId = goalOf(goals, "k-race").goalId
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<ClaimResult> { goals.claim(goalId, TTL) } }.map { it.get() }
            assertEquals(1, results.count { it is ClaimResult.Claimed }, "exactly one claim wins: $results")
            assertEquals(7, results.count { it == ClaimResult.Refused(ClaimRefusal.NOT_CLAIMABLE) })
        } finally {
            pool.shutdown()
        }
        assertEquals(GoalState.PLANNING, goals.get(goalId)!!.state)
    }

    @Test
    fun `a claim is refused while the autopilot is paused for everything`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val control = SelfDevAutopilotControlRepository(db.database)
        val goalId = goalOf(goals, "k-paused").goalId
        control.setPaused(true, "op")
        assertEquals(ClaimResult.Refused(ClaimRefusal.PAUSED), goals.claim(goalId, TTL))
        control.setPaused(false, "op")
        assertIs<ClaimResult.Claimed>(goals.claim(goalId, TTL))
    }

    @Test
    fun `the claim fence refuses a token that is not the current one, even at the current version`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val goal = claimed(goals, "k-token")
        val wrongToken = GoalClaim(goal.goalId, goal.version, "not-the-token")
        assertEquals(CommitResult.StaleClaim, goals.commitPlan(wrongToken, newPlan(), newTasks()))
        assertEquals(0, db.count("selfdev_plans"))
        assertEquals(GoalState.PLANNING, goals.get(goal.goalId)!!.state)
    }

    @Test
    fun `the claim fence refuses the current token at a stale version`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val goal = claimed(goals, "k-version")
        val staleVersion = GoalClaim(goal.goalId, goal.version - 1, goal.claimToken!!)
        assertEquals(CommitResult.StaleClaim, goals.commitPlan(staleVersion, newPlan(), newTasks()))
        assertEquals(0, db.count("selfdev_plans"))
    }

    @Test
    fun `a planner holding an expired claim cannot write after the goal was claimed again`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val first = claimed(goals, "k-expired")
        val oldClaim = GoalClaim(first.goalId, first.version, first.claimToken!!)
        // Make the first claim expire on the DB clock, then let a second planner take the goal.
        db.expireGoalClaim(first.goalId)
        assertTrue(goals.expireClaim(first.goalId))
        assertEquals(GoalState.GOAL_SUBMITTED, goals.get(first.goalId)!!.state)
        assertEquals("CLAIM_EXPIRED", goals.get(first.goalId)!!.stateReason?.name)
        val second = assertIs<ClaimResult.Claimed>(goals.claim(first.goalId, TTL)).goal
        assertTrue(second.claimToken != first.claimToken)

        assertEquals(CommitResult.StaleClaim, goals.commitPlan(oldClaim, newPlan(), newTasks()))
        val fresh = GoalClaim(second.goalId, second.version, second.claimToken!!)
        val committed = assertIs<CommitResult.Committed>(goals.commitPlan(fresh, newPlan(), newTasks()))
        assertEquals(1, committed.plan.revision)
        assertEquals(GoalState.PLAN_PROPOSED, goals.get(second.goalId)!!.state)
    }

    @Test
    fun `a commit stores the plan as PROPOSED and every task as PENDING_APPROVAL with its idempotency key`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val (goal, committed) = proposed(goals, "k-commit")
        assertEquals(1, goals.get(goal.goalId)!!.revision)
        assertEquals(PlanState.PROPOSED, committed.plan.state)
        val tasks = committed.tasks
        assertEquals(listOf(1, 2), tasks.map { it.ordinal })
        assertTrue(tasks.all { it.state == TaskState.PENDING_APPROVAL && it.version == 0L && it.attempts == 0 })
        assertEquals("ap:${committed.plan.planId}:1", tasks[0].idempotencyKey)
        assertEquals("ap:${committed.plan.planId}:2", tasks[1].idempotencyKey)
        assertEquals(listOf("docs/a.md"), tasks[0].targetPaths)
        assertEquals(listOf(1), tasks[1].dependsOn)
        assertTrue(tasks[1].requiresApproval)
    }

    @Test
    fun `a plan with an invalid task is refused before anything is written`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val claim = claimed(goals, "k-invalid").let { GoalClaim(it.goalId, it.version, it.claimToken!!) }
        assertFailsWith<IllegalArgumentException> {
            goals.commitPlan(claim, newPlan(), listOf(newTask(dependsOn = listOf(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            goals.commitPlan(claim, newPlan(), listOf(newTask(sizeEstimate = "XL")))
        }
        assertFailsWith<IllegalArgumentException> {
            goals.commitPlan(claim, newPlan(), listOf(newTask(paths = (1..11).map { "p$it" })))
        }
        assertEquals(0, db.count("selfdev_plans"))
        assertEquals(0, db.count("selfdev_plan_tasks"))
    }

    @Test
    fun `a replan supersedes the proposed plan, and the third revision is the last`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        var goal = goalOf(goals, "k-replan")
        for (round in 1..3) {
            goal = claimed(goals, goal)
            assertIs<CommitResult.Committed>(goals.commitPlan(GoalClaim(goal.goalId, goal.version, goal.claimToken!!), newPlan(), newTasks()))
            goal = goals.get(goal.goalId)!!
            assertEquals(round, goal.revision)
            if (round < 3) {
                goal = movedGoal(goals.transition(goal.goalId, goal.version, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED))
                assertEquals(GoalState.GOAL_SUBMITTED, goal.state)
            }
        }
        assertEquals(listOf(PlanState.SUPERSEDED, PlanState.SUPERSEDED, PlanState.PROPOSED), plans.plansOf(goal.goalId).map { it.state })
        // The goal is at the revision cap: a fourth Plan is never created, because the replan edge is refused.
        assertEquals(
            Transition.Refused(TransitionRefusal.GUARD_FAILED),
            goals.transition(goal.goalId, goal.version, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
    }

    @Test
    fun `a goal transition is CAS on version, and an edge the state machine does not have is refused`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val (goal, _) = proposed(goals, "k-cas")
        assertEquals(Transition.NotFound, goals.transition("01NOTAGOAL0000000000000000", 0, GoalState.TASKS_QUEUED, null))
        assertEquals(Transition.StaleVersion, goals.transition(goal.goalId, goal.version + 5, GoalState.TASKS_QUEUED, null))
        assertEquals(
            Transition.Refused(TransitionRefusal.NO_EDGE),
            goals.transition(goal.goalId, goal.version, GoalState.RUNNING, null),
        )
        val moved = assertIs<Transition.Moved<SelfDevGoal>>(goals.transition(goal.goalId, goal.version, GoalState.TASKS_QUEUED, null))
        // Two callers with the same expected version: the second one sees the new version and changes nothing.
        assertEquals(Transition.StaleVersion, goals.transition(goal.goalId, goal.version, GoalState.STOPPED, AutopilotReason.OPERATOR_STOPPED))
        assertEquals(GoalState.TASKS_QUEUED, goals.get(goal.goalId)!!.state)
        assertEquals(moved.row.version, goals.get(goal.goalId)!!.version)
    }

    @Test
    fun `pausing keeps the state to return to, and resuming restores it with its reason`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val (goal, _) = proposed(goals, "k-pause")
        var current = assertIs<Transition.Moved<SelfDevGoal>>(goals.transition(goal.goalId, goal.version, GoalState.TASKS_QUEUED, null)).row
        current = assertIs<Transition.Moved<SelfDevGoal>>(goals.transition(current.goalId, current.version, GoalState.RUNNING, null)).row
        current = assertIs<Transition.Moved<SelfDevGoal>>(
            goals.transition(current.goalId, current.version, GoalState.BLOCKED, AutopilotReason.DAILY_RUN_CAP),
        ).row
        val paused = assertIs<Transition.Moved<SelfDevGoal>>(
            goals.transition(current.goalId, current.version, GoalState.PAUSED, AutopilotReason.OPERATOR_PAUSED),
        ).row
        assertEquals(GoalState.PAUSED, paused.state)
        assertEquals(GoalState.BLOCKED, paused.resumeState)
        assertEquals(AutopilotReason.DAILY_RUN_CAP, paused.resumeReason)
        // A paused goal does not go to any state but the one it was paused from.
        assertEquals(
            Transition.Refused(TransitionRefusal.GUARD_FAILED),
            goals.transition(paused.goalId, paused.version, GoalState.RUNNING, AutopilotReason.OPERATOR_RESUMED),
        )
        val resumed = assertIs<Transition.Moved<SelfDevGoal>>(
            goals.transition(paused.goalId, paused.version, GoalState.BLOCKED, AutopilotReason.OPERATOR_RESUMED),
        ).row
        assertEquals(GoalState.BLOCKED, resumed.state)
        assertEquals(AutopilotReason.DAILY_RUN_CAP, resumed.stateReason)
        assertNull(resumed.resumeState)
        assertNull(resumed.resumeReason)
    }

    @Test
    fun `stopping a goal clears a claim it still holds`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val claimedGoal = claimed(goals, "k-stop")
        val stopped = assertIs<Transition.Moved<SelfDevGoal>>(
            goals.transition(claimedGoal.goalId, claimedGoal.version, GoalState.STOPPED, AutopilotReason.OPERATOR_STOPPED),
        ).row
        assertEquals(GoalState.STOPPED, stopped.state)
        assertNull(stopped.claimToken)
        assertNull(stopped.claimedUntil)
        // The worker that held the claim can no longer write.
        assertEquals(
            CommitResult.StaleClaim,
            goals.commitPlan(GoalClaim(stopped.goalId, claimedGoal.version, claimedGoal.claimToken!!), newPlan(), newTasks()),
        )
    }

    @Test
    fun `a task moves through approval, submission and the Run's outcome, and records its run`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        val (_, committed) = proposed(goals, "k-task")
        val planId = committed.plan.planId
        val runId = db.insertRun("selfdev:task-1")

        var task = plans.task(planId, 1)!!
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.SUBMITTING, null))
        assertEquals(1, task.attempts)
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.SUBMITTED, null, runId = runId))
        assertEquals(runId, task.runId)
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.FAILED, AutopilotReason.RUN_FAILED))
        // The Run stays as it was. The task is a projection, and its run id is kept.
        assertEquals(runId, task.runId)
        assertEquals(
            DevelopmentRunState.CREATED.name,
            transaction(db.database) { execQuery("SELECT state FROM development_runs WHERE run_id = ?", runId) { it.getString(1) }.single() },
        )
        // A failed task is re-queued by the operator; the attempt count stays at the submissions made.
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.QUEUED, AutopilotReason.OPERATOR_REQUEUED))
        assertEquals(1, task.attempts)
    }

    @Test
    fun `a stale task version is refused, and an edge the table does not have is refused`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        val (_, committed) = proposed(goals, "k-task-cas")
        val planId = committed.plan.planId
        val task = plans.task(planId, 1)!!
        assertEquals(
            Transition.Refused(TransitionRefusal.NO_EDGE),
            plans.transitionTask(planId, 1, task.version, TaskState.SUBMITTED, null, runId = "x".repeat(26)),
        )
        assertEquals(Transition.StaleVersion, plans.transitionTask(planId, 1, task.version + 1, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
        assertEquals(Transition.NotFound, plans.transitionTask(planId, 9, 0, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
        assertEquals(TaskState.PENDING_APPROVAL, plans.task(planId, 1)!!.state)
    }

    @Test
    fun `a dependent task is not submitted until its dependency is COMPLETED`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        val (_, committed) = proposed(goals, "k-dep")
        val planId = committed.plan.planId
        var second = plans.task(planId, 2)!!
        second = movedTask(plans.transitionTask(planId, 2, second.version, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
        assertEquals(
            Transition.Refused(TransitionRefusal.GUARD_FAILED),
            plans.transitionTask(planId, 2, second.version, TaskState.SUBMITTING, null),
        )
        // Complete task 1 through its Run: SUBMITTED, then PR_MERGED.
        var first = plans.task(planId, 1)!!
        first = movedTask(plans.transitionTask(planId, 1, first.version, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
        first = movedTask(plans.transitionTask(planId, 1, first.version, TaskState.SUBMITTING, null))
        first = movedTask(plans.transitionTask(planId, 1, first.version, TaskState.SUBMITTED, null, runId = db.insertRun("selfdev:dep")))
        first = movedTask(plans.transitionTask(planId, 1, first.version, TaskState.COMPLETED, AutopilotReason.PR_MERGED))
        assertEquals(
            TaskState.SUBMITTING,
            movedTask(plans.transitionTask(planId, 2, second.version, TaskState.SUBMITTING, null)).state,
        )
    }

    @Test
    fun `a submission that the limit refused goes back to QUEUED without a failure`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        val (_, committed) = proposed(goals, "k-limit")
        val planId = committed.plan.planId
        var task = plans.task(planId, 1)!!
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED))
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.SUBMITTING, null))
        task = movedTask(plans.transitionTask(planId, 1, task.version, TaskState.QUEUED, AutopilotReason.ACTIVE_RUN_LIMIT))
        assertEquals(TaskState.QUEUED, task.state)
        assertEquals(1, task.attempts)
    }

    @Test
    fun `a task's idempotency key is unique, so a second row with the same key is refused`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val (_, committed) = proposed(goals, "k-idem")
        val existingKey = "ap:${committed.plan.planId}:1"
        assertFailsWith<SQLException> {
            transaction(db.database) {
                execUpdate(
                    """
                    INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, target_paths, size_estimate, depends_on,
                        requires_approval, state, idempotency_key, attempts, version, created_at, updated_at)
                    VALUES (?, 9, 't', '[]', 'S', '[]', FALSE, 'PENDING_APPROVAL', ?, 0, 0, NOW(6), NOW(6))
                    """.trimIndent(),
                    committed.plan.planId, existingKey,
                )
            }
        }
        assertEquals(2, db.count("selfdev_plan_tasks"))
    }

    @Test
    fun `the control row is seeded and its failure count adds, resets and opens the breaker at the limit`() = TestDb().use { db ->
        val control = SelfDevAutopilotControlRepository(db.database)
        assertEquals(0, control.read().consecutiveFailures)
        assertTrue(!control.read().breakerOpen && !control.read().paused)

        control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = 3)
        control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = 3)
        assertEquals(2, control.read().consecutiveFailures)
        assertTrue(!control.read().breakerOpen)

        control.recordRunOutcome(succeeded = true, maxConsecutiveFailures = 3)
        assertEquals(0, control.read().consecutiveFailures)

        repeat(3) { control.recordRunOutcome(succeeded = false, maxConsecutiveFailures = 3) }
        assertEquals(3, control.read().consecutiveFailures)
        assertTrue(control.read().breakerOpen)

        // A success does not close an open breaker; the operator's resume does, and it resets the count.
        control.recordRunOutcome(succeeded = true, maxConsecutiveFailures = 3)
        assertTrue(control.read().breakerOpen)
        control.resetBreaker()
        assertEquals(0, control.read().consecutiveFailures)
        assertTrue(!control.read().breakerOpen)
    }

    @Test
    fun `the daily Run count stops at the cap and starts again on the next UTC day`() {
        val clock = AdjustableClock()
        TestDb().use { db ->
            val control = SelfDevAutopilotControlRepository(db.database, clock)
            assertTrue(control.tryReserveRun(dailyLimit = 2))
            assertTrue(control.tryReserveRun(dailyLimit = 2))
            assertTrue(!control.tryReserveRun(dailyLimit = 2))
            assertEquals(2, control.read().runsToday)

            clock.offset = Duration.ofDays(1)
            assertTrue(control.tryReserveRun(dailyLimit = 2))
            assertEquals(1, control.read().runsToday)
            assertEquals(clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(), control.read().dayUtc)
        }
    }

    @Test
    fun `a pause records who paused and when, and resuming keeps that record`() = TestDb().use { db ->
        val control = SelfDevAutopilotControlRepository(db.database)
        val paused = control.setPaused(true, "op-pauser")
        assertTrue(paused.paused)
        assertEquals("op-pauser", paused.pausedBy)
        assertNotNull(paused.pausedAt)
        val resumed = control.setPaused(false, "op-resumer")
        assertTrue(!resumed.paused)
        assertEquals("op-pauser", resumed.pausedBy)
        assertTrue(resumed.version > paused.version)
    }

    @Test
    fun `the promotion counts read only operator-approved tasks, and a policy rejection counts by its task or by its Run`() = TestDb().use { db ->
        val goals = SelfDevGoalRepository(db.database)
        val plans = SelfDevPlanRepository(db.database)
        val (_, committed) = proposed(goals, "k-promotion")
        val planId = committed.plan.planId
        assertEquals(PromotionCounts(0, 0, 0), plans.promotionCounts())

        // Ten approved and COMPLETED tasks count; an unapproved COMPLETED task does not.
        for (ordinal in 3..12) db.insertPlanTask(planId, ordinal, "COMPLETED", approved = true)
        db.insertPlanTask(planId, 13, "COMPLETED", approved = false)
        // A POLICY_REJECTED on the task row, and one on the Run the task points to.
        db.insertPlanTask(planId, 14, "FAILED", approved = true, reason = "POLICY_REJECTED")
        val policyRun = db.insertRun("selfdev:promotion-policy")
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = 'FAILED', state_reason = 'POLICY_REJECTED' WHERE run_id = ?", policyRun)
        }
        db.insertPlanTask(planId, 15, "FAILED", approved = true, reason = "RUN_FAILED", runId = policyRun)
        // An out-of-scope task (the stage 2 re-check) is counted in the scope count whether or not it was approved.
        db.insertPlanTask(planId, 16, "PENDING_APPROVAL", approved = false, reason = "AUTO_SCOPE_EXCEEDED")

        assertEquals(PromotionCounts(approvedCompleted = 10, approvedPolicyRejected = 2, scopeExceeded = 1), plans.promotionCounts())
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------

    private fun goalOf(goals: SelfDevGoalRepository, key: String) =
        assertIs<SubmitResult.Created>(goals.submit("op", key, "goal $key", "body", "kigawa-net/kivra")).goal

    private fun claimed(goals: SelfDevGoalRepository, key: String): SelfDevGoal = claimed(goals, goalOf(goals, key))

    private fun claimed(goals: SelfDevGoalRepository, goal: SelfDevGoal): SelfDevGoal =
        assertIs<ClaimResult.Claimed>(goals.claim(goal.goalId, TTL)).goal

    private fun proposed(goals: SelfDevGoalRepository, key: String): Pair<SelfDevGoal, SelfDevPlanCommit> {
        val goal = claimed(goals, key)
        val committed = assertIs<CommitResult.Committed>(
            goals.commitPlan(GoalClaim(goal.goalId, goal.version, goal.claimToken!!), newPlan(), newTasks()),
        )
        return goals.get(goal.goalId)!! to SelfDevPlanCommit(committed.plan, committed.tasks)
    }

    private fun movedTask(result: Transition<SelfDevPlanTask>): SelfDevPlanTask =
        assertIs<Transition.Moved<SelfDevPlanTask>>(result).row

    private fun movedGoal(result: Transition<SelfDevGoal>): SelfDevGoal =
        assertIs<Transition.Moved<SelfDevGoal>>(result).row

    /** Makes the claim's deadline pass on the DB clock. */
    private fun TestDb.expireGoalClaim(goalId: String) = transaction(database) {
        execUpdate("UPDATE selfdev_goals SET claimed_until = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE goal_id = ?", goalId)
    }

    private fun TestDb.insertPlanTask(
        planId: String,
        ordinal: Int,
        state: String,
        approved: Boolean,
        reason: String? = null,
        runId: String? = null,
    ) {
        transaction(database) {
            execUpdate(
                """
                INSERT INTO selfdev_plan_tasks
                    (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval, state, state_reason,
                     idempotency_key, run_id, attempts, version, created_at, updated_at, approved_by)
                VALUES (?, ?, 'task', NULL, '[]', 'S', '[]', FALSE, ?, ?, ?, ?, 0, 0, NOW(6), NOW(6), ?)
                """.trimIndent(),
                planId, ordinal, state, reason, "ap:$planId:$ordinal", runId, if (approved) "op" else null,
            )
        }
    }

    private fun TestDb.insertRun(task: String): String {
        val runId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO development_runs
                    (run_id, task_id, state, completion_mode, attempt, plan_revision, lease_generation, cancel_requested, version, created_at, updated_at)
                VALUES (?, ?, 'CREATED', ?, 0, 0, 0, FALSE, 0, NOW(6), NOW(6))
                """.trimIndent(),
                runId, task, RunCompletionMode.RELEASE.name,
            )
        }
        return runId
    }

    private fun newPlan() = NewPlan(summary = "summary", plannerModel = "claude-haiku-5-5", outputSha256 = SHA, requestLedgerId = null)

    private fun newTask(
        paths: List<String> = listOf("docs/a.md"),
        sizeEstimate: String = "S",
        dependsOn: List<Int> = emptyList(),
    ) = NewTask(title = "task", body = "text", targetPaths = paths, sizeEstimate = sizeEstimate, dependsOn = dependsOn, requiresApproval = false)

    private fun newTasks() = listOf(
        NewTask("first", "body", listOf("docs/a.md"), "S", emptyList(), requiresApproval = false),
        NewTask("second", null, listOf("README.md"), "M", listOf(1), requiresApproval = true),
    )

    /** A commit result without the sealed wrapper, for the helpers. */
    private data class SelfDevPlanCommit(val plan: SelfDevPlan, val tasks: List<SelfDevPlanTask>)
}
