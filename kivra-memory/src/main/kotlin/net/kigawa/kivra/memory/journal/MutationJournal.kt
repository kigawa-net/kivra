package net.kigawa.kivra.memory.journal

import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.NewMemoryEntry
import net.kigawa.kivra.memory.SecretFilter
import java.time.Instant

/*
 * Durable Memory Mutation Journal port (issue #77, M5). Kivra's M0 `external_operations` (kivra-app, OperationJournalRepository)
 * is the single source of truth for external operations; this port is the M5-facing view of it. It deliberately does NOT
 * create a second operation history table.
 *
 * ## Status mapping (M5 view -> M0 `OperationStatus`, kivra-core States.kt)
 * | M5 `MutationStatus` | M0 `OperationStatus` | Notes |
 * |---|---|---|
 * | PREPARED | PREPARED | Intent recorded, no external call started. |
 * | PREPARED (after NotSent proof) | RETRYABLE (via IN_FLIGHT -> RETRYABLE, `markNotSent`) | Only a trusted NotSent proof may return to PREPARED. |
 * | IN_FLIGHT | IN_FLIGHT | External call may have reached the provider. Never re-sent by a different writer generation. |
 * | UNKNOWN_HELD | UNKNOWN (and RECONCILIATION_REQUIRED once operator-escalated) | Sent-or-unknown. Never re-sent automatically. |
 * | CONFIRMED | CONFIRMED | Success response, or a bound read-only lookup that found the request applied. |
 * | FAILED_PERMANENT | FAILED | Explicit refusal (local guard / contract validation). Not a retry candidate. |
 *
 * The M0 adapter is `M0MemoryMutationJournalAdapter` (kivra-app, Issue #91). It is the only place that knows M0 types.
 * [InMemoryMemoryMutationJournal] is the dev/test implementation: process-local and not durable.
 *
 * ## Invariants enforced by every implementation (see MutationJournalContract in tests)
 * 1. [prepare] is the atomic record step: one operationId maps to one immutable binding (identity, requestId, digest).
 * 2. Only the owning writer generation may move a row out of IN_FLIGHT; stale generations are rejected (fail-closed).
 * 3. A PREPARED row owned by another generation is re-dispatched only with a trusted [MutationEvidence] of NotSent.
 * 4. UNKNOWN_HELD is never returned to a dispatchable state by any automatic path.
 * 5. Records contain identifiers, digests and reason codes only. Payload bodies, titles and summaries are never stored here.
 */
enum class MutationStatus { PREPARED, IN_FLIGHT, UNKNOWN_HELD, CONFIRMED, FAILED_PERMANENT }

enum class MutationKind { SAVE, UPDATE, SUPERSEDE, DELETE }

/**
 * Mutation capability for the Memory write path. Stays [DISABLED] until recl `recl.memory.v1` is accepted with
 * server-side idempotency, authorisation and receipts (recl#20, kivra#71). Mirrors `ReclConfig.mutationsEnabled=false`.
 */
enum class MutationCapability { DISABLED, ENABLED }

private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
private val SAFE_REF = Regex("[A-Za-z0-9._:/-]{1,256}")
private val REQUEST_ID = Regex("[A-Za-z0-9-]{1,64}")
private val DIGEST = Regex("sha256:[0-9a-f]{64}")
private val REASON_CODE = Regex("[A-Z_]{1,64}")

/** Who wrote: tenant + principal bind every row, lookup and evidence. */
data class MutationIdentity(val tenantId: String, val principalId: String, val providerId: String, val contractVersion: String) {
    init {
        require(SAFE_ID.matches(tenantId) && SAFE_ID.matches(principalId)) { "invalid tenant or principal id" }
        require(SAFE_ID.matches(providerId) && SAFE_ID.matches(contractVersion)) { "invalid provider or contract version" }
    }
}

/**
 * A running writer. [writerGeneration] must be unique per replica start (for example a fresh value from the lease / epoch
 * store). A restarted replica gets a higher generation and sweeps the older generations' IN_FLIGHT rows to UNKNOWN_HELD.
 */
data class MutationWriter(val identity: MutationIdentity, val writerGeneration: Long) {
    init {
        require(writerGeneration >= 0) { "writer generation must not be negative" }
    }
}

/** Intent recorded before any external call. Carries digests and identifiers only, never the payload. */
data class MutationIntent(
    val operationId: String,
    val identity: MutationIdentity,
    val kind: MutationKind,
    val requestId: String,
    val payloadDigest: String,
    val expectedRevision: Long,
    val writerGeneration: Long,
    val executionId: String,
) {
    init {
        require(SAFE_ID.matches(operationId) && SAFE_ID.matches(executionId)) { "invalid operation or execution id" }
        require(REQUEST_ID.matches(requestId)) { "invalid request id" }
        require(DIGEST.matches(payloadDigest)) { "payload digest must be sha256:<64 hex>" }
        require(expectedRevision >= 0 && writerGeneration >= 0) { "revision and generation must not be negative" }
    }

    override fun toString(): String =
        "MutationIntent(operationId=$operationId, kind=$kind, providerId=${identity.providerId}, writerGeneration=$writerGeneration, expectedRevision=$expectedRevision)"
}

data class MutationRecord(
    val intent: MutationIntent,
    val status: MutationStatus,
    /** Optimistic-lock version (CAS): every transition increments it. */
    val version: Long,
    /** Generation of the writer currently owning the row (changes only on a proven takeover from PREPARED). */
    val ownerGeneration: Long,
    val attempts: Int,
    /** Machine code only (for example `PROVIDER_OUTCOME_UNKNOWN`, `SWEPT_WORKER_LOST`). Never free text. */
    val reasonCode: String?,
    val evidenceRef: String?,
    val updatedAt: Instant,
) {
    val operationId: String get() = intent.operationId
}

/**
 * Observation bound to one operation: identity, operationId and payload digest must match the stored row, and the
 * [issuer] must be on the trusted allowlist of the journal. [evidenceRef] is an artifact pointer (no content).
 */
data class MutationEvidence(
    val identity: MutationIdentity,
    val operationId: String,
    val payloadDigest: String,
    val issuer: String,
    val evidenceRef: String,
    val observedAt: Instant,
) {
    init {
        require(SAFE_ID.matches(operationId) && SAFE_ID.matches(issuer)) { "invalid evidence binding" }
        require(DIGEST.matches(payloadDigest)) { "invalid evidence digest" }
        require(SAFE_REF.matches(evidenceRef) && !SecretFilter.containsSecret(evidenceRef)) { "invalid evidence ref" }
    }
}

enum class ConflictCode { DIGEST_MISMATCH, IDENTITY_MISMATCH, REQUEST_ID_REUSED }

sealed interface PrepareResult {
    data class Prepared(val record: MutationRecord) : PrepareResult

    /** Same operation, same binding: the existing row is adopted, nothing new is written. */
    data class Existing(val record: MutationRecord) : PrepareResult
    data class Conflict(val code: ConflictCode) : PrepareResult
}

enum class RefusalCode { NOT_FOUND, NOT_PREPARED, NEEDS_NOT_SENT_PROOF, STALE_GENERATION, VERSION_CONFLICT, PROOF_REJECTED, BAD_TRANSITION }

sealed interface DispatchDecision {
    /** This caller is the single dispatch winner: the row is IN_FLIGHT under [MutationRecord.version]. */
    data class Won(val record: MutationRecord) : DispatchDecision
    data class Refused(val code: RefusalCode, val record: MutationRecord?) : DispatchDecision
}

/** A journal transition was refused. The message carries only the code. */
class MutationJournalRejectedException(val code: RefusalCode) : RuntimeException("journal transition refused: ${code.name}")

/**
 * Thrown by a provider adapter that can PROVE the request never left the process (for example a pre-send guard).
 * This is the only input that may move IN_FLIGHT back to PREPARED. Plain 429 / 5xx / timeouts never produce it.
 */
class NotSentDispatchException(val evidence: MutationEvidence) : RuntimeException("provider proved the request was not sent")

interface MemoryMutationJournalPort {
    /** Records the intent atomically before any external call. Re-entrant for the same operation and binding. */
    suspend fun prepare(intent: MutationIntent): PrepareResult

    /**
     * CAS: PREPARED -> IN_FLIGHT. Exactly one caller wins per row version. A PREPARED row owned by another generation
     * needs [notSent] (a trusted proof bound to this row); without it the caller is refused.
     */
    suspend fun beginDispatch(operationId: String, writerGeneration: Long, expectedVersion: Long, notSent: MutationEvidence? = null): DispatchDecision

    /** IN_FLIGHT -> CONFIRMED. Only the owning generation may settle. */
    suspend fun markConfirmed(operationId: String, writerGeneration: Long, expectedVersion: Long): MutationRecord

    /** IN_FLIGHT -> UNKNOWN_HELD. Sent-or-unknown: never re-sent automatically. */
    suspend fun markUnknownHeld(operationId: String, writerGeneration: Long, expectedVersion: Long, reasonCode: String): MutationRecord

    /** IN_FLIGHT -> FAILED_PERMANENT, only for an explicit refusal the caller is allowed to classify as permanent. */
    suspend fun markFailedPermanent(operationId: String, writerGeneration: Long, expectedVersion: Long, reasonCode: String): MutationRecord

    /** IN_FLIGHT -> PREPARED, only with a trusted NotSent [MutationEvidence] bound to this row. */
    suspend fun markNotSent(operationId: String, writerGeneration: Long, expectedVersion: Long, evidence: MutationEvidence): MutationRecord

    /** Restart sweep: IN_FLIGHT rows of older generations become UNKNOWN_HELD (`SWEPT_WORKER_LOST`). Returns the count. */
    suspend fun sweepStaleInFlight(writerGeneration: Long): Int

    /** UNKNOWN_HELD -> CONFIRMED on a trusted, identity-bound observation that the request was applied. */
    suspend fun settleFromLookup(operationId: String, expectedVersion: Long, evidence: MutationEvidence): MutationRecord

    suspend fun get(operationId: String): MutationRecord?

    /** Rows of one tenant + principal only. Another identity's rows are never visible here. */
    suspend fun unknownHeld(identity: MutationIdentity): List<MutationRecord>
}

object MutationIds {
    /** Stable across retries and restarts: same identity + same idempotency key -> same operation. */
    fun operationId(identity: MutationIdentity, key: IdempotencyKey): String =
        "mop-" + sha256Hex(listOf(identity.tenantId, identity.principalId, identity.providerId, identity.contractVersion, key.value).joinToString("\u0000")).take(40)

    /**
     * Stable request id sent to the provider. NOTE: a name-based UUID (v3 style), not UUIDv7; recl's id format is unconfirmed (要確認).
     */
    fun requestId(identity: MutationIdentity, key: IdempotencyKey): String =
        java.util.UUID.nameUUIDFromBytes(
            listOf("kivra-mem-req", identity.tenantId, identity.principalId, identity.providerId, key.value).joinToString("\u0000").toByteArray(),
        ).toString()
}

object MutationDigest {
    /**
     * Canonical digest of the write content. Only the digest is stored in the journal; the content is never persisted
     * by this module. Fields are length-independent joined with NUL, so the encoding is unambiguous for the given values.
     */
    fun of(kind: MutationKind, e: NewMemoryEntry): String {
        val parts = listOf(
            kind.name, e.kind.name, e.scope.joinToString(",") { it.value }, e.title, e.summary, e.body ?: "",
            e.sources.joinToString("\n") { listOf(it.type.name, it.uri, it.locator ?: "", it.contentHash ?: "", it.retrievedAt.toString()).joinToString("|") },
            e.provenance.name, e.confidence.toString(), e.importance.toString(), e.sensitivity.name, e.validFrom?.toString() ?: "",
            e.validUntil?.toString() ?: "", e.retention, e.supersedes.joinToString(",") { it.value }, e.pinned.toString(),
            e.writtenBy.taskId, e.writtenBy.executionId, e.writtenBy.createdBy,
        )
        return "sha256:" + sha256Hex(parts.joinToString("\u0000"))
    }
}

private fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun validReasonCode(code: String): String {
    require(REASON_CODE.matches(code)) { "reason code must be an upper-case machine code" }
    return code
}
