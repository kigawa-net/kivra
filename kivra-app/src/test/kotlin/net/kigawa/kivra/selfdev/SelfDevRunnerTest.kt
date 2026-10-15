package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.DeveloperRefusal
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.DeveloperUnknown
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.PullRequestState
import org.slf4j.LoggerFactory
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Failed
import net.kigawa.kivra.selfdev.AdvanceResult.Skipped
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import net.kigawa.kivra.selfdev.CommitFault.UNKNOWN_MOVED

/**
 * Self-development runner (ADR 0008 part 4). Runs on H2 with the real run repository and the real lease store; GitHub, the Developer and
 * the repository reader are in-memory doubles. No network, no model, no real token.
 */
class SelfDevRunnerTest {
    private val defaultReadme = "# kivra\n"

    private val defaultFiles = mapOf(
        "README.md" to defaultReadme,
        "docs/a.md" to "old text\n",
        "docs/big.md" to "x".repeat(40 * 1024), // over the per-file limit, so it is never shown
        "src/Main.kt" to "fun main() = Unit\n",
    )

    private inner class Harness(
        val db: TestDb,
        val developer: DeveloperPort,
        val config: SelfDevConfig = SelfDevConfig(enabled = true),
        val clock: Clock = Clock.systemUTC(),
        val files: Map<String, String> = defaultFiles,
        val github: FakeGitHub = FakeGitHub(),
        val taskText: TaskText? = DevFixtures.taskText,
    ) {
        fun runner(
            dev: DeveloperPort = developer,
            cfg: SelfDevConfig = config,
            clk: Clock = clock,
        ) = SelfDevRunner(
            config = cfg,
            runs = DbDevelopmentRunStore(db.runs),
            leases = db.leases,
            developer = dev,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(taskText),
            clock = clk,
        )

        fun newRun(): RunId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

        fun state(runId: RunId): DevelopmentRunState = db.runs.get(runId)!!.state
    }

    private fun proposal(vararg writes: Pair<String, String>) = FakeDeveloper.returning(DevFixtures.proposed(*writes))

    // ---- happy path and the single-PR rule ----

    @Test
    fun happyPathOpensOneDraftlessPullRequestAndKivraNeverMerges() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new text\n"))
            val runner = h.runner()
            val runId = h.newRun()

            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.DEVELOPING), runner.advance(runId))
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
            assertEquals(1, h.github.commitsOnBranch.size)

            // Repeated calls wait and never open a second pull request.
            repeat(5) { assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), runner.advance(runId)) }
            assertEquals(1, h.github.pullRequests().size)
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)

            // A human merges the pull request. The runner does not complete the run (see the next test).
            h.github.humanMerges(h.github.pullRequests().single().number)
            assertEquals(Waiting(WaitReason.AWAITING_MERGE_VERIFICATION), runner.advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
        }
    }

    @Test
    fun theProviderPortHasNoMergeOperationAndTheRunnerNeverCallsOne() {
        val names = GitHubPort::class.java.methods.map { it.name.lowercase() }
        assertTrue(names.none { "merge" in it }, names.toString())
    }

    @Test
    fun aMergedPullRequestIsNotCompletedWithoutVerifiedMergeEvidence() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            runner.advance(runId)
            h.github.humanMerges(h.github.pullRequests().single().number)
            repeat(3) { runner.advance(runId) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
        }
    }

    // ---- kill switch and scope ----

    @Test
    fun theKillSwitchIsOffByDefaultAndWhileOffNothingHappens() = runBlocking {
        assertFalse(SelfDevConfig().enabled)
        TestDb().use { db ->
            val dev = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "x\n"))
            val h = Harness(db, dev, config = SelfDevConfig(enabled = false))
            val runId = h.newRun()
            assertEquals(Skipped(SkipReason.DISABLED), h.runner().advance(runId))
            assertEquals(0, dev.callCount)
            assertEquals(0, h.github.totalCalls)
            assertEquals(DevelopmentRunState.CREATED, h.state(runId))
        }
    }

    @Test
    fun releaseRunsAreNeverTouched() = runBlocking {
        TestDb().use { db ->
            val dev = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "x\n"))
            val h = Harness(db, dev)
            val runId = db.runs.create("task-1", "test", RunCompletionMode.RELEASE).runId
            assertEquals(Skipped(SkipReason.NOT_PR_ONLY), h.runner().advance(runId))
            assertEquals(0, dev.callCount)
            assertEquals(0, h.github.totalCalls)
        }
    }

    @Test
    fun unknownRunsAndTerminalRunsAreSkipped() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "x\n")))
            assertEquals(Skipped(SkipReason.RUN_NOT_FOUND), h.runner().advance(RunId("01HZZZZZZZZZZZZZZZZZZZZZZZ")))
            val runId = h.newRun()
            db.forceState(runId.value, DevelopmentRunState.COMPLETED)
            assertEquals(Skipped(SkipReason.TERMINAL), h.runner().advance(runId))
        }
    }

    // ---- PatchPolicy on the runner side and UNSEEN_OVERWRITE ----

    @Test
    fun aDisallowedPathIsRejectedWithoutAnyGitHubWrite() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal(".github/workflows/ci.yml" to "name: ci\n"))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), runner.advance(runId))
            assertEquals(DevelopmentRunState.FAILED, h.state(runId))
            assertEquals(emptyList(), h.github.writes)
        }
    }

    @Test
    fun overwritingAFileTheModelWasNotShownIsRejected() = runBlocking {
        TestDb().use { db ->
            // Only README.md is in the context, so docs/a.md exists but was never shown to the model.
            val h = Harness(db, proposal("docs/a.md" to "replaced\n"), config = SelfDevConfig(enabled = true, contextPathPrefixes = listOf("README.md")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), runner.advance(runId))
            assertEquals(emptyList(), h.github.writes)
        }
    }

    @Test
    fun overwritingAFileTheModelWasShownIsAccepted() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("README.md" to "replaced\n"), config = SelfDevConfig(enabled = true, contextPathPrefixes = listOf("README.md")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
        }
    }

    @Test
    fun aDeleteOfAnUnseenFileIsRejected() = runBlocking {
        TestDb().use { db ->
            val dev = FakeDeveloper { DeveloperResult.Proposed(net.kigawa.kivra.core.patch.PatchSet(listOf(net.kigawa.kivra.core.patch.Delete("docs/a.md")), "削除"), DevFixtures.usage) }
            val h = Harness(db, dev, config = SelfDevConfig(enabled = true, contextPathPrefixes = listOf("README.md")))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), runner.advance(runId))
            assertEquals(emptyList(), h.github.writes)
        }
    }

    // ---- DeveloperPort results ----

    @Test
    fun aRefusalFailsTheRunAndAnUnknownOutcomeMovesItToUnknown() = runBlocking {
        TestDb().use { db ->
            val refused = Harness(db, FakeDeveloper.returning(DeveloperResult.Refused(DeveloperRefusal.INVALID_OUTPUT)))
            val refusedRun = refused.newRun()
            refused.runner().advance(refusedRun)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "DEVELOPER_INVALID_OUTPUT"),
                refused.runner().advance(refusedRun),
            )
            assertEquals(emptyList(), refused.github.writes)

            val unknown = Harness(db, FakeDeveloper.returning(DeveloperResult.Unknown(DeveloperUnknown.OUTCOME_UNKNOWN)))
            val unknownRun = unknown.newRun()
            unknown.runner().advance(unknownRun)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "DEVELOPER_OUTCOME_UNKNOWN"),
                unknown.runner().advance(unknownRun),
            )
            assertEquals(emptyList(), unknown.github.writes)
            assertEquals(Waiting(WaitReason.AWAITING_OPERATOR), unknown.runner().advance(unknownRun))
        }
    }

    // ---- commitFiles outcomes ----

    @Test
    fun aCommitWhoseRefWasNotMovedIsRetriedOnceAndLandsOnce() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.commitFaults.add(CommitFault.NOT_APPLIED)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            assertEquals(1, h.github.commitsOnBranch.size)
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
        }
    }

    @Test
    fun aCommitThatIsNotAppliedTwiceBecomesUnknownAndWritesNoCommit() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.commitFaults.add(CommitFault.NOT_APPLIED)
            h.github.commitFaults.add(CommitFault.NOT_APPLIED)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "COMMIT_NOT_APPLIED_TWICE"),
                runner.advance(runId),
            )
            assertEquals(emptyList(), h.github.commitsOnBranch)
            assertEquals(listOf("createRef"), h.github.writes)
        }
    }

    @Test
    fun aCommitThatMovedTheRefButLostItsResponseIsNeverRetried() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.commitFaults.add(UNKNOWN_MOVED)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "GITHUB_UNKNOWN_OUTCOME"),
                runner.advance(runId),
            )
            assertEquals(1, h.github.commitsOnBranch.size, "exactly one commit on the branch, never a second one")
            assertEquals(listOf("createRef", "commit"), h.github.writes)
        }
    }

    // ---- lease fencing ----

    @Test
    fun aWorkerWhoseLeaseWasTakenOverWritesNothing() = runBlocking {
        TestDb().use { db ->
            var runIdText = ""
            // The Developer call is the long step. While it runs, the lease expires and a newer worker takes it over.
            val dev = FakeDeveloper {
                db.expireLease(runIdText)
                assertIs<net.kigawa.kivra.core.Lease>(db.leases.acquire(RunId(runIdText), "newer-worker", Duration.ofMinutes(5)))
                DevFixtures.proposed("docs/a.md" to "x\n")
            }
            val h = Harness(db, dev)
            val runner = h.runner()
            val runId = h.newRun()
            runIdText = runId.value
            runner.advance(runId) // CREATED -> DEVELOPING
            assertEquals(Skipped(SkipReason.LEASE_LOST), runner.advance(runId))
            assertEquals(DevelopmentRunState.DEVELOPING, h.state(runId))
            assertEquals(emptyList(), h.github.writes)
            // While the newer worker holds the lease, this worker is not admitted at all.
            assertEquals(Skipped(SkipReason.LEASE_NOT_ACQUIRED), runner.advance(runId))
        }
    }

    // ---- crashes and resume ----

    @Test
    fun aCrashBeforeAnyBranchWriteResumesFromPatchReadyWithoutADuplicateRequest() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.crashBefore = "ensureRunBranch"
            val runId = h.newRun()
            h.runner().advance(runId)
            assertFailsWith<CrashInjected> { h.runner().advance(runId) }
            assertEquals(DevelopmentRunState.PATCH_READY, h.state(runId))
            assertEquals(emptyList(), h.github.writes)

            // The ledger refuses a second call for the same run, so the resume ends in UNKNOWN and nothing is written.
            val resumed = h.runner(dev = FakeDeveloper.returning(DeveloperResult.Refused(DeveloperRefusal.PROVIDER_REFUSED)))
            assertEquals(
                Advanced(DevelopmentRunState.PATCH_READY, DevelopmentRunState.UNKNOWN, "DEVELOPER_PROVIDER_REFUSED"),
                resumed.advance(runId),
            )
            assertEquals(emptyList(), h.github.writes)
        }
    }

    @Test
    fun aCrashAfterTheCommitDoesNotCommitTwiceOrOpenASecondPullRequest() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.crashAfter = "commit"
            val runId = h.newRun()
            h.runner().advance(runId)
            assertFailsWith<CrashInjected> { h.runner().advance(runId) }
            assertEquals(DevelopmentRunState.PATCH_READY, h.state(runId))
            assertEquals(1, h.github.commitsOnBranch.size)

            // Even if the Developer answers again (no ledger here), the branch is not at base, so the run is not committed again.
            val resumed = h.runner(dev = proposal("docs/a.md" to "new again\n"))
            assertEquals(
                Advanced(DevelopmentRunState.PATCH_READY, DevelopmentRunState.UNKNOWN, "BRANCH_ADVANCED_BEFORE_COMMIT"),
                resumed.advance(runId),
            )
            assertEquals(1, h.github.commitsOnBranch.size)
            assertEquals(0, h.github.pullRequests().size)
        }
    }

    @Test
    fun aCrashAfterThePullRequestOpensDoesNotOpenASecondOne() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.crashAfter = "createPr"
            val runId = h.newRun()
            h.runner().advance(runId)
            assertFailsWith<CrashInjected> { h.runner().advance(runId) }
            assertEquals(DevelopmentRunState.PATCH_READY, h.state(runId))
            assertEquals(1, h.github.pullRequests().size)

            val resumed = h.runner(dev = proposal("docs/a.md" to "new\n"))
            assertEquals(
                Advanced(DevelopmentRunState.PATCH_READY, DevelopmentRunState.UNKNOWN, "BRANCH_ADVANCED_BEFORE_COMMIT"),
                resumed.advance(runId),
            )
            assertEquals(1, h.github.pullRequests().size)
            assertEquals(1, h.github.commitsOnBranch.size)
        }
    }

    @Test
    fun aCrashAfterTheBranchWasCreatedResumesAndOpensExactlyOnePullRequest() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.crashAfter = "createRef"
            val runId = h.newRun()
            h.runner().advance(runId)
            assertFailsWith<CrashInjected> { h.runner().advance(runId) }
            assertEquals(DevelopmentRunState.PATCH_READY, h.state(runId))

            // The branch exists at base, so the resume commits once and opens the one pull request.
            val resumed = h.runner(dev = proposal("docs/a.md" to "new\n"))
            assertEquals(Advanced(DevelopmentRunState.PATCH_READY, DevelopmentRunState.PULL_REQUEST), resumed.advance(runId))
            assertEquals(1, h.github.commitsOnBranch.size)
            assertEquals(1, h.github.pullRequests().size)
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
        }
    }

    // ---- closing an abandoned run ----

    @Test
    fun aClosedPullRequestCancelsTheRunAndFreesTheTask() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            runner.advance(runId)
            h.github.humanCloses(h.github.pullRequests().single().number)

            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.CANCELLED, "PR_ABANDONED"), runner.advance(runId))
            assertEquals(DevelopmentRunState.CANCELLED, h.state(runId))
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
            // The one-active-run key is free again: a new run for the same task can be created.
            db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        }
    }

    @Test
    fun anOpenPullRequestPastTheReviewTimeoutCancelsTheRunButIsNotClosed() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            val runId = h.newRun()
            h.runner().advance(runId)
            h.runner().advance(runId)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))

            val later = Clock.offset(Clock.systemUTC(), Duration.ofDays(8))
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.CANCELLED, "PR_ABANDONED"), h.runner(clk = later).advance(runId))
            // Only the run is cancelled. The pull request stays open and no write reached GitHub.
            assertEquals(PullRequestState.OPEN, h.github.pullRequests().single().state)
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
        }
    }

    @Test
    fun aCancelRequestIsAcknowledgedAsCancelled() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            val runId = h.newRun()
            db.runs.requestCancel(runId, "operator")
            assertEquals(Advanced(DevelopmentRunState.CREATED, DevelopmentRunState.CANCELLED, "CANCEL_REQUESTED"), h.runner().advance(runId))
            assertEquals(0, h.github.totalCalls)
        }
    }

    // ---- reads that fail, and missing task text ----

    @Test
    fun aMissingTaskTextDoesNotAdvanceTheRun() = runBlocking {
        TestDb().use { db ->
            val dev = proposal("docs/a.md" to "new\n")
            val h = Harness(db, dev, taskText = null)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(Failed(FailureCode.TASK_TEXT_UNAVAILABLE), runner.advance(runId))
            assertEquals(DevelopmentRunState.DEVELOPING, h.state(runId))
            assertEquals(0, dev.callCount)
        }
    }

    // ---- secrets and text never leave through results or logs ----

    @Test
    fun taskTextAndModelOutputDoNotAppearInLogsOrResults() = runBlocking {
        val marker = "MARK-TASK-LEAK"
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        try {
            TestDb().use { db ->
                val dev = FakeDeveloper.returning(DevFixtures.proposed(".github/x.yml" to "y\n", summary = "MARK-LLM-OUTPUT"))
                val h = Harness(db, dev, taskText = TaskText("title $marker", "body $marker"))
                val runner = h.runner()
                val runId = h.newRun()
                val results = listOf(runner.advance(runId), runner.advance(runId))
                assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, "POLICY_REJECTED"), results.last())
                results.forEach { assertFalse(it.toString().contains(marker)) }
                results.forEach { assertFalse(it.toString().contains("MARK-LLM-OUTPUT")) }
            }
        } finally {
            root.detachAppender(appender)
        }
        assertTrue(appender.list.none { it.formattedMessage.contains("MARK-") }, appender.list.joinToString { it.formattedMessage })
    }

    // ---- determinism ----

    @Test
    fun theSameInputGivesTheSameSequenceOfResultsAndWrites() = runBlocking {
        suspend fun scenario(): Pair<List<AdvanceResult>, List<String>> = TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            val runner = h.runner()
            val runId = h.newRun()
            val results = ArrayList<AdvanceResult>()
            repeat(3) { results += runner.advance(runId) }
            h.github.humanCloses(h.github.pullRequests().single().number)
            results += runner.advance(runId)
            results to h.github.writes.toList()
        }
        assertEquals(scenario(), scenario())
    }

    // ---- context selection ----

    @Test
    fun contextSelectionTakesMatchingFilesInPathOrderWithinTheLimits() {
        val config = SelfDevConfig(enabled = true, maxContextFiles = 2, maxContextFileBytes = 10, maxContextTotalBytes = 15)
        val entries = listOf(
            RepoEntry("docs/b.md", 5),
            RepoEntry("docs/a.md", 6),
            RepoEntry("docs/c.md", 9), // would exceed the total after a and b
            RepoEntry("docs/huge.md", 11), // over the per-file limit
            RepoEntry("src/x.kt", 1), // not in the context
            RepoEntry("README.md", 3),
        )
        val picked = ContextSelection.select(entries, config).map { it.path }
        assertEquals(listOf("README.md", "docs/a.md"), picked)
    }

    @Test
    fun contextSelectionMatchesDirectoriesOnlyByTheirPrefix() {
        val config = SelfDevConfig(enabled = true, contextPathPrefixes = listOf("docs/"))
        val entries = listOf(RepoEntry("docs/a.md", 1), RepoEntry("docsx/b.md", 1), RepoEntry("README.md", 1))
        assertEquals(listOf("docs/a.md"), ContextSelection.select(entries, config).map { it.path })
    }

    // ---- the Developer is shown what it is allowed to see ----

    @Test
    fun theDeveloperIsShownOnlyTheContextFiles() = runBlocking {
        TestDb().use { db ->
            val dev = proposal("docs/a.md" to "new\n")
            val h = Harness(db, dev)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            runner.advance(runId)
            val shown = dev.requests.single().repoContext.map { it.path }
            assertEquals(listOf("README.md", "docs/a.md"), shown)
            assertFalse(shown.contains("docs/big.md"))
            assertFalse(shown.contains("src/Main.kt"))
        }
    }

    @Test
    fun aCommitRetriedOnceThatIsNotAppliedAgainBecomesUnknownAfterExactlyTwoCalls() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.commitFaults.add(CommitFault.NOT_APPLIED)
            h.github.commitFaults.add(CommitFault.NOT_APPLIED)
            h.github.commitFaults.add(CommitFault.NOT_APPLIED) // must never be used
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "COMMIT_NOT_APPLIED_TWICE"),
                runner.advance(runId),
            )
            assertEquals(2, h.github.commitCalls, "one retry, never a third call")
            assertEquals(emptyList(), h.github.commitsOnBranch)
        }
    }

    @Test
    fun aConflictIsFailedAndNeverRetried() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.commitFaults.add(CommitFault.CONFLICT)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.FAILED, GitHubFailureKind.CONFLICT.code),
                runner.advance(runId),
            )
            assertEquals(1, h.github.commitCalls)
            assertEquals(emptyList(), h.github.commitsOnBranch)
        }
    }

    @Test
    fun anUnconfirmedUnknownIsNeverRetried() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, proposal("docs/a.md" to "new\n"))
            h.github.commitFaults.add(CommitFault.UNKNOWN_NOT_CONFIRMED)
            val runner = h.runner()
            val runId = h.newRun()
            runner.advance(runId)
            assertEquals(
                Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN, "GITHUB_UNKNOWN_OUTCOME"),
                runner.advance(runId),
            )
            assertEquals(1, h.github.commitCalls)
            assertEquals(emptyList(), h.github.commitsOnBranch)
        }
    }
}
