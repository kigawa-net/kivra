package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.NotSentProof
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ReconciliationEvidence
import net.kigawa.kivra.core.RejectedProof
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TrustedEvidenceIssuers
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

/**
 * Restart sweep (run from the lease acquire transaction, see SqlLeaseStore.acquire): every IN_FLIGHT row last written by
 * an earlier lease generation belongs to a worker that died or lost its lease mid-call. Its external effect is unknown,
 * so it moves to UNKNOWN (fail-closed) and is never re-sent by the new owner. Returns the number of rows swept.
 */
internal fun Transaction.sweepStaleInFlight(lease: Lease, clock: Clock): Int {
    val stale = execQuery(
        """
        SELECT op_id, version FROM external_operations
         WHERE run_id = ? AND status = 'IN_FLIGHT' AND lease_generation < ?
         FOR UPDATE
        """.trimIndent(),
        lease.runId.value, lease.generation,
    ) { it.getString(1) to it.getLong(2) }
    var swept = 0
    for ((opId, version) in stale) {
        swept += execUpdate(
            """
            UPDATE external_operations
               SET status = 'UNKNOWN', version = version + 1, lease_generation = ?, updated_at = ?,
                   transition_reason = 'SWEPT_STALE_IN_FLIGHT', last_error_class = 'WORKER_LOST'
             WHERE op_id = ? AND status = 'IN_FLIGHT' AND version = ?
            """.trimIndent(),
            lease.generation, utcNow(clock), opId, version,
        )
    }
    return swept
}

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
    val evidenceIssuer: String? = null,
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
class OperationJournalRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val trustedIssuers: Set<String> = TrustedEvidenceIssuers.DEFAULT,
) {

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
     * [OperationStatus.next]. In particular, nothing leaves IN_FLIGHT except CONFIRMED and UNKNOWN here: a timeout or
     * 503 after the request may have left the process is not proof of non-execution. `IN_FLIGHT -> RETRYABLE` needs
     * [markNotSent] and `IN_FLIGHT -> FAILED` needs [markRejected]. `UNKNOWN -> RETRYABLE/CONFIRMED/FAILED` and
     * `RECONCILIATION_REQUIRED -> CONFIRMED/FAILED` need [reconcile].
     *
     * `RETRYABLE -> IN_FLIGHT` is the retry send. When the attempt budget ([JournalEntry.maxAttempts]) is already used up,
     * the row is NOT moved to IN_FLIGHT: it settles as FAILED with reason `MAX_ATTEMPTS_EXCEEDED` and the returned entry
     * shows that. Callers must check the returned status before sending.
     *
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
    ): JournalEntry = applyTransition(lease, opId, expectedVersion, to, Gate.Plain, externalRef, errorClass, error, nextActionAt)

    /**
     * Leaves UNKNOWN / RECONCILIATION_REQUIRED on the strength of an observation of the real external state.
     * The evidence is bound to this operation (run, op id, request hash), must come from a trusted issuer, must not be
     * observed in the future, and must not predate the moment the entry entered its current status. Persisted as the reason.
     * - CONFIRMED needs EXISTS (an already existing resource is adopted, never re-created; [externalRef] should be set),
     * - RETRYABLE needs ABSENT from an AUTHORITATIVE_ABSENCE or AUTHORITATIVE_IDEMPOTENCY source (never a plain lookup miss),
     * - FAILED needs FAILED_CONFIRMED.
     */
    fun reconcile(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        to: OperationStatus,
        evidence: ReconciliationEvidence,
        externalRef: String? = null,
        nextActionAt: LocalDateTime? = null,
    ): JournalEntry = applyTransition(lease, opId, expectedVersion, to, Gate.Evidence(evidence), externalRef, null, null, nextActionAt)

    /**
     * IN_FLIGHT -> RETRYABLE, only with a [NotSentProof] from a trusted issuer that the call never left the process.
     * The only evidence-free-looking route back into the retry loop, and it is still gated by the proof.
     */
    fun markNotSent(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        proof: NotSentProof,
        nextActionAt: LocalDateTime? = null,
    ): JournalEntry = applyTransition(lease, opId, expectedVersion, OperationStatus.RETRYABLE, Gate.NotSent(proof), null, null, null, nextActionAt)

    /** IN_FLIGHT -> FAILED, only with a [RejectedProof] from a trusted issuer that the external system explicitly refused. */
    fun markRejected(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        proof: RejectedProof,
        errorClass: String? = null,
        error: String? = null,
    ): JournalEntry = applyTransition(lease, opId, expectedVersion, OperationStatus.FAILED, Gate.Rejected(proof), null, errorClass, error, null)

    /** Why a status change is allowed. Only [Plain] comes without a proof, and it never reaches the proof-only edges. */
    private sealed interface Gate {
        data object Plain : Gate
        class Evidence(val evidence: ReconciliationEvidence) : Gate
        class NotSent(val proof: NotSentProof) : Gate
        class Rejected(val proof: RejectedProof) : Gate
    }

    private fun applyTransition(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        to: OperationStatus,
        gate: Gate,
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
        var target = to
        var reason: String? = null
        when (gate) {
            is Gate.Plain -> {
                if (to in from.evidenceGated) {
                    throw InvalidTransitionException("operation $opId: $from -> $to requires reconciliation evidence (use reconcile)")
                }
                if (!from.canTransitionTo(to)) throw InvalidTransitionException("operation $opId: $from -> $to is not allowed")
                if (from == OperationStatus.RETRYABLE && to == OperationStatus.IN_FLIGHT &&
                    row[ExternalOperationsTable.attempt] >= row[ExternalOperationsTable.maxAttempts]
                ) {
                    // The budget is spent. Settle instead of sending again (the entry was proven not sent, so FAILED is exact).
                    target = OperationStatus.FAILED
                    reason = "MAX_ATTEMPTS_EXCEEDED"
                }
            }
            is Gate.Evidence -> {
                val ev = gate.evidence
                if (to !in from.evidenceGated) throw InvalidTransitionException("operation $opId: $from -> $to is not an evidence-based transition")
                if (!ev.justifies(to)) {
                    throw InvalidTransitionException("operation $opId: ${ev.observation}/${ev.providerCapability} does not justify $from -> $to")
                }
                requireBound(lease, opId, row, ev.runId, ev.opId, ev.requestHash, ev.issuer, ev.observedAt)
                reason = "RECONCILED_${ev.observation.name}"
            }
            is Gate.NotSent -> {
                val p = gate.proof
                if (from != OperationStatus.IN_FLIGHT || to != OperationStatus.RETRYABLE) {
                    throw InvalidTransitionException("operation $opId: a NotSentProof cannot justify $from -> $to")
                }
                requireBound(lease, opId, row, p.runId, p.opId, p.requestHash, p.issuer, p.observedAt)
                reason = "PROOF_NOT_SENT"
            }
            is Gate.Rejected -> {
                val p = gate.proof
                if (from != OperationStatus.IN_FLIGHT || to != OperationStatus.FAILED) {
                    throw InvalidTransitionException("operation $opId: a RejectedProof cannot justify $from -> $to")
                }
                requireBound(lease, opId, row, p.runId, p.opId, p.requestHash, p.issuer, p.observedAt)
                reason = "PROOF_REJECTED"
            }
        }

        val now = utcNow(clock)
        val updated = ExternalOperationsTable.update({
            (ExternalOperationsTable.opId eq opId) and
                (ExternalOperationsTable.status eq from.name) and
                (ExternalOperationsTable.version eq expectedVersion)
        }) {
            it[status] = target.name
            it[version] = expectedVersion + 1
            it[leaseGeneration] = lease.generation
            it[updatedAt] = now
            if (target == OperationStatus.IN_FLIGHT) it[attempt] = row[ExternalOperationsTable.attempt] + 1
            if (externalRef != null) it[ExternalOperationsTable.externalRef] = externalRef
            if (errorClass != null) it[lastErrorClass] = errorClass
            if (error != null) it[lastError] = error
            it[ExternalOperationsTable.nextActionAt] = nextActionAt
            if (reason != null) it[transitionReason] = reason
            when (gate) {
                is Gate.Evidence -> {
                    val ev = gate.evidence
                    it[reconcileAttempts] = row[ExternalOperationsTable.reconcileAttempts] + 1
                    it[evidenceObservation] = ev.observation.name
                    it[evidenceRef] = ev.evidenceRef
                    it[evidenceObservedAt] = ev.observedAt
                    it[evidenceIssuer] = ev.issuer
                    it[providerCapability] = ev.providerCapability.name
                }
                is Gate.NotSent -> {
                    it[evidenceRef] = gate.proof.evidenceRef
                    it[evidenceObservedAt] = gate.proof.observedAt
                    it[evidenceIssuer] = gate.proof.issuer
                }
                is Gate.Rejected -> {
                    it[evidenceRef] = gate.proof.evidenceRef
                    it[evidenceObservedAt] = gate.proof.observedAt
                    it[evidenceIssuer] = gate.proof.issuer
                }
                is Gate.Plain -> Unit
            }
        }
        if (updated != 1) throw VersionConflictException("operation $opId changed concurrently")
        ExternalOperationsTable.selectAll().where { ExternalOperationsTable.opId eq opId }.single().toEntry()
    }

    /**
     * Binding checks shared by evidence and proofs: same run and operation, same request hash, a trusted issuer,
     * not in the future, and not older than the moment the row entered its current status.
     */
    private fun requireBound(
        lease: Lease,
        opId: String,
        row: ResultRow,
        runId: RunId,
        evidenceOpId: String,
        requestHash: String,
        issuer: String,
        observedAt: LocalDateTime,
    ) {
        if (runId != lease.runId || evidenceOpId != opId) {
            throw InvalidTransitionException("operation $opId: evidence is bound to another operation")
        }
        if (requestHash != row[ExternalOperationsTable.requestHash]) {
            throw InvalidTransitionException("operation $opId: evidence request hash does not match the operation")
        }
        if (issuer !in trustedIssuers) throw InvalidTransitionException("operation $opId: issuer '$issuer' is not trusted")
        if (observedAt.isAfter(utcNow(clock))) throw InvalidTransitionException("operation $opId: evidence observedAt is in the future")
        if (observedAt.isBefore(row[ExternalOperationsTable.updatedAt])) {
            throw InvalidTransitionException("operation $opId: evidence was observed before the operation entered its current status")
        }
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
        evidenceIssuer = this[ExternalOperationsTable.evidenceIssuer],
        providerCapability = this[ExternalOperationsTable.providerCapability],
        transitionReason = this[ExternalOperationsTable.transitionReason],
    )
}
