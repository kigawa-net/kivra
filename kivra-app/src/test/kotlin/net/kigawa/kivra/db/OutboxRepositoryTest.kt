package net.kigawa.kivra.db

import net.kigawa.kivra.core.RunCompletionMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
private val CLAIM_TTL = Duration.ofSeconds(30)

class OutboxRepositoryTest {
    @Test
    fun `claimed events are delivered per run in version order and published once`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val id5 = db.outbox.appendFenced(lease, 5, "Custom", "{}")

        // Version 5 waits behind version 0 of the same run.
        val first = db.outbox.claim("pub-a", 10, CLAIM_TTL)
        assertEquals(listOf(0L), first.map { it.aggregateVersion })
        val f = first.single()
        assertTrue(db.outbox.markPublished(f.eventId, "pub-a", f.claimToken!!))
        assertFalse(db.outbox.markPublished(f.eventId, "pub-a", f.claimToken!!), "already published")

        val second = db.outbox.claim("pub-a", 10, CLAIM_TTL)
        assertEquals(listOf(id5), second.map { it.eventId })
        assertTrue(db.outbox.claim("pub-b", 10, CLAIM_TTL).isEmpty(), "a live claim is exclusive")
    }

    @Test
    fun `only the claim holder can publish or fail an event`() = TestDb().use { db ->
        db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val e = db.outbox.claim("pub-a", 1, CLAIM_TTL).single()
        assertFalse(db.outbox.markPublished(e.eventId, "pub-b", e.claimToken!!))
        assertNull(db.outbox.markFailed(e.eventId, "pub-b", e.claimToken!!, Duration.ZERO, 3))
        assertTrue(db.outbox.markPublished(e.eventId, "pub-a", e.claimToken!!))
    }

    @Test
    fun `a crashed publisher's expired claim is taken over and the old publisher is rejected`() = TestDb().use { db ->
        db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val e = db.outbox.claim("pub-a", 1, CLAIM_TTL).single() // pub-a crashes here, never reports back
        assertTrue(db.outbox.claim("pub-b", 1, CLAIM_TTL).isEmpty(), "claim still live")
        db.expireClaim(e.eventId)
        val again = db.outbox.claim("pub-b", 1, CLAIM_TTL).single()
        assertEquals(e.eventId, again.eventId, "at-least-once: the same event is delivered again")
        // pub-a wakes up late: neither its publish nor its failure may touch the row now owned by pub-b.
        assertFalse(db.outbox.markPublished(e.eventId, "pub-a", e.claimToken!!))
        assertNull(db.outbox.markFailed(e.eventId, "pub-a", e.claimToken!!, Duration.ZERO, 3))
        assertEquals(0, db.count("outbox_events", "event_id = '${e.eventId}' AND publish_attempts > 0"))
        assertTrue(db.outbox.markPublished(e.eventId, "pub-b", again.claimToken!!))
    }

    @Test
    fun `an expired claim cannot be used to publish even if nobody took over`() = TestDb().use { db ->
        db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val e = db.outbox.claim("pub-a", 1, CLAIM_TTL).single()
        db.expireClaim(e.eventId)
        assertFalse(db.outbox.markPublished(e.eventId, "pub-a", e.claimToken!!))
        assertNull(db.outbox.markFailed(e.eventId, "pub-a", e.claimToken!!, Duration.ZERO, 3))
    }

    @Test
    fun `same publisher id after reclaim - the stalled earlier attempt cannot ack or nack the later claim`() = TestDb().use { db ->
        db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val stale = db.outbox.claim("pub-a", 1, CLAIM_TTL).single() // attempt 1 of pub-a stalls
        db.expireClaim(stale.eventId)
        val fresh = db.outbox.claim("pub-a", 1, CLAIM_TTL).single() // attempt 2 of the SAME publisher id
        assertEquals(stale.eventId, fresh.eventId)
        assertTrue(stale.claimToken != fresh.claimToken, "every claim gets its own token")
        // Attempt 1 wakes up: same publisher id, same event, but an old token. Nothing may change.
        assertFalse(db.outbox.markPublished(stale.eventId, "pub-a", stale.claimToken!!))
        assertNull(db.outbox.markFailed(stale.eventId, "pub-a", stale.claimToken!!, Duration.ZERO, 3))
        assertEquals(0, db.count("outbox_events", "event_id = '${stale.eventId}' AND (status <> 'PENDING' OR publish_attempts > 0)"))
        // Attempt 2 still owns the claim and succeeds.
        assertTrue(db.outbox.markPublished(fresh.eventId, "pub-a", fresh.claimToken!!))
    }

    @Test
    fun `a stale same-id attempt racing a reclaim never gets a success through`() = TestDb().use { db ->
        db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val stale = db.outbox.claim("pub-a", 1, CLAIM_TTL).single()
        db.expireClaim(stale.eventId)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reclaim = pool.submit<OutboxEvent?> {
                start.await()
                db.outbox.claim("pub-a", 1, CLAIM_TTL).singleOrNull()
            }
            val staleAcks = pool.submit<Int> {
                start.await()
                (1..50).count { db.outbox.markPublished(stale.eventId, "pub-a", stale.claimToken!!) }
            }
            start.countDown()
            reclaim.get(30, TimeUnit.SECONDS)
            assertEquals(0, staleAcks.get(30, TimeUnit.SECONDS), "a claim that expired can never be acked, with or without the reclaim")
            assertEquals(0, db.count("outbox_events", "event_id = '${stale.eventId}' AND status = 'PUBLISHED'"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent publishers never claim the same event`() = TestDb().use { db ->
        val events = 24
        repeat(events) { db.runs.create("task-$it", "test", RunCompletionMode.RELEASE) } // one event per run: no ordering constraint
        val pool = Executors.newFixedThreadPool(4)
        try {
            val claimed = (1..4).map { p ->
                pool.submit<List<String>> {
                    buildList {
                        repeat(10) {
                            val batch = try { db.outbox.claim("pub-$p", 5, CLAIM_TTL) } catch (e: Exception) { emptyList() }
                            addAll(batch.map { it.eventId })
                        }
                    }
                }
            }.flatMap { it.get(60, TimeUnit.SECONDS) }
            assertEquals(claimed.size, claimed.toSet().size, "an event was claimed twice: $claimed")
            assertEquals(events, claimed.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent failure reports do not lose an attempt and DEAD is reached exactly once`() = TestDb().use { db ->
        db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val e = db.outbox.claim("pub-a", 1, CLAIM_TTL).single()
        // Two threads race to report the same failure for the same claim: exactly one may count.
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                pool.submit<OutboxStatus?> {
                    start.await()
                    try { db.outbox.markFailed(e.eventId, "pub-a", e.claimToken!!, Duration.ZERO, 2) } catch (ex: Exception) { null }
                }
            }
            start.countDown()
            val statuses = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == OutboxStatus.PENDING }, "statuses=$statuses")
            assertEquals(1, db.count("outbox_events", "event_id = '${e.eventId}' AND publish_attempts = 1"))
        } finally {
            pool.shutdownNow()
        }
        // Second delivery attempt by a new publisher after a crash + expiry reaches the limit: DEAD.
        val again = db.outbox.claim("pub-b", 1, CLAIM_TTL).single()
        assertEquals(OutboxStatus.DEAD, db.outbox.markFailed(again.eventId, "pub-b", again.claimToken!!, Duration.ZERO, 2))
        assertEquals(1, db.count("outbox_events", "event_id = '${e.eventId}' AND status = 'DEAD' AND publish_attempts = 2"))
        assertTrue(db.outbox.claim("pub-c", 1, CLAIM_TTL).isEmpty(), "DEAD is terminal")
    }

    @Test
    fun `crash then DEAD migration then later versions are not blocked`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.outbox.appendFenced(lease, 1, "Custom", "{}")
        val e0 = db.outbox.claim("pub-a", 1, CLAIM_TTL).single() // version 0; pub-a crashes
        db.expireClaim(e0.eventId)
        val retry = db.outbox.claim("pub-b", 1, CLAIM_TTL).single()
        assertEquals(e0.eventId, retry.eventId)
        assertEquals(OutboxStatus.DEAD, db.outbox.markFailed(retry.eventId, "pub-b", retry.claimToken!!, Duration.ZERO, 1))
        assertEquals(listOf(1L), db.outbox.claim("pub-b", 5, CLAIM_TTL).map { it.aggregateVersion })
    }

    @Test
    fun `duplicate aggregate version and type is rejected by the unique constraint`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.outbox.appendFenced(lease, 7, "Custom", "{}")
        val ex = assertFailsWith<ExposedSQLException> { db.outbox.appendFenced(lease, 7, "Custom", "{}") }
        assertTrue(ex.isUniqueViolation())
    }

    @Test
    fun `a stale worker cannot append and leaves nothing behind`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.expireLease(run.runId.value)
        assertFailsWith<LeaseLostException> { db.outbox.appendFenced(lease, 9, "Custom", "{}") }
        assertEquals(1, db.count("outbox_events"))
    }

    @Test
    fun `append rolls back together with the surrounding transaction`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
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
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val first = db.outbox.claim("pub", 1, CLAIM_TTL).single()
        val id = first.eventId
        assertEquals(OutboxStatus.PENDING, db.outbox.markFailed(id, "pub", first.claimToken!!, Duration.ofHours(1), maxAttempts = 2))
        assertTrue(db.outbox.claim("pub", 10, CLAIM_TTL).isEmpty(), "backed-off event is not due")
        db.makeDue(id)
        val second = db.outbox.claim("pub", 10, CLAIM_TTL).single()
        assertEquals(OutboxStatus.DEAD, db.outbox.markFailed(id, "pub", second.claimToken!!, Duration.ofHours(1), maxAttempts = 2))
        assertNull(db.outbox.markFailed(id, "pub", second.claimToken!!, Duration.ofHours(1), maxAttempts = 2), "DEAD is terminal")
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
