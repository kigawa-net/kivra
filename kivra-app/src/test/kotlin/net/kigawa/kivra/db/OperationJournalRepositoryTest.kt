package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import net.kigawa.kivra.core.ExternalObservation
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.ProviderCapability
import net.kigawa.kivra.core.ReconciliationEvidence
import net.kigawa.kivra.core.VersionConflictException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)
private val HASH = "a".repeat(64)

private fun op(key: String = "run:x:workspace", hash: String = HASH) = PrepareOperation(
    opType = "EXKES_CREATE_WORKSPACE", idempotencyKey = key, externalSystem = "EXKES", stableRef = "kivra-run-x", requestHash = hash,
)

class OperationJournalRepositoryTest {
    private fun TestDb.leasedRun(): Lease {
        val run = runs.create("task-${System.nanoTime()}", "test")
        return leases.acquire(run.runId, "w", TTL)!!
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

    private fun TestDb.unknownOp(lease: Lease, key: String = "run:x:workspace"): JournalEntry {
        var e = journal.prepare(lease, op(key = key))
        e = journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        return journal.transition(lease, e.opId, e.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT", error = "timed out")
    }

    private fun evidence(
        observation: ExternalObservation,
        capability: ProviderCapability = ProviderCapability.LOOKUP_BY_STABLE_REF,
        observedAt: LocalDateTime = utcNow(Clock.systemUTC()).plusSeconds(1),
    ) = ReconciliationEvidence(observation, "exkes:get-workspace/kivra-run-x", observedAt, capability)

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

    @Test
    fun `timeout after the workspace already exists adopts it and never re-sends`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val found = evidence(ExternalObservation.EXISTS)
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
        assertEquals("RECONCILED_EXISTS", done.transitionReason)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, done.opId, done.version, OperationStatus.IN_FLIGHT) }
    }

    @Test
    fun `retry after UNKNOWN needs authoritative ABSENT evidence and then increments attempt`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        // Wrong observation, a human guess, and stale evidence are all refused.
        assertFailsWith<InvalidTransitionException> { db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidence(ExternalObservation.FAILED_CONFIRMED)) }
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidence(ExternalObservation.ABSENT, ProviderCapability.MANUAL_OPERATOR))
        }
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidence(ExternalObservation.ABSENT, observedAt = utcNow(Clock.systemUTC()).minusSeconds(60)))
        }
        assertEquals(OperationStatus.UNKNOWN, db.journal.get(unknown.opId)!!.status)

        var e = db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.RETRYABLE, evidence(ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_IDEMPOTENCY))
        assertEquals("RECONCILED_ABSENT", e.transitionReason)
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertEquals(2, e.attempt)
        assertEquals("TIMEOUT", e.lastErrorClass)
    }

    @Test
    fun `RECONCILIATION_REQUIRED is resolved only with evidence`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val unknown = db.unknownOp(lease)
        val rr = db.journal.transition(lease, unknown.opId, unknown.version, OperationStatus.RECONCILIATION_REQUIRED)
        assertFailsWith<InvalidTransitionException> {
            db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.RETRYABLE, evidence(ExternalObservation.ABSENT))
        }
        val failed = db.journal.reconcile(lease, rr.opId, rr.version, OperationStatus.FAILED, evidence(ExternalObservation.FAILED_CONFIRMED, ProviderCapability.MANUAL_OPERATOR))
        assertEquals(OperationStatus.FAILED, failed.status)
    }

    @Test
    fun `reconcile is refused for entries that are not UNKNOWN or awaiting reconciliation`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> { db.journal.reconcile(lease, e.opId, e.version, OperationStatus.CONFIRMED, evidence(ExternalObservation.EXISTS)) }
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
            db.journal.reconcile(lease, unknown.opId, unknown.version, OperationStatus.CONFIRMED, evidence(ExternalObservation.EXISTS))
        }
        val done = db.journal.reconcile(newLease, adopted.opId, adopted.version, OperationStatus.CONFIRMED, evidence(ExternalObservation.EXISTS), externalRef = "ws-1")
        assertEquals(newLease.generation, done.leaseGeneration)
        assertEquals(1, done.attempt)
    }

    @Test
    fun `forbidden transitions and stale versions are rejected`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val e = db.journal.prepare(lease, op())
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, e.opId, e.version, OperationStatus.CONFIRMED) }
        val inFlight = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertFailsWith<VersionConflictException> { db.journal.transition(lease, e.opId, e.version, OperationStatus.CONFIRMED) }
        val done = db.journal.transition(lease, e.opId, inFlight.version, OperationStatus.FAILED)
        assertFailsWith<InvalidTransitionException> { db.journal.transition(lease, e.opId, done.version, OperationStatus.IN_FLIGHT) }
        assertEquals(OperationStatus.FAILED, db.journal.get(e.opId)!!.status)
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
