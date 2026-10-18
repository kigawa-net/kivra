package net.kigawa.kivra.coding

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private const val AGENT = "agent-1"
private const val TITLE = "TITLE-MARKER README を直す"
private const val SUMMARY = "SUMMARY-MARKER 説明"
private const val CONTENT = "CONTENT-MARKER body"
private const val RETENTION_DAYS = 30L
private const val OLD_ROW_TIME_SQL = "2020-01-01 00:00:00"

/** The reserve of the default cost fixture: 90.750000 yen (see CostFixtures). */
private val RESERVE_YEN = BigDecimal("90.750000")
private val USAGE = CliUsage(BigDecimal("0.004268"), "claude-haiku-5-5", 4, 1200, 300, 5000, 800)

/**
 * The periodic coding work (ADR 0010 addendum 6, Issue #231): the settle of overdue attempts (no Run is moved, each reserve booked once),
 * the retention of stored change sets (only the ones nothing can read any more; attempt and usage rows stay), the per-pass limit, and the
 * logs. Real repositories and a real budget store on H2 (or MariaDB).
 */
class CodingReconcileWorkerTest {
    private val baseSha = "a".repeat(40)

    private inner class Harness(val db: TestDb) {
        val fixture = costFixture(db)
        val repo = CodingAttemptRepository(db.database)
        val results = DbCodingResultStore(db.database)
        val service = CodingRuntimeService(
            attempts = repo,
            runs = DbCodingRunPort(db.runs, db.leases),
            results = results,
            tasks = CodingTaskSource { it.taskTitle },
            cost = fixture.ledger,
        )

        fun worker(
            purgeLimit: Int = CodingReconcileWorker.DEFAULT_PURGE_LIMIT,
            purge: CodingResultPurge = results,
            retention: Duration = Duration.ofDays(RETENTION_DAYS),
        ) = CodingReconcileWorker(service, purge, Duration.ofSeconds(60), retention, Clock.systemUTC(), purgeLimit)

        fun run(state: DevelopmentRunState = DevelopmentRunState.DEVELOPING): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            db.exec("UPDATE development_runs SET task_title = ? WHERE run_id = ?", TITLE, runId)
            db.forceState(runId, state)
            return runId
        }

        fun queued(runId: String) = assertIs<CodingEnqueueOutcome.Queued>(service.enqueue(runId, baseSha)).attempt

        fun claimed(): CodingInput = assertIs<CodingClaimOutcome.Claimed>(service.claim(AGENT)).input

        fun state(attemptId: String): CodingAttemptState = repo.get(attemptId)!!.state

        fun runState(runId: String): DevelopmentRunState = db.runs.get(RunId(runId))!!.state

        /** One attempt accepted with a result and usage, so its change set is stored (RESULT_RECEIVED). Returns the attempt id and digest. */
        fun accepted(runId: String): Pair<String, String> {
            val attempt = queued(runId)
            val input = claimed()
            val result = CodingResult(
                summary = SUMMARY,
                changes = listOf(CodingChange("docs/a.md", ChangeKind.WRITE, CONTENT)),
                exitStatus = 0,
                sessionId = "session-1",
            )
            assertIs<CodingSubmitOutcome.Accepted>(service.submitResult(attempt.attemptId, AGENT, input.leaseGeneration, result, USAGE))
            return attempt.attemptId to repo.get(attempt.attemptId)!!.resultDigest!!
        }

        /** Makes the stored row of [attemptId] older than any retention. */
        fun age(attemptId: String) = db.exec("UPDATE coding_results SET created_at = ? WHERE attempt_id = ?", OLD_ROW_TIME_SQL, attemptId)

        fun hasResult(attemptId: String, digest: String): Boolean = results.load(attemptId, digest) != null
    }

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    /** Runs [block] with the root logger capturing every formatted message. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    // ---- the settle: overdue attempts, no Run moved, reserves booked once ----

    @Test
    fun `a pass marks an overdue lease ORPHANED and books its reserve, and does not move the Run`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val attempt = h.queued(runId)
        h.claimed()
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        val pass = h.worker().tick()

        assertEquals(1, pass.orphaned)
        assertEquals(CodingAttemptState.ORPHANED, h.state(attempt.attemptId))
        assertEquals(DevelopmentRunState.DEVELOPING, h.runState(runId))
        assertEquals(0, RESERVE_YEN.compareTo(h.fixture.budget.usage(COST_TENANT).committed))
    }

    @Test
    fun `a pass marks an overdue queue EXPIRED`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        assertEquals(1, h.worker().tick().queuedExpired)

        assertEquals(CodingAttemptState.EXPIRED, h.state(attempt.attemptId))
    }

    @Test
    fun `repeated passes, by one worker or by several, book each reserve once`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        h.claimed()
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        h.worker().tick()
        h.worker().tick()
        h.worker().tick()

        val usage = h.fixture.budget.usage(COST_TENANT)
        assertEquals(0, RESERVE_YEN.compareTo(usage.committed), "the reserve is booked once, not once per pass")
        assertEquals(1L, usage.unpricedCalls)
        assertEquals("ESTIMATED_RESERVE", h.fixture.usageRows.get(attempt.attemptId, 1)!!.source)
    }

    @Test
    fun `a pass that finds nothing to do changes nothing`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())

        val pass = h.worker().tick()

        assertEquals(CodingWorkerPass(queuedExpired = 0, orphaned = 0, resultsPurged = 0), pass)
        assertEquals(CodingAttemptState.QUEUED, h.state(attempt.attemptId))
        assertEquals(0L, h.fixture.budget.usage(COST_TENANT).unpricedCalls)
    }

    @Test
    fun `a failing purge does not stop the settle of overdue attempts`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)
        val failingPurge = CodingResultPurge { _, _ -> throw IllegalStateException("SECRET-PURGE-MARKER") }

        val logs = captureLogs {
            val pass = h.worker(purge = failingPurge).tick()
            assertEquals(1, pass.queuedExpired)
            assertEquals(0, pass.resultsPurged)
        }

        assertEquals(CodingAttemptState.EXPIRED, h.state(attempt.attemptId))
        assertTrue(logs.any { it.contains("step=PURGE") && it.contains("IllegalStateException") })
        assertFalse(logs.any { it.contains("SECRET-PURGE-MARKER") }, "an exception message is never logged")
    }

    // ---- the retention of stored change sets ----

    @Test
    fun `an old stored result of a terminal Run is deleted, and its attempt and usage rows stay`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val (attemptId, digest) = h.accepted(runId)
        db.forceState(runId, DevelopmentRunState.FAILED)
        h.age(attemptId)

        val pass = h.worker().tick()

        assertEquals(1, pass.resultsPurged)
        assertFalse(h.hasResult(attemptId, digest))
        assertEquals(CodingAttemptState.RESULT_RECEIVED, h.state(attemptId))
        assertNotNull(h.fixture.usageRows.get(attemptId, 1), "the usage row stays")
    }

    @Test
    fun `an old stored result of a Run that is still DEVELOPING is kept`() = TestDb().use { db ->
        val h = Harness(db)
        val (attemptId, digest) = h.accepted(h.run())
        h.age(attemptId)

        assertEquals(0, h.worker().tick().resultsPurged)

        assertTrue(h.hasResult(attemptId, digest), "the Run may still read it on resume (PATCH_READY)")
    }

    @Test
    fun `a fresh stored result is kept until it is older than the retention`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val (attemptId, digest) = h.accepted(runId)
        db.forceState(runId, DevelopmentRunState.FAILED)

        assertEquals(0, h.worker(retention = Duration.ofDays(RETENTION_DAYS)).tick().resultsPurged)
        assertTrue(h.hasResult(attemptId, digest))

        // The same row is past a one-day retention once it is aged.
        h.age(attemptId)
        assertEquals(1, h.worker(retention = Duration.ofDays(1)).tick().resultsPurged)
    }

    @Test
    fun `the stored set of an active attempt is never deleted`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val attempt = h.queued(runId)
        val input = h.claimed()
        // A row for a CLAIMED attempt cannot come from the service; it is written directly to test the rule on its own.
        val digest = "b".repeat(64)
        h.results.save(attempt.attemptId, digest, PatchSet(listOf(Write("docs/a.md", CONTENT)), SUMMARY))
        h.age(attempt.attemptId)

        assertEquals(0, h.worker().tick().resultsPurged)

        assertTrue(h.hasResult(attempt.attemptId, digest))
        assertEquals(CodingAttemptState.CLAIMED, h.state(attempt.attemptId))
        assertEquals(1L, input.leaseGeneration)
    }

    @Test
    fun `a row that was never accepted, written after a refused complete, is deleted once old even while its Run is DEVELOPING`() =
        TestDb().use { db ->
            val h = Harness(db)
            val attempt = h.queued(h.run())
            h.claimed()
            val digest = "c".repeat(64)
            h.results.save(attempt.attemptId, digest, PatchSet(listOf(Write("docs/a.md", CONTENT)), SUMMARY))
            assertIs<CodingAttemptResult.Ok>(h.service.fail(attempt.attemptId, AGENT, 1, CodingErrorCode.TIMEOUT))
            h.age(attempt.attemptId)

            assertEquals(1, h.worker().tick().resultsPurged)

            assertFalse(h.hasResult(attempt.attemptId, digest))
        }

    @Test
    fun `at most the pass limit is deleted per pass, and the rest on the next pass`() = TestDb().use { db ->
        val h = Harness(db)
        repeat(3) {
            val runId = h.run()
            val (attemptId, _) = h.accepted(runId)
            db.forceState(runId, DevelopmentRunState.FAILED)
            h.age(attemptId)
        }

        assertEquals(2, h.worker(purgeLimit = 2).tick().resultsPurged)
        assertEquals(1, h.worker(purgeLimit = 2).tick().resultsPurged)
        assertEquals(0, h.worker(purgeLimit = 2).tick().resultsPurged)
    }

    @Test
    fun `a non-positive interval, retention or pass limit is refused`() = TestDb().use { db ->
        val h = Harness(db)
        val purge = CodingResultPurge { _, _ -> 0 }
        assertFailsWith<IllegalArgumentException> { CodingReconcileWorker(h.service, purge, Duration.ZERO, Duration.ofDays(1)) }
        assertFailsWith<IllegalArgumentException> { CodingReconcileWorker(h.service, purge, Duration.ofSeconds(60), Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> {
            CodingReconcileWorker(h.service, purge, Duration.ofSeconds(60), Duration.ofDays(1), purgeLimit = 0)
        }
    }

    // ---- logs ----

    @Test
    fun `the logs of a pass carry counts and identifiers, never a stored change or a task text`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val (attemptId, _) = h.accepted(runId)
        db.forceState(runId, DevelopmentRunState.FAILED)
        h.age(attemptId)
        val queuedAttempt = h.queued(h.run())
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", queuedAttempt.attemptId)

        val logs = captureLogs { h.worker().tick() }

        assertTrue(logs.any { it.startsWith("coding reconcile pass:") && it.contains("resultsPurged=1") && it.contains("queuedExpired=1") })
        val all = logs.joinToString("\n")
        for (marker in listOf(CONTENT, SUMMARY, TITLE, "docs/a.md")) {
            assertFalse(all.contains(marker), "a log line must not carry the marker")
        }
    }

    /**
     * ADR 0012 section 3: each pass also asks the monitor purge for the events older than the monitor retention, at most 200 attempts, and
     * the number it deleted is in the pass.
     */
    @Test
    fun `each pass purges the monitor events older than the monitor retention, at most 200 attempts`() = TestDb().use { db ->
        val h = Harness(db)
        val calls = mutableListOf<Pair<LocalDateTime, Int>>()
        val worker = CodingReconcileWorker(
            h.service,
            h.results,
            Duration.ofSeconds(60),
            Duration.ofDays(RETENTION_DAYS),
            Clock.systemUTC(),
            monitorPurge = { before, maxAttempts -> calls += before to maxAttempts; 7 },
            monitorRetention = Duration.ofDays(2),
        )
        val pass = worker.tick()
        assertEquals(7, pass.monitorEventsPurged)
        val (before, maxAttempts) = calls.single()
        assertEquals(200, maxAttempts)
        val expected = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(2)
        assertTrue(Duration.between(before, expected).abs() < Duration.ofMinutes(1), "the cut-off is now minus the monitor retention")
    }
}
