package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OperationStatus
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
        assertEquals(1, db.count("operation_journal"))
    }

    @Test
    fun `same key with a different request hash is a bug and is refused`() = TestDb().use { db ->
        val lease = db.leasedRun()
        db.journal.prepare(lease, op())
        assertFailsWith<RequestHashMismatchException> { db.journal.prepare(lease, op(hash = "b".repeat(64))) }
        assertEquals(1, db.count("operation_journal"))
    }

    @Test
    fun `the database enforces uniqueness of run, type and idempotency key`() = TestDb().use { db ->
        val lease = db.leasedRun()
        val entry = db.journal.prepare(lease, op())
        val ex = assertFailsWith<ExposedSQLException> {
            transaction(db.database) {
                OperationJournalTable.insert {
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
    fun `unknown results go through reconciliation and retries increment attempt`() = TestDb().use { db ->
        val lease = db.leasedRun()
        var e = db.journal.prepare(lease, op())
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.UNKNOWN, errorClass = "TIMEOUT", error = "timed out")
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.RETRYABLE)
        e = db.journal.transition(lease, e.opId, e.version, OperationStatus.IN_FLIGHT)
        assertEquals(2, e.attempt)
        assertEquals("TIMEOUT", e.lastErrorClass)
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
        assertEquals(1, db.count("operation_journal"))
    }
}
