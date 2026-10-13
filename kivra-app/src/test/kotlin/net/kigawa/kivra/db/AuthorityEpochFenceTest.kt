package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.EvidenceAuthorityUnavailableException
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
import net.kigawa.kivra.core.EvidenceSubject
import net.kigawa.kivra.core.EvidenceVerification
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.VerifiedEvidenceRef
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)
private const val TENANT = "tenant-1"
private const val OTHER_TENANT = "tenant-2"
private const val PROVIDER = "EXKES"
private const val CONTRACT = "2026-01"
private val HASH_A = "a".repeat(64)
private val HASH_B = "c".repeat(64)

private fun now() = utcNow(Clock.systemUTC())

private fun prepareOp(key: String, tenant: String = TENANT, hash: String = HASH_A) = PrepareOperation(
    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = key, externalSystem = PROVIDER, stableRef = "kivra-run-$key",
    requestHash = hash, tenantId = tenant, providerContractVersion = CONTRACT,
)

/**
 * Issue #94: the Authority Epoch is fenced inside the Journal CAS transaction. The fake Authority's [afterResolve] hook is
 * the deterministic barrier: it runs after a successful resolve and before the transition's own transaction starts, so
 * the test moves the epoch at exactly the TOCTOU point.
 */
class AuthorityEpochFenceTest {

    private fun TestDb.leasedRun(): Lease {
        val run = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        return leases.acquire(run.runId, "w", TTL)!!
    }

    private fun TestDb.inFlight(lease: Lease, key: String = "run:x:workspace", hash: String = HASH_A, tenant: String = TENANT): JournalEntry {
        val p = journal.prepare(lease, prepareOp(key, tenant, hash))
        return journal.transition(lease, p.opId, p.version, OperationStatus.IN_FLIGHT)
    }

    /** Issues a ref for [e]'s own subject under the current fake Authority state and syncs the Kivra copy. */
    private fun TestDb.issueFor(
        e: JournalEntry,
        purpose: EvidencePurpose,
        observation: ExternalObservation? = null,
        capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
    ): VerifiedEvidenceRef {
        evidence.setProviderContract(PROVIDER, CONTRACT, capability)
        syncAuthorityEpoch(TENANT, PROVIDER)
        val subject = EvidenceSubject(TENANT, e.runId.value, e.opId, e.externalSystem, e.opType, e.requestHash, CONTRACT)
        val obsRef = EvidenceObservationRef("obs-${UUID.randomUUID()}")
        evidence.register(
            obsRef,
            FakeTrustedEvidenceAuthority.Observation(subject, "exkes-adapter", purpose, observation, capability, now(), now().plusHours(1)),
        )
        return (runBlocking { evidence.verify(subject, obsRef, purpose) } as EvidenceVerification.Verified).ref
    }

    private fun TestDb.auditCount(lease: Lease): Int = count("audit_events", "run_id = '${lease.runId.value}'")

    /** Everything a refused commit must leave untouched: the whole journal row and the audit trail of the run. */
    private data class Snapshot(val entry: JournalEntry?, val audit: Int)

    private fun TestDb.snapshot(lease: Lease, opId: String) = Snapshot(journal.get(opId), auditCount(lease))

    @Test
    fun `a Grant revoke that lands after resolve refuses the commit and changes nothing`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val before = db.snapshot(lease, e.opId)
        db.evidence.afterResolve = {
            db.evidence.revokeGrant(PROVIDER)
            db.syncAuthorityEpoch(TENANT, PROVIDER)
            db.evidence.afterResolve = null
        }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref, nextActionAt = now()) }
        }
        assertEquals(before, db.snapshot(lease, e.opId))
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
    }

    @Test
    fun `an authority epoch bump after resolve refuses reconcile and keeps UNKNOWN with no retry`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val unknown = db.journal.transition(lease, e.opId, e.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT", error = "timed out")
        val ref = db.issueFor(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT)
        val before = db.snapshot(lease, e.opId)
        db.evidence.afterResolve = {
            db.evidence.bumpAuthorityEpoch(PROVIDER)
            db.syncAuthorityEpoch(TENANT, PROVIDER)
            db.evidence.afterResolve = null
        }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.reconcile(lease, e.opId, unknown.version, OperationStatus.RETRYABLE, ref) }
        }
        val after = db.journal.get(e.opId)!!
        assertEquals(OperationStatus.UNKNOWN, after.status)
        // attempt stays at the value the IN_FLIGHT send left (1): no retry was opened.
        assertEquals(1, after.attempt)
        assertEquals(0L, after.version - unknown.version)
        assertEquals(before, db.snapshot(lease, e.opId))
    }

    @Test
    fun `a capability downgrade after resolve refuses the commit`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val before = db.snapshot(lease, e.opId)
        db.evidence.afterResolve = {
            db.evidence.setProviderContract(PROVIDER, CONTRACT, ProviderCapability.LOOKUP_BY_STABLE_REF)
            db.syncAuthorityEpoch(TENANT, PROVIDER)
            db.evidence.afterResolve = null
        }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) }
        }
        assertEquals(before, db.snapshot(lease, e.opId))
    }

    @Test
    fun `a Kivra copy that disagrees with the evidence refuses the commit even when the fake Authority still agrees`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        // Change only the DB copy (a newer epoch with a downgraded capability): the commit-time check is what must refuse,
        // not the Authority resolve. An older same-epoch or rolled-back copy is refused by publish itself (see
        // AuthorityEpochPublishTest), so the copy can only disagree by being ahead here.
        db.epochs.publish(
            TENANT, PROVIDER,
            AuthorityEpochSnapshot(CONTRACT, ProviderCapability.LOOKUP_BY_STABLE_REF, 2, 2, 1),
            TestDb.TEST_ISSUER,
        )
        val before = db.snapshot(lease, e.opId)
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) }
        }
        assertEquals(before, db.snapshot(lease, e.opId))
    }

    @Test
    fun `a missing Kivra epoch copy fails closed with no write`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        transaction(db.database) { execUpdate("DELETE FROM evidence_authority_epochs WHERE tenant_id = ?", TENANT) }
        val before = db.snapshot(lease, e.opId)
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) }
        }
        assertEquals(before, db.snapshot(lease, e.opId))
    }

    @Test
    fun `a ref verified for another operation, tenant or request digest is refused with no write`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val a = db.inFlight(lease, key = "run:x:a")
        val ref = db.issueFor(a, EvidencePurpose.NOT_SENT)

        // Same tenant, another operation with another request digest.
        val b = db.inFlight(lease, key = "run:x:b", hash = HASH_B)
        val beforeB = db.snapshot(lease, b.opId)
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, b.opId, b.version, ref) }
        }
        assertEquals(beforeB, db.snapshot(lease, b.opId))

        // Another tenant, same run, same request digest as the evidence.
        val other = db.journal.prepare(lease, prepareOp("run:x:other-tenant", tenant = OTHER_TENANT, hash = HASH_A))
        val inFlightOther = db.journal.transition(lease, other.opId, other.version, OperationStatus.IN_FLIGHT)
        val beforeOther = db.snapshot(lease, inFlightOther.opId)
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, inFlightOther.opId, inFlightOther.version, ref) }
        }
        assertEquals(beforeOther, db.snapshot(lease, inFlightOther.opId))

        // The original operation still settles with its own evidence.
        assertEquals(OperationStatus.RETRYABLE, runBlocking { db.journal.markNotSent(lease, a.opId, a.version, ref) }.status)
    }

    @Test
    fun `two concurrent commits of the same evidence leave exactly one winner`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        // Both resolves finish before either commit starts, so the two commits really race on the same row.
        val barrier = CyclicBarrier(2)
        db.evidence.afterResolve = { barrier.await(20, TimeUnit.SECONDS) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = (1..2).map {
                pool.submit<Boolean> {
                    runCatching { runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } }.isSuccess
                }
            }.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it })
        } finally {
            db.evidence.afterResolve = null
            pool.shutdownNow()
        }
        val settled = db.journal.get(e.opId)!!
        assertEquals(OperationStatus.RETRYABLE, settled.status)
        assertEquals(e.version + 1, settled.version)
        assertEquals(1, db.count("audit_events", "run_id = '${lease.runId.value}' AND action = 'OP_STATUS_CHANGED' AND resource = 'op:${e.opId}' AND payload LIKE '%to=RETRYABLE%'"))
    }

    @Test
    fun `an unavailable authority changes nothing for every evidence-gated transition`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val rejected = db.issueFor(e, EvidencePurpose.REJECTED)
        val before = db.snapshot(lease, e.opId)
        db.evidence.available = false
        try {
            assertFailsWith<EvidenceAuthorityUnavailableException> {
                runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) }
            }
            assertFailsWith<EvidenceAuthorityUnavailableException> {
                runBlocking { db.journal.markRejected(lease, e.opId, e.version, rejected) }
            }
        } finally {
            db.evidence.available = true
        }
        assertEquals(before, db.snapshot(lease, e.opId))
    }

    @Test
    fun `a committed transition records the epoch values and an audit event in the same transaction`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val auditBefore = db.auditCount(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val settled = runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref, nextActionAt = now()) }
        assertEquals(OperationStatus.RETRYABLE, settled.status)
        assertEquals(1L, settled.authorityEpoch)
        assertEquals(1L, settled.capabilityRevision)
        assertEquals(1L, settled.grantGeneration)
        assertNotNull(settled.evidenceContentDigest)
        assertTrue(settled.evidenceContentDigest!!.matches(Regex("[0-9a-f]{64}")))
        assertEquals(auditBefore + 1, db.auditCount(lease))
    }

    /**
     * Issue #100: a revoke published concurrently with the transition. Both start at the TOCTOU point (after resolve). The
     * Journal commit and the revoke publish serialize on the epoch row, so either the transition commits first (it recorded
     * the pre-revoke grant generation) or the revoke commits first and the transition is refused. Old evidence never commits
     * after the revoke has been published.
     */
    @Test
    fun `a revoke published concurrently with a transition is serialized on the epoch row`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val barrier = CyclicBarrier(2)
        db.evidence.afterResolve = { barrier.await(20, TimeUnit.SECONDS) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val transition = pool.submit<Result<OperationStatus>> {
                runCatching { runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref, nextActionAt = now()) }.status }
            }
            val revoke = pool.submit<Unit> {
                barrier.await(20, TimeUnit.SECONDS)
                db.evidence.revokeGrant(PROVIDER)
                db.syncAuthorityEpoch(TENANT, PROVIDER)
            }
            revoke.get(60, TimeUnit.SECONDS)
            val outcome = transition.get(60, TimeUnit.SECONDS)
            assertEquals(2L, db.epochs.current(TENANT, PROVIDER)!!.grantGeneration)
            val exception = outcome.exceptionOrNull()
            if (exception == null) {
                // The transition committed before the revoke: it recorded the pre-revoke grant generation.
                assertEquals(OperationStatus.RETRYABLE, outcome.getOrNull())
                assertEquals(1L, db.journal.get(e.opId)!!.grantGeneration)
            } else {
                assertTrue(exception is InvalidTransitionException, "unexpected failure: $exception")
                assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
            }
        } finally {
            db.evidence.afterResolve = null
            pool.shutdownNow()
        }
    }

    /**
     * Documents the limit of this slice: an Authority change that never reached the Kivra copy is not seen by the commit.
     * The Journal's guarantee is over the copy in MariaDB, which the KDoc states explicitly.
     */
    @Test
    fun `an Authority change that is not synced to the Kivra copy is not seen by the commit`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        db.evidence.afterResolve = {
            db.evidence.revokeGrant(PROVIDER)
            db.evidence.afterResolve = null
        }
        assertEquals(OperationStatus.RETRYABLE, runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) }.status)
    }

    @Test
    fun `PREPARED takeover refuses a Grant revoked between resolve and Journal commit`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, prepareOp("takeover-revoked"))
        val ref = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        val before = db.snapshot(lease, prepared.opId)
        db.evidence.afterResolve = {
            db.evidence.revokeGrant(PROVIDER)
            db.syncAuthorityEpoch(TENANT, PROVIDER)
            db.evidence.afterResolve = null
        }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, ref) }
        }
        assertEquals(before, db.snapshot(lease, prepared.opId))
        assertEquals(OperationStatus.PREPARED, db.journal.get(prepared.opId)!!.status)
    }

    @Test
    fun `PREPARED takeover refuses capability change between resolve and Journal commit`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, prepareOp("takeover-capability"))
        val ref = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        val before = db.snapshot(lease, prepared.opId)
        db.evidence.afterResolve = {
            db.evidence.setProviderContract(PROVIDER, CONTRACT, ProviderCapability.LOOKUP_BY_STABLE_REF)
            db.syncAuthorityEpoch(TENANT, PROVIDER)
            db.evidence.afterResolve = null
        }
        assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, ref) }
        }
        assertEquals(before, db.snapshot(lease, prepared.opId))
    }

    @Test
    fun `PREPARED takeover with unchanged Authority Epoch enters IN_FLIGHT`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, prepareOp("takeover-allowed"))
        val ref = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        val result = runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, ref) }
        assertEquals(OperationStatus.IN_FLIGHT, result.status)
        assertEquals(prepared.version + 1, result.version)
        assertEquals(1L, result.authorityEpoch)
    }
}
