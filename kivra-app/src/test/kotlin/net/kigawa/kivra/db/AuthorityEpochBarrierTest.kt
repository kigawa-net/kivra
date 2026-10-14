package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
import net.kigawa.kivra.core.VersionConflictException

private val BARRIER_TTL = Duration.ofSeconds(30)
private const val BARRIER_TENANT = "tenant-barrier"
private const val BARRIER_PROVIDER = "EXKES"
private const val BARRIER_CONTRACT = "2026-01"
private const val WAIT_SECONDS = 60L
private val BARRIER_HASH = "b".repeat(64)

private fun barrierNow() = utcNow(Clock.systemUTC())

private fun barrierOp(key: String) = PrepareOperation(
    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = key, externalSystem = BARRIER_PROVIDER, stableRef = "kivra-run-$key",
    requestHash = BARRIER_HASH, tenantId = BARRIER_TENANT, providerContractVersion = BARRIER_CONTRACT,
)

/** Runs [block] with a pool of [threads] workers and always shuts it down. */
private inline fun <T> withPool(threads: Int, block: (ExecutorService) -> T): T {
    val pool = Executors.newFixedThreadPool(threads)
    try {
        return block(pool)
    } finally {
        pool.shutdownNow()
    }
}

/** The Authority revokes the grant and the Kivra copy is synced: one committed publish on the epoch row. */
private fun TestDb.revokeAndSync() {
    evidence.revokeGrant(BARRIER_PROVIDER)
    syncAuthorityEpoch(BARRIER_TENANT, BARRIER_PROVIDER)
}

/**
 * Issue #94 barrier tests. The fake Authority's [FakeTrustedEvidenceAuthority.afterResolve] hook is the deterministic
 * point between a successful resolve and the Journal CAS transaction. Every ordering below is forced with latches or a
 * barrier, not with sleeps, so the result does not depend on timing. Runs on H2 by default and on MariaDB with
 * `KIVRA_TEST_DB=mariadb`.
 */
class AuthorityEpochBarrierTest {

    private fun TestDb.leasedRun(): Lease {
        val run = runs.create("barrier-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        return leases.acquire(run.runId, "w", BARRIER_TTL)!!
    }

    private fun TestDb.inFlight(lease: Lease, key: String): JournalEntry {
        val p = journal.prepare(lease, barrierOp(key))
        return journal.transition(lease, p.opId, p.version, OperationStatus.IN_FLIGHT)
    }

    /** Issues a ref for [e]'s own subject under the current Authority state and syncs the Kivra copy. */
    private fun TestDb.issueFor(
        e: JournalEntry,
        purpose: EvidencePurpose,
        observation: ExternalObservation? = null,
        capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
    ): VerifiedEvidenceRef {
        evidence.setProviderContract(BARRIER_PROVIDER, BARRIER_CONTRACT, capability)
        syncAuthorityEpoch(BARRIER_TENANT, BARRIER_PROVIDER)
        val subject = EvidenceSubject(BARRIER_TENANT, e.runId.value, e.opId, e.externalSystem, e.opType, e.requestHash, BARRIER_CONTRACT)
        val obsRef = EvidenceObservationRef("obs-${java.util.UUID.randomUUID()}")
        evidence.register(
            obsRef,
            FakeTrustedEvidenceAuthority.Observation(subject, "exkes-adapter", purpose, observation, capability, barrierNow(), barrierNow().plusHours(1)),
        )
        return (runBlocking { evidence.verify(subject, obsRef, purpose) } as EvidenceVerification.Verified).ref
    }

    private fun TestDb.auditCount(lease: Lease, opId: String, to: String): Int = count(
        "audit_events",
        "run_id = '${lease.runId.value}' AND action = 'OP_STATUS_CHANGED' AND resource = 'op:$opId' AND payload LIKE '%to=$to;%'",
    )

    @Test
    fun `a publish ordered before markNotSent CAS refuses it and leaves the row and audit unchanged`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease, "barrier:publish-first")
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val beforeEntry = db.journal.get(e.opId)
        val beforeAudit = db.auditCount(lease, e.opId, "RETRYABLE")
        withPool(1) { pool ->
            db.evidence.afterResolve = {
                // The revoke commits on another thread before this resolve returns to the transition.
                pool.submit<Unit> { db.revokeAndSync() }.get(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            try {
                assertFailsWith<InvalidTransitionException> {
                    runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref, nextActionAt = barrierNow()) }
                }
            } finally {
                db.evidence.afterResolve = null
            }
        }
        assertEquals(beforeEntry, db.journal.get(e.opId))
        assertEquals(beforeAudit, db.auditCount(lease, e.opId, "RETRYABLE"))
        assertEquals(2L, db.epochs.current(BARRIER_TENANT, BARRIER_PROVIDER)!!.grantGeneration)
    }

    @Test
    fun `a publish ordered before reconcile CAS refuses it and keeps UNKNOWN with no retry`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease, "barrier:reconcile-publish-first")
        val unknown = db.journal.transition(lease, e.opId, e.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT", error = "timed out")
        val ref = db.issueFor(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT)
        val beforeEntry = db.journal.get(e.opId)
        withPool(1) { pool ->
            db.evidence.afterResolve = { pool.submit<Unit> { db.revokeAndSync() }.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            try {
                assertFailsWith<InvalidTransitionException> {
                    runBlocking { db.journal.reconcile(lease, e.opId, unknown.version, OperationStatus.RETRYABLE, ref) }
                }
            } finally {
                db.evidence.afterResolve = null
            }
        }
        val after = db.journal.get(e.opId)!!
        assertEquals(OperationStatus.UNKNOWN, after.status)
        assertEquals(1, after.attempt)
        assertEquals(beforeEntry, after)
    }

    @Test
    fun `a publish ordered before PREPARED takeover CAS refuses it and the row stays PREPARED`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, barrierOp("barrier:takeover-publish-first"))
        val ref = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        val beforeEntry = db.journal.get(prepared.opId)
        withPool(1) { pool ->
            db.evidence.afterResolve = { pool.submit<Unit> { db.revokeAndSync() }.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            try {
                assertFailsWith<InvalidTransitionException> {
                    runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, ref) }
                }
            } finally {
                db.evidence.afterResolve = null
            }
        }
        assertEquals(beforeEntry, db.journal.get(prepared.opId))
        assertEquals(OperationStatus.PREPARED, db.journal.get(prepared.opId)!!.status)
    }

    @Test
    fun `a CAS committed before a publish keeps its recorded epoch and the later publish does not rewrite it`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease, "barrier:cas-first")
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        val settled = runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref, nextActionAt = barrierNow()) }
        assertEquals(OperationStatus.RETRYABLE, settled.status)
        withPool(1) { pool -> pool.submit<Unit> { db.revokeAndSync() }.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        assertEquals(settled, db.journal.get(e.opId))
        assertEquals(1L, db.journal.get(e.opId)!!.grantGeneration)
        assertEquals(2L, db.epochs.current(BARRIER_TENANT, BARRIER_PROVIDER)!!.grantGeneration)
        assertEquals(1, db.auditCount(lease, e.opId, "RETRYABLE"))
    }

    @Test
    fun `ten concurrent commits of the same evidence leave exactly one winner and one audit event`() = TestDb().use { db ->
        val n = 10
        val lease = db.leasedRun()
        val e = db.inFlight(lease, "barrier:ten-way")
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        // All resolves finish before any CAS starts, so the ten commits really compete for the same row.
        val barrier = CyclicBarrier(n)
        db.evidence.afterResolve = { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS) }
        val outcomes = withPool(n) { pool ->
            try {
                (1..n).map {
                    pool.submit<Result<JournalEntry>> {
                        runCatching { runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } }
                    }
                }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                db.evidence.afterResolve = null
            }
        }
        val winners = outcomes.filter { it.isSuccess }
        assertEquals(1, winners.size)
        assertEquals(OperationStatus.RETRYABLE, winners.single().getOrThrow().status)
        outcomes.filter { it.isFailure }.forEach {
            val cause = it.exceptionOrNull()
            assertTrue(
                cause is InvalidTransitionException || cause is VersionConflictException,
                "a losing commit must be refused by the Journal, got $cause",
            )
        }
        val settled = db.journal.get(e.opId)!!
        assertEquals(OperationStatus.RETRYABLE, settled.status)
        assertEquals(e.version + 1, settled.version)
        assertEquals(1, db.auditCount(lease, e.opId, "RETRYABLE"))
    }

    /**
     * Eight different operations, each with its own valid ref, race eight commits against one revoke. Whatever the
     * interleaving, every commit that succeeded recorded the pre-revoke grant generation, every refused operation is
     * unchanged, and the revoke is applied once.
     */
    @Test
    fun `commits racing a revoke succeed only with the pre-revoke epoch and refused rows stay unchanged`() = TestDb().use { db ->
        val n = 8
        val lease = db.leasedRun()
        val ops = (1..n).map { i ->
            val e = db.inFlight(lease, "barrier:race-$i")
            e to db.issueFor(e, EvidencePurpose.NOT_SENT)
        }
        val beforeEntries = ops.associate { (e, _) -> e.opId to db.journal.get(e.opId) }
        val barrier = CyclicBarrier(n + 1)
        db.evidence.afterResolve = { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS) }
        val results = withPool(n + 1) { pool ->
            try {
                val transitions = ops.map { (e, ref) ->
                    pool.submit<Result<JournalEntry>> {
                        runCatching { runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } }
                    }
                }
                val revoke = pool.submit<Unit> {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    db.revokeAndSync()
                }
                revoke.get(WAIT_SECONDS, TimeUnit.SECONDS)
                transitions.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                db.evidence.afterResolve = null
            }
        }
        var successes = 0
        for ((e, _) in ops) {
            val row = db.journal.get(e.opId)!!
            if (row.status == OperationStatus.RETRYABLE) {
                successes++
                assertEquals(1L, row.grantGeneration, "a commit may only record the pre-revoke grant generation")
            } else {
                assertEquals(beforeEntries.getValue(e.opId), row, "a refused operation must not change")
            }
        }
        assertEquals(successes, results.count { it.isSuccess })
        results.forEach {
            val cause = it.exceptionOrNull()
            if (cause != null) assertTrue(cause is InvalidTransitionException, "unexpected failure: $cause")
        }
        assertNotNull(db.epochs.current(BARRIER_TENANT, BARRIER_PROVIDER))
        assertEquals(2L, db.epochs.current(BARRIER_TENANT, BARRIER_PROVIDER)!!.grantGeneration)
    }
}
