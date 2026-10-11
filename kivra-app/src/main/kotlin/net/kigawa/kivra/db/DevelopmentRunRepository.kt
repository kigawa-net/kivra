package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class DevelopmentRun(
    val runId: RunId,
    val taskId: String,
    val state: DevelopmentRunState,
    val stateReason: String?,
    val attempt: Int,
    val leaseOwner: String?,
    val leaseGeneration: Long,
    val leaseExpiresAt: LocalDateTime?,
    val holdReason: String?,
    val cancelRequested: Boolean,
    val version: Long,
    val traceId: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** Another non-terminal run already exists for the task (`uk_dev_runs_active_task`). */
class DuplicateActiveRunException(val taskId: String) : RuntimeException("an active run already exists for task $taskId")

/**
 * DevelopmentRun persistence. Every state change is one transaction that (1) CASes the row on
 * `lease_owner / lease_generation / lease_expires_at > NOW / version / state`, (2) appends an audit event and
 * (3) appends an outbox event (design #1 section 5.4, #11 section 5.3).
 */
class DevelopmentRunRepository(
    private val database: Database,
    private val outbox: OutboxRepository,
    private val audit: AuditRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Creates a run in CREATED (version 0) with its audit and outbox events. */
    fun create(taskId: String, actor: String, traceId: String? = null): DevelopmentRun {
        val runId = RunId(Ulid.generate(clock))
        try {
            transaction(database) {
                val now = utcNow(clock)
                DevelopmentRunsTable.insert {
                    it[DevelopmentRunsTable.runId] = runId.value
                    it[DevelopmentRunsTable.taskId] = taskId
                    it[state] = DevelopmentRunState.CREATED.name
                    it[DevelopmentRunsTable.traceId] = traceId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                audit.append(this, runId, AuditEntry(actor = actor, action = "RUN_CREATED", result = "OK", resource = "task:$taskId", traceId = traceId))
                outbox.append(this, runId, 0, "RunCreated", transitionPayload(taskId, null, DevelopmentRunState.CREATED), traceId)
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) throw DuplicateActiveRunException(taskId) else throw e
        }
        return get(runId)!!
    }

    fun get(runId: RunId): DevelopmentRun? = transaction(database) {
        DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.firstOrNull()?.toDevelopmentRun()
    }

    /**
     * Fenced state transition. Throws [LeaseLostException] if the lease is no longer valid on the DB clock,
     * [VersionConflictException] if [expectedVersion] is stale, [InvalidTransitionException] for a transition
     * the state machine forbids. Nothing is written in any failure case.
     */
    fun transition(
        lease: Lease,
        expectedVersion: Long,
        to: DevelopmentRunState,
        actor: String,
        reason: String? = null,
    ): DevelopmentRun {
        transaction(database) {
            // Locks the row; throws LeaseLostException / RunHaltedException (hold, or cancel request except the CANCELLED ack).
            requireLease(lease, allowCancelRequested = to == DevelopmentRunState.CANCELLED)
            val row = DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq lease.runId.value }.single()
            val from = DevelopmentRunState.valueOf(row[DevelopmentRunsTable.state])
            if (row[DevelopmentRunsTable.version] != expectedVersion) {
                throw VersionConflictException("run ${lease.runId} is at version ${row[DevelopmentRunsTable.version]}, expected $expectedVersion")
            }
            if (!from.canTransitionTo(to)) throw InvalidTransitionException("run ${lease.runId}: $from -> $to is not allowed")

            val updated = execUpdate(
                """
                UPDATE development_runs
                   SET state = ?, state_reason = ?, version = version + 1, updated_at = ?
                 WHERE run_id = ? AND lease_generation = ? AND lease_owner = ?
                   AND lease_expires_at > ${DbTime.NOW}
                   AND hold_reason IS NULL
                   AND version = ? AND state = ?
                """.trimIndent(),
                to.name, reason, utcNow(clock), lease.runId.value, lease.generation, lease.owner, expectedVersion, from.name,
            )
            if (updated != 1) throw LeaseLostException("fenced update rejected for run ${lease.runId}")

            val taskId = row[DevelopmentRunsTable.taskId]
            val traceId = row[DevelopmentRunsTable.traceId]
            audit.append(
                this, lease.runId,
                AuditEntry(actor = actor, action = "RUN_TRANSITION", result = "OK", resource = "task:$taskId", traceId = traceId, payload = transitionPayload(taskId, from, to)),
            )
            outbox.append(this, lease.runId, expectedVersion + 1, "RunStateChanged", transitionPayload(taskId, from, to), traceId)
        }
        return get(lease.runId)!!
    }

    /**
     * Administrator Hold. Applies to a worker that already holds the lease: its next renew, fenced transition,
     * journal / outbox write fails with [net.kigawa.kivra.core.RunHaltedException] (fail-closed), not only new acquires.
     * Needs no lease. Idempotent for the same reason; a different reason overwrites.
     */
    fun hold(runId: RunId, reason: String, actor: String): DevelopmentRun {
        adminUpdate(runId, actor, "RUN_HOLD", "RunHeld", "hold_reason = ?", reason)
        return get(runId)!!
    }

    fun clearHold(runId: RunId, actor: String): DevelopmentRun {
        adminUpdate(runId, actor, "RUN_HOLD_CLEARED", "RunHoldCleared", "hold_reason = NULL")
        return get(runId)!!
    }

    /**
     * Requests cancellation. The current worker may then only acknowledge it with a transition to CANCELLED;
     * every other write fails. Rejected for states that cannot reach CANCELLED (RELEASING / VERIFYING: use DEC rollback).
     */
    fun requestCancel(runId: RunId, actor: String): DevelopmentRun {
        adminUpdate(runId, actor, "RUN_CANCEL_REQUESTED", "RunCancelRequested", "cancel_requested = TRUE") { from ->
            if (!from.canTransitionTo(DevelopmentRunState.CANCELLED)) throw InvalidTransitionException("run $runId: $from cannot be cancelled")
        }
        return get(runId)!!
    }

    /** Completes a requested cancel when no worker owns the run any more (no lease, or it expired). Returns false if not applicable. */
    fun cancelUnowned(runId: RunId, actor: String): Boolean = transaction(database) {
        val row = lockRun(runId) ?: return@transaction false
        val from = DevelopmentRunState.valueOf(row[DevelopmentRunsTable.state])
        if (!row[DevelopmentRunsTable.cancelRequested] || !from.canTransitionTo(DevelopmentRunState.CANCELLED)) return@transaction false
        val updated = execUpdate(
            """
            UPDATE development_runs
               SET state = ?, state_reason = ?, version = version + 1, updated_at = ?,
                   lease_owner = NULL, lease_expires_at = NULL
             WHERE run_id = ? AND version = ? AND cancel_requested = TRUE
               AND (lease_owner IS NULL OR lease_expires_at <= ${DbTime.NOW})
            """.trimIndent(),
            DevelopmentRunState.CANCELLED.name, "CANCEL_REQUESTED", utcNow(clock), runId.value, row[DevelopmentRunsTable.version],
        )
        if (updated != 1) return@transaction false
        val taskId = row[DevelopmentRunsTable.taskId]
        val traceId = row[DevelopmentRunsTable.traceId]
        val payload = transitionPayload(taskId, from, DevelopmentRunState.CANCELLED)
        audit.append(this, runId, AuditEntry(actor = actor, action = "RUN_TRANSITION", result = "OK", resource = "task:$taskId", traceId = traceId, payload = payload))
        outbox.append(this, runId, row[DevelopmentRunsTable.version] + 1, "RunStateChanged", payload, traceId)
        true
    }

    private fun lockRun(runId: RunId): ResultRow? =
        DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull()

    private fun adminUpdate(
        runId: RunId, actor: String, action: String, eventType: String, setClause: String, vararg setParams: Any?,
        check: (DevelopmentRunState) -> Unit = {},
    ) {
        transaction(database) {
            val row = lockRun(runId) ?: throw NoSuchElementException("run $runId not found")
            val from = DevelopmentRunState.valueOf(row[DevelopmentRunsTable.state])
            if (from.isTerminal) throw InvalidTransitionException("run $runId is already $from")
            check(from)
            val version = row[DevelopmentRunsTable.version]
            val updated = execUpdate(
                "UPDATE development_runs SET $setClause, version = version + 1, updated_at = ? WHERE run_id = ? AND version = ?",
                *setParams, utcNow(clock), runId.value, version,
            )
            if (updated != 1) throw VersionConflictException("run $runId changed concurrently")
            val taskId = row[DevelopmentRunsTable.taskId]
            val traceId = row[DevelopmentRunsTable.traceId]
            audit.append(this, runId, AuditEntry(actor = actor, action = action, result = "OK", resource = "task:$taskId", traceId = traceId))
            outbox.append(this, runId, version + 1, eventType, buildJsonObject { put("taskId", taskId); put("state", from.name) }.toString(), traceId)
        }
    }

    private fun transitionPayload(taskId: String, from: DevelopmentRunState?, to: DevelopmentRunState): String =
        buildJsonObject {
            put("taskId", taskId)
            if (from != null) put("from", from.name)
            put("to", to.name)
        }.toString()

    private fun ResultRow.toDevelopmentRun() = DevelopmentRun(
        runId = RunId(this[DevelopmentRunsTable.runId]),
        taskId = this[DevelopmentRunsTable.taskId],
        state = DevelopmentRunState.valueOf(this[DevelopmentRunsTable.state]),
        stateReason = this[DevelopmentRunsTable.stateReason],
        attempt = this[DevelopmentRunsTable.attempt],
        leaseOwner = this[DevelopmentRunsTable.leaseOwner],
        leaseGeneration = this[DevelopmentRunsTable.leaseGeneration],
        leaseExpiresAt = this[DevelopmentRunsTable.leaseExpiresAt],
        holdReason = this[DevelopmentRunsTable.holdReason],
        cancelRequested = this[DevelopmentRunsTable.cancelRequested],
        version = this[DevelopmentRunsTable.version],
        traceId = this[DevelopmentRunsTable.traceId],
        createdAt = this[DevelopmentRunsTable.createdAt],
        updatedAt = this[DevelopmentRunsTable.updatedAt],
    )
}
