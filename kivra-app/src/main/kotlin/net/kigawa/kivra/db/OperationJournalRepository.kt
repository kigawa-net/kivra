package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class JournalEntry(
    val opId: String,
    val runId: RunId,
    val opType: String,
    val idempotencyKey: String,
    val externalSystem: String,
    val stableRef: String,
    val externalRef: String?,
    val requestHash: String,
    val status: OperationStatus,
    val attempt: Int,
    val maxAttempts: Int,
    val leaseGeneration: Long,
    val nextActionAt: LocalDateTime?,
    val lastErrorClass: String?,
    val lastError: String?,
    val version: Long,
)

data class PrepareOperation(
    val opType: String,
    val idempotencyKey: String,
    val externalSystem: String,
    val stableRef: String,
    val requestHash: String,
    val maxAttempts: Int = 3,
)

/** The same idempotency key was reused with different request content: a bug, never retried (design #11 section 4.2). */
class RequestHashMismatchException(message: String) : RuntimeException(message)

/**
 * Operation Journal (design #11 section 4). Records external side effects before they happen.
 *
 * - `(run_id, op_type, idempotency_key)` is unique, so [prepare] is re-entrant: calling it again returns the
 *   existing row instead of creating a second operation.
 * - Every write starts with [requireLease]: a worker whose lease expired or was superseded cannot touch the journal.
 * - Status changes are CAS on `(status, version)` and must follow [OperationStatus]'s state machine.
 *   `PREPARED/RETRYABLE -> IN_FLIGHT` increments `attempt`.
 */
class OperationJournalRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {

    fun prepare(lease: Lease, op: PrepareOperation): JournalEntry {
        try {
            return transaction(database) { prepareInTx(lease, op) }
        } catch (e: ExposedSQLException) {
            if (!e.isUniqueViolation()) throw e
            // Lost an insert race for the same key (cannot happen under one valid lease, but stay safe): adopt the winner.
            return transaction(database) { prepareInTx(lease, op) }
        }
    }

    private fun Transaction.prepareInTx(lease: Lease, op: PrepareOperation): JournalEntry {
        requireLease(lease)
        val existing = OperationJournalTable.selectAll().where {
            (OperationJournalTable.runId eq lease.runId.value) and
                (OperationJournalTable.opType eq op.opType) and
                (OperationJournalTable.idempotencyKey eq op.idempotencyKey)
        }.firstOrNull()
        if (existing != null) {
            if (existing[OperationJournalTable.requestHash] != op.requestHash) {
                throw RequestHashMismatchException("idempotency key ${op.idempotencyKey} reused with a different request")
            }
            return existing.toEntry()
        }
        val now = utcNow(clock)
        val opId = Ulid.generate(clock)
        OperationJournalTable.insert {
            it[OperationJournalTable.opId] = opId
            it[runId] = lease.runId.value
            it[opType] = op.opType
            it[idempotencyKey] = op.idempotencyKey
            it[externalSystem] = op.externalSystem
            it[stableRef] = op.stableRef
            it[requestHash] = op.requestHash
            it[status] = OperationStatus.PREPARED.name
            it[maxAttempts] = op.maxAttempts
            it[leaseGeneration] = lease.generation
            it[createdAt] = now
            it[updatedAt] = now
        }
        return OperationJournalTable.selectAll().where { OperationJournalTable.opId eq opId }.single().toEntry()
    }

    /**
     * CAS status change. [expectedVersion] must match; the transition must be allowed by the state machine.
     * Throws [net.kigawa.kivra.core.LeaseLostException], [VersionConflictException] or [InvalidTransitionException]
     * and writes nothing in those cases.
     */
    fun transition(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        to: OperationStatus,
        externalRef: String? = null,
        errorClass: String? = null,
        error: String? = null,
        nextActionAt: LocalDateTime? = null,
    ): JournalEntry = transaction(database) {
        requireLease(lease)
        val row = OperationJournalTable.selectAll().where {
            (OperationJournalTable.opId eq opId) and (OperationJournalTable.runId eq lease.runId.value)
        }.firstOrNull() ?: throw NoSuchElementException("operation $opId not found in run ${lease.runId}")
        val from = OperationStatus.valueOf(row[OperationJournalTable.status])
        if (row[OperationJournalTable.version] != expectedVersion) {
            throw VersionConflictException("operation $opId is at version ${row[OperationJournalTable.version]}, expected $expectedVersion")
        }
        if (!from.canTransitionTo(to)) throw InvalidTransitionException("operation $opId: $from -> $to is not allowed")

        val updated = OperationJournalTable.update({
            (OperationJournalTable.opId eq opId) and
                (OperationJournalTable.status eq from.name) and
                (OperationJournalTable.version eq expectedVersion)
        }) {
            it[status] = to.name
            it[version] = expectedVersion + 1
            it[leaseGeneration] = lease.generation
            it[updatedAt] = utcNow(clock)
            if (to == OperationStatus.IN_FLIGHT) it[attempt] = row[OperationJournalTable.attempt] + 1
            if (externalRef != null) it[OperationJournalTable.externalRef] = externalRef
            if (errorClass != null) it[lastErrorClass] = errorClass
            if (error != null) it[lastError] = error
            it[OperationJournalTable.nextActionAt] = nextActionAt
        }
        if (updated != 1) throw VersionConflictException("operation $opId changed concurrently")
        OperationJournalTable.selectAll().where { OperationJournalTable.opId eq opId }.single().toEntry()
    }

    fun get(opId: String): JournalEntry? = transaction(database) {
        OperationJournalTable.selectAll().where { OperationJournalTable.opId eq opId }.firstOrNull()?.toEntry()
    }

    fun findByKey(runId: RunId, opType: String, idempotencyKey: String): JournalEntry? = transaction(database) {
        OperationJournalTable.selectAll().where {
            (OperationJournalTable.runId eq runId.value) and
                (OperationJournalTable.opType eq opType) and
                (OperationJournalTable.idempotencyKey eq idempotencyKey)
        }.firstOrNull()?.toEntry()
    }

    private fun ResultRow.toEntry() = JournalEntry(
        opId = this[OperationJournalTable.opId],
        runId = RunId(this[OperationJournalTable.runId]),
        opType = this[OperationJournalTable.opType],
        idempotencyKey = this[OperationJournalTable.idempotencyKey],
        externalSystem = this[OperationJournalTable.externalSystem],
        stableRef = this[OperationJournalTable.stableRef],
        externalRef = this[OperationJournalTable.externalRef],
        requestHash = this[OperationJournalTable.requestHash],
        status = OperationStatus.valueOf(this[OperationJournalTable.status]),
        attempt = this[OperationJournalTable.attempt],
        maxAttempts = this[OperationJournalTable.maxAttempts],
        leaseGeneration = this[OperationJournalTable.leaseGeneration],
        nextActionAt = this[OperationJournalTable.nextActionAt],
        lastErrorClass = this[OperationJournalTable.lastErrorClass],
        lastError = this[OperationJournalTable.lastError],
        version = this[OperationJournalTable.version],
    )
}
