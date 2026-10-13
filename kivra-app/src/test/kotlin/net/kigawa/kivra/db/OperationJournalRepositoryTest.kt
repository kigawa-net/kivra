package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceRefusedException
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.VerifiedEvidenceRef
import net.kigawa.kivra.core.VerifiedEvidence
import net.kigawa.kivra.core.TrustedEvidenceAuthority
import net.kigawa.kivra.core.VersionConflictException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)
private val HASH = "a".repeat(64)
private const val TENANT = "tenant-1"
private const val PROVIDER = "EXKES"
private const val CONTRACT = "2026-01"

private fun op(key: String = "run:x:workspace", hash: String = HASH, maxAttempts: Int = 3) = PrepareOperation(
    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = key, externalSystem = PROVIDER, stableRef = "kivra-run-x",
    requestHash = hash, tenantId = TENANT, providerContractVersion = CONTRACT, maxAttempts = maxAttempts,
)

private fun now() = utcNow(Clock.systemUTC())

private fun blocking(block: suspend () -> Any?) = runBlocking { block() }

/** The subject the Journal derives from [entry]. */
private fun subjectOf(entry: JournalEntry, tenant: String = TENANT, digest: String = entry.requestHash, contract: String = CONTRACT) =
    EvidenceSubject(tenant, entry.runId.value, entry.opId, entry.externalSystem, entry.opType, digest, contract)

/**
 * Registers an observation the adapter pipeline reported and asks the (fake) Authority to verify it. [observed] is the
 * subject the observation was made for, [asked] the subject the verification is requested for: they differ in the
 * negative tests.
 */
private fun TestDb.verifyWith(
    observed: EvidenceSubject,
    asked: EvidenceSubject,
    purpose: EvidencePurpose,
    observation: ExternalObservation? = null,
    capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
    principal: String = "exkes-adapter",
    observedAt: LocalDateTime = now(),
    expiresAt: LocalDateTime = now().plusHours(1),
): EvidenceVerification {
    evidence.setProviderContract(PROVIDER, CONTRACT, capability)
    val obsRef = EvidenceObservationRef("obs-${UUID.randomUUID()}")
    evidence.register(obsRef, FakeTrustedEvidenceAuthority.Observation(observed, principal, purpose, observation, capability, observedAt, expiresAt))
    return runBlocking { evidence.verify(asked, obsRef, purpose) }
}

/** A verified ref for [entry]'s own subject. */
private fun TestDb.issue(
    entry: JournalEntry,
    purpose: EvidencePurpose,
    observation: ExternalObservation? = null,
    capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
    principal: String = "exkes-adapter",
    observedAt: LocalDateTime = now(),
): VerifiedEvidenceRef {
    val s = subjectOf(entry)
    return when (val v = verifyWith(s, s, purpose, observation, capability, principal, observedAt)) {
        is EvidenceVerification.Verified -> v.ref
        is EvidenceVerification.Refused -> error("verify refused: ${v.reason}")
    }
}

class OperationJournalRepositoryTest {
    private fun TestDb.leasedRun(): Lease {
        val run = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        return leases.acquire(run.runId, "w", TTL)!!
    }

    private fun TestDb.inFlight(lease: Lease, key: String = "run:x:workspace", maxAttempts: Int = 3): JournalEntry {
        val p = journal.prepare(lease, op(key = key, maxAttempts = maxAttempts))
        return journal.transition(lease, p.opId, p.version, OperationStatus.IN_FLIGHT)
    }

    /** An IN_FLIGHT call that timed out or got a 503: the external side may already have executed it. */
    private fun TestDb.unknownOp(lease: Lease, key: String = "run:x:workspace"): JournalEntry {
        val e = inFlight(lease, key)
        return journal.transition(lease, e.opId, e.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT", error = "timed out")
    }

    @Test
    fun `prepare records PREPARED and is re-entrant for the same idempotency key`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val first = db.journal.prepare(lease, op())
        assertEquals(OperationStatus.PREPARED, first.status)
        assertEquals(TENANT, first.tenantId)
        assertEquals(CONTRACT, first.providerContractVersion)
        val again = db.journal.prepare(lease, op())
        assertEquals(first.opId, again.opId)
        assertEquals(1, db.count("external_operations"))
    }

    @Test
    fun `same key with a different request hash is a bug and is refused`() = TestDb().use { db ->
        val lease = db.leasedRun()
        db.journal.prepare(lease, op())
        assertFailsWith<RequestHashMismatchException> { db.journal.prepare(lease, op(hash = "b".repeat(64))) }
        assertEquals(1, db.count("external_operations"))
    }

    @Test
    fun `idempotent prepare refuses cross-tenant or provider-contract rebind even with matching digest`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val original = db.journal.prepare(lease, op())
        val mismatches = listOf(
            op().copy(tenantId = "tenant-2"),
            op().copy(providerContractVersion = "2026-02"),
            op().copy(externalSystem = "OTHER_PROVIDER"),
            op().copy(stableRef = "other-workspace"),
            op().copy(maxAttempts = 99),
        )
        for (changed in mismatches) {
            assertFailsWith<OperationBindingMismatchException> { db.journal.prepare(lease, changed) }
        }
        val adopted = db.journal.prepare(lease, op())
        assertEquals(original.opId, adopted.opId)
        assertEquals(original.tenantId, adopted.tenantId)
        assertEquals(original.providerContractVersion, adopted.providerContractVersion)
        assertEquals(1, db.count("external_operations"))
    }

    @Test
    fun `the database enforces uniqueness of run, type and idempotency key`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val entry = db.journal.prepare(lease, op())
        val ex = assertFailsWith<ExposedSQLException> {
            transaction(db.database) {
                ExternalOperationsTable.insert {
                    it[opId] = Ulid.generate()
                    it[runId] = lease.runId.value
                    it[opType] = entry.opType
                    it[idempotencyKey] = entry.idempotencyKey
                    it[externalSystem] = "EXKES"
                    it[stableRef] = "dup"
                    it[requestHash] = HASH
                    it[status] = "PREPARED"
                    it[leaseGeneration] = 1
                    it[createdAt] = utcNow(java.time.Clock.systemUTC())
                    it[updatedAt] = utcNow(java.time.Clock.systemUTC())
                }
            }
        }
        assertEquals(true, ex.isUniqueViolation())
    }

    @Test
    fun `happy path PREPARED to IN_FLIGHT to CONFIRMED counts the attempt`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, op())
        val inFlight = db.journal.transition(lease, prepared.opId, prepared.version, OperationStatus.IN_FLIGHT)
        assertEquals(1, inFlight.attempt)
        val done = db.journal.transition(lease, prepared.opId, inFlight.version, OperationStatus.CONFIRMED, externalRef = "ws-123")
        assertEquals(OperationStatus.CONFIRMED, done.status)
        assertEquals("ws-123", done.externalRef)
        assertEquals(lease.generation, done.leaseGeneration)
    }

    @Test
    fun `UNKNOWN can never go straight to RETRYABLE or any settled state without evidence`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        for (to in listOf(OperationStatus.RETRYABLE, OperationStatus.CONFIRMED, OperationStatus.FAILED, OperationStatus.IN_FLIGHT)) {
            assertFailsWith<InvalidTransitionException>("UNKNOWN -> $to") { db.journal.transition(lease, unknown.opId, unknown.version, to) }
        }
        val after = db.journal.get(unknown.opId)!!
        assertEquals(OperationStatus.UNKNOWN, after.status)
        assertEquals(unknown.version, after.version)
        assertEquals(1, after.attempt, "nothing was re-dispatched")
        // The evidence-free exit is reconciliation.
        val rr = db.journal.transition(lease, unknown.opId, unknown.version, OperationStatus.RECONCILIATION_REQUIRED)
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, rr.status)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, rr.opId, rr.version, OperationStatus.CONFIRMED) }
    }

    // ---- IN_FLIGHT: a timeout / 503 after sending is not proof of anything -------------------------------------------

    @Test
    fun `after a timeout or 503 IN_FLIGHT cannot go to RETRYABLE or FAILED through transition()`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        for (to in listOf(OperationStatus.RETRYABLE, OperationStatus.FAILED)) {
            assertFailsWith<InvalidTransitionException>("IN_FLIGHT -> $to must need a proof") { db.journal.transition(lease, e.opId, e.version, to) }
        }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
        assertEquals(e.version, db.journal.get(e.opId)!!.version)
    }

    @Test
    fun `IN_FLIGHT goes to UNKNOWN on a timeout without evidence and never to a retry`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val unknown = db.journal.transition(lease, e.opId, e.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT")
        assertEquals(OperationStatus.UNKNOWN, unknown.status)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, e.opId, unknown.version, OperationStatus.RETRYABLE) }
    }

    @Test
    fun `IN_FLIGHT to RETRYABLE needs a verified NOT_SENT ref bound to this operation`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        // A string that the Authority never issued is not evidence.
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.markNotSent(lease, e.opId, e.version, VerifiedEvidenceRef("exkes-adapter-forged")) }
        }
        // Evidence verified for another purpose (a reconciliation result) cannot justify a not-sent retry.
        val absent = db.issue(e, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(lease, e.opId, e.version, absent) } }
        // An observation reported under an untrusted principal is never verified, so it cannot become a ref.
        assertIs<EvidenceVerification.Refused>(db.verifyWith(subjectOf(e), subjectOf(e), EvidencePurpose.NOT_SENT, principal = "llm-agent"))
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
        assertEquals(e.version, db.journal.get(e.opId)!!.version)

        val retry = blocking { db.journal.markNotSent(lease, e.opId, e.version, db.issue(e, EvidencePurpose.NOT_SENT), nextActionAt = now()) } as JournalEntry
        assertEquals(OperationStatus.RETRYABLE, retry.status)
        assertEquals("VERIFIED_NOT_SENT", retry.transitionReason)
        assertEquals("exkes-adapter", retry.evidenceIssuer)
        assertEquals("NOT_SENT", retry.evidencePurpose)
        assertEquals(HASH, retry.evidenceDigest)
        assertNotNull(retry.evidenceExpiresAt)
        assertTrue(retry.evidenceRef!!.startsWith("evd-"))
    }

    @Test
    fun `IN_FLIGHT to FAILED needs a verified REJECTED ref`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.markRejected(lease, e.opId, e.version, VerifiedEvidenceRef("forged")) }
        }
        // A not-sent ref does not prove an explicit rejection.
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.markRejected(lease, e.opId, e.version, db.issue(e, EvidencePurpose.NOT_SENT)) }
        }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
        val failed = blocking {
            db.journal.markRejected(lease, e.opId, e.version, db.issue(e, EvidencePurpose.REJECTED), errorClass = "HTTP_400", error = "validation failed")
        } as JournalEntry
        assertEquals(OperationStatus.FAILED, failed.status)
        assertEquals("VERIFIED_REJECTED", failed.transitionReason)
    }

    @Test
    fun `evidence APIs refuse entries that are not IN_FLIGHT`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.markNotSent(lease, prepared.opId, prepared.version, db.issue(prepared, EvidencePurpose.NOT_SENT)) }
        }
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.markRejected(lease, prepared.opId, prepared.version, db.issue(prepared, EvidencePurpose.REJECTED)) }
        }
        val unknown = db.unknownOp(lease, key = "other")
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.markNotSent(lease, unknown.opId, unknown.version, db.issue(unknown, EvidencePurpose.NOT_SENT)) }
        }
        assertEquals(OperationStatus.PREPARED, db.journal.get(prepared.opId)!!.status)
    }

    @Test
    fun `a row without a tenant binding cannot settle through evidence`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val opId = Ulid.generate()
        transaction(db.database) {
            // A row written without tenant / contract binding (as before V4).
            ExternalOperationsTable.insert {
                it[ExternalOperationsTable.opId] = opId
                it[runId] = lease.runId.value
                it[opType] = "EXKES_CREATE_WORKSPACE"
                it[idempotencyKey] = "legacy"
                it[externalSystem] = PROVIDER
                it[stableRef] = "legacy"
                it[requestHash] = HASH
                it[status] = "IN_FLIGHT"
                it[leaseGeneration] = lease.generation
                it[createdAt] = utcNow(Clock.systemUTC())
                it[updatedAt] = utcNow(Clock.systemUTC())
            }
        }
        val subject = EvidenceSubject(TENANT, lease.runId.value, opId, PROVIDER, "EXKES_CREATE_WORKSPACE", HASH, CONTRACT)
        val ref = assertIs<EvidenceVerification.Verified>(db.verifyWith(subject, subject, EvidencePurpose.NOT_SENT)).ref
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(lease, opId, 0, ref) } }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(opId)!!.status)
    }

    @Test
    fun `timeout after the workspace already exists adopts it and never re-sends`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val found = db.issue(unknown, EvidencePurpose.RECONCILE_EXISTS, ExternalObservation.EXISTS, ProviderCapability.LOOKUP_BY_STABLE_REF)
        // "It exists" can neither authorise a resend nor be used as an absence proof.
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, found) }
        }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)

        val done = blocking {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.CONFIRMED, found, externalRef = "ws-existing")
        } as JournalEntry
        assertEquals(OperationStatus.CONFIRMED, done.status)
        assertEquals("ws-existing", done.externalRef)
        assertEquals(1, done.attempt, "the external call was never repeated")
        assertEquals("EXISTS", done.evidenceObservation)
        assertEquals(found.value, done.evidenceRef)
        assertEquals("LOOKUP_BY_STABLE_REF", done.providerCapability)
        assertEquals("exkes-adapter", done.evidenceIssuer)
        assertEquals("RECONCILE_EXISTS", done.evidencePurpose)
        assertEquals("RECONCILED_EXISTS", done.transitionReason)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, done.opId, done.version, OperationStatus.IN_FLIGHT) }
    }

    @Test
    fun `retry after UNKNOWN needs authoritative ABSENT evidence and then increments attempt`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        // Wrong purpose, a human guess, and a plain lookup are all refused.
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, db.issue(unknown, EvidencePurpose.RECONCILE_FAILED, ExternalObservation.FAILED_CONFIRMED)) }
        }
        val manual = db.issue(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.MANUAL_OPERATOR)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, manual) } }
        // Stale: observed before the operation entered UNKNOWN.
        val stale = db.issue(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, observedAt = now().minusSeconds(3600))
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, stale) } }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)

        val ref = db.issue(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE)
        var e = blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, ref) } as JournalEntry
        assertEquals("RECONCILED_ABSENT", e.transitionReason)
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertEquals(2, e.attempt)
        assertEquals("TIMEOUT", e.lastErrorClass)
    }

    @Test
    fun `AUTHORITATIVE_IDEMPOTENCY ABSENT also reopens the retry path`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val ref = db.issue(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_IDEMPOTENCY)
        val e = blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, ref) } as JournalEntry
        assertEquals(OperationStatus.RETRYABLE, e.status)
    }

    @Test
    fun `a plain lookup miss (LOOKUP_BY_STABLE_REF, eventual consistency) never reopens the retry path`() = TestDb().use { db ->
        // Exkes Runtime / Execution POST has no Idempotency-Key: a list/get that does not find the resource proves nothing.
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val notFound = db.issue(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.LOOKUP_BY_STABLE_REF)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, notFound) } }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)
        // The miss can still be recorded as "stop waiting" through reconciliation, never as a resend.
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.journal.transition(lease, unknown.opId, unknown.version, OperationStatus.RECONCILIATION_REQUIRED).status)
    }

    @Test
    fun `verified evidence bound to another run, operation or request is refused on reuse`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val a = db.inFlight(lease, key = "a")
        val b = db.inFlight(lease, key = "b")
        val refForA = db.issue(a, EvidencePurpose.NOT_SENT)
        // Another operation of the same run.
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(lease, b.opId, b.version, refForA) } }

        // Another run.
        val otherLease = db.leasedRun()
        val c = db.inFlight(otherLease, key = "c")
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(otherLease, c.opId, c.version, refForA) } }

        // The same operation with a different request digest / tenant: the Authority refuses to verify the observation.
        assertIs<EvidenceVerification.Refused>(db.verifyWith(subjectOf(a, digest = "c".repeat(64)), subjectOf(a), EvidencePurpose.NOT_SENT))
        assertIs<EvidenceVerification.Refused>(db.verifyWith(subjectOf(a, tenant = "tenant-2"), subjectOf(a), EvidencePurpose.NOT_SENT))

        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(b.opId)!!.status)
        assertEquals(b.version, db.journal.get(b.opId)!!.version)
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(c.opId)!!.status)
        // The ref still works for its own operation.
        assertEquals(OperationStatus.RETRYABLE, (blocking { db.journal.markNotSent(lease, a.opId, a.version, refForA) } as JournalEntry).status)
    }

    @Test
    fun `authority refusal detail cannot leak into Journal exceptions`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val op = db.inFlight(lease)
        val credential = "Authorization: Bearer DO-NOT-LOG-SECRET"
        val hostileAuthority = object : TrustedEvidenceAuthority by db.evidence {
            override suspend fun resolve(
                ref: VerifiedEvidenceRef,
                expected: EvidenceSubject,
                requiredPurpose: EvidencePurpose,
            ): VerifiedEvidence {
                throw EvidenceRefusedException(credential)
            }
        }
        val guardedJournal = OperationJournalRepository(db.database, hostileAuthority)
        val error = assertFailsWith<InvalidTransitionException> {
            blocking { guardedJournal.markNotSent(lease, op.opId, op.version, VerifiedEvidenceRef("evd-refused")) }
        }
        assertFalse(error.message.orEmpty().contains("DO-NOT-LOG-SECRET"))
        assertTrue(error.message.orEmpty().contains("EVIDENCE_REFUSED"))
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(op.opId)!!.status)
    }

    @Test
    fun `expired, revoked and unknown evidence refs are refused and leave the entry unchanged`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val expired = db.issue(e, EvidencePurpose.NOT_SENT)
        db.evidence.expire(expired)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(lease, e.opId, e.version, expired) } }
        val revoked = db.issue(e, EvidencePurpose.NOT_SENT)
        db.evidence.revoke(revoked)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(lease, e.opId, e.version, revoked) } }
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.markNotSent(lease, e.opId, e.version, VerifiedEvidenceRef("evd-unknown")) } }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
    }

    @Test
    fun `a provider contract change or a capability downgrade makes an old ref unusable`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val u1 = db.unknownOp(lease, key = "u1")
        val byContract = db.issue(u1, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE)
        db.evidence.setProviderContract(PROVIDER, "2027-01", ProviderCapability.AUTHORITATIVE_ABSENCE)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.reconcile(lease, u1.opId, u1.version, OperationStatus.RETRYABLE, byContract) } }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(u1.opId)!!.status)

        val u2 = db.unknownOp(lease, key = "u2")
        val byCapability = db.issue(u2, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE)
        db.evidence.setProviderContract(PROVIDER, CONTRACT, ProviderCapability.LOOKUP_BY_STABLE_REF)
        assertFailsWith<InvalidTransitionException> { blocking { db.journal.reconcile(lease, u2.opId, u2.version, OperationStatus.RETRYABLE, byCapability) } }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(u2.opId)!!.status)
        assertEquals(1, db.journal.get(u2.opId)!!.attempt)
    }

    @Test
    fun `when the authority is unavailable every evidence-gated transition fails closed`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issue(e, EvidencePurpose.NOT_SENT)
        val unknown = db.unknownOp(lease, key = "unk")
        val absent = db.issue(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT)

        db.evidence.available = false
        assertFailsWith<EvidenceAuthorityUnavailableException> { blocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } }
        assertFailsWith<EvidenceAuthorityUnavailableException> { blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, absent) } }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
        assertEquals(e.version, db.journal.get(e.opId)!!.version)
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)

        // Once the authority is back, the same ref works and nothing was half-written.
        db.evidence.available = true
        assertEquals(OperationStatus.RETRYABLE, (blocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } as JournalEntry).status)
    }

    @Test
    fun `RECONCILIATION_REQUIRED is resolved only with evidence that is not older than the entry`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val rr = db.journal.transition(lease, unknown.opId, unknown.version, OperationStatus.RECONCILIATION_REQUIRED)
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.RETRYABLE, db.issue(rr, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE)) }
        }
        // Evidence observed before the entry moved to RECONCILIATION_REQUIRED is stale.
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.FAILED, db.issue(rr, EvidencePurpose.RECONCILE_FAILED, ExternalObservation.FAILED_CONFIRMED, observedAt = now().minusSeconds(3600))) }
        }
        val failed = blocking {
            db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.FAILED, db.issue(rr, EvidencePurpose.RECONCILE_FAILED, ExternalObservation.FAILED_CONFIRMED, ProviderCapability.MANUAL_OPERATOR))
        } as JournalEntry
        assertEquals(OperationStatus.FAILED, failed.status)
    }

    @Test
    fun `reconcile is refused for entries that are not UNKNOWN or awaiting reconciliation`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> {
            blocking { db.journal.reconcile(lease, e.opId, e.version, OperationStatus.CONFIRMED, db.issue(e, EvidencePurpose.RECONCILE_EXISTS, ExternalObservation.EXISTS)) }
        }
    }

    @Test
    fun `a duplicate request adopts the UNKNOWN operation instead of creating or re-sending one`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val dup = db.journal.prepare(lease, op())
        assertEquals(unknown.opId, dup.opId)
        assertEquals(OperationStatus.UNKNOWN, dup.status, "prepare must not reset the status")
        assertEquals(1, db.count("external_operations"))
        assertFailsWith<RequestHashMismatchException> { db.journal.prepare(lease, op(hash = "b".repeat(64))) }
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, dup.opId, dup.version, OperationStatus.IN_FLIGHT) }
    }

    @Test
    fun `worker handover keeps an UNKNOWN operation UNKNOWN and the old worker cannot settle it`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        db.expireLease(lease.runId.value)
        val newLease = assertNotNull(db.leases.acquire(lease.runId, "w2", TTL))

        val adopted = db.journal.prepare(newLease, op())
        assertEquals(OperationStatus.UNKNOWN, adopted.status)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(newLease, adopted.opId, adopted.version, OperationStatus.RETRYABLE) }
        // The old worker holds evidence but no lease: fenced out before the Authority is even asked.
        assertFailsWith<LeaseLostException> {
            blocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.CONFIRMED, db.issue(unknown, EvidencePurpose.RECONCILE_EXISTS, ExternalObservation.EXISTS)) }
        }
        val done = blocking {
            db.journal.reconcile(newLease, adopted.opId, adopted.version, OperationStatus.CONFIRMED, db.issue(adopted, EvidencePurpose.RECONCILE_EXISTS, ExternalObservation.EXISTS), externalRef = "ws-1")
        } as JournalEntry
        assertEquals(newLease.generation, done.leaseGeneration)
        assertEquals(1, done.attempt)
    }

    // ---- restart sweep ------------------------------------------------------------------------------------------------

    @Test
    fun `acquiring a run sweeps IN_FLIGHT left by the dead worker to UNKNOWN and the new owner never re-sends`() = TestDb().use { db ->
        val run = db.runs.create("task-sweep", "test", RunCompletionMode.RELEASE)
        val old = db.leases.acquire(run.runId, "dead-worker", TTL)!!
        val inFlight = db.inFlight(old, key = "in-flight")
        val prepared = db.journal.prepare(old, op(key = "prepared-only"))
        val confirmedEntry = db.inFlight(old, key = "confirmed")
        val confirmed = db.journal.transition(old, confirmedEntry.opId, confirmedEntry.version, OperationStatus.CONFIRMED)

        db.expireLease(run.runId.value)
        val newLease = assertNotNull(db.leases.acquire(run.runId, "new-worker", TTL))

        val swept = db.journal.get(inFlight.opId)!!
        assertEquals(OperationStatus.UNKNOWN, swept.status)
        assertEquals("SWEPT_STALE_IN_FLIGHT", swept.transitionReason)
        assertEquals("WORKER_LOST", swept.lastErrorClass)
        assertEquals(OperationStatus.PREPARED, db.journal.get(prepared.opId)!!.status, "only IN_FLIGHT rows are swept")
        assertEquals(OperationStatus.CONFIRMED, db.journal.get(confirmed.opId)!!.status, "settled rows are untouched")
        // The new owner cannot re-send the swept call, and the dead worker is fenced out.
        assertFailsWith<InvalidTransitionException> { db.journal.transition(newLease, swept.opId, swept.version, OperationStatus.IN_FLIGHT) }
        assertFailsWith<InvalidTransitionException> { db.journal.transition(newLease, swept.opId, swept.version, OperationStatus.RETRYABLE) }
        assertFailsWith<LeaseLostException> { db.journal.transition(old, swept.opId, swept.version, OperationStatus.CONFIRMED) }
    }

    @Test
    fun `the sweep is part of acquire, so a handover cannot skip it`() = TestDb().use { db ->
        val run = db.runs.create("task-sweep2", "test", RunCompletionMode.RELEASE)
        val old = db.leases.acquire(run.runId, "w1", TTL)!!
        val e = db.inFlight(old)
        db.expireLease(run.runId.value)
        db.leases.acquire(run.runId, "w2", TTL)
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(e.opId)!!.status)
    }

    @Test
    fun `an IN_FLIGHT attempt of a worker that keeps its lease is not swept by a renewal`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        assertTrue(db.leases.renew(lease, TTL))
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
    }

    // ---- maxAttempts ---------------------------------------------------------------------------------------------------

    @Test
    fun `retries are capped at maxAttempts and the over-budget send is settled as FAILED instead`() = TestDb().use { db ->
        val lease = db.leasedRun()
        var e = db.journal.prepare(lease, op(maxAttempts = 2))
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT) // attempt 1
        e = blocking { db.journal.markNotSent(lease, e.opId, e.version, db.issue(e, EvidencePurpose.NOT_SENT)) } as JournalEntry
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT) // attempt 2
        assertEquals(2, e.attempt)
        e = blocking { db.journal.markNotSent(lease, e.opId, e.version, db.issue(e, EvidencePurpose.NOT_SENT)) } as JournalEntry
        val capped = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertEquals(OperationStatus.FAILED, capped.status, "no third send")
        assertEquals("MAX_ATTEMPTS_EXCEEDED", capped.transitionReason)
        assertEquals(2, capped.attempt, "the over-budget send did not start")
    }

    @Test
    fun `a forbidden transition and a stale version are rejected`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, e.opId, e.version, OperationStatus.CONFIRMED) }
        val inFlight = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertFailsWith<VersionConflictException> { db.journal.transition(lease, e.opId, e.version, OperationStatus.CONFIRMED) }
        val done = db.journal.transition(lease, e.opId, inFlight.version, OperationStatus.CONFIRMED)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, e.opId, done.version, OperationStatus.IN_FLIGHT) }
        assertEquals(OperationStatus.CONFIRMED, db.journal.get(e.opId)!!.status)
    }

    @Test
    fun `a stale version is refused before any evidence is resolved`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issue(e, EvidencePurpose.NOT_SENT)
        assertFailsWith<VersionConflictException> { blocking { db.journal.markNotSent(lease, e.opId, e.version + 1, ref) } }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
        assertNull(db.journal.get(e.opId)!!.transitionReason)
    }

    @Test
    fun `a worker that lost its lease cannot prepare or transition operations`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.journal.prepare(lease, op())
        db.expireLease(lease.runId.value)
        assertFailsWith<LeaseLostException> { db.journal.prepare(lease, op(key = "other")) }
        assertFailsWith<LeaseLostException> { db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT) }

        val newLease = assertNotNull(db.leases.acquire(lease.runId, "w2", TTL))
        assertFailsWith<LeaseLostException> { db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT) }
        // The new owner adopts the existing operation through its idempotency key and continues.
        val adopted = db.journal.findByKey(lease.runId, "EXKES_CREATE_WORKSPACE", "run:x:workspace")!!
        db.journal.transition(newLease, adopted.opId, adopted.version, OperationStatus.IN_FLIGHT)
        assertEquals(1, db.count("external_operations"))
    }
}
