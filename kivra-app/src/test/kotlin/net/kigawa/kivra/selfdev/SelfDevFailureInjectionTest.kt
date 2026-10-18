package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.coding.ChangeKind
import net.kigawa.kivra.coding.CliUsage
import net.kigawa.kivra.coding.CodingChange
import net.kigawa.kivra.coding.CodingClaimOutcome
import net.kigawa.kivra.coding.CodingCancelOutcome
import net.kigawa.kivra.coding.CodingErrorCode
import net.kigawa.kivra.coding.CodingInput
import net.kigawa.kivra.coding.CodingResult
import net.kigawa.kivra.coding.CodingRuntimeService
import net.kigawa.kivra.coding.CodingSubmitOutcome
import net.kigawa.kivra.coding.CodingTaskSource
import net.kigawa.kivra.coding.COST_TENANT
import net.kigawa.kivra.coding.CostFixture
import net.kigawa.kivra.coding.DbCodingRunPort
import net.kigawa.kivra.coding.costFixture
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingRefusal
import net.kigawa.kivra.db.DbCodingResultStore
import net.kigawa.kivra.db.ReleaseResult
import net.kigawa.kivra.db.TaskSubmitResult
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.CheckConclusion
import net.kigawa.kivra.github.CheckRun
import net.kigawa.kivra.github.CheckRunsSummary
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RunBranch
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Failed
import net.kigawa.kivra.selfdev.AdvanceResult.Skipped
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import net.kigawa.kivra.db.DevelopmentRun
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Failure injection on the self-development path (Issue #157, #220 acceptance: restart, double start, PR creation timeout, CLI failure
 * paths, CI failure after the pull request, no leaks). The real run store, lease store, coding service, cost ledger and result store run on
 * H2. Only GitHub ([FakeGitHub]) is a fake, and the Developer must never be called on the CLI path. The agent is simulated by the calls
 * the agent would make on [CodingRuntimeService]. "Restart" means a new [SelfDevRunner] and [SelfDevWorker] over the same database.
 */
class SelfDevFailureInjectionTest {

    private inner class Stack(val db: TestDb, val github: FakeGitHub = FakeGitHub()) {
        val cost: CostFixture = costFixture(db)
        val attempts = CodingAttemptRepository(db.database)
        val results = DbCodingResultStore(db.database)
        val service = CodingRuntimeService(
            attempts = attempts,
            runs = DbCodingRunPort(db.runs, db.leases),
            results = results,
            tasks = CodingTaskSource { it.taskTitle },
            cost = cost.ledger,
        )
        val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "from the model\n"))

        fun runner(store: DevelopmentRunStore = DbDevelopmentRunStore(db.runs)): SelfDevRunner = SelfDevRunner(
            config = SelfDevConfig(enabled = true, runtime = SelfDevRuntime.CLI),
            runs = store,
            leases = db.leases,
            developer = developer,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
            clock = Clock.systemUTC(),
            coding = CodingAttemptPatchSource(service),
        )

        private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            root.addAppender(it)
        }

        /**
         * The marker check every scenario ends with: no task body, summary, file content or path in the logs, and none in the audit,
         * outbox, coding event or usage rows. Detaches the log appender, so call it once, last.
         */
        fun assertNoLeak(label: String = "") {
            root.detachAppender(appender)
            val logText = appender.list.joinToString("\n") { it.formattedMessage }
            for (marker in MARKERS) {
                assertFalse(logText.contains(marker), "$label: a log line holds $marker")
                assertEquals(emptyList(), leakingColumns(db, marker), "$label: a stored row holds $marker")
            }
        }

        fun worker(runner: SelfDevRunner) = SelfDevWorker({ db.runs.activeSelfDevRunIds() }, { runner.advance(it) }, Duration.ofSeconds(30))

        /** Submits the task through the same entry the route uses. A replay returns the existing run. */
        fun start(key: String = KEY): RunId = when (val r = db.runs.submitTask(PRINCIPAL, key, TITLE, selfDev = true, maxActiveSelfDev = 5, body = BODY)) {
            is TaskSubmitResult.Created -> r.run.runId
            is TaskSubmitResult.Replayed -> r.run.runId
            else -> error("unexpected submission outcome")
        }

        fun state(runId: RunId): DevelopmentRunState = db.runs.get(runId)!!.state

        fun runCount(): Int = db.runs.listTasks(PRINCIPAL, limit = 100, selfDev = true).size

        fun pullRequestCount(): Int = github.pullRequests().size

        fun createPrWrites(): Int = github.writes.count { it == "createPr" }

        fun commitWrites(): Int = github.writes.count { it == "commit" }

        /** Advances the Run to DEVELOPING, then queues its attempt (the second call enqueues it). */
        suspend fun toAgentWait(runner: SelfDevRunner, runId: RunId) {
            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), runner.advance(runId))
        }

        fun claim(): CodingInput = assertIs<CodingClaimOutcome.Claimed>(service.claim(AGENT)).input

        fun result(
            input: CodingInput,
            changes: List<CodingChange> = listOf(write("docs/a.md", "$CONTENT_MARK\n")),
            usage: CliUsage? = null,
            generation: Long = input.leaseGeneration,
        ): CodingSubmitOutcome = service.submitResult(input.attemptId, AGENT, generation, CodingResult(SUMMARY, changes, 0, "session-1"), usage)

        fun usageRow(input: CodingInput) = cost.usageRows.get(input.attemptId, input.leaseGeneration)

        fun budget() = cost.budget.usage(COST_TENANT)

        fun latestAttemptId(runId: RunId): String = assertNotNull(service.latest(runId.value)).attemptId

        fun exec(sql: String, vararg params: Any?) = transaction(db.database) { execUpdate(sql, *params) }

        fun expireLease(input: CodingInput) =
            exec("UPDATE coding_attempts SET lease_expires_at = TIMESTAMPADD(SECOND, -10, NOW(6)) WHERE attempt_id = ?", input.attemptId)
    }

    private fun write(path: String, content: String) = CodingChange(path, ChangeKind.WRITE, content)

    // ---- 1. double start ----

    @Test
    fun `two starts with the same key one after the other give one run, and one pull request once it is driven`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val first = s.start()
            val replay = s.start()
            assertEquals(first, replay)
            assertEquals(1, s.runCount())

            val runner = s.runner()
            s.toAgentWait(runner, first)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(first))

            // A third start after the pull request exists still replays the same run and opens nothing.
            assertEquals(first, s.start())
            assertEquals(1, s.runCount())
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), s.runner().advance(first))
            assertEquals(1, s.pullRequestCount())
            assertEquals(1, s.createPrWrites())
            s.assertNoLeak("double start")
        }
    }

    @Test
    fun `two starts with the same key at the same time give one run, and one pull request once it is driven`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val gate = CountDownLatch(1)
                val futures = (1..2).map { pool.submit(Callable { gate.await(); s.start() }) }
                gate.countDown()
                val ids = futures.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, ids.distinct().size)
                assertEquals(1, s.runCount())

                val runner = s.runner()
                s.toAgentWait(runner, ids[0])
                assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
                assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(ids[0]))
                assertEquals(1, s.pullRequestCount())
                assertEquals(1, s.commitWrites())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `two advances of the same run at the same time, one lease and one pull request`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runner = s.runner()
            val runId = s.start()
            s.toAgentWait(runner, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))

            val pool = Executors.newFixedThreadPool(2)
            try {
                val barrier = CyclicBarrier(2)
                val futures = (1..2).map { pool.submit(Callable { barrier.await(); runBlocking { runner.advance(runId) } }) }
                val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, outcomes.count { it is Advanced }, outcomes.toString())
                assertTrue(outcomes.none { it is Failed }, outcomes.toString())
                assertEquals(1, s.pullRequestCount())
                assertEquals(1, s.commitWrites())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // ---- 2. worker restarts ----

    @Test
    fun `a restart while the run waits for the agent keeps the one queued attempt, and the run continues on the new worker`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            s.toAgentWait(s.runner(), runId)
            val attemptId = s.latestAttemptId(runId)

            // A new worker (new runner, new worker loop) over the same database: no second attempt, no second wait.
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), s.runner().advance(runId))
            assertEquals(1, s.worker(s.runner()).tick())
            assertEquals(attemptId, s.latestAttemptId(runId))

            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
            s.worker(s.runner()).tick()
            assertEquals(DevelopmentRunState.PULL_REQUEST, s.state(runId))
            assertEquals(1, s.pullRequestCount())
            assertEquals(0, s.developer.callCount)
        }
    }

    @Test
    fun `a crash with the result stored and the run in PATCH_READY resumes on a new worker from the stored result, one pull request`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            s.github.crashBefore = "ensureRunBranch"
            val runId = s.start()
            val first = s.runner()
            s.toAgentWait(first, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
            assertFailsWith<CrashInjected> { first.advance(runId) }
            assertEquals(DevelopmentRunState.PATCH_READY, s.state(runId))
            assertEquals(0, s.pullRequestCount())

            val restarted = s.worker(s.runner())
            restarted.tick()
            assertEquals(DevelopmentRunState.PULL_REQUEST, s.state(runId))
            assertEquals(listOf("createRef", "commit", "createPr"), s.github.writes)
            assertEquals(1, s.pullRequestCount())
            assertEquals(0, s.developer.callCount)
            s.assertNoLeak("patch ready")
        }
    }

    @Test
    fun `a crash after the commit or after the pull request opens, restarted on a new worker, never commits again or opens a second one`() = runBlocking {
        for (point in listOf("commit", "createPr")) {
            TestDb().use { db ->
                val s = Stack(db)
                s.github.crashAfter = point
                val runId = s.start()
                val first = s.runner()
                s.toAgentWait(first, runId)
                assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
                assertFailsWith<CrashInjected>(point) { first.advance(runId) }
                assertEquals(DevelopmentRunState.PATCH_READY, s.state(runId), point)

                // The branch is no longer at base, so the resume stops for an operator instead of committing again.
                assertEquals(
                    Advanced(DevelopmentRunState.PATCH_READY, DevelopmentRunState.UNKNOWN, "BRANCH_ADVANCED_BEFORE_COMMIT"),
                    s.runner().advance(runId),
                    point,
                )
                assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), s.runner().advance(runId), point)
                assertEquals(1, s.commitWrites(), point)
                assertEquals(if (point == "createPr") 1 else 0, s.pullRequestCount(), point)
                s.assertNoLeak("crash point $point")
            }
        }
    }

    @Test
    fun `a restart with the pull request open keeps waiting for review, with the same binding and no second pull request`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            val binding = assertNotNull(db.runs.loadPullRequestBinding(runId))

            repeat(3) {
                s.worker(s.runner()).tick()
            }
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), s.runner().advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, s.state(runId))
            assertEquals(binding, db.runs.loadPullRequestBinding(runId))
            assertEquals(1, s.pullRequestCount())
            assertEquals(1, s.createPrWrites())
            s.assertNoLeak("restart review")
        }
    }

    /** Wraps the real run store and loses the answer of one binding: the crash falls between the PULL_REQUEST transition and the binding. */
    private class BindCrashStore(private val inner: DevelopmentRunStore) : DevelopmentRunStore by inner {
        var armed = true

        override fun bindPullRequest(
            lease: net.kigawa.kivra.core.Lease,
            expectedVersion: Long,
            binding: net.kigawa.kivra.core.PullRequestBinding,
        ): DevelopmentRun =
            if (armed) {
                armed = false
                throw CrashInjected("bindPullRequest")
            } else {
                inner.bindPullRequest(lease, expectedVersion, binding)
            }
    }

    @Test
    fun `a crash between the pull request opening and its binding never completes the run on merge, and a restart opens no second one`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val store = BindCrashStore(DbDevelopmentRunStore(db.runs))
            val first = s.runner(store)
            s.toAgentWait(first, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
            assertFailsWith<CrashInjected> { first.advance(runId) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, s.state(runId))
            assertNull(db.runs.loadPullRequestBinding(runId))

            val restarted = s.runner(store)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), restarted.advance(runId))
            val number = s.github.pullRequests().single().number
            s.github.humanMerges(number)
            assertEquals(Waiting(WaitReason.BINDING_MISSING), s.runner(store).advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, s.state(runId))
            assertEquals(1, s.pullRequestCount())
            assertEquals(1, s.createPrWrites())
        }
    }

    // ---- 3. pull request creation timeout ----

    @Test
    fun `a PR creation that times out after the PR was created holds the run as UNKNOWN, is never recreated, and stays held after a restart`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            s.github.prFault = PrFault.CREATED_THEN_LOST
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "GITHUB_UNKNOWN_OUTCOME"), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), s.runner().advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), s.runner().advance(runId))
            assertEquals(1, s.pullRequestCount())
            assertEquals(1, s.createPrWrites())
            assertEquals(PullRequestState.OPEN, s.github.pullRequests().single().state)

            // The operator release ends the run; nothing is written to GitHub and the PR is left as it is.
            assertIs<ReleaseResult.Released>(db.runs.releaseUnknownSelfDev(runId, OPERATOR, "checked the PR"))
            assertEquals(DevelopmentRunState.CANCELLED, s.state(runId))
            assertEquals(Skipped(SkipReason.TERMINAL), s.runner().advance(runId))
            assertEquals(1, s.pullRequestCount())
            assertEquals(1, s.createPrWrites())
            assertEquals(PullRequestState.OPEN, s.github.pullRequests().single().state)
            s.assertNoLeak("timeout created")
        }
    }

    @Test
    fun `a PR creation that times out with no PR created is not retried, and the operator release ends the run with no write`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            s.github.prFault = PrFault.NOT_CREATED_LOST
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "GITHUB_UNKNOWN_OUTCOME"), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), s.runner().advance(runId))
            assertEquals(0, s.pullRequestCount())
            assertEquals(0, s.createPrWrites())

            assertIs<ReleaseResult.Released>(db.runs.releaseUnknownSelfDev(runId, OPERATOR, "checked the PR"))
            assertEquals(Skipped(SkipReason.TERMINAL), s.runner().advance(runId))
            assertIs<ReleaseResult.NotUnknown>(db.runs.releaseUnknownSelfDev(runId, OPERATOR, "again"))
            assertEquals(0, s.pullRequestCount())
            assertEquals(1, s.commitWrites())
            s.assertNoLeak("timeout not created")
        }
    }

    @Test
    fun `a PR on the run branch that lacks the run marker is never adopted, and no pull request is created on top of it`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            s.github.injectForeignPull(headRef = RunBranch.name(runId.value), body = "a PR opened by someone else")
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))

            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, GitHubFailureKind.IDENTITY_MISMATCH.code),
                runner.advance(runId),
            )
            assertEquals(0, s.createPrWrites())
            assertEquals(1, s.pullRequestCount())
            assertFalse(s.github.pullRequests().single().hasMarker(runId.value))
            assertNull(db.runs.loadPullRequestBinding(runId))
            s.assertNoLeak("foreign PR")
        }
    }

    // ---- 4. CLI path failures ----

    @Test
    fun `an agent whose login expired ends the run UNKNOWN, books its reserve once, and opens no pull request`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val input = s.claim()
            assertIs<CodingAttemptResult.Ok>(s.service.fail(input.attemptId, AGENT, input.leaseGeneration, CodingErrorCode.AUTH_REQUIRED))
            val budgetAfterFirstFail = s.budget()

            // A repeated send of the same failure (the agent did not see the first answer) changes nothing.
            assertIs<CodingAttemptResult.Refused>(s.service.fail(input.attemptId, AGENT, input.leaseGeneration, CodingErrorCode.AUTH_REQUIRED))
            assertEquals(budgetAfterFirstFail, s.budget())
            assertEquals("ESTIMATED_RESERVE", assertNotNull(s.usageRow(input)).source)

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "CODING_AUTH_REQUIRED"), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), runner.advance(runId))
            assertEquals(0, s.pullRequestCount())
            assertEquals(0, s.commitWrites())
            s.assertNoLeak("auth")
        }
    }

    @Test
    fun `an agent that sends a corrupt result is refused, then reports BAD_OUTPUT, and the usage is booked once`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val input = s.claim()
            val usage = USAGE
            // A corrupt result (a path the policy denies) is rejected, stores nothing, and keeps the attempt claimed.
            assertIs<CodingSubmitOutcome.Rejected>(s.result(input, listOf(write(".github/x.yml", "name: x\n")), usage))
            assertNull(assertNotNull(s.service.latest(runId.value)).resultDigest)
            val budgetAfterReject = s.budget()
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), runner.advance(runId))

            // The same usage reported with the failure is the same booking, not a second one.
            assertIs<CodingAttemptResult.Ok>(s.service.fail(input.attemptId, AGENT, input.leaseGeneration, CodingErrorCode.BAD_OUTPUT, usage))
            assertEquals(budgetAfterReject, s.budget())
            assertEquals("REPORTED", assertNotNull(s.usageRow(input)).source)

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "CODING_BAD_OUTPUT"), runner.advance(runId))
            assertEquals(0, s.pullRequestCount())
            assertEquals(0, s.createPrWrites())
            assertNull(assertNotNull(s.service.latest(runId.value)).resultDigest)
            s.assertNoLeak("bad output")
        }
    }

    @Test
    fun `a lease that runs out holds the run UNKNOWN, books the reserve once however often it is settled, and a late result is refused`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val input = s.claim()
            s.expireLease(input)

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "CODING_ORPHANED"), runner.advance(runId))
            val budgetAfterSettle = s.budget()
            s.service.expireOverdue()
            s.service.reconcile()
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), runner.advance(runId))
            assertEquals(budgetAfterSettle, s.budget())
            assertEquals("ESTIMATED_RESERVE", assertNotNull(s.usageRow(input)).source)

            // The old agent may still send its result. The database refuses it: the attempt is ORPHANED, nothing is stored.
            val late = s.result(input)
            assertIs<CodingSubmitOutcome.Refused>(late)
            assertEquals(CodingRefusal.NOT_ACTIVE, late.reason)
            assertEquals(0, s.pullRequestCount())
            assertEquals(0, s.createPrWrites())
            s.assertNoLeak("orphan")
        }
    }

    @Test
    fun `a cancel while the attempt runs is acknowledged by the agent, and the run ends CANCELLED with no pull request and one booking`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val input = s.claim()
            assertEquals(CodingCancelOutcome.CancelRequested, s.service.cancel(runId.value))
            assertTrue(assertNotNull(s.service.statusOf(input.attemptId, AGENT)).cancelRequested)

            assertIs<CodingAttemptResult.Ok>(s.service.fail(input.attemptId, AGENT, input.leaseGeneration, CodingErrorCode.CANCELLED))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.CANCELLED, "CANCEL_REQUESTED"), runner.advance(runId))
            assertEquals(Skipped(SkipReason.TERMINAL), runner.advance(runId))
            assertEquals(0, s.pullRequestCount())
            assertEquals("ESTIMATED_RESERVE", assertNotNull(s.usageRow(input)).source)
            s.assertNoLeak("cancel running")
        }
    }

    @Test
    fun `a cancel of a queued attempt means no agent ever gets it, nothing is booked, and the run ends CANCELLED`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val budgetBefore = s.budget()

            assertEquals(CodingCancelOutcome.QueuedCancelled, s.service.cancel(runId.value))
            assertEquals(CodingClaimOutcome.NothingToClaim, s.service.claim(AGENT))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.CANCELLED, "CANCEL_REQUESTED"), runner.advance(runId))
            assertEquals(budgetBefore, s.budget())
            assertEquals(0, s.pullRequestCount())
            assertEquals(0, s.developer.callCount)
            s.assertNoLeak("cancel queued")
        }
    }

    @Test
    fun `a second result for the same generation is refused and booked nothing, and the first result is the one that is committed`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val input = s.claim()
            val first = assertIs<CodingSubmitOutcome.Accepted>(s.result(input, listOf(write("docs/a.md", "first\n"))))
            val budgetAfterFirst = s.budget()

            val second = s.result(input, listOf(write("docs/a.md", "second\n")), usage = USAGE)
            assertIs<CodingSubmitOutcome.Refused>(second)
            assertEquals(CodingRefusal.NOT_ACTIVE, second.reason)
            assertEquals(budgetAfterFirst, s.budget())
            assertEquals("ESTIMATED_RESERVE", assertNotNull(s.usageRow(input)).source)

            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(first.digest, assertNotNull(s.service.latest(runId.value)).resultDigest)
            assertEquals(1, s.commitWrites())
            assertEquals(1, s.pullRequestCount())
            s.assertNoLeak("second result")
        }
    }

    @Test
    fun `a result or failure from an old generation is refused and changes nothing, and the current generation still goes through`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            val input = s.claim()
            val staleGeneration = input.leaseGeneration + 1
            val budgetBefore = s.budget()

            val staleResult = s.result(input, usage = USAGE, generation = staleGeneration)
            assertIs<CodingSubmitOutcome.Refused>(staleResult)
            assertEquals(CodingRefusal.STALE_GENERATION, staleResult.reason)
            assertIs<CodingAttemptResult.Refused>(s.service.fail(input.attemptId, AGENT, staleGeneration, CodingErrorCode.AUTH_REQUIRED))
            assertEquals(budgetBefore, s.budget())
            assertNull(s.usageRow(input))
            assertEquals(DevelopmentRunState.DEVELOPING, s.state(runId))
            assertEquals(Waiting(WaitReason.AWAITING_CODING_AGENT), runner.advance(runId))

            assertIs<CodingSubmitOutcome.Accepted>(s.result(input))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(1, s.pullRequestCount())
            s.assertNoLeak("stale generation")
        }
    }

    // ---- 5. CI failure after the pull request ----

    @Test
    fun `a pull request whose checks fail stays open and the run waits for a human, and the runner reads the checks as no decision`() = runBlocking {
        TestDb().use { db ->
            val s = Stack(db)
            val runId = s.start()
            val runner = s.runner()
            s.toAgentWait(runner, runId)
            assertIs<CodingSubmitOutcome.Accepted>(s.result(s.claim()))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            val headSha = s.github.pullRequests().single().headSha
            s.github.checks = CheckRunsSummary(
                sha = headSha,
                runs = listOf(CheckRun(id = 1, name = "test", headSha = headSha, status = "completed", conclusion = CheckConclusion.FAILURE)),
                totalCount = 1,
                complete = true,
            )

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), runner.advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), s.runner().advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, s.state(runId))
            val pr = s.github.pullRequests().single()
            assertEquals(PullRequestState.OPEN, pr.state)
            assertFalse(pr.merged)
            assertEquals(1, s.createPrWrites())
            assertEquals(1, s.commitWrites())
            s.assertNoLeak("ci failure")
        }
    }

    // ---- 6. logs, audit and events never carry the task text, the summary or the content ----

    @Test
    fun `no task body, summary, file content or path appears in the logs or in the audit, outbox, coding event or usage rows`() = runBlocking {
        val scenarios = listOf("happy", "rejected", "auth", "orphan")
        for (scenario in scenarios) {
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                TestDb().use { db ->
                    val s = Stack(db)
                    val runId = s.start()
                    val runner = s.runner()
                    s.toAgentWait(runner, runId)
                    val input = s.claim()
                    when (scenario) {
                        "happy" -> assertIs<CodingSubmitOutcome.Accepted>(s.result(input, listOf(write("docs/a.md", "$CONTENT_MARK\n")), USAGE))
                        "rejected" -> assertIs<CodingSubmitOutcome.Rejected>(s.result(input, listOf(write(".github/$PATH_MARK.yml", "y\n")), USAGE))
                        "auth" -> assertIs<CodingAttemptResult.Ok>(s.service.fail(input.attemptId, AGENT, input.leaseGeneration, CodingErrorCode.AUTH_REQUIRED))
                        "orphan" -> s.expireLease(input)
                    }
                    runner.advance(runId)
                    runner.advance(runId)

                    val logText = appender.list.joinToString("\n") { it.formattedMessage }
                    for (marker in MARKERS) assertFalse(logText.contains(marker), "$scenario: log holds $marker")
                    for (marker in MARKERS) {
                        assertEquals(emptyList(), leakingColumns(db, marker), "$scenario: a stored row holds $marker")
                    }
                }
            } finally {
                root.detachAppender(appender)
            }
        }
    }

    // ---- helpers ----

    /** Columns of the tables that hold run history, with the task body left out: the body is stored on the run by design (V17). */
    private fun leakingColumns(db: TestDb, marker: String): List<String> {
        val hits = ArrayList<String>()
        db.dataSource.connection.use { conn ->
            for (table in SCANNED_TABLES) {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT * FROM $table").use { rs ->
                        val meta = rs.metaData
                        while (rs.next()) {
                            for (i in 1..meta.columnCount) {
                                val column = meta.getColumnLabel(i)
                                if (table == "development_runs" && column.equals("task_body", ignoreCase = true)) continue
                                val value = rs.getString(i) ?: continue
                                if (value.contains(marker)) hits += "$table.$column"
                            }
                        }
                    }
                }
            }
        }
        return hits
    }

    private companion object {
        const val PRINCIPAL = "operator-1"
        const val OPERATOR = "operator-1"
        const val AGENT = "agent-1"
        const val KEY = "idem-key-1"
        const val TITLE = "README を直す"
        const val BODY = "MARK-TASK-BODY 本文"
        const val SUMMARY = "MARK-SUMMARY 説明"
        const val CONTENT_MARK = "MARK-CONTENT"
        const val PATH_MARK = "MARK-PATH"
        val MARKERS = listOf("MARK-TASK-BODY", "MARK-SUMMARY", CONTENT_MARK, PATH_MARK)
        val USAGE = CliUsage(java.math.BigDecimal("0.004268"), "claude-haiku-5-5", 4, 1200, 300, 5000, 800)
        val SCANNED_TABLES = listOf("development_runs", "audit_events", "outbox_events", "coding_attempts", "coding_events", "coding_attempt_usage")

        val files = mapOf(
            "README.md" to "# kivra\n",
            "docs/a.md" to "old text\n",
            "kivra-core/src/Foo.kt" to "fun a() = Unit\n",
        )
    }
}
