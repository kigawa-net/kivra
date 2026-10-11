package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TaskRunState
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class TaskRun(
    val runId: RunId,
    val taskId: String,
    val state: TaskRunState,
    val stateReason: String?,
    val attempt: Int,
    val leaseOwner: String?,
    val leaseGeneration: Long,
    val leaseExpiresAt: LocalDateTime?,
    val holdReason: String?,
    val version: Long,
    val traceId: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Another non-terminal run already exists for the task (`uk_task_runs_active_task`). */
class DuplicateActiveRunException(val taskId: String) : RuntimeException("an active run already exists for task $taskId")

/**
 * TaskRun persistence. Every state change is one transaction that (1) CASes the row on
 * `lease_owner / lease_generation / lease_expires_at > NOW / version / state`, (2) appends an audit event and
 * (3) appends an outbox event (design #1 section 5.4, #11 section 5.3).
 */
class TaskRunRepository(
    private val database: Database,
    private val outbox: OutboxRepository,
    private val audit: AuditRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Creates a run in ACCEPTED (version 0) with its audit and outbox events. */
    fun create(taskId: String, actor: String, traceId: String? = null): TaskRun {
        val runId = RunId(Ulid.generate(clock))
        try {
            transaction(database) {
                val now = utcNow(clock)
                TaskRunsTable.insert {
                    it[TaskRunsTable.runId] = runId.value
                    it[TaskRunsTable.taskId] = taskId
                    it[state] = TaskRunState.ACCEPTED.name
                    it[TaskRunsTable.traceId] = traceId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                audit.append(this, runId, AuditEntry(actor = actor, action = "RUN_CREATED", result = "OK", resource = "task:$taskId", traceId = traceId))
                outbox.append(this, runId, 0, "RunCreated", transitionPayload(taskId, null, TaskRunState.ACCEPTED), traceId)
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) throw DuplicateActiveRunException(taskId) else throw e
        }
        return get(runId)!!
    }

    fun get(runId: RunId): TaskRun? = transaction(database) {
        TaskRunsTable.selectAll().where { TaskRunsTable.runId eq runId.value }.firstOrNull()?.toTaskRun()
    }

    /**
     * Fenced state transition. Throws [LeaseLostException] if the lease is no longer valid on the DB clock,
     * [VersionConflictException] if [expectedVersion] is stale, [InvalidTransitionException] for a transition
     * the state machine forbids. Nothing is written in any failure case.
     */
    fun transition(
        lease: Lease,
        expectedVersion: Long,
        to: TaskRunState,
        actor: String,
        reason: String? = null,
    ): TaskRun {
        transaction(database) {
            requireLease(lease) // locks the row; throws LeaseLostException
            val row = TaskRunsTable.selectAll().where { TaskRunsTable.runId eq lease.runId.value }.single()
            val from = TaskRunState.valueOf(row[TaskRunsTable.state])
            if (row[TaskRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[TaskRunsTable.version]}, expected $expectedVersion")
            }
            if (!from.canTransitionTo(to)) throw InvalidTransitionException("run ${lease.runId}: $from -> $to is not allowed")

            val updated = execUpdate(
                """
                UPDATE task_runs
                   SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ?
                   AND lease_expires_at > ${DbTime.NOW}
                   AND version = ? AND state = ?
                """.trimIndent(),
                to.name, reason, utcNow(clock), lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name,
            )
            if (updated != 1) throw LeaseLostException("fenced update rejected for run ${lease.runId}")

            val taskId = row[TaskRunsTable.taskId]
            val traceId = row[TaskRunsTable.traceId]
            audit.append(
                this, lease.runId,
                AuditEntry(actor = actor, action = "RUN_TRANSITION", result = "OK", resource = "task:$taskId", traceId = traceId, payload = transitionPayload(taskId, from, to)),
            )
            outbox.append(this, lease.runId, expectedVersion + 1, "RunStateChanged", transitionPayload(taskId, from, to), traceId)
        }
        return get(lease.runId)!!
    }

    private fun transitionPayload(taskId: String, from: TaskRunState?, to: TaskRunState): String =
        buildJsonObject {
            put("taskId", taskId)
            if (from != null) put("from", from.name)
            put("to", to.name)
        }.toString()

    private fun ResultRow.toTaskRun() = TaskRun(
        runId = RunId(this[TaskRunsTable.runId]),
        taskId = this[TaskRunsTable.taskId],
        state = TaskRunState.valueOf(this[TaskRunsTable.state]),
        stateReason = this[TaskRunsTable.stateReason],
        attempt = this[TaskRunsTable.attempt],
        leaseOwner = this[TaskRunsTable.leaseOwner],
        leaseGeneration = this[TaskRunsTable.leaseGeneration],
        leaseExpiresAt = this[TaskRunsTable.leaseExpiresAt],
        holdReason = this[TaskRunsTable.holdReason],
        version = this[TaskRunsTable.version],
        traceId = this[TaskRunsTable.traceId],
        createdAt = this[TaskRunsTable.createdAt],
        updatedAt = this[TaskRunsTable.updatedAt],
    )
}
