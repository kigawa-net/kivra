package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.core.EvidenceRefusedException
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidence
import net.kigawa.kivra.core.VerifiedEvidenceRef
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
    val tenantId: String? = null,
    val providerContractVersion: String? = null,
    val evidenceObservation: String? = null,
    val evidenceRef: String? = null,
    val evidenceObservedAt: LocalDateTime? = null,
    val evidenceIssuer: String? = null,
    val providerCapability: String? = null,
    val evidencePurpose: String? = null,
    val evidenceDigest: String? = null,
    val evidenceExpiresAt: LocalDateTime? = null,
    val transitionReason: String? = null,
)

/**
 * Operation Journal entry input. [tenantId] and [providerContractVersion] are stored with the row and become part of the
 * [EvidenceSubject] every later evidence check is bound to. There is no default: an operation without a tenant or a
 * contract version cannot settle through evidence (fail-closed).
 */
data class PrepareOperation(
    val opType: String,
    val idempotencyKey: String,
    val externalSystem: String,
    val stableRef: String,
    val requestHash: String,
    val tenantId: String,
    val providerContractVersion: String,
    val maxAttempts: Int = 3,
)

/** The same idempotency key was reused with different request content: a bug, never retried (design #11 section 4.2). */
class RequestHashMismatchException(message: String) : RuntimeException(message)

/** Existing idempotency key belongs to a different immutable provider/tenant contract. Never adopt it. */
class OperationBindingMismatchException(message: String) : RuntimeException(message)

/**
 * Operation Journal (design #11 section 4). Records external side effects before they happen.
 *
 * - `(run_id, op_type, idempotency_key)` is unique, so [prepare] is re-entrant: calling it again returns the
 *   existing row instead of creating a second operation.
 * - Every write starts with [requireLease]: a worker whose lease expired or was superseded cannot touch the journal.
 * - Status changes are CAS on `(status, version)` and must follow [OperationStatus]'s state machine.
 *   `PREPARED/RETRYABLE -> IN_FLIGHT` increments `attempt`.
 * - Fail-closed on unknown results: an UNKNOWN entry is never re-dispatched on a guess. It goes to
 *   RECONCILIATION_REQUIRED, or settles through [reconcile] with a [VerifiedEvidenceRef] that the
 *   [TrustedEvidenceAuthority] resolves for this exact operation.
 * - Evidence-gated transitions resolve the ref **before** the CAS and never change the row when the ref is refused,
 *   the Authority is unavailable, or the subject / purpose / expiry does not match. Such a call throws and leaves the
 *   entry in IN_FLIGHT / UNKNOWN / RECONCILIATION_REQUIRED.
 */
class OperationJournalRepository(
    private val database: Database,
    private val authority: TrustedEvidenceAuthority,
    private val clock: Clock = Clock.systemUTC(),
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
            // Request digest equality does NOT imply equality of the authenticated subject.
            // A duplicate key may only adopt the exact same immutable operation binding.
            if (existing[ExternalOperationsTable.externalSystem] != op.externalSystem ||
                existing[ExternalOperationsTable.stableRef] != op.stableRef ||
                existing[ExternalOperationsTable.tenantId] != op.tenantId ||
                existing[ExternalOperationsTable.providerContractVersion] != op.providerContractVersion ||
                existing[ExternalOperationsTable.maxAttempts] != op.maxAttempts
            ) {
                throw OperationBindingMismatchException("idempotency key reused with a different operation binding")
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
            it[tenantId] = op.tenantId
            it[providerContractVersion] = op.providerContractVersion
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
     * Leaves UNKNOWN / RECONCILIATION_REQUIRED on the strength of verified evidence of the real external state.
     * The ref is resolved for this operation's subject and must have the purpose that matches [to]:
     * - CONFIRMED needs RECONCILE_EXISTS (an existing resource is adopted, never re-created; [externalRef] should be set),
     * - RETRYABLE needs RECONCILE_ABSENT from an AUTHORITATIVE_ABSENCE / AUTHORITATIVE_IDEMPOTENCY capability
     *   (only from UNKNOWN, never a plain lookup miss),
     * - FAILED needs RECONCILE_FAILED.
     */
    suspend fun reconcile(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        to: OperationStatus,
        ref: VerifiedEvidenceRef,
        externalRef: String? = null,
        nextActionAt: LocalDateTime? = null,
    ): JournalEntry {
        val purpose = when (to) {
            OperationStatus.CONFIRMED -> EvidencePurpose.RECONCILE_EXISTS
            OperationStatus.RETRYABLE -> EvidencePurpose.RECONCILE_ABSENT
            OperationStatus.FAILED -> EvidencePurpose.RECONCILE_FAILED
            else -> throw InvalidTransitionException("operation $opId: reconcile cannot target $to")
        }
        val evidence = resolveFor(lease, opId, expectedVersion, purpose, ref)
        return applyTransition(lease, opId, expectedVersion, to, Gate.Verified(evidence), externalRef, null, null, nextActionAt)
    }

    /**
     * IN_FLIGHT -> RETRYABLE, only with a verified NOT_SENT ref for this operation: the call never left the process.
     * The only route back into the retry loop from IN_FLIGHT.
     */
    suspend fun markNotSent(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        ref: VerifiedEvidenceRef,
        nextActionAt: LocalDateTime? = null,
    ): JournalEntry {
        val evidence = resolveFor(lease, opId, expectedVersion, EvidencePurpose.NOT_SENT, ref)
        return applyTransition(lease, opId, expectedVersion, OperationStatus.RETRYABLE, Gate.Verified(evidence), null, null, null, nextActionAt)
    }

    /**
     * PREPARED -> IN_FLIGHT by a new writer generation (lease): the row was written by an earlier generation and never
     * dispatched. Only with a verified NOT_SENT ref for this operation (#91 M5 takeover). The lease fence of [lease] still applies.
     */
    suspend fun takeOverPrepared(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        ref: VerifiedEvidenceRef,
    ): JournalEntry {
        val evidence = resolveFor(lease, opId, expectedVersion, EvidencePurpose.NOT_SENT, ref, setOf(OperationStatus.PREPARED))
        return applyTransition(lease, opId, expectedVersion, OperationStatus.IN_FLIGHT, Gate.Takeover(evidence), null, null, null, null)
    }

    /** IN_FLIGHT -> FAILED, only with a verified REJECTED ref for this operation: the external system explicitly refused. */
    suspend fun markRejected(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        ref: VerifiedEvidenceRef,
        errorClass: String? = null,
        error: String? = null,
    ): JournalEntry {
        val evidence = resolveFor(lease, opId, expectedVersion, EvidencePurpose.REJECTED, ref)
        return applyTransition(lease, opId, expectedVersion, OperationStatus.FAILED, Gate.Verified(evidence), null, errorClass, error, null)
    }

    /** Why a status change is allowed. Only [Plain] comes without evidence, and it never reaches the evidence-only edges. */
    private sealed interface Gate {
        data object Plain : Gate
        sealed interface Proven : Gate {
            val evidence: VerifiedEvidence
        }
        class Verified(override val evidence: VerifiedEvidence) : Proven

        /** PREPARED -> IN_FLIGHT under a verified NOT_SENT ref (takeover by a newer generation). */
        class Takeover(override val evidence: VerifiedEvidence) : Proven
    }

    /** The journal edge each evidence purpose may justify: allowed source states and the single target state. */
    private fun EvidencePurpose.edge(): Pair<Set<OperationStatus>, OperationStatus> = when (this) {
        EvidencePurpose.NOT_SENT -> setOf(OperationStatus.IN_FLIGHT) to OperationStatus.RETRYABLE
        EvidencePurpose.REJECTED -> setOf(OperationStatus.IN_FLIGHT) to OperationStatus.FAILED
        EvidencePurpose.RECONCILE_EXISTS -> setOf(OperationStatus.UNKNOWN, OperationStatus.RECONCILIATION_REQUIRED) to OperationStatus.CONFIRMED
        EvidencePurpose.RECONCILE_ABSENT -> setOf(OperationStatus.UNKNOWN) to OperationStatus.RETRYABLE
        EvidencePurpose.RECONCILE_FAILED -> setOf(OperationStatus.UNKNOWN, OperationStatus.RECONCILIATION_REQUIRED) to OperationStatus.FAILED
    }

    /**
     * Step 1 of every evidence-gated transition, before the Authority is called: lease, row version and edge are checked
     * against the row, then the row's subject is handed to [TrustedEvidenceAuthority.resolve]. Nothing is written here.
     * A refusal becomes [InvalidTransitionException]; an unavailable Authority propagates as
     * [net.kigawa.kivra.core.EvidenceAuthorityUnavailableException]. In both cases the entry is unchanged.
     */
    private suspend fun resolveFor(
        lease: Lease,
        opId: String,
        expectedVersion: Long,
        purpose: EvidencePurpose,
        ref: VerifiedEvidenceRef,
        fromStates: Set<OperationStatus> = purpose.edge().first,
    ): VerifiedEvidence {
        val subject = transaction(database) {
            requireLease(lease)
            val row = rowOf(lease, opId)
            if (row[ExternalOperationsTable.version] != expectedVersion) {
                throw VersionConflictException("operation $opId is at version ${row[ExternalOperationsTable.version]}, expected $expectedVersion")
            }
            val from = OperationStatus.valueOf(row[ExternalOperationsTable.status])
            if (from !in fromStates) throw InvalidTransitionException("operation $opId: $purpose cannot justify a transition from $from")
            subjectOf(lease, row)
        }
        val evidence = try {
            authority.resolve(ref, subject, purpose)
        } catch (e: EvidenceRefusedException) {
            // Provider/Authority exception bodies can carry tokens or context content; never surface them via Journal errors.
            throw InvalidTransitionException("operation $opId: EVIDENCE_REFUSED")
        }
        // Defense in depth: the Authority must answer for exactly this subject and purpose.
        if (evidence.subject != subject || evidence.purpose != purpose || evidence.ref != ref) {
            throw InvalidTransitionException("operation $opId: authority answered for another subject or purpose")
        }
        return evidence
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
        val row = rowOf(lease, opId)
        val from = OperationStatus.valueOf(row[ExternalOperationsTable.status])
        if (row[ExternalOperationsTable.version] != expectedVersion) {
            throw VersionConflictException("operation $opId is at version ${row[ExternalOperationsTable.version]}, expected $expectedVersion")
        }
        var target = to
        var reason: String? = null
        when (gate) {
            is Gate.Plain -> {
                if (to in from.evidenceGated) {
                    throw InvalidTransitionException("operation $opId: $from -> $to requires verified evidence (use reconcile)")
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
            is Gate.Takeover -> {
                val ev = gate.evidence
                if (from != OperationStatus.PREPARED || to != OperationStatus.IN_FLIGHT || ev.purpose != EvidencePurpose.NOT_SENT) {
                    throw InvalidTransitionException("operation $opId: takeover needs a NOT_SENT ref on PREPARED -> IN_FLIGHT")
                }
                requireBound(lease, row, ev)
                reason = "VERIFIED_NOT_SENT_TAKEOVER"
            }
            is Gate.Verified -> {
                val ev = gate.evidence
                val (fromStates, edgeTarget) = ev.purpose.edge()
                if (from !in fromStates || to != edgeTarget) {
                    throw InvalidTransitionException("operation $opId: ${ev.purpose} cannot justify $from -> $to")
                }
                if (!ev.justifies(to)) {
                    throw InvalidTransitionException("operation $opId: ${ev.purpose} (${ev.observation}/${ev.capability}) does not justify $from -> $to")
                }
                requireBound(lease, row, ev)
                reason = when (ev.purpose) {
                    EvidencePurpose.NOT_SENT -> "VERIFIED_NOT_SENT"
                    EvidencePurpose.REJECTED -> "VERIFIED_REJECTED"
                    else -> "RECONCILED_${ev.observation!!.name}"
                }
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
                is Gate.Proven -> {
                    val ev = gate.evidence
                    if (ev.purpose in RECONCILIATION_PURPOSES) {
                        it[reconcileAttempts] = row[ExternalOperationsTable.reconcileAttempts] + 1
                    }
                    it[evidenceObservation] = ev.observation?.name
                    it[evidenceRef] = ev.ref.value
                    it[evidenceObservedAt] = ev.observedAt
                    it[evidenceIssuer] = ev.principal
                    it[providerCapability] = ev.capability.name
                    it[evidencePurpose] = ev.purpose.name
                    it[evidenceDigest] = ev.requestDigest
                    it[evidenceExpiresAt] = ev.expiresAt
                }
                is Gate.Plain -> Unit
            }
        }
        if (updated != 1) throw VersionConflictException("operation $opId changed concurrently")
        ExternalOperationsTable.selectAll().where { ExternalOperationsTable.opId eq opId }.single().toEntry()
    }

    /**
     * Checks that shared by all evidence: the evidence was resolved for this row (checked again here), it has not expired,
     * it is not observed in the future, and it is not older than the moment the row entered its current status.
     */
    private fun requireBound(lease: Lease, row: ResultRow, ev: VerifiedEvidence) {
        val opId = row[ExternalOperationsTable.opId]
        if (ev.subject != subjectOf(lease, row)) {
            throw InvalidTransitionException("operation $opId: evidence is bound to another operation")
        }
        val now = utcNow(clock)
        if (!now.isBefore(ev.expiresAt)) throw InvalidTransitionException("operation $opId: evidence has expired")
        if (ev.observedAt.isAfter(now)) throw InvalidTransitionException("operation $opId: evidence observedAt is in the future")
        if (ev.observedAt.isBefore(row[ExternalOperationsTable.updatedAt])) {
            throw InvalidTransitionException("operation $opId: evidence was observed before the operation entered its current status")
        }
    }

    private fun rowOf(lease: Lease, opId: String): ResultRow =
        ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.opId eq opId) and (ExternalOperationsTable.runId eq lease.runId.value)
        }.firstOrNull() ?: throw NoSuchElementException("operation $opId not found in run ${lease.runId}")

    /**
     * The subject an evidence must be bound to, read from the row. A row without tenant or contract version (written before
     * V4) has no subject and therefore cannot settle through evidence.
     */
    internal fun subjectOf(lease: Lease, row: ResultRow): EvidenceSubject {
        val opId = row[ExternalOperationsTable.opId]
        val tenant = row[ExternalOperationsTable.tenantId]
            ?: throw InvalidTransitionException("operation $opId has no tenant binding")
        val contract = row[ExternalOperationsTable.providerContractVersion]
            ?: throw InvalidTransitionException("operation $opId has no provider contract binding")
        return EvidenceSubject(
            tenantId = tenant,
            runId = lease.runId.value,
            operationId = opId,
            providerId = row[ExternalOperationsTable.externalSystem],
            actionId = row[ExternalOperationsTable.opType],
            requestDigest = row[ExternalOperationsTable.requestHash],
            providerContractVersion = contract,
        )
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
        tenantId = this[ExternalOperationsTable.tenantId],
        providerContractVersion = this[ExternalOperationsTable.providerContractVersion],
        evidenceObservation = this[ExternalOperationsTable.evidenceObservation],
        evidenceRef = this[ExternalOperationsTable.evidenceRef],
        evidenceObservedAt = this[ExternalOperationsTable.evidenceObservedAt],
        evidenceIssuer = this[ExternalOperationsTable.evidenceIssuer],
        providerCapability = this[ExternalOperationsTable.providerCapability],
        evidencePurpose = this[ExternalOperationsTable.evidencePurpose],
        evidenceDigest = this[ExternalOperationsTable.evidenceDigest],
        evidenceExpiresAt = this[ExternalOperationsTable.evidenceExpiresAt],
        transitionReason = this[ExternalOperationsTable.transitionReason],
    )

    private companion object {
        val RECONCILIATION_PURPOSES = setOf(
            EvidencePurpose.RECONCILE_EXISTS, EvidencePurpose.RECONCILE_ABSENT, EvidencePurpose.RECONCILE_FAILED,
        )
    }
}
