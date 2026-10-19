package net.kigawa.kivra.coding

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.RejectReason
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.CodingRefusal
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private const val TITLE = "TITLE-MARKER README を直す"
private const val SUMMARY = "SUMMARY-MARKER 説明"
private const val CONTENT = "CONTENT-MARKER body"
private const val PATH = ".github/path-marker.yml"

/** Keeps the accepted change sets in memory, per (attempt, digest). Test double only; there is no production store yet. */
class InMemoryCodingResultStore : CodingResultStore {
    var saves = 0
        private set
    var failNext = false
    private val rows = HashMap<Pair<String, String>, PatchSet>()

    override fun save(attemptId: String, digest: String, patch: PatchSet) {
        if (failNext) throw IllegalStateException("store is down")
        rows[attemptId to digest] = patch
        saves++
    }

    override fun load(attemptId: String, digest: String): PatchSet? = rows[attemptId to digest]
}

class CodingRuntimeServiceTest {
    @Test
    fun theDefaultOutputLimitIsEightMiBForTheConfigAndTheLimitsSentToTheAgent() {
        assertEquals(8L * 1024 * 1024, CodingRuntimeConfig().maxOutputBytes)
        assertEquals(8L * 1024 * 1024, CodingLimits().maxOutputBytes)
    }

    private val baseSha = "a".repeat(40)

    private fun goodResult(
        changes: List<CodingChange> = listOf(CodingChange("docs/a.md", ChangeKind.WRITE, "new text\n")),
        summary: String = "docs を更新する",
        exitStatus: Int = 0,
        sessionId: String? = "session-1",
    ) = CodingResult(summary, changes, exitStatus, sessionId)

    private inner class Harness(
        val db: TestDb,
        val config: CodingRuntimeConfig = CodingRuntimeConfig(),
        val policy: PatchPolicyConfig = PatchPolicyConfig(),
        val store: InMemoryCodingResultStore = InMemoryCodingResultStore(),
        tasks: CodingTaskSource = CodingTaskSource { it.taskTitle },
    ) {
        val repo = CodingAttemptRepository(db.database)
        val service = CodingRuntimeService(
            attempts = repo,
            runs = DbCodingRunPort(db.runs, db.leases),
            results = store,
            tasks = tasks,
            cost = UnmeteredCodingCost,
            policyConfig = policy,
            config = config,
        )

        /** A Run in [state] whose task title is [title]. */
        fun run(state: DevelopmentRunState = DevelopmentRunState.DEVELOPING, title: String? = TITLE): String {
            val runId = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY).runId.value
            db.exec("UPDATE development_runs SET task_title = ? WHERE run_id = ?", title, runId)
            db.forceState(runId, state)
            return runId
        }

        fun queued(runId: String): CodingAttempt = assertIs<CodingEnqueueOutcome.Queued>(service.enqueue(runId, baseSha)).attempt

        fun claimedInput(): CodingInput = assertIs<CodingClaimOutcome.Claimed>(service.claim("agent-1")).input

        fun state(attemptId: String): CodingAttemptState = repo.get(attemptId)!!.state
    }

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    // ---- enqueue ----

    @Test
    fun `enqueue queues one attempt, returns it while it is active, and never queues again after it settles`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val first = h.queued(runId)
        assertEquals(first.attemptId, assertIs<CodingEnqueueOutcome.Active>(h.service.enqueue(runId, baseSha)).attempt.attemptId)
        assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))

        val input = h.claimedInput()
        assertIs<CodingAttemptResult.Ok>(h.service.fail(first.attemptId, "agent-1", input.leaseGeneration, CodingErrorCode.CLI_NONZERO))
        assertEquals(first.attemptId, assertIs<CodingEnqueueOutcome.Settled>(h.service.enqueue(runId, baseSha)).latest.attemptId)
        assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))
    }

    @Test
    fun `enqueue refuses an unknown Run, a Run that is not DEVELOPING, a held or cancelled Run, and a bad base sha`() = TestDb().use { db ->
        val h = Harness(db)
        assertEquals(CodingEnqueueOutcome.RunNotFound, h.service.enqueue("01HNOSUCHRUN0000000000000A", baseSha))
        assertEquals(CodingEnqueueOutcome.RunNotDeveloping, h.service.enqueue(h.run(DevelopmentRunState.CREATED), baseSha))

        val held = h.run()
        db.runs.hold(RunId(held), "operator-hold", "test")
        assertEquals(CodingEnqueueOutcome.RunNotDeveloping, h.service.enqueue(held, baseSha))

        val cancelled = h.run()
        db.runs.requestCancel(RunId(cancelled), "test")
        assertEquals(CodingEnqueueOutcome.RunNotDeveloping, h.service.enqueue(cancelled, baseSha))

        assertEquals(CodingEnqueueOutcome.InvalidInput, h.service.enqueue(h.run(), "NOT-A-SHA"))
        assertEquals(0, db.count("coding_attempts", "run_id = '$held'"))
    }

    // ---- claim ----

    @Test
    fun `the claim hands the agent the base sha, the limits and the task title as untrusted data`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        assertEquals(attempt.attemptId, input.attemptId)
        assertEquals(1L, input.leaseGeneration)
        assertEquals(baseSha, input.baseSha)
        assertEquals(Trust.UNTRUSTED, input.task.trust)
        assertEquals("task_title", input.task.origin)
        assertEquals(TITLE, input.task.value)
        assertFalse(input.task.truncated)
        assertEquals(h.service.limits, input.limits)
        assertEquals(PatchPolicyConfig().allowedPathPatterns, input.allowedPathPatterns)
        assertEquals(PatchPolicyConfig().deniedPathPatterns, input.deniedPathPatterns)
        assertFalse(input.toString().contains(TITLE), "the input's toString must not print the task text")
    }

    @Test
    fun `a long task title is cut to the limit and marked truncated`() = TestDb().use { db ->
        // task_title is VARCHAR(200) in the database, so the long text comes from the task source instead.
        val long = "x".repeat(CodingRuntimeService.MAX_TASK_CHARS + 500)
        val h = Harness(db, tasks = CodingTaskSource { long })
        h.queued(h.run())
        val task = h.claimedInput().task
        assertEquals(CodingRuntimeService.MAX_TASK_CHARS, task.value.length)
        assertTrue(task.truncated)
    }

    @Test
    fun `the limits the agent gets are the PatchPolicy's own file limits`() = TestDb().use { db ->
        val policy = PatchPolicyConfig()
        val limits = Harness(db).service.limits
        assertEquals(policy.maxFiles, limits.maxChangedFiles)
        assertEquals(policy.maxFileBytes, limits.maxFileBytes)
        assertEquals(policy.maxTotalBytes, limits.maxTotalBytes)
        assertEquals(policy.maxDeletes, limits.maxDeletes)
    }

    @Test
    fun `concurrent claims hand each queued attempt to exactly one agent`() = TestDb().use { db ->
        val h = Harness(db)
        val attemptIds = (1..2).map { h.queued(h.run()).attemptId }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val barrier = CyclicBarrier(8)
            val outcomes = (1..8).map { i ->
                pool.submit<CodingClaimOutcome> {
                    barrier.await()
                    h.service.claim("agent-$i")
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
            val claimed = outcomes.filterIsInstance<CodingClaimOutcome.Claimed>().map { it.input.attemptId }
            assertEquals(2, claimed.size)
            assertEquals(attemptIds.toSet(), claimed.toSet())
            assertEquals(6, outcomes.count { it == CodingClaimOutcome.NothingToClaim })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `claim cancels the queued attempt of a Run that asked to cancel, and leaves a held Run's attempt queued`() = TestDb().use { db ->
        val h = Harness(db)
        val cancelled = h.queued(h.run())
        db.runs.requestCancel(RunId(cancelled.runId), "test")
        val held = h.queued(h.run())
        db.runs.hold(RunId(held.runId), "operator-hold", "test")

        assertEquals(CodingClaimOutcome.NothingToClaim, h.service.claim("agent-1"))
        assertEquals(CodingAttemptState.CANCELLED, h.state(cancelled.attemptId))
        assertEquals(CodingAttemptState.QUEUED, h.state(held.attemptId))
    }

    // ---- fencing: stale generations ----

    @Test
    fun `a stale generation is refused for heartbeat, submit and fail, and the current one is accepted`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        val stale = input.leaseGeneration - 1

        assertEquals(CodingAttemptResult.Refused(CodingRefusal.STALE_GENERATION), h.service.heartbeat(attempt.attemptId, "agent-1", stale))
        assertEquals(
            CodingSubmitOutcome.Refused(CodingRefusal.STALE_GENERATION),
            h.service.submitResult(attempt.attemptId, "agent-1", stale, goodResult()),
        )
        assertEquals(
            CodingAttemptResult.Refused(CodingRefusal.STALE_GENERATION),
            h.service.fail(attempt.attemptId, "agent-1", stale, CodingErrorCode.TIMEOUT),
        )
        assertEquals(CodingAttemptState.CLAIMED, h.state(attempt.attemptId))
        assertEquals(0, h.store.saves)

        assertIs<CodingAttemptResult.Ok>(h.service.heartbeat(attempt.attemptId, "agent-1", input.leaseGeneration, ProgressPhase.EDITING))
        assertTrue("PROGRESS" in h.repo.events(attempt.attemptId).map { it.type })
        assertEquals("{\"phase\":\"EDITING\"}", h.repo.events(attempt.attemptId).single { it.type == "PROGRESS" }.payloadJson)
    }

    // ---- submit: accepted and refused results ----

    @Test
    fun `an accepted result is stored under its digest and the attempt becomes RESULT_RECEIVED`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        val result = goodResult()

        val accepted = assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, "agent-1", input.leaseGeneration, result))
        assertEquals(CodingResultValidator.digestOf(result), accepted.digest)
        val row = h.repo.get(attempt.attemptId)!!
        assertEquals(CodingAttemptState.RESULT_RECEIVED, row.state)
        assertEquals(accepted.digest, row.resultDigest)
        assertEquals(0, row.exitStatus)
        assertEquals("session-1", row.sessionId)
        assertEquals(1, h.store.saves)
        assertEquals(listOf("docs/a.md"), assertNotNull(h.service.loadResult(row)).files.map { it.path })
    }

    @Test
    fun `the stored body is looked up by the digest of the row, not by any other body saved for the attempt`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, "agent-1", input.leaseGeneration, goodResult()))
        // Another body under a different digest (for example from a stale holder) must not replace the accepted one.
        h.store.save(attempt.attemptId, "f".repeat(64), PatchSet(emptyList(), "other"))
        val row = h.repo.get(attempt.attemptId)!!
        assertEquals(listOf("docs/a.md"), assertNotNull(h.service.loadResult(row)).files.map { it.path })
    }

    @Test
    fun `a result that the shape checks or the PatchPolicy refuse is rejected, and nothing is stored or marked`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        val secret = "gh" + "p_" + "A".repeat(30)
        val oversize = "x".repeat(200 * 1024 + 1)
        val many = (1..21).map { CodingChange("docs/f$it.md", ChangeKind.WRITE, "x") }

        data class Case(val name: String, val result: CodingResult, val reason: CodingRejectReason, val policy: RejectReason? = null)
        val cases = listOf(
            Case("secret-shaped content", goodResult(listOf(CodingChange("docs/a.md", ChangeKind.WRITE, "v $secret"))), CodingRejectReason.POLICY_REJECTED, RejectReason.SECRET_LIKE_CONTENT),
            Case("denied path", goodResult(listOf(CodingChange(PATH, ChangeKind.WRITE, "x"))), CodingRejectReason.POLICY_REJECTED, RejectReason.PATH_DENIED),
            Case("path outside the allowlist", goodResult(listOf(CodingChange("secrets/notes.txt", ChangeKind.WRITE, "x"))), CodingRejectReason.POLICY_REJECTED, RejectReason.PATH_NOT_ALLOWED),
            Case("file over the size limit", goodResult(listOf(CodingChange("docs/a.md", ChangeKind.WRITE, oversize))), CodingRejectReason.POLICY_REJECTED, RejectReason.FILE_TOO_LARGE),
            Case("symlink", goodResult(listOf(CodingChange("docs/link.md", ChangeKind.WRITE, "", FileKind.SYMLINK))), CodingRejectReason.SYMLINK),
            Case("submodule", goodResult(listOf(CodingChange("docs/sub", ChangeKind.WRITE, "", FileKind.SUBMODULE))), CodingRejectReason.SUBMODULE),
            Case("executable bit", goodResult(listOf(CodingChange("docs/run.md", ChangeKind.WRITE, "x", FileKind.EXECUTABLE))), CodingRejectReason.EXECUTABLE_BIT),
            Case("binary file", goodResult(listOf(CodingChange("docs/img.md", ChangeKind.WRITE, "x", FileKind.BINARY))), CodingRejectReason.BINARY_FILE),
            Case("too many changes", goodResult(many), CodingRejectReason.TOO_MANY_CHANGES),
            Case("write without content", goodResult(listOf(CodingChange("docs/a.md", ChangeKind.WRITE, null))), CodingRejectReason.MALFORMED_CHANGE),
            Case("delete with content", goodResult(listOf(CodingChange("docs/a.md", ChangeKind.DELETE, "x"))), CodingRejectReason.MALFORMED_CHANGE),
            Case("exit status out of range", goodResult(exitStatus = 999), CodingRejectReason.MALFORMED_RESULT),
            Case("session id with a space", goodResult(sessionId = "has space"), CodingRejectReason.MALFORMED_RESULT),
            Case("empty summary", goodResult(summary = " "), CodingRejectReason.POLICY_REJECTED, RejectReason.SUMMARY_EMPTY),
        )
        for (case in cases) {
            val outcome = h.service.submitResult(attempt.attemptId, "agent-1", input.leaseGeneration, case.result)
            val rejected = assertIs<CodingSubmitOutcome.Rejected>(outcome, case.name)
            assertTrue(case.reason in rejected.reasons, "${case.name}: ${rejected.reasons}")
            if (case.policy != null) assertTrue(case.policy in rejected.policyReasons, "${case.name}: ${rejected.policyReasons}")
        }
        assertEquals(0, h.store.saves)
        assertEquals(CodingAttemptState.CLAIMED, h.state(attempt.attemptId))
        assertEquals(0, db.count("coding_events", "attempt_id = '${attempt.attemptId}' AND type = 'RESULT_RECEIVED'"))
    }

    @Test
    fun `a store failure leaves the attempt unchanged, so the same result can be sent again`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        h.store.failNext = true
        assertEquals(CodingSubmitOutcome.StoreFailed, h.service.submitResult(attempt.attemptId, "agent-1", input.leaseGeneration, goodResult()))
        assertEquals(CodingAttemptState.CLAIMED, h.state(attempt.attemptId))

        h.store.failNext = false
        assertIs<CodingSubmitOutcome.Accepted>(h.service.submitResult(attempt.attemptId, "agent-1", input.leaseGeneration, goodResult()))
        assertEquals(CodingAttemptState.RESULT_RECEIVED, h.state(attempt.attemptId))
    }

    @Test
    fun `a result sent after the lease expired is refused by the database and never becomes the attempt's result`() = TestDb().use { db ->
        val h = Harness(db)
        val attempt = h.queued(h.run())
        val input = h.claimedInput()
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)
        assertEquals(
            CodingSubmitOutcome.Refused(CodingRefusal.LEASE_EXPIRED),
            h.service.submitResult(attempt.attemptId, "agent-1", input.leaseGeneration, goodResult()),
        )
        // The body is written before the transition (so an accepted result is never lost if the store fails afterwards). A refused
        // completion can therefore leave an unreferenced body: it is never returned, because only a RESULT_RECEIVED row names a digest.
        h.repo.expire()
        val row = h.repo.get(attempt.attemptId)!!
        assertEquals(CodingAttemptState.ORPHANED, row.state)
        assertEquals(null, row.resultDigest)
        assertEquals(null, h.service.loadResult(row))
    }

    // ---- fail and cancel ----

    @Test
    fun `fail takes a fixed code and maps it to FAILED, or to CANCELLED for the cancel acknowledgement`() = TestDb().use { db ->
        val h = Harness(db)
        val first = h.queued(h.run())
        val input = h.claimedInput()
        assertIs<CodingAttemptResult.Ok>(h.service.fail(first.attemptId, "agent-1", input.leaseGeneration, CodingErrorCode.TIMEOUT))
        assertEquals(CodingAttemptState.FAILED, h.state(first.attemptId))
        assertEquals("TIMEOUT", h.repo.get(first.attemptId)!!.errorCode)

        val second = h.queued(h.run())
        val second0 = h.claimedInput()
        assertIs<CodingAttemptResult.Ok>(h.service.fail(second.attemptId, "agent-1", second0.leaseGeneration, CodingErrorCode.CANCELLED))
        assertEquals(CodingAttemptState.CANCELLED, h.state(second.attemptId))
    }

    @Test
    fun `cancel cancels a queued attempt at once, only flags a claimed one, and refuses a Run that cannot be cancelled`() = TestDb().use { db ->
        val h = Harness(db)
        val queuedRun = h.run()
        val queued = h.queued(queuedRun)
        assertEquals(CodingCancelOutcome.QueuedCancelled, h.service.cancel(queuedRun))
        assertEquals(CodingAttemptState.CANCELLED, h.state(queued.attemptId))
        assertTrue(db.runs.get(RunId(queuedRun))!!.cancelRequested)

        val claimedRun = h.run()
        val claimed = h.queued(claimedRun)
        assertIs<CodingClaimOutcome.Claimed>(h.service.claim("agent-1"))
        assertEquals(CodingCancelOutcome.CancelRequested, h.service.cancel(claimedRun))
        assertEquals(CodingAttemptState.CLAIMED, h.state(claimed.attemptId))

        assertEquals(CodingCancelOutcome.RunNotCancellable, h.service.cancel(h.run(DevelopmentRunState.FAILED)))
        assertEquals(CodingCancelOutcome.RunNotCancellable, h.service.cancel(h.run(DevelopmentRunState.UNKNOWN)))
        assertEquals(CodingCancelOutcome.RunNotFound, h.service.cancel("01HNOSUCHRUN0000000000000A"))
    }

    // ---- reconcile ----

    @Test
    fun `reconcile expires an unclaimed attempt and holds its Run as UNKNOWN, without queuing another attempt`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val attempt = h.queued(runId)
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        assertEquals(CodingReconcileReport(queuedExpired = 1, orphaned = 0, runsHeldForOperator = 1, runsSkipped = 0), h.service.reconcile())
        val run = db.runs.get(RunId(runId))!!
        assertEquals(DevelopmentRunState.UNKNOWN, run.state)
        assertEquals(CodingRuntimeService.REASON_QUEUE_EXPIRED, run.stateReason)
        assertEquals(CodingAttemptState.EXPIRED, h.state(attempt.attemptId))
        assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))
        assertEquals(CodingReconcileReport(0, 0, 0, 0), h.service.reconcile())
    }

    @Test
    fun `reconcile orphans an attempt whose lease ran out and holds its Run as UNKNOWN, without a second attempt`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val attempt = h.queued(runId)
        val input = h.claimedInput()
        db.exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)

        assertEquals(CodingReconcileReport(queuedExpired = 0, orphaned = 1, runsHeldForOperator = 1, runsSkipped = 0), h.service.reconcile())
        assertEquals(CodingAttemptState.ORPHANED, h.state(attempt.attemptId))
        val run = db.runs.get(RunId(runId))!!
        assertEquals(DevelopmentRunState.UNKNOWN, run.state)
        assertEquals(CodingRuntimeService.REASON_ORPHANED, run.stateReason)
        assertEquals(1, db.count("coding_attempts", "run_id = '$runId'"))
        assertEquals(CodingEnqueueOutcome.RunNotDeveloping, h.service.enqueue(runId, baseSha))
        assertEquals(1L, input.leaseGeneration)
    }

    @Test
    fun `reconcile leaves a Run that already moved on alone`() = TestDb().use { db ->
        val h = Harness(db)
        val runId = h.run()
        val attempt = h.queued(runId)
        db.exec("UPDATE coding_attempts SET queued_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", attempt.attemptId)
        db.forceState(runId, DevelopmentRunState.FAILED)

        assertEquals(CodingReconcileReport(queuedExpired = 1, orphaned = 0, runsHeldForOperator = 0, runsSkipped = 0), h.service.reconcile())
        assertEquals(DevelopmentRunState.FAILED, db.runs.get(RunId(runId))!!.state)
    }

    // ---- logs ----

    @Test
    fun `the logs carry identifiers and fixed codes only, never the title, a path, the summary or a change`() = TestDb().use { db ->
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(CodingRuntimeService::class.java) as Logger
        logger.addAppender(appender)
        try {
            val h = Harness(db)
            val rejectedRun = h.run()
            val rejected = h.queued(rejectedRun)
            val rejectedInput = h.claimedInput()
            h.service.submitResult(
                rejected.attemptId, "agent-1", rejectedInput.leaseGeneration,
                goodResult(listOf(CodingChange(PATH, ChangeKind.WRITE, CONTENT)), summary = SUMMARY),
            )

            h.store.failNext = true
            val storeRun = h.run()
            val stored = h.queued(storeRun)
            val storedInput = h.claimedInput()
            h.service.submitResult(stored.attemptId, "agent-1", storedInput.leaseGeneration, goodResult(summary = SUMMARY))
            h.store.failNext = false
            h.service.submitResult(stored.attemptId, "agent-1", storedInput.leaseGeneration, goodResult(listOf(CodingChange("docs/a.md", ChangeKind.WRITE, CONTENT)), summary = SUMMARY))

            val text = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(appender.list.isNotEmpty(), "the rejection and the store failure must be logged")
            assertFalse(text.contains(TITLE), text)
            assertFalse(text.contains(PATH), text)
            assertFalse(text.contains(SUMMARY), text)
            assertFalse(text.contains(CONTENT), text)
        } finally {
            logger.detachAppender(appender)
        }
    }
}
