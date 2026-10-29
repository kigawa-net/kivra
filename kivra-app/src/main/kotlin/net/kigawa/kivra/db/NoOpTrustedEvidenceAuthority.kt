package net.kigawa.kivra.db

import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidenceRef

/**
 * A [TrustedEvidenceAuthority] that always refuses evidence verification.
 * Used by components that need a [TrustedEvidenceAuthority] but don't actually perform evidence-gated transitions.
 * The auto-recovery worker uses this because it only does plain transitions (UNKNOWN → RECONCILIATION_REQUIRED → FAILED),
 * which don't require evidence verification.
 */
object NoOpTrustedEvidenceAuthority : TrustedEvidenceAuthority {
    override suspend fun verify(
        subject: EvidenceSubject,
        observation: EvidenceObservationRef,
        purpose: EvidencePurpose,
    ): EvidenceVerification = EvidenceVerification.Refused("NO_OP_AUTHORITY")

    override suspend fun resolve(
        ref: VerifiedEvidenceRef,
        subject: EvidenceSubject,
        purpose: EvidencePurpose,
    ): Nothing = throw EvidenceAuthorityUnavailableException("NoOpTrustedEvidenceAuthority does not support resolve")
}
