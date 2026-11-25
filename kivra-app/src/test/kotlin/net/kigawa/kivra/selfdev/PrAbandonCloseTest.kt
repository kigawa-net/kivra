package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEventsTable
import net.kigawa.kivra.db.PR_ABANDON_CLOSED
import net.kigawa.kivra.db.PR_ABANDON_CLOSE_ATTEMPTED
import net.kigawa.kivra.db.PR_ABANDON_CLOSE_GAVE_UP
import net.kigawa.kivra.db.PR_ABANDON_COMMENTED
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Closing kivra's own pull request when its review times out (ADR 0008 追補 2026-11-18, `KIVRA_SELFDEV_PR_ABANDON_CLOSE_ENABLED`), with the
 * real run repository on H2 and the fake GitHub: what is closed and what is not, the order (GitHub first, then the run), the retry of a
 * close that did not land, the single comment, the bounded attempts, and the audit rows.
 */
class PrAbandonCloseTest {
    private val files = mapOf("README.md" to "# kivra\n", "docs/a.md" to "old\n")
    private val later: Clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(8))

    private inner class Harness(val db: TestDb, val closing: Boolean = true) {
        val github = FakeGitHub()

        fun runner(clk: Clock = Clock.systemUTC()) = SelfDevRunner(
            config = SelfDevConfig(enabled = true),
            runs = DbDevelopmentRunStore(db.runs),
            leases = db.leases,
            developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new\n")),
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
            clock = clk,
            abandonCloser = if (closing) PrAbandonCloser(github.repo, BOT_USER_ID, github, github, DbDevelopmentRunStore(db.runs)) else null,
        )

        /** A run whose pull request is open and bound. */
        suspend fun openedRun(): RunId {
            val runId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId
            runner().advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner().advance(runId))
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), runner().advance(runId))
            return runId
        }

        fun pr() = github.pullRequests().single()

        fun state(runId: RunId) = db.runs.get(runId)!!.state

        /** The PR_ABANDON audit rows of the run, in order: (action, payload). */
        fun trail(runId: RunId): List<Pair<String, String?>> = transaction(db.database) {
            AuditEventsTable.selectAll()
                .where { AuditEventsTable.runId eq runId.value }
                .orderBy(AuditEventsTable.seq to SortOrder.ASC)
                .map { it[AuditEventsTable.action] to it[AuditEventsTable.payload] }
                .filter { it.first.startsWith("PR_ABANDON") }
        }
    }

    private val abandoned = Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.CANCELLED, "PR_ABANDONED")

    @Test
    fun aTimedOutPullRequestOfTheBotIsClosedWithOneFixedCommentThenTheRunIsAbandoned() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val number = h.pr().number

            assertEquals(abandoned, h.runner(later).advance(runId))
            assertEquals(PullRequestState.CLOSED, h.pr().state)
            assertFalse(h.pr().merged)
            assertEquals(listOf("createRef", "commit", "createPr", "closePullRequest", "createPullRequestComment"), h.github.writes)
            // The comment is the fixed text: it names the run, never the task or the change. No branch is deleted (no other write).
            assertEquals(listOf(number to PrAbandonCloser.commentBody(runId.value)), h.github.comments)
            assertFalse(PrAbandonCloser.commentBody(runId.value).contains(DevFixtures.taskText.title))
            assertEquals(
                listOf(
                    PR_ABANDON_CLOSE_ATTEMPTED to """{"prNumber":$number}""",
                    PR_ABANDON_CLOSED to """{"prNumber":$number,"code":"PR_CLOSED_TIMEOUT"}""",
                    PR_ABANDON_COMMENTED to """{"prNumber":$number}""",
                ),
                h.trail(runId),
            )
            assertEquals("PR_ABANDONED", db.runs.get(runId)!!.stateReason)
        }
    }

    @Test
    fun withTheSwitchOffTheRunIsAbandonedAndThePullRequestStaysOpen() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, closing = false)
            val runId = h.openedRun()
            assertEquals(abandoned, h.runner(later).advance(runId))
            assertEquals(PullRequestState.OPEN, h.pr().state)
            assertEquals(listOf("createRef", "commit", "createPr"), h.github.writes)
            assertEquals(emptyList(), h.trail(runId))
        }
    }

    @Test
    fun beforeTheTimeoutNothingIsClosed() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            repeat(2) { assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId)) }
            assertEquals(PullRequestState.OPEN, h.pr().state)
            assertEquals(emptyList(), h.github.closeCalls)
        }
    }

    @Test
    fun aPullRequestOfAnotherAuthorOrWithoutABindingIsNotClosed() = runBlocking {
        for (case in listOf("another author", "no binding")) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                if (case == "another author") {
                    h.github.setPullAuthor(h.pr().number, 1L)
                } else {
                    transaction(db.database) {
                        execUpdate(
                            "UPDATE development_runs SET pr_number = NULL, pr_node_id = NULL, pr_repository = NULL, pr_repository_id = NULL WHERE run_id = ?",
                            runId.value,
                        )
                    }
                }
                assertEquals(abandoned, h.runner(later).advance(runId), case)
                assertEquals(PullRequestState.OPEN, h.pr().state, case)
                assertEquals(emptyList(), h.github.closeCalls, case)
                assertEquals(emptyList(), h.github.comments, case)
                assertEquals(emptyList(), h.trail(runId), case)
            }
        }
    }

    @Test
    fun aMergedOrAPersonClosedPullRequestIsNeverClosedOrCommentedByKivra() = runBlocking {
        TestDb().use { db ->
            // Merged: the merge path runs first; the run is completed through the Authority, never abandoned or closed.
            val h = Harness(db)
            val runId = h.openedRun()
            h.github.humanMerges(h.pr().number)
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner(later).advance(runId))
            assertEquals(emptyList(), h.github.closeCalls)
            assertEquals(emptyList(), h.github.comments)
        }
        TestDb().use { db ->
            // Closed by a person (kivra never tried): abandoned as before, without a write.
            val h = Harness(db)
            val runId = h.openedRun()
            h.github.humanCloses(h.pr().number)
            assertEquals(abandoned, h.runner(later).advance(runId))
            assertEquals(emptyList(), h.github.closeCalls)
            assertEquals(emptyList(), h.github.comments)
            assertEquals(emptyList(), h.trail(runId))
        }
    }

    @Test
    fun aRefusedCloseKeepsTheRunAndTheNextCallClosesItWithOneComment() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.github.closeFailure = GitHubFailureKind.PERMISSION

            assertEquals(Waiting(WaitReason.AWAITING_PR_CLOSE, GitHubFailureKind.PERMISSION.code), h.runner(later).advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            assertEquals(PullRequestState.OPEN, h.pr().state)
            assertEquals(emptyList(), h.github.comments, "no comment before the pull request is closed")

            assertEquals(abandoned, h.runner(later).advance(runId))
            assertEquals(PullRequestState.CLOSED, h.pr().state)
            assertEquals(2, h.github.closeCalls.size)
            assertEquals(1, h.github.comments.size)
            assertEquals(
                listOf(PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_CLOSED, PR_ABANDON_COMMENTED),
                h.trail(runId).map { it.first },
            )
        }
    }

    @Test
    fun aCloseWhoseAnswerWasLostIsReadBackAndNotSentAgain() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.github.closeLost = true

            assertEquals(Waiting(WaitReason.AWAITING_PR_CLOSE, PrAbandonCloser.CLOSE_UNKNOWN), h.runner(later).advance(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, h.state(runId))
            // The close had landed: the next call sees the pull request closed, finishes (audit and comment) and abandons the run.
            assertEquals(abandoned, h.runner(later).advance(runId))
            assertEquals(1, h.github.closeCalls.size)
            assertEquals(1, h.github.comments.size)
            assertEquals(
                listOf(PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_CLOSED, PR_ABANDON_COMMENTED),
                h.trail(runId).map { it.first },
            )
        }
    }

    @Test
    fun aCommentThatFailsOrIsLostIsNeverPostedTwiceAndDoesNotKeepTheRun() = runBlocking {
        for (case in listOf("lost", "refused")) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                if (case == "lost") h.github.commentLost = true else h.github.commentFailure = GitHubFailureKind.SERVER_ERROR

                assertEquals(abandoned, h.runner(later).advance(runId), case)
                assertEquals(PullRequestState.CLOSED, h.pr().state, case)
                // A cancelled run is not advanced again, so nothing is posted later either.
                assertEquals(DevelopmentRunState.CANCELLED, h.state(runId), case)
                val expected = if (case == "lost") {
                    listOf(PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_CLOSED, PR_ABANDON_COMMENTED)
                } else {
                    listOf(PR_ABANDON_CLOSE_ATTEMPTED, PR_ABANDON_CLOSED)
                }
                assertEquals(expected, h.trail(runId).map { it.first }, case)
                if (case == "lost") assertTrue(h.trail(runId).last().second!!.contains("PR_COMMENT_UNKNOWN"))
            }
        }
    }

    @Test
    fun afterTheAttemptsRunOutTheRunIsAbandonedWithThePullRequestLeftOpen() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            repeat(PrAbandonCloser.DEFAULT_MAX_ATTEMPTS) {
                h.github.closeFailure = GitHubFailureKind.PERMISSION
                assertEquals(Waiting(WaitReason.AWAITING_PR_CLOSE, GitHubFailureKind.PERMISSION.code), h.runner(later).advance(runId))
            }
            // The attempts are audit rows only: the run's update time did not move, so it is still timed out.
            assertEquals(abandoned, h.runner(later).advance(runId))
            assertEquals(PullRequestState.OPEN, h.pr().state)
            assertEquals(PrAbandonCloser.DEFAULT_MAX_ATTEMPTS, h.github.closeCalls.size)
            assertEquals(emptyList(), h.github.comments)
            assertEquals(PR_ABANDON_CLOSE_GAVE_UP, h.trail(runId).last().first)
            assertEquals(PrAbandonCloser.DEFAULT_MAX_ATTEMPTS, h.trail(runId).count { it.first == PR_ABANDON_CLOSE_ATTEMPTED })
        }
    }

    @Test
    fun theAuditAndTheLogsCarryNoTextOfTheTaskOrThePullRequest() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            assertEquals(abandoned, h.runner(later).advance(runId))
            val payloads = h.trail(runId).joinToString("\n") { it.second.orEmpty() }
            assertFalse(payloads.contains(DevFixtures.taskText.title))
            assertFalse(payloads.contains("Run "), "the comment text is not in the audit")
            assertTrue(Regex("""\{"prNumber":\d+(,"code":"[A-Z_]+")?}""").let { re -> h.trail(runId).all { re.matches(it.second!!) } })
        }
    }
}
