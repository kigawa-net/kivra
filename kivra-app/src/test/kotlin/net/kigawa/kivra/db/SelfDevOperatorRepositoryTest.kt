package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.core.RunCompletionMode
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(60)
private val SHA = "b".repeat(64)

/**
 * The operator's actions (ADR 0011 sections 1, 4, 5.4): approval (plan and task, with the approver stored), rejection, stop and
 * replan. Runs on H2 by default and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 */
class SelfDevOperatorRepositoryTest {

    @Test
    fun `approving the plan moves the goal to TASKS_QUEUED, stores the approver, and queues no task`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val (goal, plan) = proposed(goals, "k1")

            val moved = movedGoal(ops.approvePlan(goal.goalId, goal.version, "op-1"))
            assertEquals(GoalState.TASKS_QUEUED, moved.state)
            assertEquals("op-1", approvedBy(db, "selfdev_goals", "goal_id", goal.goalId))
            // The plan approval queues nothing: every task still waits for its own approval (the denylisted one too).
            assertEquals(listOf(TaskState.PENDING_APPROVAL, TaskState.PENDING_APPROVAL), db.tasksOf(plan.planId).map { it.state })
        }
    }

    @Test
    fun `a plan approval with a stale version, or for a goal that is not proposed, changes nothing`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val (goal, _) = proposed(goals, "k1")

            assertIs<Transition.StaleVersion>(ops.approvePlan(goal.goalId, goal.version + 1, "op-1"))
            assertIs<Transition.NotFound>(ops.approvePlan("01AAAAAAAAAAAAAAAAAAAAAAAA", 0, "op-1"))
            movedGoal(ops.approvePlan(goal.goalId, goal.version, "op-1"))
            // The second approval is an edge the state machine does not have from TASKS_QUEUED.
            val again = ops.approvePlan(goal.goalId, goal.version + 1, "op-2")
            assertIs<Transition.Refused>(again)
            assertEquals("op-1", approvedBy(db, "selfdev_goals", "goal_id", goal.goalId))

            val queued = goals.submit("op", "k-fresh", "fresh", null, "kigawa-net/kivra").let { assertIs<SubmitResult.Created>(it).goal }
            assertIs<Transition.Refused>(ops.approvePlan(queued.goalId, queued.version, "op-1"))
        }
    }

    @Test
    fun `a denylisted task is QUEUED only by its own approval, after the plan is approved`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val (goal, plan) = proposed(goals, "k1")
            val denylisted = plan.tasks.first { it.requiresApproval }
            val other = plan.tasks.first { !it.requiresApproval }

            // Before the plan is approved, a task cannot be approved: the goal is still PLAN_PROPOSED.
            assertIs<Transition.Refused>(ops.approveTask(plan.planId, denylisted.ordinal, denylisted.version, "op-1"))

            movedGoal(ops.approvePlan(goal.goalId, goal.version, "op-1"))
            val queued = movedTask(ops.approveTask(plan.planId, denylisted.ordinal, denylisted.version, "op-1"))
            assertEquals(TaskState.QUEUED, queued.state)
            assertEquals(AutopilotReason.OPERATOR_APPROVED, queued.stateReason)
            assertEquals("op-1", approvedBy(db, "selfdev_plan_tasks", "ordinal", denylisted.ordinal, plan.planId))
            // The other task is untouched until it is approved in its own right.
            assertEquals(TaskState.PENDING_APPROVAL, db.tasksOf(plan.planId).first { it.ordinal == other.ordinal }.state)
        }
    }

    @Test
    fun `a task is approved once, and a rejected task cannot be approved`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val (goal, plan) = proposed(goals, "k1")
            movedGoal(ops.approvePlan(goal.goalId, goal.version, "op-1"))
            val first = plan.tasks.first { !it.requiresApproval }
            val second = plan.tasks.first { it.requiresApproval }

            val approved = movedTask(ops.approveTask(plan.planId, first.ordinal, first.version, "op-1"))
            assertIs<Transition.Refused>(ops.approveTask(plan.planId, first.ordinal, approved.version, "op-1"))
            assertIs<Transition.StaleVersion>(ops.approveTask(plan.planId, first.ordinal, first.version, "op-1"))

            val skipped = movedTask(ops.rejectTask(plan.planId, second.ordinal, second.version))
            assertEquals(TaskState.SKIPPED, skipped.state)
            assertEquals(AutopilotReason.OPERATOR_SKIPPED, skipped.stateReason)
            assertIs<Transition.Refused>(ops.approveTask(plan.planId, second.ordinal, skipped.version, "op-1"))
        }
    }

    @Test
    fun `stopping a goal skips its unsubmitted tasks, leaves a submitted Run alone, and a second stop is refused`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val (goal, plan) = proposed(goals, "k1")
            movedGoal(ops.approvePlan(goal.goalId, goal.version, "op-1"))
            val first = plan.tasks.first { !it.requiresApproval }
            val second = plan.tasks.first { it.requiresApproval }
            val queued = movedTask(ops.approveTask(plan.planId, first.ordinal, first.version, "op-1"))
            // The first task has been submitted: a Run exists and the task points to it.
            val submitting = movedTask(SelfDevPlanRepository(db.database).transitionTask(plan.planId, first.ordinal, queued.version, TaskState.SUBMITTING, null))
            val runId = db.insertRun("selfdev:first")
            movedTask(SelfDevPlanRepository(db.database).transitionTask(plan.planId, first.ordinal, submitting.version, TaskState.SUBMITTED, null, runId))

            val stopped = movedGoal(ops.stopGoal(goal.goalId, ops.goal(goal.goalId)!!.version))
            assertEquals(GoalState.STOPPED, stopped.state)
            assertEquals(AutopilotReason.OPERATOR_STOPPED, stopped.stateReason)

            val after = db.tasksOf(plan.planId).associateBy { it.ordinal }
            assertEquals(TaskState.SUBMITTED, after.getValue(first.ordinal).state)
            assertEquals(runId, after.getValue(first.ordinal).runId)
            assertEquals(TaskState.SKIPPED, after.getValue(second.ordinal).state)
            assertEquals(AutopilotReason.TASK_STOPPED, after.getValue(second.ordinal).stateReason)

            assertIs<Transition.Refused>(ops.stopGoal(goal.goalId, stopped.version))
        }
    }

    @Test
    fun `a stop with a stale version changes nothing`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val (goal, plan) = proposed(goals, "k1")
            assertIs<Transition.StaleVersion>(ops.stopGoal(goal.goalId, goal.version + 5))
            assertEquals(GoalState.PLAN_PROPOSED, ops.goal(goal.goalId)!!.state)
            assertTrue(db.tasksOf(plan.planId).all { it.state == TaskState.PENDING_APPROVAL })
        }
    }

    @Test
    fun `a planning-stage block is replanned to GOAL_SUBMITTED, and a dispatch block or a goal in another state is refused`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val goal = goalOf(goals, "k1")
            val claim = claimed(goals, goal)
            assertEquals(true, goals.blockPlanning(GoalClaim(goal.goalId, claim.version, claim.claimToken!!), AutopilotReason.PLAN_INVALID))
            val blocked = ops.goal(goal.goalId)!!

            val replanned = movedGoal(ops.replanBlocked(goal.goalId, blocked.version))
            assertEquals(GoalState.GOAL_SUBMITTED, replanned.state)
            assertEquals(AutopilotReason.REPLAN_REQUESTED, replanned.stateReason)

            // A goal that is not blocked, and a block that the dispatch guards made, are refused.
            assertIs<Transition.Refused>(ops.replanBlocked(goal.goalId, replanned.version))
            val other = goalOf(goals, "k2")
            assertIs<Transition.Refused>(ops.replanBlocked(other.goalId, other.version))
        }
    }

    @Test
    fun `a goal at the revision limit is not replanned, even when it is blocked at planning`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            val goal = goalOf(goals, "k1")
            val claim = claimed(goals, goal)
            goals.blockPlanning(GoalClaim(goal.goalId, claim.version, claim.claimToken!!), AutopilotReason.PLAN_CALL_FAILED)
            transaction(db.database) {
                execUpdate("UPDATE selfdev_goals SET revision = 3 WHERE goal_id = ?", goal.goalId)
        }
        val blocked = ops.goal(goal.goalId)!!
        assertIs<Transition.Refused>(ops.replanBlocked(goal.goalId, blocked.version))
        assertEquals(GoalState.BLOCKED, ops.goal(goal.goalId)!!.state)
        }
    }

    @Test
    fun `the goal list is newest first and carries no plan`() {
        TestDb().use { db ->
            val goals = SelfDevGoalRepository(db.database)
            val ops = SelfDevOperatorRepository(db.database)
            goalOf(goals, "a")
            goalOf(goals, "b")
            val listed = ops.listGoals(10)
            assertEquals(2, listed.size)
            assertEquals(listed.sortedByDescending { it.updatedAt }.map { it.goalId }, listed.map { it.goalId })
            assertNull(SelfDevPlanRepository(db.database).plansOf(listed.first().goalId).firstOrNull())
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun goalOf(goals: SelfDevGoalRepository, key: String): SelfDevGoal =
        assertIs<SubmitResult.Created>(goals.submit("op", key, "goal $key", "body", "kigawa-net/kivra")).goal

    private fun claimed(goals: SelfDevGoalRepository, goal: SelfDevGoal): SelfDevGoal =
        assertIs<ClaimResult.Claimed>(goals.claim(goal.goalId, TTL)).goal

    private fun proposed(goals: SelfDevGoalRepository, key: String): Pair<SelfDevGoal, SelfDevPlanCommit> {
        val goal = claimed(goals, goalOf(goals, key))
        val committed = assertIs<CommitResult.Committed>(
            goals.commitPlan(GoalClaim(goal.goalId, goal.version, goal.claimToken!!), newPlan(), newTasks()),
        )
        return goals.get(goal.goalId)!! to SelfDevPlanCommit(committed.plan, committed.tasks)
    }

    private fun movedTask(result: Transition<SelfDevPlanTask>): SelfDevPlanTask =
        assertIs<Transition.Moved<SelfDevPlanTask>>(result).row

    private fun movedGoal(result: Transition<SelfDevGoal>): SelfDevGoal =
        assertIs<Transition.Moved<SelfDevGoal>>(result).row

    private fun TestDb.tasksOf(planId: String): List<SelfDevPlanTask> = SelfDevPlanRepository(database).tasksOf(planId)

    private fun approvedBy(db: TestDb, table: String, key: String, value: String): String? =
        approvedByRow(db, "SELECT approved_by FROM $table WHERE $key = ?", value)

    private fun approvedBy(db: TestDb, table: String, key: String, ordinal: Int, planId: String): String? =
        approvedByRow(db, "SELECT approved_by FROM $table WHERE plan_id = ? AND ordinal = ?", planId, ordinal)

    private fun approvedByRow(db: TestDb, sql: String, vararg params: Any?): String? = transaction(db.database) {
        execQuery(sql, *params) { it.getString(1) }.single()
    }

    private fun newPlan() = NewPlan(summary = "summary", plannerModel = "claude-haiku-5-5", outputSha256 = SHA, requestLedgerId = null)

    private fun newTasks() = listOf(
        NewTask("first", "body", listOf("docs/a.md"), "S", emptyList(), requiresApproval = false),
        NewTask("second", null, listOf(".github/workflows/ci.yml"), "M", listOf(1), requiresApproval = true),
    )

    /** A commit result without the sealed wrapper, for the helpers. */
    private data class SelfDevPlanCommit(val plan: SelfDevPlan, val tasks: List<SelfDevPlanTask>) {
        val planId: String get() = plan.planId
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
}
