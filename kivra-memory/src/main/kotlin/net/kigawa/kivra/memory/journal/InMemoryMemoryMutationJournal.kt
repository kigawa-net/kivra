package net.kigawa.kivra.memory.journal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * DEV / TEST ONLY. Process-local, NON-DURABLE journal: it does not survive a JVM exit and it does not coordinate
 * across processes. Its CAS is a process-wide mutex, so it can only model several replicas inside one JVM (for example
 * in tests). Production must use the M0 `external_operations` adapter (not yet implemented, see the status mapping in
 * MutationJournal.kt).
 *
 * An EXPLICIT `KIVRA_ENVIRONMENT` of `dev` / `test` / `local` is required. Missing environment is
 * NOT assumed to be local: production deployments without the flag must fail closed.
 */
class InMemoryMemoryMutationJournal(
    private val trustedIssuers: Set<String> = emptySet(),
    private val clock: Clock = Clock.systemUTC(),
    environment: String? = System.getenv("KIVRA_ENVIRONMENT"),
) : MemoryMutationJournalPort {
    init {
        val env = environment?.trim()?.lowercase()
        check(env in DEV_ENVIRONMENTS) {
            "InMemoryMemoryMutationJournal is dev/test only (non-durable, not CAS across processes); refusing to start with KIVRA_ENVIRONMENT=$env"
        }
    }

    private val mutex = Mutex()
    private val rows = HashMap<String, MutationRecord>()
    private val byRequest = HashMap<Triple<String, String, String>, String>()

    override suspend fun prepare(intent: MutationIntent): PrepareResult = mutex.withLock {
        rows[intent.operationId]?.let { return@withLock bind(it.intent, intent, it) }
        val requestKey = Triple(intent.identity.tenantId, intent.identity.providerId, intent.requestId)
        if (byRequest.containsKey(requestKey)) return@withLock PrepareResult.Conflict(ConflictCode.REQUEST_ID_REUSED)
        val row = MutationRecord(intent, MutationStatus.PREPARED, 0, intent.writerGeneration, 0, null, null, clock.instant())
        rows[intent.operationId] = row
        byRequest[requestKey] = intent.operationId
        PrepareResult.Prepared(row)
    }

    private fun bind(stored: MutationIntent, incoming: MutationIntent, row: MutationRecord): PrepareResult = when {
        stored.identity != incoming.identity || stored.requestId != incoming.requestId || stored.kind != incoming.kind ||
            stored.executionId != incoming.executionId || stored.expectedRevision != incoming.expectedRevision ->
            PrepareResult.Conflict(ConflictCode.IDENTITY_MISMATCH)
        stored.payloadDigest != incoming.payloadDigest -> PrepareResult.Conflict(ConflictCode.DIGEST_MISMATCH)
        else -> PrepareResult.Existing(row)
    }

    override suspend fun beginDispatch(operationId: String, writerGeneration: Long, expectedVersion: Long, notSent: MutationEvidence?): DispatchDecision =
        mutex.withLock {
            val row = rows[operationId] ?: return@withLock DispatchDecision.Refused(RefusalCode.NOT_FOUND, null)
            if (row.version != expectedVersion) return@withLock DispatchDecision.Refused(RefusalCode.VERSION_CONFLICT, row)
            if (row.status != MutationStatus.PREPARED) return@withLock DispatchDecision.Refused(RefusalCode.NOT_PREPARED, row)
            if (notSent != null) {
                if (!proofAccepted(row, notSent, row.updatedAt)) return@withLock DispatchDecision.Refused(RefusalCode.PROOF_REJECTED, row)
            } else if (writerGeneration != row.ownerGeneration) {
                return@withLock DispatchDecision.Refused(RefusalCode.NEEDS_NOT_SENT_PROOF, row)
            }
            val next = row.copy(
                status = MutationStatus.IN_FLIGHT, version = row.version + 1, ownerGeneration = writerGeneration,
                attempts = row.attempts + 1, reasonCode = null, updatedAt = clock.instant(),
            )
            rows[operationId] = next
            DispatchDecision.Won(next)
        }

    override suspend fun markConfirmed(operationId: String, writerGeneration: Long, expectedVersion: Long): MutationRecord = mutex.withLock {
        transition(operationId, writerGeneration, expectedVersion, MutationStatus.IN_FLIGHT) { row ->
            row.copy(status = MutationStatus.CONFIRMED, reasonCode = null)
        }
    }

    override suspend fun markUnknownHeld(operationId: String, writerGeneration: Long, expectedVersion: Long, reasonCode: String): MutationRecord =
        mutex.withLock {
            val code = validReasonCode(reasonCode)
            transition(operationId, writerGeneration, expectedVersion, MutationStatus.IN_FLIGHT) { row ->
                row.copy(status = MutationStatus.UNKNOWN_HELD, reasonCode = code)
            }
        }

    override suspend fun markFailedPermanent(operationId: String, writerGeneration: Long, expectedVersion: Long, reasonCode: String): MutationRecord =
        mutex.withLock {
            val code = validReasonCode(reasonCode)
            transition(operationId, writerGeneration, expectedVersion, MutationStatus.IN_FLIGHT) { row ->
                row.copy(status = MutationStatus.FAILED_PERMANENT, reasonCode = code)
            }
        }

    override suspend fun markNotSent(operationId: String, writerGeneration: Long, expectedVersion: Long, evidence: MutationEvidence): MutationRecord =
        mutex.withLock {
            transition(operationId, writerGeneration, expectedVersion, MutationStatus.IN_FLIGHT) { row ->
                if (!proofAccepted(row, evidence, row.updatedAt)) throw MutationJournalRejectedException(RefusalCode.PROOF_REJECTED)
                row.copy(status = MutationStatus.PREPARED, reasonCode = "NOT_SENT_PROVEN", evidenceRef = evidence.evidenceRef)
            }
        }

    override suspend fun sweepStaleInFlight(writerGeneration: Long): Int = mutex.withLock {
        var swept = 0
        for ((opId, row) in rows.entries.toList()) {
            if (row.status == MutationStatus.IN_FLIGHT && row.ownerGeneration < writerGeneration) {
                rows[opId] = row.copy(
                    status = MutationStatus.UNKNOWN_HELD, version = row.version + 1, reasonCode = "SWEPT_WORKER_LOST", updatedAt = clock.instant(),
                )
                swept++
            }
        }
        swept
    }

    override suspend fun settleFromLookup(operationId: String, expectedVersion: Long, evidence: MutationEvidence): MutationRecord = mutex.withLock {
        val row = rows[operationId] ?: throw MutationJournalRejectedException(RefusalCode.NOT_FOUND)
        if (row.version != expectedVersion) throw MutationJournalRejectedException(RefusalCode.VERSION_CONFLICT)
        if (row.status != MutationStatus.UNKNOWN_HELD) throw MutationJournalRejectedException(RefusalCode.BAD_TRANSITION)
        if (!proofAccepted(row, evidence, row.updatedAt)) throw MutationJournalRejectedException(RefusalCode.PROOF_REJECTED)
        val next = row.copy(
            status = MutationStatus.CONFIRMED, version = row.version + 1, reasonCode = "LOOKUP_APPLIED",
            evidenceRef = evidence.evidenceRef, updatedAt = clock.instant(),
        )
        rows[operationId] = next
        next
    }

    override suspend fun get(operationId: String): MutationRecord? = mutex.withLock { rows[operationId] }

    override suspend fun unknownHeld(identity: MutationIdentity): List<MutationRecord> = mutex.withLock {
        rows.values.filter { it.status == MutationStatus.UNKNOWN_HELD && it.intent.identity == identity }.sortedBy { it.updatedAt }
    }

    /**
     * CAS helper. Must be called under [mutex]. Throws [MutationJournalRejectedException] and writes nothing on refusal.
     * Order of checks: owner generation first, so a late result from a superseded writer is always STALE_GENERATION.
     */
    private inline fun transition(
        operationId: String,
        writerGeneration: Long,
        expectedVersion: Long,
        from: MutationStatus,
        change: (MutationRecord) -> MutationRecord,
    ): MutationRecord {
        val row = rows[operationId] ?: throw MutationJournalRejectedException(RefusalCode.NOT_FOUND)
        if (row.ownerGeneration != writerGeneration) throw MutationJournalRejectedException(RefusalCode.STALE_GENERATION)
        if (row.version != expectedVersion) throw MutationJournalRejectedException(RefusalCode.VERSION_CONFLICT)
        if (row.status != from) throw MutationJournalRejectedException(RefusalCode.BAD_TRANSITION)
        val next = change(row).copy(version = row.version + 1, updatedAt = clock.instant())
        rows[operationId] = next
        return next
    }

    private fun proofAccepted(row: MutationRecord, ev: MutationEvidence, since: Instant): Boolean {
        val now = clock.instant()
        return ev.identity == row.intent.identity &&
            ev.operationId == row.intent.operationId &&
            ev.payloadDigest == row.intent.payloadDigest &&
            ev.issuer in trustedIssuers &&
            !ev.observedAt.isAfter(now) &&
            !ev.observedAt.isBefore(since)
    }

    private companion object {
        val DEV_ENVIRONMENTS = setOf("dev", "test", "local")
    }
}
