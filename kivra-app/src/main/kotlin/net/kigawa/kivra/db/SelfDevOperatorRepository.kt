package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.MAX_REVISIONS
import net.kigawa.kivra.core.autopilot.TaskContext
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionDecision
import net.kigawa.kivra.core.autopilot.TransitionRefusal
import net.kigawa.kivra.core.autopilot.checkTaskTransition
import org.jetbrains.exposed.sql.Database
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
)

sealed interface TaskTransition {
    data class Moved(val row: SelfDevTask) : TaskTransition
    data object NotFound : TaskTransition
    data object StaleVersion : TaskTransition
    data class Refused(val reason: TransitionRefusal) : TaskTransition
}

class SelfDevOperatorRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {
    fun listTasks(limit: Int): List<SelfDevTask> = transaction(database) {
        SelfDevTasksTable.selectAll()
            .orderBy(SelfDevTasksTable.updatedAt to SortOrder.DESC, SelfDevTasksTable.taskId to SortOrder.DESC)
            .limit(limit).map { taskOf(it) }
    }
    fun task(taskId: String): SelfDevTask? = transaction(database) { taskIn(taskId) }

    fun approveTask(taskId: String, expectedVersion: Long, approver: String): TaskTransition = transaction(database) {
        val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
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
        val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
        if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        val decision = checkTaskTransition(current.state, TaskState.SKIPPED, AutopilotReason.OPERATOR_SKIPPED, contextOf(current))
        if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
        val changed = execUpdate(
            "UPDATE selfdev_tasks SET state = 'SKIPPED', state_reason = 'OPERATOR_SKIPPED', version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'PENDING_APPROVAL'",
            utcNow(clock), taskId, expectedVersion)
        if (changed != 1) return@transaction TaskTransition.StaleVersion
        TaskTransition.Moved(taskIn(taskId) ?: error("vanished"))
    }

    fun stopTask(taskId: String, expectedVersion: Long): TaskTransition = try {
        transaction(database) {
            val current = lockTask(taskId) ?: return@transaction TaskTransition.NotFound
            if (current.version != expectedVersion) return@transaction TaskTransition.StaleVersion
            val decision = checkTaskTransition(current.state, TaskState.STOPPED, AutopilotReason.OPERATOR_STOPPED, contextOf(current))
            if (decision is TransitionDecision.Refused) return@transaction TaskTransition.Refused(decision.reason)
            val now = utcNow(clock)
            val changed = execUpdate(
                "UPDATE selfdev_tasks SET state = 'STOPPED', state_reason = 'OPERATOR_STOPPED', resume_state = NULL, resume_reason = NULL, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ?",
                now, taskId, expectedVersion)
            if (changed != 1) return@transaction TaskTransition.StaleVersion
            skipUnsubmittedTasks(taskId, now)
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

    private class TaskMovedDuringStop : RuntimeException(null, null, false, false)
    private fun Transaction.lockTask(taskId: String): SelfDevTask? {
        execQuery("SELECT task_id FROM selfdev_tasks WHERE task_id = ? FOR UPDATE", taskId) { it.getString(1) }
        return taskIn(taskId)
    }
    private fun taskIn(taskId: String): SelfDevTask? =
        SelfDevTasksTable.selectAll().where { SelfDevTasksTable.taskId eq taskId }.firstOrNull()?.let { taskOf(it) }
    private fun Transaction.skipUnsubmittedTasks(taskId: String, now: LocalDateTime) {
        val task = taskIn(taskId) ?: return
        for (depId in task.dependencies) {
            val dep = taskIn(depId) ?: continue
            if (dep.state != TaskState.PENDING_APPROVAL && dep.state != TaskState.QUEUED) continue
            check(checkTaskTransition(dep.state, TaskState.SKIPPED, AutopilotReason.TASK_STOPPED, contextOf(dep)) is TransitionDecision.Allowed)
            if (execUpdate("UPDATE selfdev_tasks SET state = 'SKIPPED', state_reason = 'TASK_STOPPED', version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state IN ('PENDING_APPROVAL', 'QUEUED')", now, depId, dep.version) != 1)
                throw TaskMovedDuringStop()
        }
    }
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
        approvedBy = row[SelfDevTasksTable.approvedBy])
    private companion object { val JSON = Json }
}
