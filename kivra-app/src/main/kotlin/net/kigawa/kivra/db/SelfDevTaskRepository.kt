package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.autopilot.AutopilotReason
import net.kigawa.kivra.core.autopilot.TaskContext
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.core.autopilot.TransitionDecision
import net.kigawa.kivra.core.autopilot.checkTaskTransition
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

enum class TaskClaimRefusal { NOT_FOUND, NOT_CLAIMABLE, PAUSED }
sealed interface TaskClaimResult {
    data class Claimed(val task: SelfDevTask) : TaskClaimResult
    data class Refused(val reason: TaskClaimRefusal) : TaskClaimResult
}
data class TaskClaim(val taskId: String, val version: Long, val claimToken: String)
data class NewTask(
    val title: String,
    val body: String?,
    val targetPaths: List<String>,
    val sizeEstimate: String,
    val dependsOn: List<Int>,
    val requiresApproval: Boolean,
)

data class NewTaskPlan(val summary: String?, val plannerModel: String, val outputSha256: String, val requestLedgerId: String?)
sealed interface TaskCommitResult {
    data class Committed(val task: SelfDevTask) : TaskCommitResult
    data object StaleClaim : TaskCommitResult
}
data class DailyTaskLimit(val max: Int, val since: LocalDateTime)
class DailyTaskLimitException : RuntimeException("daily task limit reached")
sealed interface FlatTaskSubmitResult {
    data class Created(val task: SelfDevTask) : FlatTaskSubmitResult
    data class Replayed(val task: SelfDevTask) : FlatTaskSubmitResult
    data object KeyConflict : FlatTaskSubmitResult
}

class SelfDevTaskRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {
    fun submit(createdBy: String, idempotencyKey: String, title: String, body: String?, targetRepo: String, dailyLimit: DailyTaskLimit? = null): FlatTaskSubmitResult {
        require(createdBy.isNotEmpty() && createdBy.length <= 255)
        require(idempotencyKey.isNotEmpty() && idempotencyKey.length <= 128)
        require(title.isNotEmpty() && title.codePointCount(0, title.length) <= 200)
        require(targetRepo.isNotEmpty() && targetRepo.length <= 200)
        val nb = body?.takeIf { it.isNotEmpty() }
        require(nb == null || nb.codePointCount(0, nb.length) <= 8000)
        return transaction(database) {
            fun rc(e: SelfDevTask) = if (e.title == title && e.body == nb && e.targetRepo == targetRepo) FlatTaskSubmitResult.Replayed(e) else FlatTaskSubmitResult.KeyConflict
            findByKey(createdBy, idempotencyKey)?.let { return@transaction rc(it) }
            if (dailyLimit != null && countSince(createdBy, dailyLimit.since) >= dailyLimit.max) throw DailyTaskLimitException()
            val taskId = Ulid.generate(clock)
            val now = utcNow(clock)
            try {
                SelfDevTasksTable.insert { tbl ->
                    tbl[SelfDevTasksTable.taskId] = taskId
                    tbl[SelfDevTasksTable.createdBy] = createdBy
                    tbl[SelfDevTasksTable.idempotencyKey] = idempotencyKey
                    tbl[SelfDevTasksTable.title] = title
                    tbl[SelfDevTasksTable.body] = nb
                    tbl[SelfDevTasksTable.targetRepo] = targetRepo
                    tbl[SelfDevTasksTable.state] = TaskState.TASK_SUBMITTED.name
                    tbl[SelfDevTasksTable.revision] = 0
                    tbl[SelfDevTasksTable.version] = 0
                    tbl[SelfDevTasksTable.dependencies] = JSON.encodeToString(ListSerializer(String.serializer()), emptyList<String>())
                    tbl[SelfDevTasksTable.requiresApproval] = false
                    tbl[SelfDevTasksTable.attempts] = 0
                    tbl[SelfDevTasksTable.createdAt] = now
                    tbl[SelfDevTasksTable.updatedAt] = now
                }
            } catch (e: ExposedSQLException) {
                if (!e.isUniqueViolation()) throw e
                return@transaction rc(findByKey(createdBy, idempotencyKey) ?: throw e)
            }
            FlatTaskSubmitResult.Created(getIn(taskId) ?: error("vanished"))
        }
    }
    fun get(taskId: String): SelfDevTask? = transaction(database) { getIn(taskId) }
    fun claim(taskId: String, ttl: Duration): TaskClaimResult = transaction(database) {
        val c = getIn(taskId) ?: return@transaction TaskClaimResult.Refused(TaskClaimRefusal.NOT_FOUND)
        if (c.state != TaskState.TASK_SUBMITTED) return@transaction TaskClaimResult.Refused(TaskClaimRefusal.NOT_CLAIMABLE)
        requireAllowed(checkTaskTransition(c.state, TaskState.PLANNING, null, ctx(c)))
        val token = UUID.randomUUID().toString()
        val now = utcNow(clock)
        val u = execUpdate("UPDATE selfdev_tasks SET state = 'PLANNING', claim_token = ?, claimed_until = ${DbTime.nowPlusSeconds()}, state_reason = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND state = 'TASK_SUBMITTED' AND NOT EXISTS (SELECT 1 FROM selfdev_autopilot_control WHERE control_id = 1 AND paused = TRUE)", token, ttl.seconds, now, taskId)
        if (u == 1) return@transaction TaskClaimResult.Claimed(getIn(taskId) ?: error("vanished"))
        val after = getIn(taskId) ?: return@transaction TaskClaimResult.Refused(TaskClaimRefusal.NOT_FOUND)
        val paused = execQuery("SELECT paused FROM selfdev_autopilot_control WHERE control_id = 1") { it.getBoolean(1) }.single()
        TaskClaimResult.Refused(if (paused && after.state == TaskState.TASK_SUBMITTED) TaskClaimRefusal.PAUSED else TaskClaimRefusal.NOT_CLAIMABLE)
    }
    fun submittedTaskIds(limit: Int): List<String> = transaction(database) {
        execQuery("SELECT task_id FROM selfdev_tasks WHERE state = 'TASK_SUBMITTED' ORDER BY created_at, task_id LIMIT ?", limit) { it.getString(1) }
    }
    fun expiredClaimTaskIds(limit: Int): List<String> = transaction(database) {
        execQuery("SELECT task_id FROM selfdev_tasks WHERE state = 'PLANNING' AND claimed_until <= ${DbTime.NOW} ORDER BY claimed_until, task_id LIMIT ?", limit) { it.getString(1) }
    }
    fun expireClaim(taskId: String): Boolean = transaction(database) {
        val c = getIn(taskId) ?: return@transaction false
        if (c.state != TaskState.PLANNING) return@transaction false
        requireAllowed(checkTaskTransition(c.state, TaskState.TASK_SUBMITTED, AutopilotReason.CLAIM_EXPIRED, ctx(c)))
        execUpdate("UPDATE selfdev_tasks SET state = 'TASK_SUBMITTED', state_reason = 'CLAIM_EXPIRED', claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND state = 'PLANNING' AND claimed_until <= ${DbTime.NOW}", utcNow(clock), taskId) == 1
    }
    fun commitPlan(claim: TaskClaim, plan: NewTaskPlan): TaskCommitResult = transaction(database) {
        val now = utcNow(clock)
        val c = getIn(claim.taskId)
        if (c == null || c.state != TaskState.PLANNING) return@transaction TaskCommitResult.StaleClaim
        requireAllowed(checkTaskTransition(c.state, TaskState.PENDING_APPROVAL, null, ctx(c)))
        val f = execUpdate("UPDATE selfdev_tasks SET state = 'PENDING_APPROVAL', revision = revision + 1, state_reason = NULL, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND claim_token = ? AND state = 'PLANNING' AND claimed_until > ${DbTime.NOW}", now, claim.taskId, claim.version, claim.claimToken)
        if (f != 1) return@transaction TaskCommitResult.StaleClaim
        TaskCommitResult.Committed(getIn(claim.taskId) ?: error("vanished"))
    }
    fun blockPlanning(claim: TaskClaim, reason: AutopilotReason): Boolean = transaction(database) {
        val c = getIn(claim.taskId) ?: return@transaction false
        if (c.state != TaskState.PLANNING) return@transaction false
        requireAllowed(checkTaskTransition(c.state, TaskState.BLOCKED, reason, ctx(c)))
        execUpdate("UPDATE selfdev_tasks SET state = 'BLOCKED', state_reason = ?, claim_token = NULL, claimed_until = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND claim_token = ? AND state = 'PLANNING'", reason.name, utcNow(clock), claim.taskId, claim.version, claim.claimToken) == 1
    }
    fun transition(taskId: String, expectedVersion: Long, to: TaskState, reason: AutopilotReason?): TaskTransition = transaction(database) {
        val c = getIn(taskId) ?: return@transaction TaskTransition.NotFound
        if (c.version != expectedVersion) return@transaction TaskTransition.StaleVersion
        val d = checkTaskTransition(c.state, to, reason, ctx(c))
        if (d is TransitionDecision.Refused) return@transaction TaskTransition.Refused(d.reason)
        val resuming = c.state == TaskState.PAUSED
        val pausing = to == TaskState.PAUSED
        val nr = if (resuming) c.resumeReason else reason
        val ch = execUpdate("UPDATE selfdev_tasks SET state = ?, state_reason = ?, resume_state = ?, resume_reason = ?, claim_token = CASE WHEN ? = 'PLANNING' THEN claim_token ELSE NULL END, claimed_until = CASE WHEN ? = 'PLANNING' THEN claimed_until ELSE NULL END, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ?", to.name, nr?.name, if (pausing) c.state.name else null, if (pausing) c.stateReason?.name else null, to.name, to.name, utcNow(clock), taskId, expectedVersion)
        if (ch != 1) return@transaction TaskTransition.StaleVersion
        TaskTransition.Moved(getIn(taskId) ?: error("vanished"))
    }
    fun autoTaskOfRun(runId: String): SelfDevTask? = transaction(database) {
        val submitted = SelfDevTasksTable.state eq TaskState.SUBMITTED.name
        val settled = (SelfDevTasksTable.state eq TaskState.PENDING_APPROVAL.name) and (SelfDevTasksTable.stateReason eq AutopilotReason.AUTO_SCOPE_EXCEEDED.name)
        SelfDevTasksTable.selectAll().where { (SelfDevTasksTable.runId eq runId) and SelfDevTasksTable.approvedBy.isNull() and (SelfDevTasksTable.requiresApproval eq false) and (submitted or settled) }.firstOrNull()?.let { taskOf(it) }
    }
    fun settleAutoScopeExceeded(runId: String): Boolean = transaction(database) {
        val t = autoTaskOfRun(runId) ?: return@transaction false
        if (t.state == TaskState.PENDING_APPROVAL) return@transaction true
        val d = checkTaskTransition(t.state, TaskState.PENDING_APPROVAL, AutopilotReason.AUTO_SCOPE_EXCEEDED, ctx(t))
        if (d is TransitionDecision.Refused) return@transaction false
        execUpdate("UPDATE selfdev_tasks SET state = 'PENDING_APPROVAL', state_reason = 'AUTO_SCOPE_EXCEEDED', run_id = NULL, version = version + 1, updated_at = ? WHERE task_id = ? AND version = ? AND state = 'SUBMITTED'", utcNow(clock), t.taskId, t.version) == 1
    }
    fun taskOfRun(runId: String): SelfDevTask? = transaction(database) {
        SelfDevTasksTable.selectAll().where { SelfDevTasksTable.runId eq runId }.firstOrNull()?.let { taskOf(it) }
    }
    fun hasUnknownRun(taskId: String): Boolean = transaction(database) {
        execQuery("SELECT 1 FROM selfdev_tasks t JOIN development_runs r ON r.run_id = t.run_id WHERE t.task_id = ? AND r.state = 'UNKNOWN'", taskId) { it.getInt(1) }.isNotEmpty()
    }
    private fun findByKey(cb: String, ik: String): SelfDevTask? = SelfDevTasksTable.selectAll().where { (SelfDevTasksTable.createdBy eq cb) and (SelfDevTasksTable.idempotencyKey eq ik) }.firstOrNull()?.let { taskOf(it) }
    private fun countSince(cb: String, since: LocalDateTime): Long = SelfDevTasksTable.selectAll().where { (SelfDevTasksTable.createdBy eq cb) and (SelfDevTasksTable.createdAt greaterEq since) }.count()
    private fun getIn(taskId: String): SelfDevTask? = SelfDevTasksTable.selectAll().where { SelfDevTasksTable.taskId eq taskId }.firstOrNull()?.let { taskOf(it) }
    private fun ctx(t: SelfDevTask) = TaskContext(revision = t.revision, blockedReason = if (t.state == TaskState.BLOCKED) t.stateReason else null, resumeState = t.resumeState)
    private fun requireAllowed(d: TransitionDecision) { check(d is TransitionDecision.Allowed) }
    private fun taskOf(row: org.jetbrains.exposed.sql.ResultRow) = SelfDevTask(
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
