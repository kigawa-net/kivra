package net.kigawa.kivra.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedEvidenceTest {
    private val now = LocalDateTime.of(2026, 10, 12, 10, 0)
    private val subject = EvidenceSubject("t1", "run1", "op1", "EXKES", "EXKES_CREATE_WORKSPACE", "a".repeat(64), "2026-01")

    private fun ev(
        purpose: EvidencePurpose,
        observation: ExternalObservation? = null,
        capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
        principal: String = "exkes-adapter",
    ) = VerifiedEvidence(
        ref = VerifiedEvidenceRef("evd-1"),
        subject = subject,
        purpose = purpose,
        principal = principal,
        providerContractVersion = subject.providerContractVersion,
        capability = capability,
        observation = observation,
        requestDigest = subject.requestDigest,
        observedAt = now,
        expiresAt = now.plusHours(1),
        authorityEpoch = 1,
        capabilityRevision = 1,
        grantGeneration = 1,
        evidenceDigest = "b".repeat(64),
    )

    @Test
    fun `evidence must carry a SHA-256 digest and non-negative epoch values`() {
        assertFailsWith<IllegalArgumentException> {
            ev(EvidencePurpose.NOT_SENT).copy(evidenceDigest = "not-a-digest")
        }
        assertFailsWith<IllegalArgumentException> {
            ev(EvidencePurpose.NOT_SENT).copy(authorityEpoch = -1)
        }
    }

    @Test
    fun `NOT_SENT and REJECTED justify only their own edge`() {
        assertTrue(ev(EvidencePurpose.NOT_SENT).justifies(OperationStatus.RETRYABLE))
        assertFalse(ev(EvidencePurpose.NOT_SENT).justifies(OperationStatus.FAILED))
        assertTrue(ev(EvidencePurpose.REJECTED).justifies(OperationStatus.FAILED))
        assertFalse(ev(EvidencePurpose.REJECTED).justifies(OperationStatus.RETRYABLE))
    }

    @Test
    fun `EXISTS justifies CONFIRMED only, never a re-dispatch`() {
        val exists = ev(EvidencePurpose.RECONCILE_EXISTS, ExternalObservation.EXISTS, ProviderCapability.LOOKUP_BY_STABLE_REF)
        assertTrue(exists.justifies(OperationStatus.CONFIRMED))
        assertFalse(exists.justifies(OperationStatus.RETRYABLE))
    }

    @Test
    fun `ABSENT reopens the retry only with an authoritative capability`() {
        assertTrue(ev(EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE).justifies(OperationStatus.RETRYABLE))
        assertTrue(ev(EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_IDEMPOTENCY).justifies(OperationStatus.RETRYABLE))
        assertFalse(ev(EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.MANUAL_OPERATOR).justifies(OperationStatus.RETRYABLE))
        assertFalse(ev(EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.LOOKUP_BY_STABLE_REF).justifies(OperationStatus.RETRYABLE), "plain lookup miss")
    }

    @Test
    fun `FAILED_CONFIRMED justifies FAILED only`() {
        val failed = ev(EvidencePurpose.RECONCILE_FAILED, ExternalObservation.FAILED_CONFIRMED, ProviderCapability.MANUAL_OPERATOR)
        assertTrue(failed.justifies(OperationStatus.FAILED))
        assertFalse(failed.justifies(OperationStatus.CONFIRMED))
    }

    @Test
    fun `observation is required exactly for reconciliation purposes and the principal must be present`() {
        assertFailsWith<IllegalArgumentException> { ev(EvidencePurpose.RECONCILE_EXISTS, observation = null) }
        assertFailsWith<IllegalArgumentException> { ev(EvidencePurpose.NOT_SENT, observation = ExternalObservation.ABSENT) }
        assertFailsWith<IllegalArgumentException> { ev(EvidencePurpose.NOT_SENT, principal = " ") }
        assertEquals("exkes-adapter", ev(EvidencePurpose.NOT_SENT).principal)
    }
}
