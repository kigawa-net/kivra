package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.coding.MonitorEvent
import net.kigawa.kivra.coding.MonitorKind
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import org.jetbrains.exposed.sql.transactions.transaction

private const val AGENT = "agent-1"
private val LEASE = Duration.ofMinutes(2)

/**
 * The monitor event log (V20, ADR 0012 section 2.2 and 3) on H2 or MariaDB: the append rules (fencing, contiguous seq, resend, conflict,
 * gap, cap and TRUNCATED mark) and the retention. The attempts are real (the attempt repository's enqueue and claim).
 */
class CodingMonitorEventRepositoryTest {

    private inner class Harness(val db: TestDb, cap: Int = CodingMonitorEventRepository.DEFAULT_MAX_EVENTS_PER_ATTEMPT) {
        val attempts = CodingAttemptRepository(db.database)
        val monitor = CodingMonitorEventRepository(db.database, maxEventsPerAttempt = cap)

        fun run(state: DevelopmentRunState = DevelopmentRunState.DEVELOPING): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            db.forceState(runId, state)
            return runId
        }

        /** A claimed attempt of a DEVELOPING Run, held by [AGENT] at generation 1. */
        fun claimed(runId: String = run()): String {
            val queued = assertIs<EnqueueResult.Enqueued>(attempts.enqueue(runId, BASE_SHA, Duration.ofHours(24)))
            assertIs<CodingAttemptResult.Ok>(attempts.claim(queued.attempt.attemptId, AGENT, LEASE))
            return queued.attempt.attemptId
        }

        fun rowCount(attemptId: String): Int = transaction(db.database) {
            execQuery("SELECT COUNT(*) FROM coding_monitor_events WHERE attempt_id = ?", attemptId) { it.getInt(1) }.single()
        }
    }

    private fun turn(seq: Int, count: Int = 1) = MonitorEvent(seq, MonitorKind.TURN, count = count)

    private fun tool(seq: Int, name: String = "Read") = MonitorEvent(seq, MonitorKind.TOOL_USE, toolName = name)

    @Test
    fun `a batch is stored in order and a resend of the same content is accepted without new rows`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        val batch = listOf(MonitorEvent(1, MonitorKind.ATTEMPT_STARTED), tool(2), turn(3, count = 2))
        assertEquals(MonitorAppendResult.Accepted(3, truncated = false), h.monitor.append(attemptId, AGENT, 1, batch))
        assertEquals(3, h.rowCount(attemptId))
        assertEquals(MonitorAppendResult.Accepted(3, truncated = false), h.monitor.append(attemptId, AGENT, 1, batch))
        assertEquals(3, h.rowCount(attemptId))

        val stored = h.monitor.read(attemptId, afterGeneration = 0, afterSeq = 0, limit = 10)
        assertEquals(listOf(1, 2, 3), stored.map { it.event.seq })
        assertEquals(MonitorEvent(2, MonitorKind.TOOL_USE, toolName = "Read"), stored[1].event)
        assertEquals(MonitorEvent(3, MonitorKind.TURN, count = 2), stored[2].event)
        assertEquals(listOf(1L, 1L, 1L), stored.map { it.leaseGeneration })
        // The cursor: the rows after (generation 1, seq 2) are the last one.
        assertEquals(listOf(3), h.monitor.read(attemptId, afterGeneration = 1, afterSeq = 2, limit = 10).map { it.event.seq })
    }

    @Test
    fun `the next batch continues from the last accepted seq, and a gap is refused with nothing stored`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        assertEquals(MonitorAppendResult.Accepted(2, false), h.monitor.append(attemptId, AGENT, 1, listOf(turn(1), turn(2))))
        assertEquals(MonitorAppendResult.Gap(2), h.monitor.append(attemptId, AGENT, 1, listOf(turn(4), turn(5))))
        assertEquals(2, h.rowCount(attemptId))
        // A batch that begins with a resend and goes on is accepted in part.
        assertEquals(MonitorAppendResult.Accepted(4, false), h.monitor.append(attemptId, AGENT, 1, listOf(turn(2), turn(3), turn(4))))
        assertEquals(4, h.rowCount(attemptId))
    }

    @Test
    fun `a seq that is stored with different content is a conflict, and nothing in that batch is stored`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        h.monitor.append(attemptId, AGENT, 1, listOf(turn(1, count = 1), turn(2, count = 1)))
        assertEquals(MonitorAppendResult.Conflict(2), h.monitor.append(attemptId, AGENT, 1, listOf(turn(2, count = 9), turn(3, count = 1))))
        assertEquals(2, h.rowCount(attemptId))
        assertEquals(MonitorAppendResult.Conflict(2), h.monitor.append(attemptId, AGENT, 1, listOf(turn(1, count = 5))))
    }

    @Test
    fun `a stale generation, another holder, a missing attempt and a terminal attempt are all refused and store nothing`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        assertEquals(MonitorAppendResult.Refused(CodingRefusal.STALE_GENERATION), h.monitor.append(attemptId, AGENT, 2, listOf(turn(1))))
        assertEquals(MonitorAppendResult.Refused(CodingRefusal.STALE_GENERATION), h.monitor.append(attemptId, AGENT, 0, listOf(turn(1))))
        assertEquals(MonitorAppendResult.Refused(CodingRefusal.NOT_FOUND), h.monitor.append(attemptId, "agent-2", 1, listOf(turn(1))))
        assertEquals(MonitorAppendResult.Refused(CodingRefusal.NOT_FOUND), h.monitor.append("01ARZ3NDEKTSV4RRFFQ69G5FAV", AGENT, 1, listOf(turn(1))))
        assertEquals(0, h.rowCount(attemptId))

        // A terminal attempt takes no more events. Its earlier events stay.
        h.monitor.append(attemptId, AGENT, 1, listOf(turn(1)))
        assertIs<CodingAttemptResult.Ok>(h.attempts.fail(attemptId, AGENT, 1, "TIMEOUT"))
        assertEquals(MonitorAppendResult.Refused(CodingRefusal.NOT_ACTIVE), h.monitor.append(attemptId, AGENT, 1, listOf(turn(2))))
        assertEquals(1, h.rowCount(attemptId))
    }

    @Test
    fun `a queued attempt, which nobody holds yet, takes no events`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val queued = assertIs<EnqueueResult.Enqueued>(h.attempts.enqueue(runId, BASE_SHA, Duration.ofHours(24)))
        assertEquals(
            MonitorAppendResult.Refused(CodingRefusal.NOT_FOUND),
            h.monitor.append(queued.attempt.attemptId, AGENT, 0, listOf(turn(1))),
        )
    }

    @Test
    fun `the cap stores at most the cap, writes one TRUNCATED mark, and refuses the rest with truncated set`() = TestDb().use { db ->
        val h = Harness(db, cap = 5)
        val attemptId = h.claimed()
        assertEquals(MonitorAppendResult.Accepted(3, false), h.monitor.append(attemptId, AGENT, 1, listOf(turn(1), turn(2), turn(3))))
        // Two of these fit, the rest is dropped; the mark is written once.
        assertEquals(MonitorAppendResult.Accepted(5, true), h.monitor.append(attemptId, AGENT, 1, listOf(turn(4), turn(5), turn(6), turn(7))))
        assertEquals(MonitorAppendResult.Accepted(5, true), h.monitor.append(attemptId, AGENT, 1, listOf(turn(6), turn(7))))
        assertEquals(MonitorAppendResult.Accepted(5, true), h.monitor.append(attemptId, AGENT, 1, listOf(turn(1), turn(2))))
        // 5 events and one mark.
        assertEquals(6, h.rowCount(attemptId))
        val stored = h.monitor.read(attemptId, 0, 0, 200)
        assertEquals(MonitorKind.TRUNCATED, stored.first().event.kind)
        assertEquals(0, stored.first().event.seq)
        assertEquals(1, stored.count { it.event.kind == MonitorKind.TRUNCATED })
    }

    @Test
    fun `the default cap is two thousand events per attempt, and the two thousand and first is refused`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        for (start in 1..2000 step 50) {
            val batch = (start until start + 50).map { turn(it, count = 0) }
            assertEquals(MonitorAppendResult.Accepted(start + 49, truncated = start + 49 >= 2000), h.monitor.append(attemptId, AGENT, 1, batch))
        }
        assertEquals(2001, h.rowCount(attemptId), "2000 events and the TRUNCATED mark")
        assertEquals(MonitorAppendResult.Accepted(2000, true), h.monitor.append(attemptId, AGENT, 1, listOf(turn(2001))))
        assertEquals(2001, h.rowCount(attemptId))
    }

    @Test
    fun `an event with a cost keeps six decimals through the database`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        val cost = MonitorEvent(1, MonitorKind.COST, costUsd = BigDecimal("12.345678").setScale(6))
        h.monitor.append(attemptId, AGENT, 1, listOf(cost))
        assertEquals(cost, h.monitor.read(attemptId, 0, 0, 1).single().event)
    }

    @Test
    fun `the retention deletes the events of ended attempts whose Run is terminal, and keeps active ones`() = TestDb().use { db ->
        val h = Harness(db)
        // Ended, Run terminal: deleted.
        val ended = h.claimed(h.run(DevelopmentRunState.COMPLETED))
        h.monitor.append(ended, AGENT, 1, listOf(turn(1)))
        assertIs<CodingAttemptResult.Ok>(h.attempts.fail(ended, AGENT, 1, "TIMEOUT"))
        // Still active: kept, even though its Run is terminal (a lease still held).
        val active = h.claimed(h.run(DevelopmentRunState.COMPLETED))
        h.monitor.append(active, AGENT, 1, listOf(turn(1)))
        // Ended, Run still DEVELOPING: kept (the ADR keeps these until the Run ends).
        val pendingRun = h.claimed(h.run())
        h.monitor.append(pendingRun, AGENT, 1, listOf(turn(1)))
        assertIs<CodingAttemptResult.Ok>(h.attempts.fail(pendingRun, AGENT, 1, "TIMEOUT"))

        val later = LocalDateTime.now(ZoneOffset.UTC).plusDays(1)
        assertEquals(1, h.monitor.purgeBefore(later, maxAttempts = 200))
        assertEquals(0, h.rowCount(ended))
        assertEquals(1, h.rowCount(active))
        assertEquals(1, h.rowCount(pendingRun))
        // The attempt row stays.
        assertEquals(CodingAttemptState.FAILED, h.attempts.get(ended)!!.state)
    }

    @Test
    fun `the retention is by age, and at most the given number of attempts per pass`() = TestDb().use { db ->
        val h = Harness(db)
        val first = h.claimed(h.run(DevelopmentRunState.COMPLETED))
        val second = h.claimed(h.run(DevelopmentRunState.COMPLETED))
        for (id in listOf(first, second)) {
            h.monitor.append(id, AGENT, 1, listOf(turn(1), turn(2)))
            assertIs<CodingAttemptResult.Ok>(h.attempts.fail(id, AGENT, 1, "TIMEOUT"))
        }
        // Not yet older than the cut-off: nothing goes.
        assertEquals(0, h.monitor.purgeBefore(LocalDateTime.now(ZoneOffset.UTC).minusDays(1), 200))
        // Older than the cut-off, one attempt per pass.
        val later = LocalDateTime.now(ZoneOffset.UTC).plusDays(1)
        assertEquals(2, h.monitor.purgeBefore(later, maxAttempts = 1))
        assertTrue(h.rowCount(first) + h.rowCount(second) == 2, "exactly one attempt was cleared")
        assertEquals(2, h.monitor.purgeBefore(later, maxAttempts = 1))
        assertEquals(0, h.rowCount(first) + h.rowCount(second))
    }

    @Test
    fun `read returns at most the limit, in cursor order`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptId = h.claimed()
        h.monitor.append(attemptId, AGENT, 1, (1..5).map { turn(it) })
        assertEquals(listOf(1, 2), h.monitor.read(attemptId, 0, 0, 2).map { it.event.seq })
        assertEquals(listOf(3, 4), h.monitor.read(attemptId, 1, 2, 2).map { it.event.seq })
    }

    private companion object {
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
