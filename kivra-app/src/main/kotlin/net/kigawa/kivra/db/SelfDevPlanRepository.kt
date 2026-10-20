package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.GoalContext
import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.PlanState
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionDecision
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.core.autopilot.checkGoalTransition
import net.kigawa.kivra.core.autopilot.checkTaskTransition
import net.kigawa.kivra.selfdev.PromotionCounts
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Plans and their tasks (V18, ADR 0011 sections 2.2, 2.3, 2.5). Plans are written only by [SelfDevGoalRepository.commitPlan], in the
 * same transaction as the goal change. This repository reads them and moves tasks, one conditional UPDATE per change (CAS on version
 * and state).
 *
 * A task is a projection of its Run (ADR 0001). This repository never writes the Run. It records the run id that the dispatcher
 * got back, and the Run's outcome is passed in by the caller after it has been read from the Run.
 */

/** Exposed mirror of V18 `selfdev_plans`. */
internal object SelfDevPlansTable : Table("selfdev_plans") {
    val planId = char("plan_id", 26)
    val goalId = char("goal_id", 26)
    val revision = integer("revision")
    val state = varchar("state", 16)
    val summary = varchar("summary", 500).nullable()
    val plannerModel = varchar("planner_model", 64)
    val outputSha256 = char("output_sha256", 64)
    val requestLedgerId = varchar("request_ledger_id", 64).nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(planId)
}

/** Exposed mirror of V18 `selfdev_plan_tasks`. */
internal object SelfDevPlanTasksTable : Table("selfdev_plan_tasks") {
    val planId = char("plan_id", 26)
    val ordinal = integer("ordinal")
    val title = varchar("title", 200)
    val body = text("body").nullable()
    val targetPaths = text("target_paths")
    val sizeEstimate = varchar("size_estimate", 2)
    val dependsOn = text("depends_on")
    val requiresApproval = bool("requires_approval")
    val state = varchar("state", 24)
    val stateReason = varchar("state_reason", 64).nullable()
    val idempotencyKey = varchar("idempotency_key", 128)
    val runId = char("run_id", 26).nullable()
    val attempts = integer("attempts").default(0)
    val version = long("version").default(0)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val approvedBy = varchar("approved_by", 255).nullable()
    override val primaryKey = PrimaryKey(planId, ordinal)
}

data class SelfDevPlan(
    val planId: String,
    val goalId: String,
    val revision: Int,
    val state: PlanState,
    val summary: String?,
    val plannerModel: String,
    val outputSha256: String,
    val requestLedgerId: String?,
    val createdAt: LocalDateTime,
)

data class SelfDevPlanTask(
    val planId: String,
    val ordinal: Int,
    val title: String,
    val body: String?,
    val targetPaths: List<String>,
    val sizeEstimate: String,
    val dependsOn: List<Int>,
    val requiresApproval: Boolean,
    val state: TaskState,
    val stateReason: AutopilotReason?,
    val idempotencyKey: String,
    val runId: String?,
    val attempts: Int,
    val version: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    /** The operator who approved this task (`approved_by`, V19). Null for a task approved by the autopilot or not yet approved. */
    val approvedBy: String? = null,
)

/**
 * A task of a Plan before it is stored. [dependsOn] holds ordinals (1-based) that must each be smaller than the task's own ordinal.
 * [initialState] is PENDING_APPROVAL, or QUEUED for a task the autopilot approval setting lets through (ADR 0011 section 6.1). A task
 * with [requiresApproval] is never QUEUED.
 */
data class NewTask(
    val title: String,
    val body: String?,
    val targetPaths: List<String>,
    val sizeEstimate: String,
    val dependsOn: List<Int>,
    val requiresApproval: Boolean,
    val initialState: TaskState = TaskState.PENDING_APPROVAL,
)

/** The outcome of [SelfDevPlanRepository.startSubmission]. */
sealed interface SubmissionStart {
    data class Moved(val task: SelfDevPlanTask) : SubmissionStart
    /** The goal is not TASKS_QUEUED or RUNNING under the lock (for example STOPPED or PAUSED). Nothing changed. */
    data object GoalNotDispatchable : SubmissionStart
    /** The task did not move (stale version, no edge, dependency not COMPLETED, not found). Nothing changed. */
    data class Rejected(val result: Transition<SelfDevPlanTask>) : SubmissionStart
}

class SelfDevPlanRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun plansOf(goalId: String): List<SelfDevPlan> = transaction(database) {
        SelfDevPlansTable.selectAll().where { SelfDevPlansTable.goalId eq goalId }
            .orderBy(SelfDevPlansTable.revision to org.jetbrains.exposed.sql.SortOrder.ASC)
            .map { it.toPlan() }
    }

    fun tasksOf(planId: String): List<SelfDevPlanTask> = transaction(database) { tasksIn(planId) }

    fun task(planId: String, ordinal: Int): SelfDevPlanTask? = transaction(database) { taskIn(planId, ordinal) }

    /**
     * The counts for the stage 1 -> 2 readout (ADR 0011 section 6.3). SELECT only: it reads the task rows and the Runs they point to.
     * "Approved" means `approved_by` is set (the operator's approval, V19). A POLICY_REJECTED is counted when the task row or its Run says so.
     */
    fun promotionCounts(): PromotionCounts = transaction(database) {
        PromotionCounts(
            approvedCompleted = execQuery(
                "SELECT COUNT(*) FROM selfdev_plan_tasks WHERE approved_by IS NOT NULL AND state = ?",
                TaskState.COMPLETED.name,
            ) { it.getLong(1) }.single(),
            approvedPolicyRejected = execQuery(
                """
                SELECT COUNT(*) FROM selfdev_plan_tasks t
                WHERE t.approved_by IS NOT NULL
                  AND (t.state_reason = ? OR EXISTS (SELECT 1 FROM development_runs r WHERE r.run_id = t.run_id AND r.state_reason = ?))
                """.trimIndent(),
                AutopilotReason.POLICY_REJECTED.name,
                AutopilotReason.POLICY_REJECTED.name,
            ) { it.getLong(1) }.single(),
            scopeExceeded = execQuery(
                "SELECT COUNT(*) FROM selfdev_plan_tasks WHERE state_reason = ?",
                AutopilotReason.AUTO_SCOPE_EXCEEDED.name,
            ) { it.getLong(1) }.single(),
        )
    }

    /**
     * Moves one task. The state machine decides whether the edge exists and the reason is allowed. The row must still be at
     * [expectedVersion] and in the state it was read in. [runId] is required for SUBMITTING -> SUBMITTED and kept otherwise.
     *
     * QUEUED -> SUBMITTING also requires every dependency to be COMPLETED (ADR section 5.1): the dependency is "merged", not
     * "PR created".
     */
    fun transitionTask(
        planId: String,
        ordinal: Int,
        expectedVersion: Long,
        to: TaskState,
        reason: AutopilotReason?,
        runId: String? = null,
    ): Transition<SelfDevPlanTask> = transaction(database) { transitionTaskIn(planId, ordinal, expectedVersion, to, reason, runId) }

    /**
     * The task that the autopilot put on [runId] without an operator's approval (ADR 0011 section 6.2): `approved_by` is null, the task is
     * not denylisted, and it is either SUBMITTED, or already moved back by an earlier scope check (PENDING_APPROVAL with
     * AUTO_SCOPE_EXCEEDED, not yet approved again). Null for every other Run. An operator-approved task is never returned.
     */
    fun autoTaskOfRun(runId: String): SelfDevPlanTask? = transaction(database) { autoTaskOfRunIn(runId) }

    /**
     * Moves the auto-approved task of [runId] from SUBMITTED to PENDING_APPROVAL with AUTO_SCOPE_EXCEEDED, and a RUNNING or TASKS_QUEUED
     * goal to BLOCKED with the same reason, in one transaction. The goal row is locked first, as the dispatcher's submission does. Returns
     * true when the task is in that state afterwards (including when an earlier call already moved it), false when there is no such task
     * or it changed under this call. The Run itself is not written: the caller ends it.
     */
    fun settleAutoScopeExceeded(runId: String): Boolean = transaction(database) {
        val task = autoTaskOfRunIn(runId) ?: return@transaction false
        if (task.state == TaskState.PENDING_APPROVAL) return@transaction true
        val goalId = SelfDevPlansTable.selectAll().where { SelfDevPlansTable.planId eq task.planId }
            .firstOrNull()?.get(SelfDevPlansTable.goalId) ?: return@transaction false
        val goalRow = SelfDevGoalsTable.selectAll().where { SelfDevGoalsTable.goalId eq goalId }
            .forUpdate()
            .firstOrNull() ?: return@transaction false
        val goalState = GoalState.valueOf(goalRow[SelfDevGoalsTable.state])
        val moved = transitionTaskIn(task.planId, task.ordinal, task.version, TaskState.PENDING_APPROVAL, AutopilotReason.AUTO_SCOPE_EXCEEDED, null)
        if (moved !is Transition.Moved) return@transaction false
        if (goalState == GoalState.RUNNING || goalState == GoalState.TASKS_QUEUED) {
            val decision = checkGoalTransition(goalState, GoalState.BLOCKED, AutopilotReason.AUTO_SCOPE_EXCEEDED, GoalContext())
            if (decision is TransitionDecision.Refused) return@transaction false
            val changed = execUpdate(
                """
                UPDATE selfdev_goals
                   SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
                 WHERE goal_id = ? AND version = ? AND state = ?
                """.trimIndent(),
                GoalState.BLOCKED.name, AutopilotReason.AUTO_SCOPE_EXCEEDED.name, utcNow(clock),
                goalId, goalRow[SelfDevGoalsTable.version], goalState.name,
            )
            if (changed != 1) return@transaction false
        }
        true
    }

    private fun Transaction.autoTaskOfRunIn(runId: String): SelfDevPlanTask? {
        val submitted = SelfDevPlanTasksTable.state eq TaskState.SUBMITTED.name
        val settled = (SelfDevPlanTasksTable.state eq TaskState.PENDING_APPROVAL.name) and
            (SelfDevPlanTasksTable.stateReason eq AutopilotReason.AUTO_SCOPE_EXCEEDED.name)
        return SelfDevPlanTasksTable.selectAll().where {
            (SelfDevPlanTasksTable.runId eq runId) and
                SelfDevPlanTasksTable.approvedBy.isNull() and
                (SelfDevPlanTasksTable.requiresApproval eq false) and
                (submitted or settled)
        }.firstOrNull()?.toTask()
    }

    /**
     * QUEUED -> SUBMITTING for a task of [goalId]. The goal row is locked (the operator's stop takes the same lock), and the goal must
     * still be TASKS_QUEUED or RUNNING under that lock, so a goal stopped or paused just before this point gets no Run (ADR 0011 section 5.1).
     */
    fun startSubmission(goalId: String, planId: String, ordinal: Int, expectedVersion: Long): SubmissionStart = transaction(database) {
        if (!goalDispatchableLocked(goalId)) return@transaction SubmissionStart.GoalNotDispatchable
        when (val r = transitionTaskIn(planId, ordinal, expectedVersion, TaskState.SUBMITTING, null, null)) {
            is Transition.Moved -> SubmissionStart.Moved(r.row)
            else -> SubmissionStart.Rejected(r)
        }
    }

    /**
     * The same goal check as [startSubmission], for a task already SUBMITTING (its Run is created with the same key). It takes the lock
     * for a moment only, so a stop that lands after the check is not seen: the window is the same as for a Run already being created.
     */
    fun isGoalDispatchable(goalId: String): Boolean = transaction(database) { goalDispatchableLocked(goalId) }

    private fun Transaction.goalDispatchableLocked(goalId: String): Boolean {
        val state = SelfDevGoalsTable.selectAll().where { SelfDevGoalsTable.goalId eq goalId }.forUpdate().firstOrNull()
            ?.let { GoalState.valueOf(it[SelfDevGoalsTable.state]) }
        return state == GoalState.TASKS_QUEUED || state == GoalState.RUNNING
    }

    private fun Transaction.transitionTaskIn(
        planId: String,
        ordinal: Int,
        expectedVersion: Long,
        to: TaskState,
        reason: AutopilotReason?,
        runId: String?,
    ): Transition<SelfDevPlanTask> {
        val current = taskIn(planId, ordinal) ?: return Transition.NotFound
        if (current.version != expectedVersion) return Transition.StaleVersion
        val decision = checkTaskTransition(current.state, to, reason)
        if (decision is TransitionDecision.Refused) return Transition.Refused(decision.reason)
        if (to == TaskState.SUBMITTING && !dependenciesCompleted(current)) {
            return Transition.Refused(TransitionRefusal.GUARD_FAILED)
        }
        require((to == TaskState.SUBMITTED) == (runId != null)) { "a run id is given exactly when the task is SUBMITTED" }
        val changed = execUpdate(
            """
            UPDATE selfdev_plan_tasks
               SET state = ?, state_reason = ?, run_id = ?, attempts = attempts + ?, version = version + 1, updated_at = ?
             WHERE plan_id = ? AND ordinal = ? AND version = ? AND state = ?
            """.trimIndent(),
            to.name,
            reason?.name,
            runId ?: current.runId,
            if (to == TaskState.SUBMITTING) 1 else 0,
            utcNow(clock),
            planId,
            ordinal,
            expectedVersion,
            current.state.name,
        )
        if (changed != 1) return Transition.StaleVersion
        return Transition.Moved(taskIn(planId, ordinal) ?: error("task vanished after transition"))
    }

    /**
     * Inserts a Plan and its tasks inside the caller's transaction. Each task starts in its [NewTask.initialState]. The values are validated here,
     * not trusted from the caller: the Planner's output is checked before this point, and this is the last check before storage.
     */
    internal fun insertPlan(
        tx: Transaction,
        goalId: String,
        revision: Int,
        plan: NewPlan,
        tasks: List<NewTask>,
        now: LocalDateTime,
    ): Pair<SelfDevPlan, List<SelfDevPlanTask>> = with(tx) {
        require(plan.summary == null || plan.summary.codePointCount(0, plan.summary.length) <= 500) { "summary must be at most 500 characters" }
        require(plan.plannerModel.isNotEmpty() && plan.plannerModel.length <= 64) { "planner model must be 1 to 64 characters" }
        require(SHA256_HEX.matches(plan.outputSha256)) { "output digest must be a lowercase SHA-256 hex" }
        require(plan.requestLedgerId == null || plan.requestLedgerId.length <= 64) { "request ledger id must be at most 64 characters" }
        require(tasks.isNotEmpty()) { "a plan has at least one task" }

        val planId = Ulid.generate(clock)
        SelfDevPlansTable.insert {
            it[SelfDevPlansTable.planId] = planId
            it[SelfDevPlansTable.goalId] = goalId
            it[SelfDevPlansTable.revision] = revision
            it[SelfDevPlansTable.state] = PlanState.PROPOSED.name
            it[SelfDevPlansTable.summary] = plan.summary
            it[SelfDevPlansTable.plannerModel] = plan.plannerModel
            it[SelfDevPlansTable.outputSha256] = plan.outputSha256
            it[SelfDevPlansTable.requestLedgerId] = plan.requestLedgerId
            it[SelfDevPlansTable.createdAt] = now
        }
        val stored = mutableListOf<SelfDevPlanTask>()
        tasks.forEachIndexed { index, task ->
            val ordinal = index + 1
            val body = task.body?.takeIf { it.isNotEmpty() }
            require(task.title.isNotEmpty() && task.title.codePointCount(0, task.title.length) <= 200) { "task title must be 1 to 200 characters" }
            require(body == null || body.codePointCount(0, body.length) <= 8000) { "task body must be at most 8000 characters" }
            require(task.targetPaths.size <= 10 && task.targetPaths.all { it.isNotEmpty() }) { "a task has at most 10 non-empty target paths" }
            require(task.sizeEstimate in SIZES) { "size estimate must be S, M or L" }
            require(task.dependsOn.all { it in 1 until ordinal }) { "a dependency must be an earlier ordinal" }
            require(task.initialState == TaskState.PENDING_APPROVAL || task.initialState == TaskState.QUEUED) {
                "a new task starts PENDING_APPROVAL or QUEUED"
            }
            require(task.initialState == TaskState.PENDING_APPROVAL || !task.requiresApproval) {
                "a task that requires approval starts PENDING_APPROVAL"
            }
            SelfDevPlanTasksTable.insert {
                it[SelfDevPlanTasksTable.planId] = planId
                it[SelfDevPlanTasksTable.ordinal] = ordinal
                it[SelfDevPlanTasksTable.title] = task.title
                it[SelfDevPlanTasksTable.body] = body
                it[SelfDevPlanTasksTable.targetPaths] = JSON.encodeToString(ListSerializer(String.serializer()), task.targetPaths)
                it[SelfDevPlanTasksTable.sizeEstimate] = task.sizeEstimate
                it[SelfDevPlanTasksTable.dependsOn] = JSON.encodeToString(ListSerializer(Int.serializer()), task.dependsOn)
                it[SelfDevPlanTasksTable.requiresApproval] = task.requiresApproval
                it[SelfDevPlanTasksTable.state] = task.initialState.name
                it[SelfDevPlanTasksTable.idempotencyKey] = "ap:$planId:$ordinal"
                it[SelfDevPlanTasksTable.attempts] = 0
                it[SelfDevPlanTasksTable.version] = 0
                it[SelfDevPlanTasksTable.createdAt] = now
                it[SelfDevPlanTasksTable.updatedAt] = now
            }
            stored += SelfDevPlanTask(
                planId = planId, ordinal = ordinal, title = task.title, body = body, targetPaths = task.targetPaths,
                sizeEstimate = task.sizeEstimate, dependsOn = task.dependsOn, requiresApproval = task.requiresApproval,
                state = task.initialState, stateReason = null, idempotencyKey = "ap:$planId:$ordinal", runId = null,
                attempts = 0, version = 0, createdAt = now, updatedAt = now,
            )
        }
        val storedPlan = SelfDevPlan(planId, goalId, revision, PlanState.PROPOSED, plan.summary, plan.plannerModel, plan.outputSha256, plan.requestLedgerId, now)
        storedPlan to stored
    }

    private fun dependenciesCompleted(task: SelfDevPlanTask): Boolean {
        if (task.dependsOn.isEmpty()) return true
        val byOrdinal = tasksIn(task.planId).associateBy { it.ordinal }
        return task.dependsOn.all { byOrdinal[it]?.state == TaskState.COMPLETED }
    }

    private fun tasksIn(planId: String): List<SelfDevPlanTask> =
        SelfDevPlanTasksTable.selectAll().where { SelfDevPlanTasksTable.planId eq planId }
            .orderBy(SelfDevPlanTasksTable.ordinal to org.jetbrains.exposed.sql.SortOrder.ASC)
            .map { it.toTask() }

    private fun taskIn(planId: String, ordinal: Int): SelfDevPlanTask? =
        SelfDevPlanTasksTable.selectAll()
            .where { (SelfDevPlanTasksTable.planId eq planId) and (SelfDevPlanTasksTable.ordinal eq ordinal) }
            .firstOrNull()?.toTask()

    private fun ResultRow.toPlan() = SelfDevPlan(
        planId = this[SelfDevPlansTable.planId],
        goalId = this[SelfDevPlansTable.goalId],
        revision = this[SelfDevPlansTable.revision],
        state = PlanState.valueOf(this[SelfDevPlansTable.state]),
        summary = this[SelfDevPlansTable.summary],
        plannerModel = this[SelfDevPlansTable.plannerModel],
        outputSha256 = this[SelfDevPlansTable.outputSha256],
        requestLedgerId = this[SelfDevPlansTable.requestLedgerId],
        createdAt = this[SelfDevPlansTable.createdAt],
    )

    private fun ResultRow.toTask() = SelfDevPlanTask(
        planId = this[SelfDevPlanTasksTable.planId],
        ordinal = this[SelfDevPlanTasksTable.ordinal],
        title = this[SelfDevPlanTasksTable.title],
        body = this[SelfDevPlanTasksTable.body],
        targetPaths = JSON.decodeFromString(ListSerializer(String.serializer()), this[SelfDevPlanTasksTable.targetPaths]),
        sizeEstimate = this[SelfDevPlanTasksTable.sizeEstimate],
        dependsOn = JSON.decodeFromString(ListSerializer(Int.serializer()), this[SelfDevPlanTasksTable.dependsOn]),
        requiresApproval = this[SelfDevPlanTasksTable.requiresApproval],
        state = TaskState.valueOf(this[SelfDevPlanTasksTable.state]),
        stateReason = this[SelfDevPlanTasksTable.stateReason]?.let { AutopilotReason.valueOf(it) },
        idempotencyKey = this[SelfDevPlanTasksTable.idempotencyKey],
        runId = this[SelfDevPlanTasksTable.runId],
        attempts = this[SelfDevPlanTasksTable.attempts],
        version = this[SelfDevPlanTasksTable.version],
        createdAt = this[SelfDevPlanTasksTable.createdAt],
        updatedAt = this[SelfDevPlanTasksTable.updatedAt],
        approvedBy = this[SelfDevPlanTasksTable.approvedBy],
    )

    private companion object {
        val JSON = Json
        val SHA256_HEX = Regex("[0-9a-f]{64}")
        val SIZES = setOf("S", "M", "L")
    }
}
