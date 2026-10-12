package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.NotSentProof
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.ReconciliationEvidence
import net.kigawa.kivra.core.RejectedProof
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)
private val HASH = "a".repeat(64)

private fun op(key: String = "run:x:workspace", hash: String = HASH, maxAttempts: Int = 3) = PrepareOperation(
    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = key, externalSystem = "EXKES", stableRef = "kivra-run-x",
    requestHash = hash, maxAttempts = maxAttempts,
)

private fun now() = utcNow(Clock.systemUTC())

/** Evidence bound to [entry] by default; override any field to test the refusals. */
private fun evidenceFor(
    entry: JournalEntry,
    observation: ExternalObservation,
    capability: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE,
    observedAt: LocalDateTime = now(),
    issuer: String = "exkes-adapter",
    opId: String = entry.opId,
    runId: RunId = entry.runId,
    requestHash: String = entry.requestHash,
) = ReconciliationEvidence(runId, opId, requestHash, issuer, observation, "exkes:get-workspace/kivra-run-x", observedAt, capability)

private fun proofFor(
    entry: JournalEntry,
    observedAt: LocalDateTime = now(),
    issuer: String = "exkes-adapter",
    opId: String = entry.opId,
    runId: RunId = entry.runId,
    requestHash: String = entry.requestHash,
) = NotSentProof(runId, opId, requestHash, issuer, "exkes:connect-refused/kivra-run-x", observedAt)

private fun rejectedFor(entry: JournalEntry, observedAt: LocalDateTime = now(), issuer: String = "exkes-adapter") =
    RejectedProof(entry.runId, entry.opId, entry.requestHash, issuer, "exkes:400/kivra-run-x", observedAt)

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
    fun `IN_FLIGHT to RETRYABLE needs a NotSentProof from a trusted issuer bound to this operation`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        // Untrusted issuer, wrong operation, wrong request hash, future and too-old proofs are all refused.
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, e.opId, e.version, proofFor(e, issuer = "random-agent")) }
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, e.opId, e.version, proofFor(e, opId = Ulid.generate())) }
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, e.opId, e.version, proofFor(e, requestHash = "c".repeat(64))) }
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, e.opId, e.version, proofFor(e, observedAt = now().plusHours(1))) }
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, e.opId, e.version, proofFor(e, observedAt = now().minusHours(1))) }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)

        val retry = db.journal.markNotSent(lease, e.opId, e.version, proofFor(e), nextActionAt = now())
        assertEquals(OperationStatus.RETRYABLE, retry.status)
        assertEquals("PROOF_NOT_SENT", retry.transitionReason)
        assertEquals("exkes-adapter", retry.evidenceIssuer)
    }

    @Test
    fun `IN_FLIGHT to FAILED needs a RejectedProof from a trusted issuer`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.inFlight(lease)
        assertFailsWith<InvalidTransitionException> { db.journal.markRejected(lease, e.opId, e.version, rejectedFor(e, issuer = "unknown-issuer")) }
        assertEquals(OperationStatus.IN_FLIGHT, db.journal.get(e.opId)!!.status)
        val failed = db.journal.markRejected(lease, e.opId, e.version, rejectedFor(e), errorClass = "HTTP_400", error = "validation failed")
        assertEquals(OperationStatus.FAILED, failed.status)
        assertEquals("PROOF_REJECTED", failed.transitionReason)
    }

    @Test
    fun `proof APIs refuse entries that are not IN_FLIGHT`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val prepared = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, prepared.opId, prepared.version, proofFor(prepared)) }
        assertFailsWith<InvalidTransitionException> { db.journal.markRejected(lease, prepared.opId, prepared.version, rejectedFor(prepared)) }
        val unknown = db.unknownOp(lease, key = "other")
        assertFailsWith<InvalidTransitionException> { db.journal.markNotSent(lease, unknown.opId, unknown.version, proofFor(unknown)) }
    }

    @Test
    fun `timeout after the workspace already exists adopts it and never re-sends`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val found = evidenceFor(unknown, ExternalObservation.EXISTS, ProviderCapability.LOOKUP_BY_STABLE_REF)
        // "It exists" can neither authorise a resend nor be recorded as ABSENT-style retry.
        assertFailsWith<InvalidTransitionException> { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, found) }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)

        val done = db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.CONFIRMED, found, externalRef = "ws-existing")
        assertEquals(OperationStatus.CONFIRMED, done.status)
        assertEquals("ws-existing", done.externalRef)
        assertEquals(1, done.attempt, "the external call was never repeated")
        assertEquals("EXISTS", done.evidenceObservation)
        assertEquals("exkes:get-workspace/kivra-run-x", done.evidenceRef)
        assertEquals(found.observedAt, done.evidenceObservedAt)
        assertEquals("LOOKUP_BY_STABLE_REF", done.providerCapability)
        assertEquals("exkes-adapter", done.evidenceIssuer)
        assertEquals("RECONCILED_EXISTS", done.transitionReason)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, done.opId, done.version, OperationStatus.IN_FLIGHT) }
    }

    @Test
    fun `retry after UNKNOWN needs authoritative ABSENT evidence and then increments attempt`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        // Wrong observation, a human guess, and stale evidence are all refused.
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidenceFor(unknown, ExternalObservation.FAILED_CONFIRMED))
        }
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidenceFor(unknown, ExternalObservation.ABSENT, ProviderCapability.MANUAL_OPERATOR))
        }
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidenceFor(unknown, ExternalObservation.ABSENT, observedAt = now().minusSeconds(3600)))
        }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)

        var e = db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidenceFor(unknown, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE))
        assertEquals("RECONCILED_ABSENT", e.transitionReason)
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertEquals(2, e.attempt)
        assertEquals("TIMEOUT", e.lastErrorClass)
    }

    @Test
    fun `AUTHORITATIVE_IDEMPOTENCY ABSENT also reopens the retry path`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val e = db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidenceFor(unknown, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_IDEMPOTENCY))
        assertEquals(OperationStatus.RETRYABLE, e.status)
    }

    @Test
    fun `a plain lookup miss (LOOKUP_BY_STABLE_REF, eventual consistency) never reopens the retry path`() = TestDb().use { db ->
        // Exkes Runtime / Execution POST has no Idempotency-Key: a list/get that does not find the resource proves nothing.
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val notFound = evidenceFor(unknown, ExternalObservation.ABSENT, ProviderCapability.LOOKUP_BY_STABLE_REF)
        assertFailsWith<InvalidTransitionException> { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, notFound) }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)
        // The miss can still be recorded as "stop waiting" through reconciliation, never as a resend.
        assertEquals(OperationStatus.RECONCILIATION_REQUIRED, db.journal.transition(lease, unknown.opId, unknown.version, OperationStatus.RECONCILIATION_REQUIRED).status)
    }

    @Test
    fun `evidence bound to another run, operation or request, or from an untrusted issuer is refused`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        fun attempt(ev: ReconciliationEvidence) = db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, ev)
        assertFailsWith<InvalidTransitionException> { attempt(evidenceFor(unknown, ExternalObservation.ABSENT, opId = Ulid.generate())) }
        assertFailsWith<InvalidTransitionException> { attempt(evidenceFor(unknown, ExternalObservation.ABSENT, runId = RunId(Ulid.generate()))) }
        assertFailsWith<InvalidTransitionException> { attempt(evidenceFor(unknown, ExternalObservation.ABSENT, requestHash = "d".repeat(64))) }
        assertFailsWith<InvalidTransitionException> { attempt(evidenceFor(unknown, ExternalObservation.ABSENT, issuer = "llm-agent")) }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)
        assertEquals(1, db.journal.get(unknown.opId)!!.attempt)
    }

    @Test
    fun `RECONCILIATION_REQUIRED is resolved only with evidence that is not older than the entry`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val rr = db.journal.transition(lease, unknown.opId, unknown.version, OperationStatus.RECONCILIATION_REQUIRED)
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.RETRYABLE, evidenceFor(rr, ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_ABSENCE))
        }
        // Evidence observed before the entry moved to RECONCILIATION_REQUIRED is stale.
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.FAILED, evidenceFor(rr, ExternalObservation.FAILED_CONFIRMED, observedAt = now().minusSeconds(3600)))
        }
        val failed = db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.FAILED, evidenceFor(rr, ExternalObservation.FAILED_CONFIRMED, ProviderCapability.MANUAL_OPERATOR))
        assertEquals(OperationStatus.FAILED, failed.status)
    }

    @Test
    fun `reconcile is refused for entries that are not UNKNOWN or awaiting reconciliation`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> { db.journal.reconcile(lease, e.opId, e.version, OperationStatus.CONFIRMED, evidenceFor(e, ExternalObservation.EXISTS)) }
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
        // The old worker holds stale evidence but no lease: fenced out entirely.
        assertFailsWith<LeaseLostException> {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.CONFIRMED, evidenceFor(unknown, ExternalObservation.EXISTS))
        }
        val done = db.journal.reconcile(newLease, adopted.opId, adopted.version, OperationStatus.CONFIRMED, evidenceFor(adopted, ExternalObservation.EXISTS), externalRef = "ws-1")
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
        e = db.journal.markNotSent(lease, e.opId, e.version, proofFor(e))
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT) // attempt 2
        assertEquals(2, e.attempt)
        e = db.journal.markNotSent(lease, e.opId, e.version, proofFor(e))
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
