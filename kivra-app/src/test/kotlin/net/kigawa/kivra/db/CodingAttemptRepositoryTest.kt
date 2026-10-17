package net.kigawa.kivra.db

import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunCompletionMode
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository contract of `coding_attempts` / `coding_events` (V14). Runs on H2 by default and on MariaDB with
 * `KIVRA_TEST_DB=mariadb`. Each test gets a freshly migrated database and one Run.
 */
class CodingAttemptRepositoryTest {
    private val leaseTtl = Duration.ofSeconds(60)
    private val queueTtl = Duration.ofHours(24)
    private val baseSha = "a".repeat(40)
    private val digest = "b".repeat(64)

    private fun TestDb.repo() = CodingAttemptRepository(database)

    private fun TestDb.newRun(): String = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.RELEASE).runId.value

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun TestDb.enqueued(runId: String): CodingAttempt {
        val result = repo().enqueue(runId, baseSha, queueTtl)
        return assertIs<EnqueueResult.Enqueued>(result).attempt
    }

    private fun TestDb.claimed(runId: String, agent: String = "agent-1"): CodingAttempt {
        val attempt = enqueued(runId)
        return assertIs<CodingAttemptResult.Ok>(repo().claim(attempt.attemptId, agent, leaseTtl)).attempt
    }

    @Test
    fun `enqueue creates a QUEUED attempt with attempt_no 1 and an ENQUEUED event`() = TestDb().use { db ->
        val runId = db.newRun()
        val attempt = db.enqueued(runId)
        assertEquals(CodingAttemptState.QUEUED, attempt.state)
        assertEquals(1, attempt.attemptNo)
        assertEquals(0L, attempt.leaseGeneration)
        assertNull(attempt.claimedBy)
        assertEquals(baseSha, attempt.baseSha)
        assertEquals(listOf("QUEUED"), db.repo().events(attempt.attemptId).map { it.type })
    }

    @Test
    fun `enqueue is idempotent while the Run has an active attempt`() = TestDb().use { db ->
        val runId = db.newRun()
        val first = db.enqueued(runId)
        val second = assertIs<EnqueueResult.Existing>(db.repo().enqueue(runId, baseSha, queueTtl)).attempt
        assertEquals(first.attemptId, second.attemptId)
        assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))
        assertEquals(1, db.count("coding_events", "attempt_id = '${first.attemptId}'"))
    }

    @Test
    fun `concurrent enqueues for one Run create exactly one attempt`() = TestDb().use { db ->
        val runId = db.newRun()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val barrier = CyclicBarrier(8)
            val futures = (1..8).map {
                pool.submit<EnqueueResult> {
                    barrier.await()
                    db.repo().enqueue(runId, baseSha, queueTtl)
                }
            }
            val ids = futures.map { f ->
                when (val r = f.get(30, TimeUnit.SECONDS)) {
                    is EnqueueResult.Enqueued -> r.attempt.attemptId
                    is EnqueueResult.Existing -> r.attempt.attemptId
                    else -> error("unexpected $r")
                }
            }.toSet()
            assertEquals(1, ids.size)
            assertEquals(1, futures.count { it.get() is EnqueueResult.Enqueued })
            assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `enqueue refuses an unknown Run and an invalid base sha`() = TestDb().use { db ->
        assertEquals(EnqueueResult.RunNotFound, db.repo().enqueue("01HNOSUCHRUN0000000000000A", baseSha, queueTtl))
        assertFailsWith<IllegalArgumentException> { db.repo().enqueue(db.newRun(), "NOT-A-SHA", queueTtl) }
    }

    @Test
    fun `the database allows one active attempt per Run and a new one only after the previous is terminal`() = TestDb().use { db ->
        val runId = db.newRun()
        val first = db.enqueued(runId)
        // A second active row for the same Run is refused by UNIQUE(run_id, active_flag), whatever its attempt_no.
        assertFailsWith<Exception> { insertRaw(db, runId, attemptNo = 2, state = "QUEUED") }
        assertFailsWith<Exception> { insertRaw(db, runId, attemptNo = 2, state = "CLAIMED") }
        // A terminal row does not count, so it can be inserted alongside the active one.
        insertRaw(db, runId, attemptNo = 3, state = "FAILED")
        assertEquals(first.attemptId, db.repo().findActive(runId)!!.attemptId)

        // Once the active attempt is terminal, a new attempt is queued with the next attempt_no.
        val claimed = assertIs<CodingAttemptResult.Ok>(db.repo().claim(first.attemptId, "agent-1", leaseTtl)).attempt
        assertIs<CodingAttemptResult.Ok>(db.repo().fail(first.attemptId, "agent-1", claimed.leaseGeneration, "CLI_NONZERO"))
        assertNull(db.repo().findActive(runId))
        val next = assertIs<EnqueueResult.Enqueued>(db.repo().enqueue(runId, baseSha, queueTtl)).attempt
        assertEquals(4, next.attemptNo)
    }

    @Test
    fun `concurrent claims of one attempt let exactly one win, with generation 1`() = TestDb().use { db ->
        val attempt = db.enqueued(db.newRun())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val barrier = CyclicBarrier(8)
            val results = (1..8).map { i ->
                pool.submit<CodingAttemptResult> {
                    barrier.await()
                    db.repo().claim(attempt.attemptId, "agent-$i", leaseTtl)
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
            val wins = results.filterIsInstance<CodingAttemptResult.Ok>()
            assertEquals(1, wins.size)
            assertTrue(results.filterIsInstance<CodingAttemptResult.Refused>().all { it.reason == CodingRefusal.NOT_QUEUED })
            val stored = db.repo().get(attempt.attemptId)!!
            assertEquals(CodingAttemptState.CLAIMED, stored.state)
            assertEquals(1L, stored.leaseGeneration)
            assertEquals(wins.single().attempt.claimedBy, stored.claimedBy)
            assertEquals(1, db.count("coding_events", "attempt_id = '${attempt.attemptId}' AND type = 'CLAIMED'"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a claim after the queue expired is refused, and expire moves the attempt to EXPIRED`() = TestDb().use { db ->
        val attempt = db.enqueued(db.newRun())
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.QUEUE_EXPIRED), db.repo().claim(attempt.attemptId, "agent-1", leaseTtl))

        val result = db.repo().expire()
        assertEquals(listOf(attempt.attemptId), result.expiredQueued)
        assertEquals(CodingAttemptState.EXPIRED, db.repo().get(attempt.attemptId)!!.state)
        assertNull(db.repo().findActive(attempt.runId))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_QUEUED), db.repo().claim(attempt.attemptId, "agent-1", leaseTtl))
        assertEquals(listOf("QUEUED", "EXPIRED"), db.repo().events(attempt.attemptId).map { it.type })
    }

    @Test
    fun `a stale generation cannot heartbeat, start, complete or fail`() = TestDb().use { db ->
        val claimed = db.claimed(db.newRun())
        val repo = db.repo()
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.STALE_GENERATION), repo.heartbeat(claimed.attemptId, "agent-1", 0, leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.STALE_GENERATION), repo.start(claimed.attemptId, "agent-1", 0, leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.STALE_GENERATION), repo.complete(claimed.attemptId, "agent-1", 0, digest, 0, null))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.STALE_GENERATION), repo.fail(claimed.attemptId, "agent-1", 0, "TIMEOUT"))
        assertEquals(CodingAttemptState.CLAIMED, repo.get(claimed.attemptId)!!.state)

        // The current generation is accepted: CLAIMED -> RUNNING, then the result.
        val running = assertIs<CodingAttemptResult.Ok>(repo.start(claimed.attemptId, "agent-1", 1, leaseTtl)).attempt
        assertEquals(CodingAttemptState.RUNNING, running.state)
        assertIs<CodingAttemptResult.Ok>(repo.heartbeat(claimed.attemptId, "agent-1", 1, leaseTtl))
        val done = assertIs<CodingAttemptResult.Ok>(repo.complete(claimed.attemptId, "agent-1", 1, digest, 0, "session-1")).attempt
        assertEquals(CodingAttemptState.RESULT_RECEIVED, done.state)
        assertEquals(digest, done.resultDigest)
        assertEquals(0, done.exitStatus)
        assertEquals("session-1", done.sessionId)

        // Terminal: nothing more is accepted, even with the right generation.
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_ACTIVE), repo.heartbeat(claimed.attemptId, "agent-1", 1, leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_ACTIVE), repo.complete(claimed.attemptId, "agent-1", 1, digest, 0, null))
        assertEquals(
            listOf("QUEUED", "CLAIMED", "RUNNING", "RESULT_RECEIVED"),
            repo.events(claimed.attemptId).map { it.type },
        )
    }

    @Test
    fun `an expired lease is refused and expire moves it to ORPHANED so the Run can queue a new attempt`() = TestDb().use { db ->
        val runId = db.newRun()
        val claimed = db.claimed(runId)
        val repo = db.repo()
        assertIs<CodingAttemptResult.Ok>(repo.start(claimed.attemptId, "agent-1", 1, leaseTtl))
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", claimed.attemptId)

        assertEquals(CodingAttemptResult.Refused(CodingRefusal.LEASE_EXPIRED), repo.heartbeat(claimed.attemptId, "agent-1", 1, leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.LEASE_EXPIRED), repo.complete(claimed.attemptId, "agent-1", 1, digest, 0, null))

        val result = repo.expire()
        assertEquals(listOf(claimed.attemptId), result.orphaned)
        assertTrue(result.expiredQueued.isEmpty())
        assertEquals(CodingAttemptState.ORPHANED, repo.get(claimed.attemptId)!!.state)
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_ACTIVE), repo.heartbeat(claimed.attemptId, "agent-1", 1, leaseTtl))
        assertNull(repo.findActive(runId))

        // The Run is free to get a new attempt; its number follows the orphaned one.
        val next = assertIs<EnqueueResult.Enqueued>(repo.enqueue(runId, baseSha, queueTtl)).attempt
        assertEquals(2, next.attemptNo)
        assertEquals(listOf("QUEUED", "CLAIMED", "RUNNING", "ORPHANED"), repo.events(claimed.attemptId).map { it.type })
    }

    @Test
    fun `expire leaves live leases and queues alone`() = TestDb().use { db ->
        val live = db.claimed(db.newRun())
        val queued = db.enqueued(db.newRun())
        val result = db.repo().expire()
        assertTrue(result.orphaned.isEmpty())
        assertTrue(result.expiredQueued.isEmpty())
        assertEquals(CodingAttemptState.CLAIMED, db.repo().get(live.attemptId)!!.state)
        assertEquals(CodingAttemptState.QUEUED, db.repo().get(queued.attemptId)!!.state)
    }

    @Test
    fun `fail records the fixed error code and accepts only FAILED or CANCELLED`() = TestDb().use { db ->
        val claimed = db.claimed(db.newRun())
        assertFailsWith<IllegalArgumentException> { db.repo().fail(claimed.attemptId, "agent-1", 1, "timeout with spaces") }
        assertFailsWith<IllegalArgumentException> { db.repo().fail(claimed.attemptId, "agent-1", 1, "TIMEOUT", CodingAttemptState.RUNNING) }
        assertFailsWith<IllegalArgumentException> { db.repo().complete(claimed.attemptId, "agent-1", 1, "not-a-digest", 0, null) }
        assertFailsWith<IllegalArgumentException> { db.repo().complete(claimed.attemptId, "agent-1", 1, digest, 0, "has space") }

        val cancelled = assertIs<CodingAttemptResult.Ok>(
            db.repo().fail(claimed.attemptId, "agent-1", 1, "CANCELLED", CodingAttemptState.CANCELLED),
        ).attempt
        assertEquals(CodingAttemptState.CANCELLED, cancelled.state)
        assertEquals("CANCELLED", cancelled.errorCode)
    }

    @Test
    fun `claim of an unknown attempt reports NOT_FOUND and changes nothing`() = TestDb().use { db ->
        val id = "01HNOSUCHATTEMPT00000000000"
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_FOUND), db.repo().claim(id, "agent-1", leaseTtl))
        assertEquals(0, db.count("coding_events", "attempt_id = '$id'"))
    }

    @Test
    fun `the event payload column refuses values over its size`() = TestDb().use { db ->
        val attempt = db.enqueued(db.newRun())
        assertFailsWith<Exception> {
            db.exec(
                "INSERT INTO coding_events (attempt_id, seq, type, payload_json, created_at) VALUES (?, 99, 'X', ?, NOW(6))",
                attempt.attemptId, "x".repeat(513),
            )
        }
        assertNotNull(db.repo().get(attempt.attemptId))
    }

    @Test
    fun `another agent cannot heartbeat, start, complete or fail an attempt it does not hold, even with the right generation`() = TestDb().use { db ->
        val claimed = db.claimed(db.newRun(), agent = "agent-1")
        val repo = db.repo()
        // The generation is a small integer that anyone can guess, so the holder must be checked in the same UPDATE.
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_FOUND), repo.heartbeat(claimed.attemptId, "agent-2", 1, leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_FOUND), repo.start(claimed.attemptId, "agent-2", 1, leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_FOUND), repo.complete(claimed.attemptId, "agent-2", 1, digest, 0, null))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_FOUND), repo.fail(claimed.attemptId, "agent-2", 1, "TIMEOUT"))
        assertEquals(CodingAttemptState.CLAIMED, repo.get(claimed.attemptId)!!.state)
        assertNull(repo.get(claimed.attemptId, "agent-2"))
        assertEquals("agent-1", repo.get(claimed.attemptId, "agent-1")!!.claimedBy)
        assertIs<CodingAttemptResult.Ok>(repo.heartbeat(claimed.attemptId, "agent-1", 1, leaseTtl))
    }

    /** Inserts a row without going through the repository (to test the constraints themselves). */
    private fun insertRaw(db: TestDb, runId: String, attemptNo: Int, state: String) {
        db.exec(
            """
            INSERT INTO coding_attempts
                (attempt_id, run_id, attempt_no, state, lease_generation, queued_expires_at, base_sha, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, 0, TIMESTAMPADD(HOUR, 24, NOW(6)), ?, 0, NOW(6), NOW(6))
            """.trimIndent(),
            Ulid.generate(), runId, attemptNo, state, baseSha,
        )
    }
}
