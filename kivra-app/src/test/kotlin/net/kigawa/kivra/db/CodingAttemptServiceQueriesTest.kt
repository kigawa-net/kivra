package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import org.jetbrains.exposed.sql.transactions.transaction

/** The queries and transitions the coding service adds to [CodingAttemptRepository] (V14, no schema change). */
class CodingAttemptServiceQueriesTest {
    private val leaseTtl = Duration.ofSeconds(60)
    private val queueTtl = Duration.ofHours(24)
    private val baseSha = "a".repeat(40)

    private fun TestDb.repo() = CodingAttemptRepository(database)

    private fun TestDb.newRun(): String = runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun TestDb.enqueued(runId: String): CodingAttempt =
        assertIs<EnqueueResult.Enqueued>(repo().enqueue(runId, baseSha, queueTtl)).attempt

    @Test
    fun `enqueue with allowAfterSettled false refuses a Run whose attempts all ended, and says which one`() = TestDb().use { db ->
        val runId = db.newRun()
        val first = db.enqueued(runId)
        val claimed = assertIs<CodingAttemptResult.Ok>(db.repo().claim(first.attemptId, "agent-1", leaseTtl)).attempt
        assertIs<CodingAttemptResult.Ok>(db.repo().fail(first.attemptId, "agent-1", claimed.leaseGeneration, "CLI_NONZERO"))

        val settled = assertIs<EnqueueResult.Settled>(db.repo().enqueue(runId, baseSha, queueTtl, allowAfterSettled = false))
        assertEquals(first.attemptId, settled.latest.attemptId)
        assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))
        // The default keeps the earlier behaviour: a new attempt after a terminal one.
        assertEquals(2, assertIs<EnqueueResult.Enqueued>(db.repo().enqueue(runId, baseSha, queueTtl)).attempt.attemptNo)
    }

    @Test
    fun `listClaimable returns only unexpired QUEUED attempts, oldest first`() = TestDb().use { db ->
        val older = db.enqueued(db.newRun())
        val expired = db.enqueued(db.newRun())
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", expired.attemptId)
        // created_at has microseconds, but keep the two enqueues apart so the order assertion cannot depend on timer resolution.
        Thread.sleep(5)
        val newer = db.enqueued(db.newRun())
        val claimable = db.repo().listClaimable(10)
        assertEquals(listOf(older.attemptId, newer.attemptId), claimable)
        assertIs<CodingAttemptResult.Ok>(db.repo().claim(older.attemptId, "agent-1", leaseTtl))
        assertEquals(listOf(newer.attemptId), db.repo().listClaimable(10))
    }

    @Test
    fun `cancelQueued cancels only a QUEUED attempt, and a claimed one is refused as NOT_ACTIVE`() = TestDb().use { db ->
        val queued = db.enqueued(db.newRun())
        assertIs<CodingAttemptResult.Ok>(db.repo().cancelQueued(queued.attemptId))
        assertEquals(CodingAttemptState.CANCELLED, db.repo().get(queued.attemptId)!!.state)
        assertEquals(listOf("QUEUED", "CANCELLED"), db.repo().events(queued.attemptId).map { it.type })
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_ACTIVE), db.repo().cancelQueued(queued.attemptId))

        val claimedRun = db.newRun()
        val claimed = db.enqueued(claimedRun)
        assertIs<CodingAttemptResult.Ok>(db.repo().claim(claimed.attemptId, "agent-1", leaseTtl))
        assertEquals(CodingAttemptResult.Refused(CodingRefusal.NOT_ACTIVE), db.repo().cancelQueued(claimed.attemptId))
        assertEquals(CodingAttemptState.CLAIMED, db.repo().get(claimed.attemptId)!!.state)
    }

    @Test
    fun `latestAttempt returns the highest attempt number, and unsettled lists only DEVELOPING Runs`() = TestDb().use { db ->
        val runId = db.newRun()
        val first = db.enqueued(runId)
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", first.attemptId)
        db.repo().expire()
        assertEquals(first.attemptId, db.repo().latestAttempt(runId)!!.attemptId)
        assertNull(db.repo().latestAttempt(db.newRun()))

        // Only a DEVELOPING Run's expired attempt needs settling. A new Run is CREATED, so this one is not listed yet.
        assertTrue(db.repo().unsettledOfDevelopingRuns(10).none { it.attemptId == first.attemptId })
        db.forceState(runId, DevelopmentRunState.DEVELOPING)
        assertEquals(listOf(first.attemptId), db.repo().unsettledOfDevelopingRuns(10).map { it.attemptId })
        db.forceState(runId, DevelopmentRunState.FAILED)
        assertTrue(db.repo().unsettledOfDevelopingRuns(10).none { it.attemptId == first.attemptId })
    }

    @Test
    fun `heartbeat writes PROGRESS events only up to the cap, and never refuses a heartbeat because of it`() = TestDb().use { db ->
        val runId = db.newRun()
        val attempt = db.enqueued(runId)
        val claimed = assertIs<CodingAttemptResult.Ok>(db.repo().claim(attempt.attemptId, "agent-1", leaseTtl)).attempt
        repeat(CodingAttemptRepository.MAX_PROGRESS_EVENTS.toInt() + 5) {
            assertIs<CodingAttemptResult.Ok>(
                db.repo().heartbeat(attempt.attemptId, "agent-1", claimed.leaseGeneration, leaseTtl, progressPayload = "{\"phase\":\"TESTING\"}"),
            )
        }
        assertEquals(
            CodingAttemptRepository.MAX_PROGRESS_EVENTS.toInt(),
            db.repo().events(attempt.attemptId).count { it.type == "PROGRESS" },
        )
        // A plain heartbeat writes no event.
        val before = db.repo().events(attempt.attemptId).size
        assertIs<CodingAttemptResult.Ok>(db.repo().heartbeat(attempt.attemptId, "agent-1", claimed.leaseGeneration, leaseTtl))
        assertEquals(before, db.repo().events(attempt.attemptId).size)
    }
}
