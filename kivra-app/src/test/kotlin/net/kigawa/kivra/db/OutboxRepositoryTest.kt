package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OutboxStatus
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)

class OutboxRepositoryTest {
    @Test
    fun `pending events are returned per run in version order and published once`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val id = db.outbox.appendFenced(lease, 5, "Custom", "{}")

        val pending = db.outbox.fetchPending(10)
        assertEquals(listOf(0L, 5L), pending.map { it.aggregateVersion })
        assertTrue(db.outbox.markPublished(id))
        assertFalse(db.outbox.markPublished(id), "second publisher must lose the CAS")
        assertEquals(listOf(0L), db.outbox.fetchPending(10).map { it.aggregateVersion })
    }

    @Test
    fun `duplicate aggregate version and type is rejected by the unique constraint`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.outbox.appendFenced(lease, 7, "Custom", "{}")
        val ex = assertFailsWith<ExposedSQLException> { db.outbox.appendFenced(lease, 7, "Custom", "{}") }
        assertTrue(ex.isUniqueViolation())
    }

    @Test
    fun `a stale worker cannot append and leaves nothing behind`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.expireLease(run.runId.value)
        assertFailsWith<LeaseLostException> { db.outbox.appendFenced(lease, 9, "Custom", "{}") }
        assertEquals(1, db.count("outbox_events"))
    }

    @Test
    fun `append rolls back together with the surrounding transaction`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        assertFailsWith<IllegalStateException> {
            transaction(db.database) {
                db.outbox.append(this, run.runId, 3, "Custom", "{}")
                error("boom")
            }
        }
        assertEquals(1, db.count("outbox_events"))
    }

    @Test
    fun `failed deliveries back off and become DEAD at the attempt limit`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val id = db.outbox.fetchPending(1).single().eventId
        assertEquals(OutboxStatus.PENDING, db.outbox.markFailed(id, Duration.ofHours(1), maxAttempts = 2))
        assertTrue(db.outbox.fetchPending(10).isEmpty(), "backed-off event is not due")
        assertEquals(OutboxStatus.DEAD, db.outbox.markFailed(id, Duration.ofHours(1), maxAttempts = 2))
        assertNull(db.outbox.markFailed(id, Duration.ofHours(1), maxAttempts = 2), "DEAD is terminal")
        assertEquals(1, db.count("outbox_events", "status = 'DEAD' AND aggregate_id = '${run.runId}'"))
    }

    @Test
    fun `inbox deduplicates by consumer and event id`() = TestDb().use { db ->
        val inbox = InboxRepository(db.database)
        assertTrue(inbox.tryRecord("fusha", "evt-1", "run-1", 1))
        assertFalse(inbox.tryRecord("fusha", "evt-1", "run-1", 1))
        assertTrue(inbox.tryRecord("metrics", "evt-1", "run-1", 1))
    }
}
