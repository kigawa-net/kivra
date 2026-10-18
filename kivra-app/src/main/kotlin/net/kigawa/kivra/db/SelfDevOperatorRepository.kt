package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalContext
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.MAX_REVISIONS
import net.kigawa.kivra.core.autopilot.PlanState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionDecision
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.core.autopilot.checkGoalTransition
import net.kigawa.kivra.core.autopilot.checkTaskTransition
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * The operator's actions on goals, plans and tasks (ADR 0011 sections 1, 4 and 5.4): the approval of a Plan, the approval and rejection
 * of a task, the stop of a goal, the replan of a planning-stage block, and the list views. The Planner's writes stay in
 * [SelfDevGoalRepository]; this repository is separate so the Dispatcher's files are not touched.
 *
 * Every write runs in one transaction that first locks the goal row (SELECT ... FOR UPDATE), then checks the state machine, then makes
 * one conditional UPDATE per row (CAS on version, and on the state it was read in). A stale version or a refused edge changes nothing.
 *
 * Approvals record the operator (`approved_by`, V19). A task of a denylisted path is `requires_approval`, and the only edge that moves it
 * to QUEUED is [approveTask] (the state machine has no other edge from PENDING_APPROVAL to QUEUED).
 */
class SelfDevOperatorRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** The goals, newest update first. Bodies are included: the caller decides who may see them. */
    fun listGoals(limit: Int): List<SelfDevGoal> = transaction(database) {
        SelfDevGoalsTable.selectAll()
            .orderBy(SelfDevGoalsTable.updatedAt to SortOrder.DESC, SelfDevGoalsTable.goalId to SortOrder.DESC)
            .limit(limit)
            .map { goalOf(it) }
    }

    fun goal(goalId: String): SelfDevGoal? = transaction(database) { goalIn(goalId) }

    /** PLAN_PROPOSED -> TASKS_QUEUED for the goal's proposed Plan. Fenced by [expectedVersion]; records [approver]. */
    fun approvePlan(goalId: String, expectedVersion: Long, approver: String): Transition<SelfDevGoal> = transaction(database) {
        val current = lockGoal(goalId) ?: return@transaction Transition.NotFound
        if (current.version != expectedVersion) return@transaction Transition.StaleVersion
        val decision = checkGoalTransition(current.state, GoalState.TASKS_QUEUED, null, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction Transition.Refused(decision.reason)
        if (proposedPlanOf(goalId, current.revision) == null) return@transaction Transition.Refused(TransitionRefusal.GUARD_FAILED)
        val now = utcNow(clock)
        val changed = execUpdate(
            """
            UPDATE selfdev_goals
               SET state = 'TASKS_QUEUED', state_reason = NULL, approved_by = ?, approved_at = ?, version = version + 1, updated_at = ?
             WHERE goal_id = ? AND version = ? AND state = 'PLAN_PROPOSED'
            """.trimIndent(),
            approver, now, now, goalId, expectedVersion,
        )
        if (changed != 1) return@transaction Transition.StaleVersion
        Transition.Moved(goalIn(goalId) ?: error("goal vanished after plan approval"))
    }

    /**
     * PENDING_APPROVAL -> QUEUED for one task of the goal's proposed Plan. [taskId] is the task's plan id and ordinal. The goal must
     * already have its Plan approved (TASKS_QUEUED or later), so the Dispatcher never sees a task of an unapproved Plan.
     */
    fun approveTask(planId: String, ordinal: Int, expectedVersion: Long, approver: String): Transition<SelfDevPlanTask> =
        transaction(database) {
            val plan = planOf(planId) ?: return@transaction Transition.NotFound
            val goal = lockGoal(plan.goalId) ?: return@transaction Transition.NotFound
            if (!isCurrentProposedPlan(plan, goal, APPROVABLE_TASK_GOAL_STATES)) {
                return@transaction Transition.Refused(TransitionRefusal.GUARD_FAILED)
            }
            val task = taskOf(planId, ordinal) ?: return@transaction Transition.NotFound
            if (task.version != expectedVersion) return@transaction Transition.StaleVersion
            val decision = checkTaskTransition(task.state, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED)
            if (decision is TransitionDecision.Refused) return@transaction Transition.Refused(decision.reason)
            val now = utcNow(clock)
            // A task whose Run already ended (for example a scope re-check, ADR 0011 section 6.2) gets a new idempotency key on approval.
            // The old key names the old Run, and the Run store would replay it, so the approved task would fail at once.
            val key = if (task.runId == null) task.idempotencyKey else "ap:$planId:$ordinal:a${task.attempts}"
            val changed = execUpdate(
                """
                UPDATE selfdev_plan_tasks
                   SET state = 'QUEUED', state_reason = 'OPERATOR_APPROVED', approved_by = ?, approved_at = ?,
                       idempotency_key = ?, version = version + 1, updated_at = ?
                 WHERE plan_id = ? AND ordinal = ? AND version = ? AND state = 'PENDING_APPROVAL'
                """.trimIndent(),
                approver, now, key, now, planId, ordinal, expectedVersion,
            )
            if (changed != 1) return@transaction Transition.StaleVersion
            Transition.Moved(taskOf(planId, ordinal) ?: error("task vanished after approval"))
        }

    /** PENDING_APPROVAL -> SKIPPED for one task of the goal's proposed Plan (the operator does not want it). */
    fun rejectTask(planId: String, ordinal: Int, expectedVersion: Long): Transition<SelfDevPlanTask> = transaction(database) {
        val plan = planOf(planId) ?: return@transaction Transition.NotFound
        val goal = lockGoal(plan.goalId) ?: return@transaction Transition.NotFound
        if (!isCurrentProposedPlan(plan, goal, REJECTABLE_GOAL_STATES)) return@transaction Transition.Refused(TransitionRefusal.GUARD_FAILED)
        val task = taskOf(planId, ordinal) ?: return@transaction Transition.NotFound
        if (task.version != expectedVersion) return@transaction Transition.StaleVersion
        val decision = checkTaskTransition(task.state, TaskState.SKIPPED, AutopilotReason.OPERATOR_SKIPPED)
        if (decision is TransitionDecision.Refused) return@transaction Transition.Refused(decision.reason)
        val changed = execUpdate(
            """
            UPDATE selfdev_plan_tasks
               SET state = 'SKIPPED', state_reason = 'OPERATOR_SKIPPED', version = version + 1, updated_at = ?
             WHERE plan_id = ? AND ordinal = ? AND version = ? AND state = 'PENDING_APPROVAL'
            """.trimIndent(),
            utcNow(clock), planId, ordinal, expectedVersion,
        )
        if (changed != 1) return@transaction Transition.StaleVersion
        Transition.Moved(taskOf(planId, ordinal) ?: error("task vanished after rejection"))
    }

    /**
     * The operator stops a goal (-> STOPPED). Tasks that were not submitted yet (PENDING_APPROVAL, QUEUED) become SKIPPED. Submitted
     * Runs are not touched: their PRs stay where they are (ADR 0011 section 5.4). Any task that moved while this ran rolls the whole
     * stop back, and the answer is a stale version.
     */
    fun stopGoal(goalId: String, expectedVersion: Long): Transition<SelfDevGoal> = try {
        transaction(database) {
            val current = lockGoal(goalId) ?: return@transaction Transition.NotFound
            if (current.version != expectedVersion) return@transaction Transition.StaleVersion
            val decision = checkGoalTransition(current.state, GoalState.STOPPED, AutopilotReason.OPERATOR_STOPPED, contextOf(current))
            if (decision is TransitionDecision.Refused) return@transaction Transition.Refused(decision.reason)
            val now = utcNow(clock)
            val changed = execUpdate(
                """
                UPDATE selfdev_goals
                   SET state = 'STOPPED', state_reason = 'OPERATOR_STOPPED', resume_state = NULL, resume_reason = NULL,
                       claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ?
                 WHERE goal_id = ? AND version = ?
                """.trimIndent(),
                now, goalId, expectedVersion,
            )
            if (changed != 1) return@transaction Transition.StaleVersion
            skipUnsubmittedTasks(goalId, now)
            Transition.Moved(goalIn(goalId) ?: error("goal vanished after stop"))
        }
    } catch (_: TaskMovedDuringStop) {
        Transition.StaleVersion
    }

    /**
     * U-16: returns a planning-stage BLOCKED goal to GOAL_SUBMITTED so the Planner makes a new Plan. The state machine allows only the
     * planning-stage reasons. A goal at the revision limit is refused, since its next Plan would break the revision check.
     */
    fun replanBlocked(goalId: String, expectedVersion: Long): Transition<SelfDevGoal> = transaction(database) {
        val current = lockGoal(goalId) ?: return@transaction Transition.NotFound
        if (current.version != expectedVersion) return@transaction Transition.StaleVersion
        // Only a BLOCKED goal is replanned here. PLAN_PROPOSED has its own edge, which this operation does not use.
        if (current.state != GoalState.BLOCKED) return@transaction Transition.Refused(TransitionRefusal.NO_EDGE)
        val decision = checkGoalTransition(
            current.state, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED, contextOf(current),
        )
        if (decision is TransitionDecision.Refused) return@transaction Transition.Refused(decision.reason)
        if (current.revision >= MAX_REVISIONS) return@transaction Transition.Refused(TransitionRefusal.GUARD_FAILED)
        val changed = execUpdate(
            """
            UPDATE selfdev_goals
               SET state = 'GOAL_SUBMITTED', state_reason = 'REPLAN_REQUESTED', resume_state = NULL, resume_reason = NULL,
                   version = version + 1, updated_at = ?
             WHERE goal_id = ? AND version = ? AND state = 'BLOCKED'
            """.trimIndent(),
            utcNow(clock), goalId, expectedVersion,
        )
        if (changed != 1) return@transaction Transition.StaleVersion
        Transition.Moved(goalIn(goalId) ?: error("goal vanished after replan"))
    }

    // ---- private -------------------------------------------------------------

    /** Raised inside [stopGoal] when a task changed between its read and its update. It rolls the transaction back. */
    private class TaskMovedDuringStop : RuntimeException(null, null, false, false)

    /** Locks the goal row for this transaction and returns it, or null when the goal does not exist. */
    private fun Transaction.lockGoal(goalId: String): SelfDevGoal? {
        execQuery("SELECT goal_id FROM selfdev_goals WHERE goal_id = ? FOR UPDATE", goalId) { it.getString(1) }
        return goalIn(goalId)
    }

    private fun goalIn(goalId: String): SelfDevGoal? =
        SelfDevGoalsTable.selectAll().where { SelfDevGoalsTable.goalId eq goalId }.firstOrNull()?.let { goalOf(it) }

    private fun planOf(planId: String): PlanRef? =
        SelfDevPlansTable.selectAll().where { SelfDevPlansTable.planId eq planId }.firstOrNull()?.let {
            PlanRef(
                planId = it[SelfDevPlansTable.planId],
                goalId = it[SelfDevPlansTable.goalId],
                revision = it[SelfDevPlansTable.revision],
                state = PlanState.valueOf(it[SelfDevPlansTable.state]),
            )
        }

    /** The Plan of [goalId] at [revision], when it is still PROPOSED. Null otherwise. */
    private fun proposedPlanOf(goalId: String, revision: Int): PlanRef? =
        SelfDevPlansTable.selectAll()
            .where { (SelfDevPlansTable.goalId eq goalId) and (SelfDevPlansTable.revision eq revision) }
            .firstOrNull()
            ?.let { planOf(it[SelfDevPlansTable.planId]) }
            ?.takeIf { it.state == PlanState.PROPOSED }

    /** True when [plan] is the goal's current, proposed Plan and the goal is in one of [states]. */
    private fun isCurrentProposedPlan(plan: PlanRef, goal: SelfDevGoal, states: Set<GoalState>): Boolean =
        goal.state in states && plan.state == PlanState.PROPOSED && plan.revision == goal.revision

    private fun taskOf(planId: String, ordinal: Int): SelfDevPlanTask? =
        SelfDevPlanTasksTable.selectAll()
            .where { (SelfDevPlanTasksTable.planId eq planId) and (SelfDevPlanTasksTable.ordinal eq ordinal) }
            .firstOrNull()?.let { taskOf(it) }

    private fun tasksIn(planId: String): List<SelfDevPlanTask> =
        SelfDevPlanTasksTable.selectAll().where { SelfDevPlanTasksTable.planId eq planId }
            .orderBy(SelfDevPlanTasksTable.ordinal to SortOrder.ASC)
            .map { taskOf(it) }

    /** Moves every PENDING_APPROVAL and QUEUED task of the goal's Plans to SKIPPED (GOAL_STOPPED). Inside the stop's transaction. */
    private fun Transaction.skipUnsubmittedTasks(goalId: String, now: LocalDateTime) {
        val planIds = SelfDevPlansTable.selectAll().where { SelfDevPlansTable.goalId eq goalId }.map { it[SelfDevPlansTable.planId] }
        for (planId in planIds) {
            for (task in tasksIn(planId)) {
                if (task.state != TaskState.PENDING_APPROVAL && task.state != TaskState.QUEUED) continue
                val decision = checkTaskTransition(task.state, TaskState.SKIPPED, AutopilotReason.GOAL_STOPPED)
                check(decision is TransitionDecision.Allowed) { "a task of a stopped goal is skipped by the state machine" }
                val changed = execUpdate(
                    """
                    UPDATE selfdev_plan_tasks
                       SET state = 'SKIPPED', state_reason = 'GOAL_STOPPED', version = version + 1, updated_at = ?
                     WHERE plan_id = ? AND ordinal = ? AND version = ? AND state = ?
                    """.trimIndent(),
                    now, planId, task.ordinal, task.version, task.state.name,
                )
                if (changed != 1) throw TaskMovedDuringStop()
            }
        }
    }

    private fun contextOf(goal: SelfDevGoal) = GoalContext(
        revision = goal.revision,
        blockedReason = if (goal.state == GoalState.BLOCKED) goal.stateReason else null,
        resumeState = goal.resumeState,
    )

    private data class PlanRef(val planId: String, val goalId: String, val revision: Int, val state: PlanState)

    private fun goalOf(row: ResultRow) = SelfDevGoal(
        goalId = row[SelfDevGoalsTable.goalId],
        createdBy = row[SelfDevGoalsTable.createdBy],
        idempotencyKey = row[SelfDevGoalsTable.idempotencyKey],
        title = row[SelfDevGoalsTable.title],
        body = row[SelfDevGoalsTable.body],
        targetRepo = row[SelfDevGoalsTable.targetRepo],
        state = GoalState.valueOf(row[SelfDevGoalsTable.state]),
        stateReason = row[SelfDevGoalsTable.stateReason]?.let { AutopilotReason.valueOf(it) },
        resumeState = row[SelfDevGoalsTable.resumeState]?.let { GoalState.valueOf(it) },
        resumeReason = row[SelfDevGoalsTable.resumeReason]?.let { AutopilotReason.valueOf(it) },
        revision = row[SelfDevGoalsTable.revision],
        claimToken = row[SelfDevGoalsTable.claimToken],
        claimedUntil = row[SelfDevGoalsTable.claimedUntil],
        version = row[SelfDevGoalsTable.version],
        createdAt = row[SelfDevGoalsTable.createdAt],
        updatedAt = row[SelfDevGoalsTable.updatedAt],
        approvedBy = row[SelfDevGoalsTable.approvedBy],
    )

    private fun taskOf(row: ResultRow) = SelfDevPlanTask(
        planId = row[SelfDevPlanTasksTable.planId],
        ordinal = row[SelfDevPlanTasksTable.ordinal],
        title = row[SelfDevPlanTasksTable.title],
        body = row[SelfDevPlanTasksTable.body],
        targetPaths = JSON.decodeFromString(ListSerializer(String.serializer()), row[SelfDevPlanTasksTable.targetPaths]),
        sizeEstimate = row[SelfDevPlanTasksTable.sizeEstimate],
        dependsOn = JSON.decodeFromString(ListSerializer(Int.serializer()), row[SelfDevPlanTasksTable.dependsOn]),
        requiresApproval = row[SelfDevPlanTasksTable.requiresApproval],
        state = TaskState.valueOf(row[SelfDevPlanTasksTable.state]),
        stateReason = row[SelfDevPlanTasksTable.stateReason]?.let { AutopilotReason.valueOf(it) },
        idempotencyKey = row[SelfDevPlanTasksTable.idempotencyKey],
        runId = row[SelfDevPlanTasksTable.runId],
        attempts = row[SelfDevPlanTasksTable.attempts],
        version = row[SelfDevPlanTasksTable.version],
        createdAt = row[SelfDevPlanTasksTable.createdAt],
        updatedAt = row[SelfDevPlanTasksTable.updatedAt],
        approvedBy = row[SelfDevPlanTasksTable.approvedBy],
    )

    private companion object {
        val JSON = Json
        /** Goal states in which an approved Plan's task may be approved (the Plan has been approved, see [approvePlan]). */
        val APPROVABLE_TASK_GOAL_STATES = setOf(GoalState.TASKS_QUEUED, GoalState.RUNNING, GoalState.BLOCKED, GoalState.PAUSED)
        /** Goal states in which a task may be rejected: before and after the Plan is approved, while the goal is not finished. */
        val REJECTABLE_GOAL_STATES = APPROVABLE_TASK_GOAL_STATES + GoalState.PLAN_PROPOSED
    }
}
