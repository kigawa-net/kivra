package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ReconciliationEvidence
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
    val evidenceObservation: String? = null,
    val evidenceRef: String? = null,
    val evidenceObservedAt: LocalDateTime? = null,
    val providerCapability: String? = null,
    val transitionReason: String? = null,
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
 * - Fail-closed on unknown results: an UNKNOWN entry is never re-dispatched on a guess. It goes to
 *   RECONCILIATION_REQUIRED, or settles through [reconcile] with persisted evidence of the real external state.
 *   [prepare] on an existing key returns the row as it is (status included), so a duplicate request or a new
 *   Worker adopts an UNKNOWN operation instead of re-sending it.
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
        val existing = ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.runId eq lease.runId.value) and
                (ExternalOperationsTable.opType eq op.opType) and
                (ExternalOperationsTable.idempotencyKey eq op.idempotencyKey)
        }.firstOrNull()
        if (existing != null) {
            if (existing[ExternalOperationsTable.requestHash] != op.requestHash) {
                throw RequestHashMismatchException("idempotency key ${op.idempotencyKey} reused with a different request")
            }
            return existing.toEntry()
        }
        val now = utcNow(clock)
        val opId = Ulid.generate(clock)
        ExternalOperationsTable.insert {
            it[ExternalOperationsTable.opId] = opId
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
        return ExternalOperationsTable.selectAll().where { ExternalOperationsTable.opId eq opId }.single().toEntry()
    }

    /**
     * CAS status change **without** evidence. [expectedVersion] must match; the transition must be in
     * [OperationStatus.next]. In particular `UNKNOWN -> RETRYABLE/CONFIRMED/FAILED` and
     * `RECONCILIATION_REQUIRED -> CONFIRMED/FAILED` are refused here (fail-closed): use [reconcile], which needs
     * [ReconciliationEvidence]. There is deliberately no evidence-less public path out of UNKNOWN other than
     * `RECONCILIATION_REQUIRED`.
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
    ): JournalEntry = applyTransition(lease, opId, expectedVersion, to, null, externalRef, errorClass, error, nextActionAt)

    /**
     * Leaves UNKNOWN / RECONCILIATION_REQUIRED on the strength of an observation of the real external state.
     * The evidence (observation, ref, observedAt, provider capability) is persisted as the transition reason.
     * - CONFIRMED needs EXISTS (an already existing resource is adopted, never re-created; [externalRef] should be set),
     * - RETRYABLE needs ABSENT from a source that makes a replay safe (not MANUAL_OPERATOR),
     * - FAILED needs FAILED_CONFIRMED,
     * - for UNKNOWN the observation must not predate the moment the entry became UNKNOWN.
     */
    fun reconcile(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        to: OperationStatus,
        evidence: ReconciliationEvidence,
        externalRef: String? = null,
        nextActionAt: LocalDateTime? = null,
    ): JournalEntry = applyTransition(lease, opId, expectedVersion, to, evidence, externalRef, null, null, nextActionAt)

    private fun applyTransition(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        to: OperationStatus,
        evidence: ReconciliationEvidence?,
        externalRef: String?,
        errorClass: String?,
        error: String?,
        nextActionAt: LocalDateTime?,
    ): JournalEntry = transaction(database) {
        requireLease(lease)
        val row = ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.opId eq opId) and (ExternalOperationsTable.runId eq lease.runId.value)
        }.firstOrNull() ?: throw NoSuchElementException("operation $opId not found in run ${lease.runId}")
        val from = OperationStatus.valueOf(row[ExternalOperationsTable.status])
        if (row[ExternalOperationsTable.version] != expectedVersion) {
            throw VersionConflictException("operation $opId is at version ${row[ExternalOperationsTable.version]}, expected $expectedVersion")
        }
        if (evidence == null) {
            if (to in from.evidenceGated) {
                throw InvalidTransitionException("operation $opId: $from -> $to requires reconciliation evidence (use reconcile)")
            }
            if (!from.canTransitionTo(to)) throw InvalidTransitionException("operation $opId: $from -> $to is not allowed")
        } else {
            if (to !in from.evidenceGated) throw InvalidTransitionException("operation $opId: $from -> $to is not an evidence-based transition")
            if (!evidence.justifies(to)) {
                throw InvalidTransitionException("operation $opId: ${evidence.observation}/${evidence.providerCapability} does not justify $from -> $to")
            }
            if (from == OperationStatus.UNKNOWN && evidence.observedAt.isBefore(row[ExternalOperationsTable.updatedAt])) {
                throw InvalidTransitionException("operation $opId: evidence was observed before the operation became UNKNOWN")
            }
        }

        val updated = ExternalOperationsTable.update({
            (ExternalOperationsTable.opId eq opId) and
                (ExternalOperationsTable.status eq from.name) and
                (ExternalOperationsTable.version eq expectedVersion)
        }) {
            it[status] = to.name
            it[version] = expectedVersion + 1
            it[leaseGeneration] = lease.generation
            it[updatedAt] = utcNow(clock)
            if (to == OperationStatus.IN_FLIGHT) it[attempt] = row[ExternalOperationsTable.attempt] + 1
            if (externalRef != null) it[ExternalOperationsTable.externalRef] = externalRef
            if (errorClass != null) it[lastErrorClass] = errorClass
            if (error != null) it[lastError] = error
            it[ExternalOperationsTable.nextActionAt] = nextActionAt
            if (evidence != null) {
                it[reconcileAttempts] = row[ExternalOperationsTable.reconcileAttempts] + 1
                it[evidenceObservation] = evidence.observation.name
                it[evidenceRef] = evidence.evidenceRef
                it[evidenceObservedAt] = evidence.observedAt
                it[providerCapability] = evidence.providerCapability.name
                it[transitionReason] = "RECONCILED_${evidence.observation.name}"
            }
        }
        if (updated != 1) throw VersionConflictException("operation $opId changed concurrently")
        ExternalOperationsTable.selectAll().where { ExternalOperationsTable.opId eq opId }.single().toEntry()
    }

    fun get(opId: String): JournalEntry? = transaction(database) {
        ExternalOperationsTable.selectAll().where { ExternalOperationsTable.opId eq opId }.firstOrNull()?.toEntry()
    }

    fun findByKey(runId: RunId, opType: String, idempotencyKey: String): JournalEntry? = transaction(database) {
        ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.runId eq runId.value) and
                (ExternalOperationsTable.opType eq opType) and
                (ExternalOperationsTable.idempotencyKey eq idempotencyKey)
        }.firstOrNull()?.toEntry()
    }

    private fun ResultRow.toEntry() = JournalEntry(
        opId = this[ExternalOperationsTable.opId],
        runId = RunId(this[ExternalOperationsTable.runId]),
        opType = this[ExternalOperationsTable.opType],
        idempotencyKey = this[ExternalOperationsTable.idempotencyKey],
        externalSystem = this[ExternalOperationsTable.externalSystem],
        stableRef = this[ExternalOperationsTable.stableRef],
        externalRef = this[ExternalOperationsTable.externalRef],
        requestHash = this[ExternalOperationsTable.requestHash],
        status = OperationStatus.valueOf(this[ExternalOperationsTable.status]),
        attempt = this[ExternalOperationsTable.attempt],
        maxAttempts = this[ExternalOperationsTable.maxAttempts],
        leaseGeneration = this[ExternalOperationsTable.leaseGeneration],
        nextActionAt = this[ExternalOperationsTable.nextActionAt],
        lastErrorClass = this[ExternalOperationsTable.lastErrorClass],
        lastError = this[ExternalOperationsTable.lastError],
        version = this[ExternalOperationsTable.version],
        evidenceObservation = this[ExternalOperationsTable.evidenceObservation],
        evidenceRef = this[ExternalOperationsTable.evidenceRef],
        evidenceObservedAt = this[ExternalOperationsTable.evidenceObservedAt],
        providerCapability = this[ExternalOperationsTable.providerCapability],
        transitionReason = this[ExternalOperationsTable.transitionReason],
    )
}
