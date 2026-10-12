package net.kigawa.kivra.db

import java.time.Clock
import java.time.ZoneOffset
import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceRefusedException
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidenceRef
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.canTransitionTo
import net.kigawa.kivra.memory.journal.ConflictCode
import net.kigawa.kivra.memory.journal.DispatchDecision
import net.kigawa.kivra.memory.journal.MemoryMutationJournalPort
import net.kigawa.kivra.memory.journal.MutationEvidence
import net.kigawa.kivra.memory.journal.MutationIdentity
import net.kigawa.kivra.memory.journal.MutationIntent
import net.kigawa.kivra.memory.journal.MutationJournalRejectedException
import net.kigawa.kivra.memory.journal.MutationKind
import net.kigawa.kivra.memory.journal.MutationRecord
import net.kigawa.kivra.memory.journal.MutationStatus
import net.kigawa.kivra.memory.journal.PrepareResult
import net.kigawa.kivra.memory.journal.RefusalCode
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
 * M0 adapter of the M5 [MemoryMutationJournalPort] (Issue #91). The Memory Mutation Journal is the M0 `external_operations`
 * table: one row per memory mutation (op_type [OP_TYPE], idempotency_key = the M5 operationId, V5 columns for the principal,
 * request id, expected revision, execution id and kind). No Memory canonical table is created. Only ids, digests and machine
 * reason codes are stored: never a body, a title, a summary or a secret.
 *
 * Fencing: a call with writer generation g runs under `Lease(runId, owner, g)`. The M0 lease check ([requireLease]) rejects it
 * unless g is the run's current lease generation, so a superseded writer is refused with STALE_GENERATION. Every transition
 * is one compare-and-swap (status, version) in one transaction; the repository's evidence-gated edges are used for the
 * NOT_SENT and RECONCILE transitions.
 *
 * Evidence: a NOT_SENT or RECONCILE proof is accepted only when the [TrustedEvidenceAuthority] resolves its ref for this row's
 * subject and purpose, the authority principal equals the claimed issuer, and identity / operationId / payload digest match
 * the row. The issuer string is never trusted by itself. A refused or unavailable Authority keeps the row where it is
 * (UNKNOWN_HELD stays UNKNOWN_HELD).
 *
 * Not wired into production: `mutationCapability=DISABLED` and `ReclConfig.mutationsEnabled=false` stay as they are.
 */
class M0MemoryMutationJournalAdapter(
    private val database: Database,
    private val authority: TrustedEvidenceAuthority,
    private val runId: RunId,
    private val owner: String,
    private val clock: Clock = Clock.systemUTC(),
) : MemoryMutationJournalPort {

    private val journal = OperationJournalRepository(database, authority, clock)

    /** A row as the port sees it, plus the M0 identifiers the repository needs and the subject evidence must bind to. */
    private class Snapshot(
        val journalId: String,
        val record: MutationRecord,
        val status: OperationStatus,
        val subject: EvidenceSubject,
        val attempt: Int,
        val maxAttempts: Int,
    )

    override suspend fun prepare(intent: MutationIntent): PrepareResult {
        require(intent.identity.providerId.length <= PROVIDER_WIDTH) { "provider id exceeds the external_system width" }
        return mapped {
            try {
                transaction(database) { prepareInTx(intent) }
            } catch (e: ExposedSQLException) {
                if (!e.isUniqueViolation()) throw e
                // Lost an insert race for the same operation or request id: decide again on the committed winner.
                transaction(database) { prepareInTx(intent) }
            }
        }
    }

    private fun Transaction.prepareInTx(intent: MutationIntent): PrepareResult {
        requireLease(leaseAt(intent.writerGeneration))
        rowByOperation(intent.operationId)?.let { existing ->
            val stored = snapshotOf(existing).record
            val si = stored.intent
            return when {
                si.identity != intent.identity || si.requestId != intent.requestId || si.kind != intent.kind ||
                    si.executionId != intent.executionId || si.expectedRevision != intent.expectedRevision ->
                    PrepareResult.Conflict(ConflictCode.IDENTITY_MISMATCH)
                si.payloadDigest != intent.payloadDigest -> PrepareResult.Conflict(ConflictCode.DIGEST_MISMATCH)
                else -> PrepareResult.Existing(stored)
            }
        }
        val requestBound = ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.tenantId eq intent.identity.tenantId) and
                (ExternalOperationsTable.externalSystem eq intent.identity.providerId) and
                (ExternalOperationsTable.requestId eq intent.requestId)
        }.any()
        if (requestBound) return PrepareResult.Conflict(ConflictCode.REQUEST_ID_REUSED)

        val now = utcNow(clock)
        val journalId = Ulid.generate(clock)
        val runValue = runId.value
        val digest = intent.payloadDigest.removePrefix(SHA256_PREFIX)
        ExternalOperationsTable.insert {
            it[opId] = journalId
            it[ExternalOperationsTable.runId] = runValue
            it[opType] = OP_TYPE
            it[idempotencyKey] = intent.operationId
            it[externalSystem] = intent.identity.providerId
            it[stableRef] = intent.requestId
            it[requestHash] = digest
            it[tenantId] = intent.identity.tenantId
            it[providerContractVersion] = intent.identity.contractVersion
            it[status] = OperationStatus.PREPARED.name
            it[leaseGeneration] = intent.writerGeneration
            it[createdAt] = now
            it[updatedAt] = now
            it[principalId] = intent.identity.principalId
            it[requestId] = intent.requestId
            it[expectedRevision] = intent.expectedRevision
            it[executionId] = intent.executionId
            it[memoryKind] = intent.kind.name
        }
        return PrepareResult.Prepared(snapshotOf(rowByJournalId(journalId)).record)
    }

    override suspend fun beginDispatch(
        operationId: String,
        writerGeneration: Long,
        expectedVersion: Long,
        notSent: MutationEvidence?,
    ): DispatchDecision {
        val snap = transaction(database) { rowByOperation(operationId)?.let { snapshotOf(it) } }
            ?: return DispatchDecision.Refused(RefusalCode.NOT_FOUND, null)
        val rec = snap.record
        if (rec.version != expectedVersion) return DispatchDecision.Refused(RefusalCode.VERSION_CONFLICT, rec)
        if (snap.status != OperationStatus.PREPARED && snap.status != OperationStatus.RETRYABLE) {
            return DispatchDecision.Refused(RefusalCode.NOT_PREPARED, rec)
        }
        val foreign = rec.ownerGeneration != writerGeneration
        if (notSent == null && foreign) return DispatchDecision.Refused(RefusalCode.NEEDS_NOT_SENT_PROOF, rec)
        return try {
            val ref = notSent?.let { proofRef(snap, it, EvidencePurpose.NOT_SENT) }
            if (ref != null && snap.status == OperationStatus.PREPARED && foreign) {
                // PREPARED is only written by prepare: a verified NOT_SENT ref lets a newer generation take the row over.
                mapped { journal.takeOverPrepared(leaseAt(writerGeneration), snap.journalId, expectedVersion, ref) }
                DispatchDecision.Won(transaction(database) { recordOf(snap.journalId) })
            } else {
                dispatchInTx(snap.journalId, writerGeneration, expectedVersion)
            }
        } catch (e: MutationJournalRejectedException) {
            DispatchDecision.Refused(e.code, rec)
        }
    }

    /** Plain CAS PREPARED / RETRYABLE -> IN_FLIGHT under the run lease fence. An exhausted RETRYABLE row settles as FAILED. */
    private fun dispatchInTx(journalId: String, writerGeneration: Long, expectedVersion: Long): DispatchDecision = mapped {
        transaction(database) {
            requireLease(leaseAt(writerGeneration))
            val snap = snapshotOf(rowByJournalId(journalId))
            if (snap.record.version != expectedVersion) return@transaction DispatchDecision.Refused(RefusalCode.VERSION_CONFLICT, snap.record)
            if (snap.status != OperationStatus.PREPARED && snap.status != OperationStatus.RETRYABLE) {
                return@transaction DispatchDecision.Refused(RefusalCode.NOT_PREPARED, snap.record)
            }
            if (snap.status == OperationStatus.RETRYABLE && snap.attempt >= snap.maxAttempts) {
                casMove(journalId, snap.status, expectedVersion, OperationStatus.FAILED, writerGeneration, snap.attempt, MAX_ATTEMPTS_REASON)
                return@transaction DispatchDecision.Refused(RefusalCode.NOT_PREPARED, recordOf(journalId))
            }
            val moved = casMove(journalId, snap.status, expectedVersion, OperationStatus.IN_FLIGHT, writerGeneration, snap.attempt + 1, null)
            if (moved != 1) throw MutationJournalRejectedException(RefusalCode.VERSION_CONFLICT)
            DispatchDecision.Won(recordOf(journalId))
        }
    }

    override suspend fun markConfirmed(operationId: String, writerGeneration: Long, expectedVersion: Long): MutationRecord = mapped {
        transaction(database) { settleInFlight(operationId, writerGeneration, expectedVersion, OperationStatus.CONFIRMED, null) }
    }

    override suspend fun markUnknownHeld(operationId: String, writerGeneration: Long, expectedVersion: Long, reasonCode: String): MutationRecord {
        require(REASON_CODE.matches(reasonCode)) { "reason code must be an upper-case machine code" }
        return mapped {
            transaction(database) { settleInFlight(operationId, writerGeneration, expectedVersion, OperationStatus.UNKNOWN, reasonCode) }
        }
    }

    /**
     * Not supported: M0 reaches FAILED from IN_FLIGHT only with a verified REJECTED ref, and this port carries no evidence for
     * it. Refused fail-closed (BAD_TRANSITION); the row keeps its state.
     */
    override suspend fun markFailedPermanent(operationId: String, writerGeneration: Long, expectedVersion: Long, reasonCode: String): MutationRecord =
        throw MutationJournalRejectedException(RefusalCode.BAD_TRANSITION)

    override suspend fun markNotSent(operationId: String, writerGeneration: Long, expectedVersion: Long, evidence: MutationEvidence): MutationRecord {
        val snap = snapshotByOperation(operationId)
        val ref = proofRef(snap, evidence, EvidencePurpose.NOT_SENT)
        mapped { journal.markNotSent(leaseAt(writerGeneration), snap.journalId, expectedVersion, ref) }
        return transaction(database) { recordOf(snap.journalId) }
    }

    override suspend fun sweepStaleInFlight(writerGeneration: Long): Int = mapped {
        transaction(database) {
            requireLease(leaseAt(writerGeneration))
            execUpdate(
                """
                UPDATE external_operations
                   SET status = 'UNKNOWN', version = version + 1, updated_at = ?, memory_reason = ?
                 WHERE run_id = ? AND op_type = ? AND status = 'IN_FLIGHT' AND lease_generation < ?
                """.trimIndent(),
                utcNow(clock), SWEPT_REASON, runId.value, OP_TYPE, writerGeneration,
            )
        }
    }

    /**
     * UNKNOWN -> CONFIRMED on a verified RECONCILE_EXISTS observation. Runs under the current lease of this adapter's owner: a
     * lookup has no writer generation of its own, so only the holder of the current lease may settle.
     */
    override suspend fun settleFromLookup(operationId: String, expectedVersion: Long, evidence: MutationEvidence): MutationRecord {
        val snap = snapshotByOperation(operationId)
        if (snap.status != OperationStatus.UNKNOWN && snap.status != OperationStatus.RECONCILIATION_REQUIRED) {
            throw MutationJournalRejectedException(RefusalCode.BAD_TRANSITION)
        }
        val gen = transaction(database) { currentLeaseGeneration() }
            ?: throw MutationJournalRejectedException(RefusalCode.STALE_GENERATION)
        val ref = proofRef(snap, evidence, EvidencePurpose.RECONCILE_EXISTS)
        mapped {
            try {
                journal.reconcile(leaseAt(gen), snap.journalId, expectedVersion, OperationStatus.CONFIRMED, ref)
            } catch (e: InvalidTransitionException) {
                // Not an existing resource (ABSENT / FAILED) or a bound that does not hold: the row stays UNKNOWN.
                throw MutationJournalRejectedException(RefusalCode.PROOF_REJECTED)
            }
        }
        return transaction(database) { recordOf(snap.journalId) }
    }

    override suspend fun get(operationId: String): MutationRecord? =
        transaction(database) { rowByOperation(operationId)?.let { snapshotOf(it).record } }

    override suspend fun unknownHeld(identity: MutationIdentity): List<MutationRecord> = transaction(database) {
        ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.runId eq runId.value) and (ExternalOperationsTable.opType eq OP_TYPE)
        }.map { snapshotOf(it).record }
            .filter { it.status == MutationStatus.UNKNOWN_HELD && it.intent.identity == identity }
            .sortedBy { it.updatedAt }
    }

    /** The subject a memory operation's evidence must bind to (test seam; null when the operation is unknown). */
    internal fun evidenceSubject(operationId: String): EvidenceSubject? =
        transaction(database) { rowByOperation(operationId)?.let { snapshotOf(it).subject } }

    /** CAS PREPARED/IN_FLIGHT settle with the owning generation only. Reads and checks the row in the same transaction. */
    private fun Transaction.settleInFlight(
        operationId: String,
        writerGeneration: Long,
        expectedVersion: Long,
        to: OperationStatus,
        reason: String?,
    ): MutationRecord {
        requireLease(leaseAt(writerGeneration))
        val row = rowByOperation(operationId) ?: throw MutationJournalRejectedException(RefusalCode.NOT_FOUND)
        val journalId = row[ExternalOperationsTable.opId]
        if (row[ExternalOperationsTable.leaseGeneration] != writerGeneration) throw MutationJournalRejectedException(RefusalCode.STALE_GENERATION)
        if (row[ExternalOperationsTable.version] != expectedVersion) throw MutationJournalRejectedException(RefusalCode.VERSION_CONFLICT)
        val from = OperationStatus.valueOf(row[ExternalOperationsTable.status])
        if (from != OperationStatus.IN_FLIGHT || !from.canTransitionTo(to)) throw MutationJournalRejectedException(RefusalCode.BAD_TRANSITION)
        if (casMove(journalId, from, expectedVersion, to, writerGeneration, null, reason) != 1) {
            throw MutationJournalRejectedException(RefusalCode.VERSION_CONFLICT)
        }
        return recordOf(journalId)
    }

    /**
     * The single CAS of every plain transition: `(op_id, status, version)` must still match. Lease and generation are checked by
     * the caller. Returns the number of rows changed (0 or 1).
     */
    private fun Transaction.casMove(
        journalId: String,
        from: OperationStatus,
        expectedVersion: Long,
        to: OperationStatus,
        writerGeneration: Long,
        attemptAfter: Int?,
        reason: String?,
    ): Int {
        val now = utcNow(clock)
        return ExternalOperationsTable.update({
            (ExternalOperationsTable.opId eq journalId) and
                (ExternalOperationsTable.status eq from.name) and
                (ExternalOperationsTable.version eq expectedVersion)
        }) {
            it[ExternalOperationsTable.status] = to.name
            it[ExternalOperationsTable.version] = expectedVersion + 1
            it[ExternalOperationsTable.leaseGeneration] = writerGeneration
            it[ExternalOperationsTable.updatedAt] = now
            it[ExternalOperationsTable.memoryReason] = reason
            if (attemptAfter != null) it[ExternalOperationsTable.attempt] = attemptAfter
        }
    }

    /**
     * Verifies a proof before any transition: binding to this row (identity, operationId, payload digest), then the
     * Authority's own verdict for the row's subject and [purpose], then the principal the Authority verified must equal the
     * claimed issuer. Any failure is PROOF_REJECTED and writes nothing.
     */
    private suspend fun proofRef(snap: Snapshot, ev: MutationEvidence, purpose: EvidencePurpose): VerifiedEvidenceRef {
        val rec = snap.record
        if (ev.identity != rec.intent.identity || ev.operationId != rec.operationId || ev.payloadDigest != rec.intent.payloadDigest) {
            rejectProof()
        }
        val ref = try {
            VerifiedEvidenceRef(ev.evidenceRef)
        } catch (e: IllegalArgumentException) {
            rejectProof()
        }
        val verified = try {
            authority.resolve(ref, snap.subject, purpose)
        } catch (e: EvidenceRefusedException) {
            rejectProof()
        } catch (e: EvidenceAuthorityUnavailableException) {
            rejectProof()
        }
        if (verified.principal != ev.issuer) rejectProof()
        return ref
    }

    private fun rejectProof(): Nothing = throw MutationJournalRejectedException(RefusalCode.PROOF_REJECTED)

    private fun leaseAt(writerGeneration: Long) = Lease(runId, owner, writerGeneration)

    private fun Transaction.rowByOperation(operationId: String): ResultRow? =
        ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.runId eq runId.value) and
                (ExternalOperationsTable.opType eq OP_TYPE) and
                (ExternalOperationsTable.idempotencyKey eq operationId)
        }.firstOrNull()

    private fun Transaction.rowByJournalId(journalId: String): ResultRow =
        ExternalOperationsTable.selectAll().where {
            (ExternalOperationsTable.opId eq journalId) and (ExternalOperationsTable.runId eq runId.value)
        }.singleOrNull() ?: throw MutationJournalRejectedException(RefusalCode.NOT_FOUND)

    private fun Transaction.recordOf(journalId: String): MutationRecord = snapshotOf(rowByJournalId(journalId)).record

    private fun snapshotByOperation(operationId: String): Snapshot =
        transaction(database) { rowByOperation(operationId)?.let { snapshotOf(it) } }
            ?: throw MutationJournalRejectedException(RefusalCode.NOT_FOUND)

    private fun Transaction.currentLeaseGeneration(): Long? =
        execQuery(
            "SELECT lease_generation FROM development_runs WHERE run_id = ? AND lease_owner = ?",
            runId.value, owner,
        ) { it.getLong(1) }.singleOrNull()

    private fun Transaction.snapshotOf(row: ResultRow): Snapshot {
        val journalId = row[ExternalOperationsTable.opId]
        val status = OperationStatus.valueOf(row[ExternalOperationsTable.status])
        val identity = MutationIdentity(
            tenantId = requireColumn(row[ExternalOperationsTable.tenantId], "tenant"),
            principalId = requireColumn(row[ExternalOperationsTable.principalId], "principal"),
            providerId = row[ExternalOperationsTable.externalSystem],
            contractVersion = requireColumn(row[ExternalOperationsTable.providerContractVersion], "contract version"),
        )
        val intent = MutationIntent(
            operationId = row[ExternalOperationsTable.idempotencyKey],
            identity = identity,
            kind = MutationKind.valueOf(requireColumn(row[ExternalOperationsTable.memoryKind], "memory kind")),
            requestId = requireColumn(row[ExternalOperationsTable.requestId], "request id"),
            payloadDigest = SHA256_PREFIX + row[ExternalOperationsTable.requestHash],
            expectedRevision = requireColumn(row[ExternalOperationsTable.expectedRevision], "expected revision"),
            writerGeneration = row[ExternalOperationsTable.leaseGeneration],
            executionId = requireColumn(row[ExternalOperationsTable.executionId], "execution id"),
        )
        val record = MutationRecord(
            intent = intent,
            status = mapStatus(status),
            version = row[ExternalOperationsTable.version],
            ownerGeneration = row[ExternalOperationsTable.leaseGeneration],
            attempts = row[ExternalOperationsTable.attempt],
            reasonCode = row[ExternalOperationsTable.memoryReason] ?: row[ExternalOperationsTable.transitionReason],
            evidenceRef = row[ExternalOperationsTable.evidenceRef],
            updatedAt = row[ExternalOperationsTable.updatedAt].toInstant(ZoneOffset.UTC),
        )
        return Snapshot(
            journalId = journalId,
            record = record,
            status = status,
            subject = journal.subjectOf(leaseAt(0), row),
            attempt = row[ExternalOperationsTable.attempt],
            maxAttempts = row[ExternalOperationsTable.maxAttempts],
        )
    }

    private fun requireColumn(value: String?, what: String): String =
        value ?: error("external_operations row has no $what binding")

    private fun requireColumn(value: Long?, what: String): Long =
        value ?: error("external_operations row has no $what binding")

    /** Journal status as the M5 port sees it (see the status mapping in MutationJournal.kt). */
    private fun mapStatus(status: OperationStatus): MutationStatus = when (status) {
        OperationStatus.PREPARED, OperationStatus.RETRYABLE -> MutationStatus.PREPARED
        OperationStatus.IN_FLIGHT -> MutationStatus.IN_FLIGHT
        OperationStatus.UNKNOWN, OperationStatus.RECONCILIATION_REQUIRED -> MutationStatus.UNKNOWN_HELD
        OperationStatus.CONFIRMED -> MutationStatus.CONFIRMED
        OperationStatus.FAILED -> MutationStatus.FAILED_PERMANENT
    }

    /**
     * Maps a repository refusal to the port's refusal code. A run that is on hold or cancelled is not a refusal of this write:
     * it propagates so the worker stops (fail-closed).
     */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: RunHaltedException) {
        throw e
    } catch (e: LeaseLostException) {
        throw MutationJournalRejectedException(RefusalCode.STALE_GENERATION)
    } catch (e: VersionConflictException) {
        throw MutationJournalRejectedException(RefusalCode.VERSION_CONFLICT)
    } catch (e: InvalidTransitionException) {
        throw MutationJournalRejectedException(RefusalCode.BAD_TRANSITION)
    } catch (e: NoSuchElementException) {
        throw MutationJournalRejectedException(RefusalCode.NOT_FOUND)
    }

    companion object {
        /** op_type of every memory mutation row in external_operations. */
        const val OP_TYPE = "MEMORY_MUTATION"

        private const val SHA256_PREFIX = "sha256:"
        private const val PROVIDER_WIDTH = 16
        private const val SWEPT_REASON = "SWEPT_WORKER_LOST"
        private const val MAX_ATTEMPTS_REASON = "MAX_ATTEMPTS_EXCEEDED"
        private val REASON_CODE = Regex("[A-Z_]{1,64}")
    }
}
