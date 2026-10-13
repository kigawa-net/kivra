package net.kigawa.kivra.db

import java.time.Duration
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.EvidenceObservationRef
import net.kigawa.kivra.core.EvidencePurpose
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
import net.kigawa.kivra.core.VersionConflictException
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)
private const val TENANT = "tenant-1"
private const val PROVIDER = "EXKES"
private const val CONTRACT = "2026-01"
private val HASH_A = "a".repeat(64)

/** Moves the Authority Epoch copy's clock (shared by the Journal and the copy) past the freshness bound. */
private val STALE_BY = Duration.ofMinutes(6)

private fun prepareOp(key: String) = PrepareOperation(
    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = key, externalSystem = PROVIDER, stableRef = "kivra-run-$key",
    requestHash = HASH_A, tenantId = TENANT, providerContractVersion = CONTRACT,
)

/**
 * Issue #100 / #101: the Authority Epoch copy must be fresh inside the Journal CAS transaction, and the evidence gate is
 * one function for [Gate.Verified] and [Gate.Takeover]. Every refusal here must leave the Journal, the Audit trail and the
 * Outbox untouched. The clock is deterministic: [TestDb.clock] is moved, nothing sleeps.
 */
class AuthorityEpochFreshnessTest {

    private fun TestDb.leasedRun(): Lease {
        val run = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE)
        return leases.acquire(run.runId, "w", TTL)!!
    }

    private fun TestDb.inFlight(lease: Lease, key: String): JournalEntry {
        val p = journal.prepare(lease, prepareOp(key))
        return journal.transition(lease, p.opId, p.version, OperationStatus.IN_FLIGHT)
    }

    /** Issues a ref for [e]'s own subject and publishes the copy at the current clock reading (the Authority sync). */
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
        val now = utcNow(clock)
        evidence.register(
            obsRef,
            FakeTrustedEvidenceAuthority.Observation(subject, "exkes-adapter", purpose, observation, capability, now, now.plusHours(1)),
        )
        return (runBlocking { evidence.verify(subject, obsRef, purpose) } as EvidenceVerification.Verified).ref
    }

    private fun TestDb.auditCount(lease: Lease): Int = count("audit_events", "run_id = '${lease.runId.value}'")

    private data class Snapshot(val entry: JournalEntry?, val audit: Int)

    private fun TestDb.snapshot(lease: Lease, opId: String) = Snapshot(journal.get(opId), auditCount(lease))

    /** The refusal reason without the operation id and the measured age, so two refusals can be compared by content. */
    private fun reason(e: Throwable): String = e.message!!.substringAfter(": ").substringBefore(" (age")

    @Test
    fun `a stale copy refuses every evidence-gated transition with no write`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val sent = db.inFlight(lease, "stale:not-sent")
        val notSentRef = db.issueFor(sent, EvidencePurpose.NOT_SENT)
        val rejectedRow = db.inFlight(lease, "stale:rejected")
        val rejectedRef = db.issueFor(rejectedRow, EvidencePurpose.REJECTED)
        val unknownBase = db.inFlight(lease, "stale:unknown")
        val unknown = db.journal.transition(lease, unknownBase.opId, unknownBase.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT")
        val absentRef = db.issueFor(unknown, EvidencePurpose.RECONCILE_ABSENT, ExternalObservation.ABSENT)
        val prepared = db.journal.prepare(lease, prepareOp("stale:takeover"))
        val takeoverRef = db.issueFor(prepared, EvidencePurpose.NOT_SENT)

        // Age the copy past the bound. The refs are still valid evidence, only the copy is stale.
        db.clock.offset = STALE_BY
        assertFailsWith<AuthorityEpochNotFreshException> { db.epochs.requireFresh(TENANT, PROVIDER) }

        val cases: List<Pair<String, () -> Unit>> = listOf(
            "markNotSent" to { runBlocking { db.journal.markNotSent(lease, sent.opId, sent.version, notSentRef) } },
            "markRejected" to { runBlocking { db.journal.markRejected(lease, rejectedRow.opId, rejectedRow.version, rejectedRef) } },
            "reconcile" to { runBlocking { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, absentRef) } },
            "takeOverPrepared" to { runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, takeoverRef) } },
        )
        for ((name, call) in cases) {
            val opId = when (name) {
                "markNotSent" -> sent.opId
                "markRejected" -> rejectedRow.opId
                "reconcile" -> unknown.opId
                else -> prepared.opId
            }
            val before = db.snapshot(lease, opId)
            val e = assertFailsWith<InvalidTransitionException>(name) { call() }
            assertTrue(e.message!!.contains("not fresh"), "$name refused for another reason: ${e.message}")
            assertEquals(before, db.snapshot(lease, opId), "$name changed state on a stale copy")
        }
    }

    @Test
    fun `a heartbeat publication after the bound makes the same evidence valid again`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease, "heartbeat")
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        db.clock.offset = STALE_BY
        val before = db.snapshot(lease, e.opId)
        assertFailsWith<InvalidTransitionException> { runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } }
        assertEquals(before, db.snapshot(lease, e.opId))

        // The authenticated sync re-asserts the same state: published_at moves to now, the epoch values do not change.
        db.syncAuthorityEpoch(TENANT, PROVIDER)
        val settled = runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) }
        assertEquals(OperationStatus.RETRYABLE, settled.status)
        assertEquals(e.version + 1, settled.version)
    }

    @Test
    fun `a copy without a publish time is refused as a pre-V8 row`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease, "no-publish-time")
        val ref = db.issueFor(e, EvidencePurpose.NOT_SENT)
        transaction(db.database) { execUpdate("UPDATE evidence_authority_epochs SET published_at = NULL WHERE tenant_id = ?", TENANT) }
        val before = db.snapshot(lease, e.opId)
        val refused = assertFailsWith<InvalidTransitionException> { runBlocking { db.journal.markNotSent(lease, e.opId, e.version, ref) } }
        assertTrue(refused.message!!.contains("no publish time"), refused.message)
        assertEquals(before, db.snapshot(lease, e.opId))
    }

    @Test
    fun `a writer generation older than the current lease cannot take over a PREPARED row`() = TestDb().use { db ->
        val oldLease = db.leasedRun()
        val prepared = db.journal.prepare(oldLease, prepareOp("stale-generation"))
        val ref = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        // The first writer stops renewing; a newer writer generation takes the run.
        db.expireLease(oldLease.runId.value)
        val newLease = db.leases.acquire(oldLease.runId, "w-new", TTL)!!
        assertTrue(newLease.generation > oldLease.generation)

        val before = db.snapshot(newLease, prepared.opId)
        assertFailsWith<LeaseLostException> {
            runBlocking { db.journal.takeOverPrepared(oldLease, prepared.opId, prepared.version, ref) }
        }
        assertEquals(before, db.snapshot(newLease, prepared.opId))

        // The current generation takes the same evidence over.
        val taken = runBlocking { db.journal.takeOverPrepared(newLease, prepared.opId, prepared.version, ref) }
        assertEquals(OperationStatus.IN_FLIGHT, taken.status)
    }

    @Test
    fun `two takeovers racing on the same PREPARED row leave exactly one winner`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, prepareOp("race-takeover"))
        val ref = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        // Both resolves finish before either commit starts, so the two takeovers really race on the row.
        val barrier = CyclicBarrier(2)
        db.evidence.afterResolve = { barrier.await(20, TimeUnit.SECONDS) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = (1..2).map {
                pool.submit<Result<OperationStatus>> {
                    runCatching { runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, ref) }.status }
                }
            }.map { it.get(60, TimeUnit.SECONDS) }
            val winners = outcomes.filter { it.isSuccess }
            assertEquals(1, winners.size, "expected exactly one winner, got $outcomes")
            for (loser in outcomes.filter { it.isFailure }) {
                val failure = loser.exceptionOrNull()
                assertTrue(
                    failure is InvalidTransitionException || failure is VersionConflictException,
                    "unexpected loser failure: $failure",
                )
            }
        } finally {
            db.evidence.afterResolve = null
            pool.shutdownNow()
        }
        val settled = db.journal.get(prepared.opId)!!
        assertEquals(OperationStatus.IN_FLIGHT, settled.status)
        assertEquals(prepared.version + 1, settled.version)
        assertEquals(1, settled.attempt)
        assertEquals(
            1,
            db.count(
                "audit_events",
                "run_id = '${lease.runId.value}' AND action = 'OP_STATUS_CHANGED' AND resource = 'op:${prepared.opId}' AND payload LIKE '%to=IN_FLIGHT%'",
            ),
        )
    }

    @Test
    fun `takeover and verified transitions refuse a stale copy with the same reason`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val sent = db.inFlight(lease, "parity-verified")
        val sentRef = db.issueFor(sent, EvidencePurpose.NOT_SENT)
        val prepared = db.journal.prepare(lease, prepareOp("parity-takeover"))
        val takeoverRef = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        db.clock.offset = STALE_BY

        val verified = assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, sent.opId, sent.version, sentRef) }
        }
        val takeover = assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, takeoverRef) }
        }
        assertEquals(reason(verified), reason(takeover))
    }

    @Test
    fun `takeover and verified transitions refuse an epoch change with the same reason`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val sent = db.inFlight(lease, "parity-epoch-verified")
        val sentRef = db.issueFor(sent, EvidencePurpose.NOT_SENT)
        val prepared = db.journal.prepare(lease, prepareOp("parity-epoch-takeover"))
        val takeoverRef = db.issueFor(prepared, EvidencePurpose.NOT_SENT)
        // The copy moves to a newer grant generation (authority epoch 2) that the evidence does not carry. Publish accepts it.
        db.epochs.publish(TENANT, PROVIDER, AuthorityEpochSnapshot(CONTRACT, ProviderCapability.AUTHORITATIVE_ABSENCE, 1, 2, 2), TestDb.TEST_ISSUER)

        val verified = assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.markNotSent(lease, sent.opId, sent.version, sentRef) }
        }
        val takeover = assertFailsWith<InvalidTransitionException> {
            runBlocking { db.journal.takeOverPrepared(lease, prepared.opId, prepared.version, takeoverRef) }
        }
        assertEquals("authority epoch changed after the evidence was resolved", reason(verified))
        assertEquals(reason(verified), reason(takeover))
    }
}
