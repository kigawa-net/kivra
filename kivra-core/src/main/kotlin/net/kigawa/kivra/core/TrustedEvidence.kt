package net.kigawa.kivra.core

import java.time.LocalDateTime

/*
 * Trusted Evidence Authority (Issue #82; parents #60 / #61). Evidence that can move an Operation Journal entry out of
 * IN_FLIGHT / UNKNOWN is never a value a caller can construct. A caller presents an observation, the Authority verifies
 * it against the Provider Contract and Capability registry, persists it and returns an opaque [VerifiedEvidenceRef].
 * The Journal then resolves that ref again against the operation's own subject and purpose.
 *
 * Observation is not authority: an adapter reporting "not found" or naming an issuer does not gain any retry right.
 */

/**
 * Opaque identifier of evidence the [TrustedEvidenceAuthority] verified and persisted. Holding a ref grants nothing by
 * itself; the Journal resolves it against the operation subject, purpose and expiry before any transition.
 * The value is an authority-issued identifier, never a credential or a payload.
 */
@JvmInline
value class VerifiedEvidenceRef(val value: String) {
    init {
        require(value.isNotBlank()) { "evidence ref must not be blank" }
        require(value.length <= 255) { "evidence ref is too long" }
    }
}

/** Reference to the raw observation artifact (adapter response id, log line). Input to verification, never a permission. */
@JvmInline
value class EvidenceObservationRef(val value: String) {
    init {
        require(value.isNotBlank()) { "observation ref must not be blank" }
        require(value.length <= 255) { "observation ref is too long" }
    }
}

/**
 * The operation an evidence is about. Every field is bound: evidence for one subject is refused for any other.
 * Journal rows map to it as: tenantId / providerContractVersion from the row, runId / operationId from the row's keys,
 * providerId = external_system, actionId = op_type, requestDigest = request_hash.
 */
data class EvidenceSubject(
    val tenantId: String,
    val runId: String,
    val operationId: String,
    val providerId: String,
    val actionId: String,
    val requestDigest: String,
    val providerContractVersion: String,
)

/** What a piece of evidence is allowed to justify. Each purpose has exactly one journal edge (see the Journal). */
enum class EvidencePurpose {
    /** The request was never sent: justifies IN_FLIGHT -> RETRYABLE. */
    NOT_SENT,

    /** The external system explicitly refused the request: justifies IN_FLIGHT -> FAILED. */
    REJECTED,

    /** The resource exists: justifies UNKNOWN / RECONCILIATION_REQUIRED -> CONFIRMED (adopt, never re-create). */
    RECONCILE_EXISTS,

    /** Authoritatively absent: justifies UNKNOWN -> RETRYABLE. Needs an AUTHORITATIVE_* capability. */
    RECONCILE_ABSENT,

    /** The operation failed for good: justifies UNKNOWN / RECONCILIATION_REQUIRED -> FAILED. */
    RECONCILE_FAILED,
}

/** Result of [TrustedEvidenceAuthority.verify]. A refusal is normal control flow; the caller must not retry around it. */
sealed interface EvidenceVerification {
    data class Verified(val ref: VerifiedEvidenceRef) : EvidenceVerification

    data class Refused(val reason: String) : EvidenceVerification
}

/**
 * Evidence as the Authority holds it. Produced only by [TrustedEvidenceAuthority.resolve]; callers cannot choose
 * the principal, capability, contract version or expiry, they can only read them.
 *
 * - [principal]: the verified service principal that produced the observation (not a self-declared string).
 * - [capability]: the Provider Capability the Authority verified for this contract version at verification time.
 * - [observation]: the observed external state, for RECONCILE_* purposes only (null for NOT_SENT / REJECTED).
 * - [authorityEpoch], [capabilityRevision], [grantGeneration]: the Authority Epoch values the evidence was verified under.
 *   The Journal compares them with the current values inside the transition's own transaction (Issue #94). Only the
 *   Authority implementation constructs this value; the Journal never trusts these numbers without that comparison.
 * - [evidenceDigest]: the Authority-bound SHA-256 hex digest of the evidence content. Stored for audit only.
 */
data class VerifiedEvidence(
    val ref: VerifiedEvidenceRef,
    val subject: EvidenceSubject,
    val purpose: EvidencePurpose,
    val principal: String,
    val providerContractVersion: String,
    val capability: ProviderCapability,
    val observation: ExternalObservation?,
    val requestDigest: String,
    val observedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
    val authorityEpoch: Long,
    val capabilityRevision: Long,
    val grantGeneration: Long,
    val evidenceDigest: String,
) {
    init {
        require(principal.isNotBlank()) { "principal must not be blank" }
        require((purpose in RECONCILIATION) == (observation != null)) {
            "observation is required exactly for RECONCILE_* purposes"
        }
        require(authorityEpoch >= 0 && capabilityRevision >= 0 && grantGeneration >= 0) { "epoch values must not be negative" }
        require(SHA256_HEX.matches(evidenceDigest)) { "evidence digest must be a SHA-256 hex digest" }
    }

    /**
     * Whether this evidence justifies the journal target [to]. The purpose already fixes the edge; this checks the
     * observation and capability rules of design #11 section 4.1. A non-authoritative "not found" never justifies a retry.
     */
    fun justifies(to: OperationStatus): Boolean = when (purpose) {
        EvidencePurpose.NOT_SENT -> to == OperationStatus.RETRYABLE
        EvidencePurpose.REJECTED -> to == OperationStatus.FAILED
        EvidencePurpose.RECONCILE_EXISTS -> to == OperationStatus.CONFIRMED && observation == ExternalObservation.EXISTS
        EvidencePurpose.RECONCILE_ABSENT -> to == OperationStatus.RETRYABLE &&
            observation == ExternalObservation.ABSENT &&
            (capability == ProviderCapability.AUTHORITATIVE_ABSENCE || capability == ProviderCapability.AUTHORITATIVE_IDEMPOTENCY)
        EvidencePurpose.RECONCILE_FAILED -> to == OperationStatus.FAILED && observation == ExternalObservation.FAILED_CONFIRMED
    }

    companion object {
        private val RECONCILIATION = setOf(
            EvidencePurpose.RECONCILE_EXISTS, EvidencePurpose.RECONCILE_ABSENT, EvidencePurpose.RECONCILE_FAILED,
        )
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/**
 * Verifies observations and resolves evidence refs. Adapters and operator tooling call [verify] to obtain a ref; the
 * Operation Journal calls [resolve] before every evidence-gated transition. A real implementation (signature checks,
 * Provider Contract and Capability registry, persistence) is out of scope for #82 and tracked by #60 / #61 / #71.
 *
 * Epoch fencing (Issue #94): [resolve] is not the linearization point. [VerifiedEvidence] carries the Authority Epoch
 * values it was verified under, and the Journal re-compares them with its own copy of the current epoch in the same
 * database transaction as the status CAS. A Grant revoke or epoch bump that lands after [resolve] returned, but before
 * that commit, refuses the transition with no write. The epoch of record is the copy written to Kivra MariaDB; a
 * change made only on the external side and not yet synced is not seen by the commit. Closing that gap needs a separate
 * short-lived Permit / Revocation ACK protocol, which is not part of this slice.
 */
interface TrustedEvidenceAuthority {
    /** Verifies the observation for [subject] and [requiredPurpose] and persists it. Refusals are returned, not thrown. */
    suspend fun verify(
        subject: EvidenceSubject,
        observation: EvidenceObservationRef,
        requiredPurpose: EvidencePurpose,
    ): EvidenceVerification

    /**
     * Returns the stored evidence for [ref] if it is still valid for [expected] (same subject, same purpose, not expired,
     * not revoked, contract version and capability unchanged).
     *
     * @throws EvidenceRefusedException when the ref is unknown, bound to another subject / purpose, expired, revoked,
     *   or no longer matches the current contract or capability.
     * @throws EvidenceAuthorityUnavailableException when the Authority cannot answer. The Journal treats it as no evidence.
     */
    suspend fun resolve(
        ref: VerifiedEvidenceRef,
        expected: EvidenceSubject,
        requiredPurpose: EvidencePurpose,
    ): VerifiedEvidence
}

/** The Authority refused to resolve a ref. Mapped to an InvalidTransitionException by the Journal (fail-closed). */
class EvidenceRefusedException(message: String) : RuntimeException(message)

/** The Authority is unavailable. Nothing is persisted and the entry keeps its state (fail-closed). */
class EvidenceAuthorityUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
