package net.kigawa.kivra.db

import java.security.MessageDigest
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
 *
 * Epochs (Issue #94): each provider carries an Authority Epoch, a Capability Revision and a Grant Generation. A ref
 * binds the values current at [verify]; [resolve] refuses it once any of them moved. [afterResolve] runs after a
 * successful resolve and before it returns, so a test can move the Authority at exactly that point (deterministic
 * barrier). The Kivra DB copy is updated separately with [TestDb.syncAuthorityEpoch], which models the Authority sync.
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

    /** The Authority-side state of one provider. */
    data class ProviderState(
        val contractVersion: String,
        val capability: ProviderCapability,
        val capabilityRevision: Long,
        val authorityEpoch: Long,
        val grantGeneration: Long,
    )

    private class Issued(val evidence: VerifiedEvidence, var expiresAt: LocalDateTime, var revoked: Boolean)

    private val observations = ConcurrentHashMap<String, Observation>()
    private val issued = ConcurrentHashMap<String, Issued>()
    private val providers = ConcurrentHashMap<String, ProviderState>()

    /** Principals this fake accepts. The principal is decided here, never by the caller of [verify]. */
    private val trustedPrincipals = setOf("exkes-adapter", "fusha-task-adapter", "github-adapter", "operator-console")

    @Volatile
    var available: Boolean = true

    /** Runs after a successful [resolve] checks, before it returns the evidence. Used by tests as a deterministic barrier. */
    @Volatile
    var afterResolve: ((VerifiedEvidence) -> Unit)? = null

    fun register(observationRef: EvidenceObservationRef, observation: Observation) {
        observations[observationRef.value] = observation
    }

    /**
     * The Provider Contract and capability the registry currently declares for [providerId]. A change of contract version
     * or capability bumps the capability revision. The authority epoch and grant generation are left alone.
     */
    fun setProviderContract(providerId: String, contractVersion: String, capability: ProviderCapability) {
        providers.compute(providerId) { _, cur ->
            when {
                cur == null -> ProviderState(contractVersion, capability, 1, 1, 1)
                cur.contractVersion == contractVersion && cur.capability == capability -> cur
                else -> cur.copy(contractVersion = contractVersion, capability = capability, capabilityRevision = cur.capabilityRevision + 1)
            }
        }
    }

    /** Authority-wide change: every ref verified before this call is refused by [resolve]. */
    fun bumpAuthorityEpoch(providerId: String) {
        providers.computeIfPresent(providerId) { _, s -> s.copy(authorityEpoch = s.authorityEpoch + 1) }
    }

    /** Provider Grant revoked: every ref verified before this call is refused by [resolve]. */
    fun revokeGrant(providerId: String) {
        providers.computeIfPresent(providerId) { _, s -> s.copy(grantGeneration = s.grantGeneration + 1) }
    }

    fun state(providerId: String): ProviderState? = providers[providerId]

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
        val state = providers[subject.providerId] ?: return EvidenceVerification.Refused("provider is not certified")
        if (state.contractVersion != subject.providerContractVersion) return EvidenceVerification.Refused("provider contract version differs")
        if (state.capability != obs.capability) return EvidenceVerification.Refused("capability differs from the registry")

        val ref = VerifiedEvidenceRef("evd-${UUID.randomUUID()}")
        val evidence = VerifiedEvidence(
            ref = ref,
            subject = subject,
            purpose = obs.purpose,
            principal = obs.principal,
            providerContractVersion = state.contractVersion,
            capability = state.capability,
            observation = obs.observation,
            requestDigest = subject.requestDigest,
            observedAt = obs.observedAt,
            expiresAt = obs.expiresAt,
            authorityEpoch = state.authorityEpoch,
            capabilityRevision = state.capabilityRevision,
            grantGeneration = state.grantGeneration,
            evidenceDigest = contentDigest(ref, subject, obs),
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
        val state = providers[expected.providerId] ?: throw EvidenceRefusedException("provider is no longer certified")
        if (state.contractVersion != evidence.providerContractVersion) throw EvidenceRefusedException("provider contract version changed")
        if (state.capability != evidence.capability) throw EvidenceRefusedException("capability changed to ${state.capability}")
        if (state.capabilityRevision != evidence.capabilityRevision) throw EvidenceRefusedException("capability revision changed")
        if (state.authorityEpoch != evidence.authorityEpoch) throw EvidenceRefusedException("authority epoch changed")
        if (state.grantGeneration != evidence.grantGeneration) throw EvidenceRefusedException("provider grant was revoked")
        afterResolve?.invoke(evidence)
        return evidence
    }

    private fun checkAvailable() {
        if (!available) throw EvidenceAuthorityUnavailableException("fake authority is unavailable")
    }

    /** Authority-bound digest over the evidence content. Hashes identifiers only, never a token or payload. */
    private fun contentDigest(ref: VerifiedEvidenceRef, subject: EvidenceSubject, obs: Observation): String {
        val material = listOf(
            ref.value, subject.tenantId, subject.operationId, subject.providerId, subject.requestDigest,
            obs.purpose.name, obs.observation?.name ?: "-", obs.principal,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
