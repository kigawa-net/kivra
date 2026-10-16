package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPrMergeAuthority
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting

/**
 * The production merge path (ADR 0008 part 7, #56 PR4): a PR_ONLY run is completed by the real [GitHubPrMergeAuthority] installed
 * through [DeferredPrMergeAuthority], against the in-memory GitHub double. The run repository is built before the authority exists,
 * as in Application.kt. The author id is [BOT_USER_ID], the head is the committed sha, the merge is the human merge in the double.
 */
class SelfDevMergeVerificationWiringTest {
    private val files = mapOf("README.md" to "# kivra\n", "docs/a.md" to "old\n")

    private inner class Harness(val db: TestDb) {
        val github = FakeGitHub()
        val deferred = DeferredPrMergeAuthority()
        val runs = DevelopmentRunRepository(db.database, db.outbox, db.audit, deferred)
        val developer: DeveloperPort = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new\n"))

        fun runner() = SelfDevRunner(
            config = SelfDevConfig(enabled = true),
            runs = DbDevelopmentRunStore(runs),
            leases = db.leases,
            developer = developer,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
        )

        /** The same installation that buildSelfDev performs. */
        fun install() = deferred.install(GitHubPrMergeAuthority(github, BOT_USER_ID))

        /** Advances a new run until its pull request is open. */
        suspend fun openedRun(): RunId {
            val runId = runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId
            val runner = runner()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            return runId
        }

        fun prNumber(): Int = github.pullRequests().single().number
    }

    private fun Harness.completedEvents(): Int = db.count("outbox_events", "event_type = 'RunCompleted'")

    @Test
    fun aMergedRunWaitsUntilTheAuthorityIsInstalledAndIsNotCompletedBefore() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.github.humanMerges(h.prNumber())

            // Nothing is installed yet: the merged run waits and is not completed.
            repeat(2) { assertEquals(Waiting(WaitReason.AWAITING_MERGE_VERIFICATION), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.runs.get(runId)!!.state)
            assertEquals(0, h.completedEvents())

            h.install()
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
            assertEquals(1, h.completedEvents())
        }
    }

    @Test
    fun aMergeOfTheBoundPullRequestByTheBotCompletesTheRun() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.install()
            h.github.humanMerges(h.prNumber())
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
            assertEquals(1, h.completedEvents())
        }
    }

    @Test
    fun aPullRequestOpenedByAnotherAuthorIsRefusedAndRetried() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.install()
            h.github.humanMerges(h.prNumber())
            h.github.setPullAuthor(h.prNumber(), 1L)
            repeat(2) { assertEquals(Waiting(WaitReason.MERGE_NOT_VERIFIED, "AUTHOR_MISMATCH"), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.runs.get(runId)!!.state)
            assertEquals(0, h.completedEvents())
        }
    }

    @Test
    fun aHeadThatMovedAfterTheBindingIsRefused() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.install()
            h.github.humanMerges(h.prNumber())
            h.github.setPullHead(h.prNumber(), "d4".repeat(20))
            assertEquals(Waiting(WaitReason.MERGE_NOT_VERIFIED, "HEAD_SHA_MISMATCH"), h.runner().advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.runs.get(runId)!!.state)
            assertEquals(0, h.completedEvents())
        }
    }

    @Test
    fun anUnavailableProviderWaitsAndTheNextCallCompletes() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.install()
            h.github.humanMerges(h.prNumber())
            h.github.pullReadFailure = GitHubFailureKind.AUTH
            repeat(2) { assertEquals(Waiting(WaitReason.AWAITING_MERGE_VERIFICATION), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.runs.get(runId)!!.state)
            assertEquals(0, h.completedEvents())

            h.github.pullReadFailure = null
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
        }
    }

    @Test
    fun theInstallationCanHappenOnlyOnce() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            h.install()
            assertFailsWith<IllegalStateException> { h.install() }
        }
    }
}
