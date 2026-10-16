package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.ObservedPullRequest
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.FakeTrustedPrMergeAuthority
import net.kigawa.kivra.db.NoPrMergeAuthority
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Skipped
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting

/**
 * Pull request binding and merged-pull-request completion (ADR 0011, PR2 and PR3). Runs on H2 with the real run repository, the real
 * lease store and the test merge Authority ([FakeTrustedPrMergeAuthority], which goes through the core verifier). GitHub is in memory.
 */
class SelfDevPullRequestBindingTest {
    private val files = mapOf("README.md" to "# kivra\n", "docs/a.md" to "old\n")

    private inner class Harness(
        val db: TestDb,
        val config: SelfDevConfig = SelfDevConfig(enabled = true),
        val runs: DevelopmentRunRepository = db.runs,
        val developer: DeveloperPort = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new\n")),
    ) {
        val github = FakeGitHub()

        fun runner(cfg: SelfDevConfig = config) = SelfDevRunner(
            config = cfg,
            runs = DbDevelopmentRunStore(runs),
            leases = db.leases,
            developer = developer,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
        )

        fun newRun(): RunId = runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

        fun state(runId: RunId): DevelopmentRunState = runs.get(runId)!!.state

        /** Advances a new run until its pull request is open. Returns the run. */
        suspend fun openedRun(): RunId {
            val runId = newRun()
            val runner = runner()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            return runId
        }

        fun humanMerges() = github.humanMerges(github.pullRequests().single().number)
    }

    private fun Harness.completedEvents(): Int = db.count("outbox_events", "event_type = 'RunCompleted'")

    private fun Harness.boundEvents(): Int = db.count("outbox_events", "event_type = 'RunPullRequestBound'")

    @Test
    fun theBindingIsRecordedWhenThePullRequestOpens() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val pr = h.github.pullRequests().single()
            val binding = assertNotNull(db.runs.loadPullRequestBinding(runId))

            assertEquals("kigawa-net/kivra", binding.repository)
            assertEquals(42L, binding.repositoryId)
            assertEquals(pr.number.toLong(), binding.prNumber)
            assertEquals(pr.nodeId, binding.prNodeId)
            assertEquals("main", binding.baseRef)
            assertEquals(h.github.commitsOnBranch.single(), binding.headSha)
            assertEquals("patchpolicy-v1", binding.policyRevision)
            assertEquals(64, binding.reviewManifestDigest.length)
            assertEquals(1, h.boundEvents())
            assertEquals(1, db.count("audit_events", "action = 'RUN_PR_BOUND'"))
        }
    }

    @Test
    fun theBindingIsRecordedOnceAndACallAfterARestartDoesNotRebindIt() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val first = assertNotNull(db.runs.loadPullRequestBinding(runId))
            repeat(3) { assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId)) }
            // A new runner instance stands for a restarted worker.
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))

            assertEquals(first, db.runs.loadPullRequestBinding(runId))
            assertEquals(1, h.boundEvents())
            assertEquals(1, h.github.pullRequests().size)
        }
    }

    @Test
    fun aMergedPullRequestIsCompletedOnceAndLaterCallsAreSkipped() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.humanMerges()

            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
            assertEquals(DevelopmentRunState.COMPLETED, h.state(runId))
            assertEquals(Skipped(SkipReason.TERMINAL), h.runner().advance(runId))
            assertEquals(Skipped(SkipReason.TERMINAL), h.runner().advance(runId))

            assertEquals(1, h.completedEvents())
            assertEquals(1, db.count("audit_events", "action = 'RUN_TRANSITION' AND payload LIKE '%\"to\":\"COMPLETED\"%'"))
        }
    }

    @Test
    fun aRefusedMergeKeepsTheRunInPullRequestWithTheReasonCode() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.humanMerges()
            val cases: List<Pair<String, (ObservedPullRequest) -> ObservedPullRequest>> = listOf(
                "HEAD_SHA_MISMATCH" to { o -> o.copy(headSha = "d4".repeat(20)) },
                "AUTHOR_MISMATCH" to { o -> o.copy(authorId = 1L) },
                "MERGE_COMMIT_SHA_MISSING" to { o -> o.copy(mergeCommitSha = null) },
            )
            val before = h.github.writes.toList()
            for ((code, change) in cases) {
                db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it).let(change) }
                repeat(2) { assertEquals(Waiting(WaitReason.MERGE_NOT_VERIFIED, code), h.runner().advance(runId)) }
                assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
                assertEquals(0, h.completedEvents())
            }
            assertEquals(before, h.github.writes)

            // The same merge, verified, completes the run.
            db.prAuthority.answer = { FakeTrustedPrMergeAuthority.merged(it) }
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
        }
    }

    @Test
    fun anUnavailableAuthorityWaitsAndTheNextCallCompletes() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.humanMerges()
            db.prAuthority.failure = PrMergeAuthorityUnavailableException("provider unavailable")
            repeat(2) { assertEquals(Waiting(WaitReason.AWAITING_MERGE_VERIFICATION), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            assertEquals(0, h.completedEvents())

            db.prAuthority.failure = null
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
        }
    }

    @Test
    fun theProductionWiringWithoutAnAuthorityWaitsAndNeverCompletes() = runBlocking {
        TestDb().use { db ->
            // The wiring in Application.kt: the run repository gets NoPrMergeAuthority.
            val production = DevelopmentRunRepository(db.database, db.outbox, db.audit, NoPrMergeAuthority)
            val h = Harness(db, runs = production)
            val runId = h.openedRun()
            assertEquals(1, h.boundEvents())
            h.humanMerges()
            repeat(2) { assertEquals(Waiting(WaitReason.AWAITING_MERGE_VERIFICATION), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            assertEquals(0, h.completedEvents())
        }
    }

    @Test
    fun aPullRequestRunWithoutABindingIsNotCompletedAndTheAuthorityIsNotAsked() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            // Simulates a crash after the pull request was opened and before the binding was recorded.
            db.clearBinding(runId.value)
            h.humanMerges()
            repeat(2) { assertEquals(Waiting(WaitReason.BINDING_MISSING), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            assertEquals(0, db.prAuthority.calls)
            assertEquals(0, h.completedEvents())
        }
    }

    @Test
    fun aLeaseLostDuringVerificationWritesNothing() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.humanMerges()
            // The lease expires while the Authority is answering: the completion is fenced out.
            db.prAuthority.beforeAnswer = { db.expireLease(runId.value) }
            assertEquals(Skipped(SkipReason.LEASE_LOST), h.runner().advance(runId))
            db.prAuthority.beforeAnswer = {}
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            assertEquals(0, h.completedEvents())
        }
    }

    @Test
    fun theKillSwitchStopsTheCompletionPathToo() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.humanMerges()
            val callsBefore = db.prAuthority.calls
            val providerCallsBefore = h.github.totalCalls
            assertEquals(Skipped(SkipReason.DISABLED), h.runner(cfg = SelfDevConfig(enabled = false)).advance(runId))
            assertEquals(callsBefore, db.prAuthority.calls)
            assertEquals(providerCallsBefore, h.github.totalCalls)
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
        }
    }

    @Test
    fun aClosedPullRequestNeverReachesTheAuthority() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.github.humanCloses(h.github.pullRequests().single().number)
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.CANCELLED, "PR_ABANDONED"), h.runner().advance(runId))
            assertEquals(0, db.prAuthority.calls)
            assertEquals(PullRequestState.CLOSED, h.github.pullRequests().single().state)
        }
    }
}
