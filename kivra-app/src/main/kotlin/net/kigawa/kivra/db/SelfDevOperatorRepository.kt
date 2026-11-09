package net.kigawa.kivra.db

import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.DISPATCH_BLOCK_REASONS
import net.kigawa.kivra.core.autopilot.MAX_REVISIONS
import net.kigawa.kivra.core.autopilot.PLAN_CHILD_END_STATES
import net.kigawa.kivra.core.autopilot.TaskContext
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionDecision
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.core.autopilot.checkTaskTransition
import net.kigawa.kivra.selfdev.NewTask
import net.kigawa.kivra.selfdev.autofix.INCIDENT_FIX_PRINCIPAL
import net.kigawa.kivra.selfdev.PromotionCounts
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class SelfDevTask(
    val taskId: String, val createdBy: String, val idempotencyKey: String,
    val title: String, val body: String?, val targetRepo: String,
    val state: TaskState, val stateReason: AutopilotReason?,
    val resumeState: TaskState?, val resumeReason: AutopilotReason?,
    val revision: Int, val claimToken: String?, val claimedUntil: LocalDateTime?,
    val dependencies: List<String>, val requiresApproval: Boolean,
    val runId: String?, val attempts: Int, val version: Long,
    val createdAt: LocalDateTime, val updatedAt: LocalDateTime, val approvedBy: String?,
    /** The plan root this task was planned under (V44), or null for a task the operator submitted. */
    val parentTaskId: String? = null,
    /** The project the task belongs to (V50, ADR 0022), or null when it is not assigned. */
    val projectId: String? = null,
) {
    /** A plan root has been planned at least once. Its own run is never submitted; its planned tasks carry the work. */
    val isPlanRoot: Boolean get() = revision > 0
}

/** The claim a Planner holds on one task: the fence its commit must match (ADR 0011 section 2.7). */
data class PlanFence(val taskId: String, val version: Long, val claimToken: String)

/** A claim that was granted: the fence and the task's text that the prompt needs. */
data class PlanClaim(val fence: PlanFence, val title: String, val body: String?)

/** The result of a Planner commit. [Stale] means the fence no longer matched; nothing was written. */
sealed interface PlanCommit {
    data class Committed(val taskCount: Int, val queuedCount: Int, val revision: Int) : PlanCommit
    data object Stale : PlanCommit
}

/** A submitted task whose Run exists, read by the dispatcher for the Run's outcome. */
data class SubmittedRun(val taskId: String, val runId: String)

sealed interface TaskTransition {
    data class Moved(val row: SelfDevTask) : TaskTransition
    data object NotFound : TaskTransition
    data object StaleVersion : TaskTransition
    data class Refused(val reason: TransitionRefusal) : TaskTransition
}

sealed interface SelfDevSubmitResult {
    data class Created(val task: SelfDevTask) : SelfDevSubmitResult
    data class Replayed(val task: SelfDevTask) : SelfDevSubmitResult
    /** The idempotency key was used before with another title, body or target. */
    data object KeyConflict : SelfDevSubmitResult
}

/**
 * True when every dependency has the task's own project (ADR 0022). A dependency in another project, or an unassigned one while the task is
 * assigned (or the reverse), is out of scope. Two unassigned tasks are in scope, as before V50.
 */
internal fun dependenciesInScope(taskProjectId: String?, dependencyProjectIds: List<String?>): Boolean =
    dependencyProjectIds.all { it == taskProjectId }

class SelfDevOperatorRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {
    /** The newest-updated tasks, narrowed to [project] (ADR 0022 PR-D). The autonomous loops call the unnarrowed form. */
    fun listTasks(limit: Int, project: ProjectFilter = ProjectFilter.All): List<SelfDevTask> = transaction(database) {
        SelfDevTasksTable.selectAll()
            .where { project.condition(SelfDevTasksTable.projectId) ?: Op.TRUE }
            .orderBy(SelfDevTasksTable.updatedAt to SortOrder.DESC, SelfDevTasksTable.taskId to SortOrder.DESC)
            .limit(limit).map { taskOf(it) }
    }
    fun task(taskId: String): SelfDevTask? = transaction(database) { taskIn(taskId) }

    /**
     * Accepts a task from the operator (V40 `selfdev_tasks`, state TASK_SUBMITTED). The pair (created_by, idempotency_key) is unique: the
     * same key with the same title, body, target and project is a replay, and with other content it is a conflict. Nothing is planned here.
     * [projectId] is the project the task belongs to (V50, ADR 0022). The API requires it; the system's own submissions pass null.
     */
    fun submitTask(
        createdBy: String,
        idempotencyKey: String,
        title: String,
        body: String?,
        targetRepo: String,
        projectId: String? = null,
    ): SelfDevSubmitResult {
        val created = try {
            transaction(database) {
                val now = utcNow(clock)
                val taskId = Ulid.generate(clock)
                // ADR 0022 PR-B: a task the system files takes the project of its repository (project_repos), else none.
                // ADR 0022 PR-F: a task without a project is never written (V52 makes the column NOT NULL). Nothing is inserted then.
                val project = (projectId ?: if (createdBy in SYSTEM_TASK_PRINCIPALS) projectIdOfRepo(targetRepo) else null)
                    ?: throw ProjectRequiredException("a task needs a project (ADR 0022)")
                execUpdate(
                    "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, body, target_repo, state, dependencies, requires_approval, revision, attempts, version, created_at, updated_at, project_id) VALUES (?, ?, ?, ?, ?, ?, 'TASK_SUBMITTED', '[]', TRUE, 0, 0, 0, ?, ?, ?)",
                    taskId, createdBy, idempotencyKey, title, body, targetRepo, now, now, project,
                )
                taskIn(taskId) ?: error("vanished")
            }
        } catch (e: Exception) {
            if (!e.isDuplicateKey()) throw e
            null
        }
        if (created != null) return SelfDevSubmitResult.Created(created)
        // The key was used before (or a concurrent request took it): compare with the stored task.
        val existing = transaction(database) {
            SelfDevTasksTable.selectAll()
                .where { (SelfDevTasksTable.createdBy eq createdBy) and (SelfDevTasksTable.idempotencyKey eq idempotencyKey) }
                .firstOrNull()?.let { taskOf(it) }
        } ?: error("the duplicate key has no row")
        // A system task without a project in the request takes the one its repository has now: the replay does not compare it.
        val sameProject = existing.projectId == projectId || (projectId == null && createdBy in SYSTEM_TASK_PRINCIPALS)
        return if (existing.title == title && existing.body == body && existing.targetRepo == targetRepo && sameProject) {
            SelfDevSubmitResult.Replayed(existing)
        } else {
            SelfDevSubmitResult.KeyConflict
        }
    }

    /** The counts of the stage 1 -> 2 readout, over all of history. Read only. */
    fun promotionCounts(): PromotionCounts = transaction(database) {
        PromotionCounts(
            approvedCompleted = countWhere("approved_by IS NOT NULL AND state = 'COMPLETED'"),
            approvedPolicyRejected = countWhere("approved_by IS NOT NULL AND state_reason = 'POLICY_REJECTED'"),
            scopeExceeded = countWhere("state_reason = 'AUTO_SCOPE_EXCEEDED'"),
        )
    }
    private fun Transaction.countWhere(predicate: String): Long =
        execQuery("SELECT COUNT(*) FROM selfdev_tasks WHERE $predicate") { it.getLong(1) }.single()

    fun approveTask(taskId: String, expectedVersion: Long, approver: String): TaskTransition = transaction(database) {
        val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        // A plan root is not run itself: its planned tasks are approved one by one (ADR 0011 section 2.9).
        if (current.isPlanRoot) return@transaction TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
        val decision = checkTaskTransition(current.state, TaskState.QUEUED, AutopilotReason.OPERATOR_APPROVED, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
        val now = utcNow(clock)
        val key = if (current.runId == null) current.idempotencyKey else "ap:${current.taskId}:a${current.attempts}"
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'QUEUED', state_reason = 'OPERATOR_APPROVED', approved_by = ?, idempotency_key = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PENDING_APPROVAL'",
            approver, key, now, taskId, expectedVersion)
        if (changed != 1) return@transaction TaskTransition.StaleVersion
        TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    fun skipTask(taskId: String, expectedVersion: Long): TaskTransition = transaction(database) {
        val current = lockWithParent(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        if (current.isPlanRoot) return@transaction TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
        val decision = checkTaskTransition(current.state, TaskState.SKIPPED, AutopilotReason.OPERATOR_SKIPPED, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
        val now = utcNow(clock)
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'SKIPPED', state_reason = 'OPERATOR_SKIPPED', version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PENDING_APPROVAL'",
            now, taskId, expectedVersion)
        if (changed != 1) return@transaction TaskTransition.StaleVersion
        settlePlanOf(current.parentTaskId, now)
        TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    fun stopTask(taskId: String, expectedVersion: Long): TaskTransition = try {
        transaction(database) {
            val current = lockWithParent(taskId) ?: return@transaction TaskTransition.NotFound
            if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
            val decision = checkTaskTransition(current.state, TaskState.STOPPED, AutopilotReason.OPERATOR_STOPPED, contextOf(current))
            if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
            val now = utcNow(clock)
            val changed = execUpdate(
                "UPDATE selfdev_tasks SET state = 'STOPPED', state_reason = 'OPERATOR_STOPPED', resume_state = NULL, resume_reason = NULL, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ?",
                now, taskId, expectedVersion)
            if (changed != 1) return@transaction TaskTransition.StaleVersion
            skipUnsubmittedTasks(taskId, now)
            settlePlanOf(current.parentTaskId, now)
            TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
        }
    } catch (_: TaskMovedDuringStop) { TaskTransition.StaleVersion }

    fun replanBlocked(taskId: String, expectedVersion: Long): TaskTransition = transaction(database) {
        val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        if (current.state != TaskState.BLOCKED) return@transaction TaskTransition.Refused(TransitionRefusal.NO_EDGE)
        val decision = checkTaskTransition(current.state, TaskState.TASK_SUBMITTED, AutopilotReason.REPLAN_REQUESTED, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
        if (current.revision >= MAX_REVISIONS) return@transaction TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'TASK_SUBMITTED', state_reason = 'REPLAN_REQUESTED', resume_state = NULL, resume_reason = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'BLOCKED'",
            utcNow(clock), taskId, expectedVersion)
        if (changed != 1) return@transaction TaskTransition.StaleVersion
        TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    // ---- Planner (ADR 0011 section 3). The model call is never inside these transactions. ----

    /**
     * Claims a TASK_SUBMITTED task for planning: TASK_SUBMITTED -> PLANNING with [claimToken] and the claim's expiry. Returns null when the
     * task is not claimable (missing, not submitted, or at the revision cap). The version rises, so an older claim cannot commit.
     */
    fun claimForPlanning(taskId: String, claimToken: String, claimTtl: Duration): PlanClaim? = transaction(database) {
        val current = lockTask(taskId) ?: return@transaction null
        if (current.state != TaskState.TASK_SUBMITTED || current.revision >= MAX_REVISIONS) return@transaction null
        if (checkTaskTransition(current.state, TaskState.PLANNING, null, contextOf(current)) is TransitionDecision.Refused) return@transaction null
        val now = utcNow(clock)
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'PLANNING', state_reason = NULL, claim_token = ?, claimed_until = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'TASK_SUBMITTED'",
            claimToken, now.plus(claimTtl), now, taskId, current.version,
        )
        if (changed != 1) return@transaction null
        PlanClaim(PlanFence(taskId, current.version + 1, claimToken), current.title, current.body)
    }

    /** The TASK_SUBMITTED plan roots, oldest first: the Planner's queue. Planned tasks are never TASK_SUBMITTED. */
    fun submittedTaskIds(limit: Int): List<String> = idsWhere("state = 'TASK_SUBMITTED' AND parent_task_id IS NULL", limit)

    /** The PLANNING tasks whose claim time is over, moved back to TASK_SUBMITTED (CLAIM_EXPIRED). Returns the ids moved. */
    fun expireStalePlanClaims(limit: Int): List<String> {
        val now = utcNow(clock)
        val stale = transaction(database) {
            execQuery(
                "SELECT task_id FROM selfdev_tasks WHERE state = 'PLANNING' AND claimed_until < ? ORDER BY updated_at, task_id LIMIT ?",
                now, limit,
            ) { it.getString(1) }
        }
        return stale.filter { id -> transaction(database) { expireClaimOf(id, now) } }
    }
    private fun Transaction.expireClaimOf(taskId: String, now: LocalDateTime): Boolean {
        val current = lockTask(taskId) ?: return false
        val until = current.claimedUntil ?: return false
        if (current.state != TaskState.PLANNING || !until.isBefore(now)) return false
        if (checkTaskTransition(current.state, TaskState.TASK_SUBMITTED, AutopilotReason.CLAIM_EXPIRED, contextOf(current)) is TransitionDecision.Refused) return false
        return execUpdate(
            "UPDATE selfdev_tasks SET state = 'TASK_SUBMITTED', state_reason = 'CLAIM_EXPIRED', claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PLANNING'",
            now, taskId, current.version,
        ) == 1
    }

    /**
     * Writes the plan of a claimed task in one transaction (ADR 0011 sections 2.6 and 5.1). Each planned task becomes a row whose
     * parent_task_id is the root, in PENDING_APPROVAL, or in QUEUED when the stage rules approved it (AUTO_APPROVED). The root moves
     * PLANNING -> PENDING_APPROVAL and its revision rises. A fence that no longer matches writes nothing and returns [PlanCommit.Stale].
     */
    fun commitPlan(fence: PlanFence, tasks: List<NewTask>): PlanCommit = try {
        transaction(database) {
            val current = lockTask(fence.taskId) ?: return@transaction PlanCommit.Stale
            if (!fenceMatches(current, fence)) return@transaction PlanCommit.Stale
            check(checkTaskTransition(TaskState.PLANNING, TaskState.PENDING_APPROVAL, null, contextOf(current)) is TransitionDecision.Allowed)
            // ADR 0022 PR-F: the planned tasks take the root's project. A root without one would give NULL rows, so nothing is written.
            val project = current.projectId ?: throw ProjectRequiredException("a plan's root needs a project (ADR 0022)")
            val revision = current.revision + 1
            val now = utcNow(clock)
            val ids = tasks.map { Ulid.generate(clock) }
            tasks.forEachIndexed { index, plan ->
                val queued = plan.initialState == TaskState.QUEUED
                check(checkTaskTransition(null, TaskState.TASK_SUBMITTED, null, TaskContext()) is TransitionDecision.Allowed)
                check(queued || plan.initialState == TaskState.PENDING_APPROVAL) { "a planned task starts in PENDING_APPROVAL or QUEUED" }
                if (queued) {
                    check(checkTaskTransition(TaskState.PENDING_APPROVAL, TaskState.QUEUED, AutopilotReason.AUTO_APPROVED, TaskContext()) is TransitionDecision.Allowed)
                }
                val dependencies = plan.dependsOn.map { ids[it - 1] }
                execUpdate(
                    // A planned task inherits the root's project (ADR 0022). Its dependencies are planned tasks of the same root, so they share it.
                    "INSERT INTO selfdev_tasks (task_id, created_by, idempotency_key, title, body, target_repo, state, state_reason, dependencies, requires_approval, parent_task_id, revision, attempts, version, created_at, updated_at, project_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?)",
                    ids[index], current.createdBy, "plan:${current.taskId}:r$revision:${index + 1}", plan.title, plan.body, current.targetRepo,
                    plan.initialState.name, if (queued) AutopilotReason.AUTO_APPROVED.name else null,
                    JSON.encodeToString(ListSerializer(String.serializer()), dependencies), plan.requiresApproval, current.taskId, now, now,
                    project,
                )
            }
            val changed = execUpdate(
                "UPDATE selfdev_tasks SET state = 'PENDING_APPROVAL', state_reason = NULL, revision = ?, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PLANNING' AND claim_token = ?",
                revision, now, fence.taskId, fence.version, fence.claimToken,
            )
            if (changed != 1) throw PlanMovedDuringCommit()
            PlanCommit.Committed(tasks.size, tasks.count { it.initialState == TaskState.QUEUED }, revision)
        }
    } catch (_: PlanMovedDuringCommit) {
        PlanCommit.Stale
    }

    /** Blocks a claimed task with a planning-stage [reason] (PLANNING -> BLOCKED), fenced by the claim. False when the fence is stale. */
    fun blockPlanning(fence: PlanFence, reason: AutopilotReason): Boolean = transaction(database) {
        val current = lockTask(fence.taskId) ?: return@transaction false
        if (!fenceMatches(current, fence)) return@transaction false
        if (checkTaskTransition(current.state, TaskState.BLOCKED, reason, contextOf(current)) is TransitionDecision.Refused) return@transaction false
        execUpdate(
            "UPDATE selfdev_tasks SET state = 'BLOCKED', state_reason = ?, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PLANNING' AND claim_token = ?",
            reason.name, utcNow(clock), fence.taskId, fence.version, fence.claimToken,
        ) == 1
    }

    private fun fenceMatches(current: SelfDevTask, fence: PlanFence): Boolean =
        current.state == TaskState.PLANNING && current.version == fence.version && current.claimToken == fence.claimToken

    // ---- Dispatcher (ADR 0011 section 5). The Run is created outside these transactions; the task rows are the record. ----

    /** The QUEUED tasks, oldest first. */
    fun queuedTaskIds(limit: Int): List<String> = idsWhere("state = 'QUEUED'", limit)

    /**
     * The tasks BLOCKED by a dispatch guard, oldest first. The dispatcher clears the guards it can clear. Planning blocks are left out, so
     * a long run of them cannot starve the dispatch guards of the limit.
     */
    fun blockedTasks(limit: Int): List<SelfDevTask> = transaction(database) {
        SelfDevTasksTable.selectAll()
            .where {
                (SelfDevTasksTable.state eq TaskState.BLOCKED.name) and
                    (SelfDevTasksTable.stateReason inList DISPATCH_BLOCK_REASONS.map { it.name })
            }
            .orderBy(SelfDevTasksTable.createdAt to SortOrder.ASC, SelfDevTasksTable.taskId to SortOrder.ASC)
            .limit(limit).map { taskOf(it) }
    }

    /** SUBMITTING tasks with no Run yet: a submission the process did not finish. The same key is submitted again. */
    fun interruptedSubmissionIds(limit: Int): List<String> = idsWhere("state = 'SUBMITTING' AND run_id IS NULL", limit)

    /** SUBMITTED tasks with their Run, oldest first. */
    fun submittedRuns(limit: Int): List<SubmittedRun> = transaction(database) {
        execQuery(
            "SELECT task_id, run_id FROM selfdev_tasks WHERE state = 'SUBMITTED' AND run_id IS NOT NULL ORDER BY created_at, task_id LIMIT ?",
            limit,
        ) { SubmittedRun(it.getString(1), it.getString(2)) }
    }

    /** The task whose Run is [runId], or null. */
    fun taskOfRun(runId: String): SelfDevTask? = transaction(database) {
        SelfDevTasksTable.selectAll().where { SelfDevTasksTable.runId eq runId }.firstOrNull()?.let { taskOf(it) }
    }

    /** The states of the tasks [ids]. A missing id is absent from the map. */
    fun statesOf(ids: List<String>): Map<String, TaskState> = if (ids.isEmpty()) emptyMap() else transaction(database) {
        SelfDevTasksTable.selectAll()
            .where { SelfDevTasksTable.taskId inList ids }
            .associate { it[SelfDevTasksTable.taskId] to TaskState.valueOf(it[SelfDevTasksTable.state]) }
    }

    /**
     * Begins a submission: QUEUED -> SUBMITTING, with the attempt counted. Refused (no write) when a dependency is not COMPLETED, or when a
     * planned sibling of the same root is already SUBMITTING or SUBMITTED (one Run per plan at a time, ADR 0011 section 5.1). The root is
     * locked first, so two dispatchers cannot both start a sibling.
     */
    fun beginSubmission(taskId: String, expectedVersion: Long): TaskTransition = transaction(database) {
        val probe = taskIn(taskId) ?: return@transaction TaskTransition.NotFound
        probe.parentTaskId?.let { lockTask(it) }
        val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        val decision = checkTaskTransition(current.state, TaskState.SUBMITTING, null, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
        current.parentTaskId?.let { parent ->
            val inFlight = execQuery(
                "SELECT COUNT(*) FROM selfdev_tasks WHERE parent_task_id = ? AND state IN ('SUBMITTING', 'SUBMITTED')", parent,
            ) { it.getLong(1) }.single()
            if (inFlight > 0) return@transaction TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
        }
        val deps = current.dependencies
        if (deps.isNotEmpty()) {
            val completed = execQuery(
                "SELECT COUNT(*) FROM selfdev_tasks WHERE state = 'COMPLETED' AND task_id IN (${deps.joinToString { "?" }})", *deps.toTypedArray(),
            ) { it.getLong(1) }.single()
            if (completed != deps.size.toLong()) return@transaction TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
            val depProjects = execQuery(
                "SELECT project_id FROM selfdev_tasks WHERE task_id IN (${deps.joinToString { "?" }})", *deps.toTypedArray(),
            ) { it.getString(1) }
            // A dependency across projects, or between an assigned and an unassigned task, is refused (ADR 0022).
            if (!dependenciesInScope(current.projectId, depProjects)) return@transaction TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
        }
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'SUBMITTING', state_reason = NULL, attempts = attempts + 1, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'QUEUED'",
            utcNow(clock), taskId, expectedVersion,
        )
        if (changed != 1) return@transaction TaskTransition.StaleVersion
        TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    /** SUBMITTING -> SUBMITTED with the Run's id, after the Run was created (or found again by the task's key). */
    fun attachRun(taskId: String, expectedVersion: Long, runId: String): TaskTransition = transaction(database) {
        val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        val decision = checkTaskTransition(current.state, TaskState.SUBMITTED, null, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'SUBMITTED', state_reason = NULL, run_id = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'SUBMITTING'",
            runId, utcNow(clock), taskId, expectedVersion,
        )
        if (changed != 1) return@transaction TaskTransition.StaleVersion
        TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    /**
     * The one generic write of the dispatcher: moves a task to [to] with [reason] when the edge, the reason and the context guard allow it,
     * and the version still matches. Used for the dispatch guards, the Run's outcome and the submission rollback.
     */
    fun moveTask(taskId: String, expectedVersion: Long, to: TaskState, reason: AutopilotReason?): TaskTransition = transaction(database) {
        moveTaskIn(taskId, expectedVersion, to, reason)
    }

    /**
     * Records a Run's outcome in one transaction: the task moves to [to] with [reason], and the circuit breaker counts the outcome when
     * [succeeded] is not null. Both happen or neither does, so a crash never keeps the move without its count. A task that did not move
     * counts nothing; it is still SUBMITTED, so the next pass looks at its Run again.
     */
    fun settleRunOutcome(
        taskId: String, expectedVersion: Long, to: TaskState, reason: AutopilotReason, succeeded: Boolean?, maxConsecutiveFailures: Int,
    ): TaskTransition = transaction(database) {
        val result = moveTaskIn(taskId, expectedVersion, to, reason)
        if (result is TaskTransition.Moved && succeeded != null) recordRunOutcomeIn(succeeded, maxConsecutiveFailures)
        result
    }

    /**
     * Settles the plan roots whose planned tasks have all ended (a catch-up: the settlement is also made in the same transaction as the last
     * planned task's move, so this only finds one that a crash left behind). Returns how many settled.
     */
    fun settleFinishedPlans(limit: Int): Int {
        val roots = transaction(database) {
            execQuery(
                """
                SELECT r.task_id FROM selfdev_tasks r
                 WHERE r.state = 'PENDING_APPROVAL' AND r.parent_task_id IS NULL AND r.revision > 0
                   AND EXISTS (SELECT 1 FROM selfdev_tasks c WHERE c.parent_task_id = r.task_id)
                   AND NOT EXISTS (SELECT 1 FROM selfdev_tasks c WHERE c.parent_task_id = r.task_id
                                    AND c.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED', 'STOPPED'))
                 ORDER BY r.created_at, r.task_id LIMIT ?
                """.trimIndent(),
                limit,
            ) { it.getString(1) }
        }
        return roots.count { rootId -> transaction(database) { settlePlanIfFinished(rootId, utcNow(clock)) } }
    }

    /** The move of [moveTask], in the caller's transaction. */
    private fun Transaction.moveTaskIn(taskId: String, expectedVersion: Long, to: TaskState, reason: AutopilotReason?): TaskTransition {
        val current = lockWithParent(taskId) ?: return TaskTransition.NotFound
        if (current.version != expectedVersion) return TaskTransition.StaleVersion
        if (reopensUnderClosedPlan(current)) return TaskTransition.Refused(TransitionRefusal.GUARD_FAILED)
        val decision = checkTaskTransition(current.state, to, reason, contextOf(current))
        if (decision is TransitionDecision.Refused) return TaskTransition.Refused(decision.reason)
        val now = utcNow(clock)
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = ?, state_reason = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = ?",
            to.name, reason?.name, now, taskId, expectedVersion, current.state.name,
        )
        if (changed != 1) return TaskTransition.StaleVersion
        settlePlanOf(current.parentTaskId, now)
        return TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    /**
     * The auto-approved task (no `approved_by`) whose Run [runId] has an out-of-scope change: SUBMITTED -> PENDING_APPROVAL with
     * AUTO_SCOPE_EXCEEDED, so the operator approves it again (ADR 0011 section 6.2). Returns false when no such task is SUBMITTED.
     */
    fun settleScopeExceeded(runId: String): Boolean = transaction(database) {
        val taskId = execQuery(
            "SELECT task_id FROM selfdev_tasks WHERE run_id = ? AND state = 'SUBMITTED' AND approved_by IS NULL FOR UPDATE", runId,
        ) { it.getString(1) }.firstOrNull() ?: return@transaction false
        val current = lockTask(taskId) ?: return@transaction false
        if (checkTaskTransition(current.state, TaskState.PENDING_APPROVAL, AutopilotReason.AUTO_SCOPE_EXCEEDED, contextOf(current)) is TransitionDecision.Refused) {
            return@transaction false
        }
        execUpdate(
            "UPDATE selfdev_tasks SET state = 'PENDING_APPROVAL', state_reason = 'AUTO_SCOPE_EXCEEDED', version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'SUBMITTED'",
            utcNow(clock), taskId, current.version,
        ) == 1
    }

    private fun idsWhere(predicate: String, limit: Int): List<String> = transaction(database) {
        execQuery("SELECT task_id FROM selfdev_tasks WHERE $predicate ORDER BY created_at, task_id LIMIT ?", limit) { it.getString(1) }
    }

    private class PlanMovedDuringCommit : RuntimeException(null, null, false, false)
    private class TaskMovedDuringStop : RuntimeException(null, null, false, false)
    private fun Transaction.lockTask(taskId: String): SelfDevTask? {
        execQuery("SELECT task_id FROM selfdev_tasks WHERE task_id = ? FOR UPDATE", taskId) { it.getString(1) }
        return taskIn(taskId)
    }

    /** Locks the plan root first, then the task: the order [beginSubmission] uses too, so a planned task and its root never lock in reverse. */
    private fun Transaction.lockWithParent(taskId: String): SelfDevTask? {
        val probe = taskIn(taskId) ?: return null
        probe.parentTaskId?.let { lockTask(it) }
        return lockTask(taskId)
    }

    /** A failed or cancelled planned task is not requeued once its plan root has settled (DONE or STOPPED). */
    private fun Transaction.reopensUnderClosedPlan(task: SelfDevTask): Boolean {
        val parentId = task.parentTaskId ?: return false
        if (task.state != TaskState.FAILED && task.state != TaskState.CANCELLED) return false
        val parentState = taskIn(parentId)?.state ?: return false
        return parentState == TaskState.DONE || parentState == TaskState.STOPPED
    }

    /** Settles the plan root of a planned task (see [settlePlanIfFinished]). A task with no root is left alone. */
    private fun Transaction.settlePlanOf(parentId: String?, now: LocalDateTime) {
        if (parentId != null) settlePlanIfFinished(parentId, now)
    }

    /**
     * A plan root (PENDING_APPROVAL, planned at least once) moves on when every planned task has ended: DONE (ALL_TASKS_COMPLETED) when all
     * of them COMPLETED, otherwise STOPPED (TASKS_INCOMPLETE), ADR 0011 section 2.8b. The root is never run itself. Returns true when it moved.
     */
    private fun Transaction.settlePlanIfFinished(rootId: String, now: LocalDateTime): Boolean {
        val root = lockTask(rootId) ?: return false
        if (root.state != TaskState.PENDING_APPROVAL || !root.isPlanRoot) return false
        val states = childStatesOf(rootId)
        if (states.isEmpty() || states.any { it !in PLAN_CHILD_END_STATES }) return false
        val (to, reason) = if (states.all { it == TaskState.COMPLETED }) {
            TaskState.DONE to AutopilotReason.ALL_TASKS_COMPLETED
        } else {
            TaskState.STOPPED to AutopilotReason.TASKS_INCOMPLETE
        }
        if (checkTaskTransition(root.state, to, reason, contextOf(root).copy(childStates = states)) is TransitionDecision.Refused) return false
        return execUpdate(
            "UPDATE selfdev_tasks SET state = ?, state_reason = ?, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PENDING_APPROVAL'",
            to.name, reason.name, now, rootId, root.version,
        ) == 1
    }

    private fun Transaction.childStatesOf(parentId: String): List<TaskState> =
        execQuery("SELECT state FROM selfdev_tasks WHERE parent_task_id = ?", parentId) { TaskState.valueOf(it.getString(1)) }
    private fun taskIn(taskId: String): SelfDevTask? =
        SelfDevTasksTable.selectAll().where { SelfDevTasksTable.taskId eq taskId }.firstOrNull()?.let { taskOf(it) }
    private fun Transaction.skipUnsubmittedTasks(taskId: String, now: LocalDateTime) {
        val task = taskIn(taskId) ?: return
        for (depId in task.dependencies) skipIfUnsubmitted(depId, now)
        // The planned tasks of a plan root (V44) that are not submitted yet stop with it. Submitted ones are left to finish.
        for (childId in childIdsOf(taskId)) skipIfUnsubmitted(childId, now)
    }
    private fun Transaction.skipIfUnsubmitted(id: String, now: LocalDateTime) {
        val row = taskIn(id) ?: return
        if (row.state != TaskState.PENDING_APPROVAL && row.state != TaskState.QUEUED) return
        check(checkTaskTransition(row.state, TaskState.SKIPPED, AutopilotReason.TASK_STOPPED, contextOf(row)) is TransitionDecision.Allowed)
        if (execUpdate("UPDATE selfdev_tasks SET state = 'SKIPPED', state_reason = 'TASK_STOPPED', version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state IN ('PENDING_APPROVAL', 'QUEUED')", now, id, row.version) != 1)
            throw TaskMovedDuringStop()
        settlePlanOf(row.parentTaskId, now)
    }
    private fun Transaction.childIdsOf(parentId: String): List<String> =
        execQuery("SELECT task_id FROM selfdev_tasks WHERE parent_task_id = ?", parentId) { it.getString(1) }
    private fun contextOf(t: SelfDevTask) = TaskContext(revision = t.revision, blockedReason = if (t.state == TaskState.BLOCKED) t.stateReason else null, resumeState = t.resumeState)
    private fun taskOf(row: ResultRow) = SelfDevTask(
        taskId = row[SelfDevTasksTable.taskId], createdBy = row[SelfDevTasksTable.createdBy],
        idempotencyKey = row[SelfDevTasksTable.idempotencyKey], title = row[SelfDevTasksTable.title],
        body = row[SelfDevTasksTable.body], targetRepo = row[SelfDevTasksTable.targetRepo],
        state = TaskState.valueOf(row[SelfDevTasksTable.state]),
        stateReason = row[SelfDevTasksTable.stateReason]?.let { AutopilotReason.valueOf(it) },
        resumeState = row[SelfDevTasksTable.resumeState]?.let { TaskState.valueOf(it) },
        resumeReason = row[SelfDevTasksTable.resumeReason]?.let { AutopilotReason.valueOf(it) },
        revision = row[SelfDevTasksTable.revision], claimToken = row[SelfDevTasksTable.claimToken],
        claimedUntil = row[SelfDevTasksTable.claimedUntil],
        dependencies = JSON.decodeFromString(ListSerializer(String.serializer()), row[SelfDevTasksTable.dependencies]),
        requiresApproval = row[SelfDevTasksTable.requiresApproval], runId = row[SelfDevTasksTable.runId],
        attempts = row[SelfDevTasksTable.attempts], version = row[SelfDevTasksTable.version],
        createdAt = row[SelfDevTasksTable.createdAt], updatedAt = row[SelfDevTasksTable.updatedAt],
        approvedBy = row[SelfDevTasksTable.approvedBy], parentTaskId = row[SelfDevTasksTable.parentTaskId],
        projectId = row[SelfDevTasksTable.projectId])
    /** A unique-key violation anywhere in the cause chain: MariaDB 1062, H2 23505. */
    private fun Throwable.isDuplicateKey(): Boolean =
        generateSequence(this) { it.cause }.any { it is SQLException && (it.sqlState == "23505" || it.errorCode == 1062 || it.errorCode == 23505) }
    private companion object {
        val JSON = Json

        /** The principals the system files tasks as (their project comes from the repository, ADR 0022 PR-B). */
        val SYSTEM_TASK_PRINCIPALS: Set<String> = setOf(INCIDENT_FIX_PRINCIPAL)
    }
}
