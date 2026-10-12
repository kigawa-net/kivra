package net.kigawa.kivra.db

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceRefusedException
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VerifiedEvidence
import net.kigawa.kivra.core.VerifiedEvidenceRef

/**
 * TEST ONLY. In-memory stand-in for the [TrustedEvidenceAuthority] so the Operation Journal can be tested without the real
 * Authority (#60 / #61) and without a Provider Contract (#71). It is in the test source set on purpose and must never be
 * wired into production: it performs no signature check, trusts whatever the test registers, and keeps everything in memory.
 *
 * The test registers what "the adapter pipeline observed" with [register], declares the current Provider Contract and
 * capability with [setProviderContract] (the registry the real Authority would read), and can revoke, expire, or make the
 * Authority unavailable. It applies the same refusal rules as the production contract: unknown / other subject / other
 * purpose / expired / revoked refs are refused, a contract or capability change refuses old refs, and an
 * unavailable Authority throws [EvidenceAuthorityUnavailableException].
 */
class FakeTrustedEvidenceAuthority : TrustedEvidenceAuthority {

    /** A scripted observation, as the adapter pipeline would have reported it. */
    data class Observation(
        val subject: EvidenceSubject,
        val principal: String,
        val purpose: EvidencePurpose,
        val observation: ExternalObservation?,
        val capability: ProviderCapability,
        val observedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    )

    private class Issued(val evidence: VerifiedEvidence, var expiresAt: LocalDateTime, var revoked: Boolean)

    private data class Contract(val version: String, val capability: ProviderCapability)

    private val observations = ConcurrentHashMap<String, Observation>()
    private val issued = ConcurrentHashMap<String, Issued>()
    private val contracts = ConcurrentHashMap<String, Contract>()

    /** Principals this fake accepts. The principal is decided here, never by the caller of [verify]. */
    private val trustedPrincipals = setOf("exkes-adapter", "fusha-task-adapter", "github-adapter", "operator-console")

    @Volatile
    var available: Boolean = true

    fun register(observationRef: EvidenceObservationRef, observation: Observation) {
        observations[observationRef.value] = observation
    }

    /** The Provider Contract and capability the registry currently declares for [providerId]. */
    fun setProviderContract(providerId: String, contractVersion: String, capability: ProviderCapability) {
        contracts[providerId] = Contract(contractVersion, capability)
    }

    fun revoke(ref: VerifiedEvidenceRef) {
        issued[ref.value]?.revoked = true
    }

    /** Moves the expiry of [ref] into the past. */
    fun expire(ref: VerifiedEvidenceRef) {
        issued[ref.value]?.expiresAt = utcNow(java.time.Clock.systemUTC()).minusMinutes(1)
    }

    override suspend fun verify(
        subject: EvidenceSubject,
        observation: EvidenceObservationRef,
        requiredPurpose: EvidencePurpose,
    ): EvidenceVerification {
        checkAvailable()
        val obs = observations[observation.value] ?: return EvidenceVerification.Refused("unknown observation")
        if (obs.subject != subject) return EvidenceVerification.Refused("observation is bound to another subject")
        if (obs.principal !in trustedPrincipals) return EvidenceVerification.Refused("principal '${obs.principal}' is not trusted")
        if (obs.purpose != requiredPurpose) return EvidenceVerification.Refused("observation is not for purpose $requiredPurpose")
        val contract = contracts[subject.providerId] ?: return EvidenceVerification.Refused("provider is not certified")
        if (contract.version != subject.providerContractVersion) return EvidenceVerification.Refused("provider contract version differs")
        if (contract.capability != obs.capability) return EvidenceVerification.Refused("capability differs from the registry")

        val ref = VerifiedEvidenceRef("evd-${UUID.randomUUID()}")
        val evidence = VerifiedEvidence(
            ref = ref,
            subject = subject,
            purpose = obs.purpose,
            principal = obs.principal,
            providerContractVersion = contract.version,
            capability = contract.capability,
            observation = obs.observation,
            requestDigest = subject.requestDigest,
            observedAt = obs.observedAt,
            expiresAt = obs.expiresAt,
        )
        issued[ref.value] = Issued(evidence, obs.expiresAt, revoked = false)
        return EvidenceVerification.Verified(ref)
    }

    override suspend fun resolve(
        ref: VerifiedEvidenceRef,
        expected: EvidenceSubject,
        requiredPurpose: EvidencePurpose,
    ): VerifiedEvidence {
        checkAvailable()
        val record = issued[ref.value] ?: throw EvidenceRefusedException("unknown evidence ref")
        if (record.revoked) throw EvidenceRefusedException("evidence was revoked")
        if (!utcNow(java.time.Clock.systemUTC()).isBefore(record.expiresAt)) throw EvidenceRefusedException("evidence has expired")
        val evidence = record.evidence
        if (evidence.subject != expected) throw EvidenceRefusedException("evidence is bound to another subject")
        if (evidence.purpose != requiredPurpose) throw EvidenceRefusedException("evidence was verified for ${evidence.purpose}")
        val contract = contracts[expected.providerId] ?: throw EvidenceRefusedException("provider is no longer certified")
        if (contract.version != evidence.providerContractVersion) throw EvidenceRefusedException("provider contract version changed")
        if (contract.capability != evidence.capability) throw EvidenceRefusedException("capability changed to ${contract.capability}")
        return evidence
    }

    private fun checkAvailable() {
        if (!available) throw EvidenceAuthorityUnavailableException("fake authority is unavailable")
    }
}
