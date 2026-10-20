package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditEventsTable
import net.kigawa.kivra.db.AuditEntry
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.AdjustableClock
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.FakeTrustedPrMergeAuthority
import net.kigawa.kivra.db.PR_REBASE_REBOUND
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.developer.FakeDeveloper
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PullRequest
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Keeping a waiting self-development pull request on top of `main` (issue #276). H2 with the real run repository and lease store;
 * GitHub is in memory ([FakeGitHub] for pull requests and branches, [FakeGitObjects] for commits and trees, [FakePrBranch] for the
 * update port). The base tree is `README.md` and `docs/a.md`; the pull request changes `docs/a.md`.
 */
class PrBranchRebaseTest {
    private val files = mapOf("README.md" to "# kivra\n", "docs/a.md" to "old\n")
    private val repo = RepoRef("kigawa-net", "kivra")

    private inner class Harness(
        val db: TestDb,
        val runs: DevelopmentRunRepository = db.runs,
        val clock: Clock = Clock.systemUTC(),
        val withRebaser: Boolean = true,
    ) {
        val github = FakeGitHub()
        val git = FakeGitObjects()
        val port = FakePrBranch(github, git)

        init {
            // The base commit the run branches from: a root commit with the base tree.
            git.commit(github.baseSha, emptyList(), BASE_TREE)
        }

        private val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new\n"))
        private val rebaser = PrBranchRebaser(
            repo = repo,
            baseBranch = "main",
            botUserId = BOT_USER_ID,
            port = port,
            github = github,
            runs = DbDevelopmentRunStore(runs),
            settings = PrRebaseSettings(),
            clock = clock,
            pause = {},
        )

        fun runner() = SelfDevRunner(
            config = SelfDevConfig(enabled = true),
            runs = DbDevelopmentRunStore(runs),
            leases = db.leases,
            developer = developer,
            github = github,
            repoReader = InMemoryRepoReader(files, github.baseSha),
            taskTexts = FixedTaskTextSource(DevFixtures.taskText),
            prRebaser = if (withRebaser) rebaser else null,
        )

        /** Opens the pull request and registers its head commit (the recorded head) with the change to `docs/a.md`. */
        suspend fun openedRun(): RunId {
            val runId = runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId
            val runner = runner()
            runner.advance(runId)
            assertEquals(Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST), runner.advance(runId))
            git.commit(head(), listOf(github.baseSha), BASE_TREE + ("docs/a.md" to ("100644" to BLOB_NEW)))
            return runId
        }

        fun head(): String = github.commitsOnBranch.single()

        fun pr(): PullRequest = github.pullRequests().single()

        /** Moves `main` past the base: a new commit that changes another file. Returns its id. */
        fun mainMoves(): String {
            git.commit(MAIN, listOf(github.baseSha), BASE_TREE + ("other.md" to ("100644" to "blob-other")))
            github.moveBranch("main", MAIN)
            return MAIN
        }

        /** Moves `main` once more, to [MAIN2] (a child of [MAIN]), and returns its id. */
        fun mainMovesAgain(): String {
            git.commit(MAIN2, listOf(MAIN), BASE_TREE + ("other.md" to ("100644" to "blob-other")) + ("more.md" to ("100644" to "blob-more")))
            github.moveBranch("main", MAIN2)
            return MAIN2
        }

        /** A person merges the pull request, as GitHub reports it (the head is whatever the pull request has now). */
        fun humanMerges() = github.humanMerges(pr().number)

        /** Registers a commit with [parents] whose `docs/a.md` has [docsBlob] and whose other files are those of `main`. */
        fun registerMerge(sha: String, docsBlob: String, parents: List<String> = listOf(head(), MAIN)) {
            git.commit(sha, parents, BASE_TREE + ("other.md" to ("100644" to "blob-other")) + ("docs/a.md" to ("100644" to docsBlob)))
        }

        fun binding(runId: RunId) = assertNotNull(runs.loadPullRequestBinding(runId)).headSha
    }

    /** The PR_REBASE audit rows of a run, in order: (action, payload). */
    private fun TestDb.rebaseRows(runId: RunId): List<Pair<String, String?>> = transaction(database) {
        AuditEventsTable.selectAll()
            .where { AuditEventsTable.runId eq runId.value }
            .orderBy(AuditEventsTable.seq to SortOrder.ASC)
            .map { it[AuditEventsTable.action] to it[AuditEventsTable.payload] }
            .filter { it.first.startsWith("PR_REBASE") }
    }

    private fun TestDb.allPayloads(runId: RunId): String = transaction(database) {
        AuditEventsTable.selectAll()
            .where { AuditEventsTable.runId eq runId.value }
            .mapNotNull { it[AuditEventsTable.payload] }
            .joinToString("\n")
    }

    @Test
    fun aSameDiffIsReboundAndTheAuditHoldsOnlyPrefixesAndAFixedCode() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))

            // The three heads agree after the rebind (ADR 0014 C4): the recorded head, the pull request head, and the one the update used.
            assertEquals(MERGE, h.binding(runId))
            assertEquals(MERGE, h.pr().headSha)
            assertEquals(old, h.port.lastExpectedHead)
            assertEquals(1, h.port.updateCalls)

            val rows = db.rebaseRows(runId)
            assertEquals(listOf("PR_REBASE_ATTEMPTED", PR_REBASE_REBOUND), rows.map { it.first })
            assertEquals(
                """{"from":"${old.take(12)}","to":"${MERGE.take(12)}","reasonCode":"PR_REBASE_VERIFIED"}""",
                rows.last().second,
            )
            // The audit carries no title, body, path or content, and not the full heads either.
            val payloads = db.allPayloads(runId)
            for (forbidden in listOf("docs/a.md", "README.md", "Self-development", "kivra-run", "kivra-head-sha", "new", old, MERGE)) {
                assertFalse(payloads.contains(forbidden), "audit payload contains a forbidden value")
            }
        }
    }

    @Test
    fun aChangedDiffIsNotReboundAndTheRunStaysForAPerson() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            // The update puts a different content into the pull request's own file: the change set differs.
            h.registerMerge(MERGE, "blob-tampered")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_DIFF_CHANGED"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(listOf("PR_REBASE_ATTEMPTED", "PR_REBASE_HELD"), db.rebaseRows(runId).map { it.first })

            // The next call sees the changed head without a new update request, and refuses it the same way.
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_DIFF_CHANGED"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(1, h.port.updateCalls)
        }
    }

    @Test
    fun aNewHeadThatMergesOnlyTheRecordedHeadIsRefused() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            // A plain commit on top of the recorded head: one parent, not a merge with main.
            h.registerMerge(MERGE, "blob-new", parents = listOf(old))
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_PARENTS_MISMATCH"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    @Test
    fun aMergeWithAnotherCommitThanMainIsRefused() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            // OTHER is a real commit, but not in the history of main: it has a commit main lacks (ahead_by 1).
            h.git.commit(OTHER, listOf(h.github.baseSha), BASE_TREE)
            h.registerMerge(MERGE, "blob-new", parents = listOf(old, OTHER))
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_PARENTS_MISMATCH"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    @Test
    fun aMergeWithAnAncestorOfMainIsAcceptedAfterMainMovedAgain() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            // The update is made against MAIN; main then moves on to MAIN2 before the new head is read.
            h.mainMoves()
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
            h.port.beforeUpdate = { _ -> h.mainMovesAgain() }
            h.registerMerge(MERGE, "blob-new", parents = listOf(old, MAIN))

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(listOf("PR_REBASE_ATTEMPTED", PR_REBASE_REBOUND), db.rebaseRows(runId).map { it.first })
        }
    }

    @Test
    fun aParentThatCannotBeComparedWithMainIsNotVerified() = runBlocking {
        for (answer in listOf(GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, "NOT_FOUND"), GitHubResult.Success(-1))) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.registerMerge(MERGE, "blob-new")
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
                h.port.aheadResult = answer

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFY_FAILED"), h.runner().advance(runId), "$answer")
                assertEquals(old, h.binding(runId))
            }
        }
    }

    @Test
    fun aMergeWithOtherThanTwoDistinctParentsIsRefused() = runBlocking {
        val cases = listOf(
            "three parents" to listOf("OLD", "MAIN", "OTHER"),
            "the recorded head twice" to listOf("OLD", "OLD"),
        )
        for ((name, shape) in cases) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.git.commit(OTHER, listOf(h.github.baseSha), BASE_TREE)
                val parents = shape.map { if (it == "OLD") old else if (it == "MAIN") MAIN else OTHER }
                h.registerMerge(MERGE, "blob-new", parents = parents)
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_PARENTS_MISMATCH"), h.runner().advance(runId), name)
                assertEquals(old, h.binding(runId), name)
            }
        }
    }

    @Test
    fun aMergeWhosePathModeOrBlobDiffersFromTheRecordedChangeIsRefused() = runBlocking {
        val tampered = mapOf(
            "a blob" to mapOf("README.md" to ("100644" to "blob-readme"), "docs/a.md" to ("100644" to "blob-tampered"), "other.md" to ("100644" to "blob-other")),
            "a mode" to mapOf("README.md" to ("100644" to "blob-readme"), "docs/a.md" to ("100755" to "blob-new"), "other.md" to ("100644" to "blob-other")),
            "a path" to mapOf(
                "README.md" to ("100644" to "blob-readme"), "docs/a.md" to ("100644" to "blob-new"),
                "other.md" to ("100644" to "blob-other"), "extra.md" to ("100644" to "blob-extra"),
            ),
        )
        for ((name, tree) in tampered) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.git.commit(MERGE, listOf(old, MAIN), tree)
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_DIFF_CHANGED"), h.runner().advance(runId), name)
                assertEquals(old, h.binding(runId), name)
            }
        }
    }

    @Test
    fun aMergedPullRequestWhoseVerifiedHeadDiffersIsRecordedAndCompleted() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new", parents = listOf(old, MAIN))
            // The head moved without the rebaser (no update request from kivra), and a person merged it.
            h.github.setPullHead(h.pr().number, MERGE)
            h.humanMerges()
            // The Authority reports the pull request's head as GitHub has it.
            db.prAuthority.answer = { b -> FakeTrustedPrMergeAuthority.merged(b).copy(headSha = h.pr().headSha) }

            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(0, h.port.updateCalls)
            // The head move is audited as a rebind only: it is not an update request, so no attempt is counted.
            assertEquals(listOf(PR_REBASE_REBOUND), db.rebaseRows(runId).map { it.first })
        }
    }

    @Test
    fun aMergedPullRequestWhoseNewHeadDoesNotVerifyStaysWaitingOnTheOldHead() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            // A diff that changed: the verification refuses it.
            h.registerMerge(MERGE, "blob-tampered", parents = listOf(old, MAIN))
            h.github.setPullHead(h.pr().number, MERGE)
            h.humanMerges()
            db.prAuthority.answer = { b -> FakeTrustedPrMergeAuthority.merged(b).copy(headSha = h.pr().headSha) }

            repeat(2) { assertEquals(Waiting(WaitReason.MERGE_NOT_VERIFIED, "HEAD_SHA_MISMATCH"), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(runId)!!.state)
            assertEquals(old, h.binding(runId))
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(0, h.port.updateCalls)
        }
    }

    @Test
    fun aMergedPullRequestOfAnotherAuthorIsNotReconciled() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new", parents = listOf(old, MAIN))
            h.github.setPullHead(h.pr().number, MERGE)
            h.github.setPullAuthor(h.pr().number, 1L)
            h.humanMerges()
            // The Authority reports the pull request as GitHub has it: its head and its author.
            db.prAuthority.answer = { b -> FakeTrustedPrMergeAuthority.merged(b).copy(headSha = h.pr().headSha, authorId = h.pr().authorId) }

            repeat(2) { assertEquals(Waiting(WaitReason.MERGE_NOT_VERIFIED, "HEAD_SHA_MISMATCH"), h.runner().advance(runId)) }
            assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(runId)!!.state)
            assertEquals(old, h.binding(runId))
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(0, h.port.updateCalls)
        }
    }

    @Test
    fun aPullRequestWithAConflictIsNotTouched() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            // Behind by one commit, but a conflict ("dirty", mergeable false) or a mergeable value not computed yet (null): left alone.
            for ((state, mergeable) in listOf("dirty" to true, "dirty" to false, "unstable" to false, "unstable" to null)) {
                h.port.mergeableState = state
                h.port.mergeable = mergeable
                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId), "state=$state mergeable=$mergeable")
            }
            assertEquals(0, h.port.updateCalls)
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    @Test
    fun aHeadBehindMainIsUpdatedWithoutTheUpToDateProtection() = runBlocking {
        // The live repository has no "require up to date" protection, so GitHub reports "unstable", never "behind".
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.mergeableState = "unstable"
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(1, h.port.updateCalls)
        }
    }

    @Test
    fun aHeadThatContainsMainIsNotUpdatedWhateverTheMergeableState() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            // main has nothing the head lacks (behind_by is 0), so no state reported by GitHub is a reason to update.
            for (state in listOf("clean", "unstable", "behind")) {
                h.port.mergeableState = state
                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId), "state=$state")
            }
            assertEquals(3, h.port.compareCalls)
            assertEquals(0, h.port.updateCalls)
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    @Test
    fun aDistanceThatCannotBeReadIsNotAReasonToUpdate() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
            h.port.compareResult = GitHubResult.Failure(GitHubFailureKind.PROTOCOL, "unexpected response shape: compare.behind_by")

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))
            assertEquals(0, h.port.updateCalls)
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    @Test
    fun aPullRequestOpenedByAnotherAuthorIsNotTouched() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.mainMoves()
            h.github.setPullAuthor(h.pr().number, 1L)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))
            assertEquals(0, h.port.updateCalls)
        }
    }

    @Test
    fun anExpectedHeadRefusalLeavesTheRunAtTheOldHead() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.port.updateResult = GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "GITHUB_CONFLICT"), h.runner().advance(runId))
            assertEquals(old, h.lastExpected())
            assertEquals(old, h.binding(runId))
            assertEquals(listOf("PR_REBASE_ATTEMPTED", "PR_REBASE_HELD"), db.rebaseRows(runId).map { it.first })
        }
    }

    @Test
    fun aHeadThatMovedBeforeTheUpdateIsNotRecordedWhenItDoesNotVerify() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            // A person pushes a plain commit between the read and the update: the update is refused, and the new head does not verify.
            h.registerMerge(PERSON, "blob-new", parents = listOf(old))
            h.port.beforeUpdate = { number -> h.github.setPullHead(number, PERSON) }
            h.port.updateResult = GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_PARENTS_MISMATCH"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    @Test
    fun anUnknownUpdateOutcomeIsReadBackAndAVerifiedHeadIsRecorded() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            // The request may have landed although the answer was lost: the head is read, and the verified head is recorded.
            h.port.beforeUpdate = { number -> h.github.setPullHead(number, MERGE) }
            h.port.updateResult = GitHubResult.Unknown("updatePrBranch", "response lost")

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(1, h.port.updateCalls)
        }
    }

    @Test
    fun anUpdateThatDoesNotShowANewHeadIsHeldAndALaterLandingIsStillVerified() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_NOT_OBSERVED"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))

            // The update lands later. The next call verifies it without a second update request.
            h.github.setPullHead(h.pr().number, MERGE)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(1, h.port.updateCalls)
        }
    }

    @Test
    fun theHourlyAndTotalLimitsHoldTheRunForAPerson() = runBlocking {
        TestDb().use { db ->
            val clock = AdjustableClock()
            // The audit rows and the limit read the same adjustable clock.
            val runs = DevelopmentRunRepository(db.database, db.outbox, AuditRepository(clock), db.prAuthority, clock)
            val h = Harness(db, runs = runs, clock = clock)
            val runId = h.openedRun()
            h.mainMoves()
            // The update is accepted but the head does not move, so each attempt ends in NOT_OBSERVED.
            suspend fun tick() = h.runner().advance(runId)
            val waiting = Waiting(WaitReason.AWAITING_HUMAN_REVIEW)

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_NOT_OBSERVED"), tick())
            assertEquals(waiting, tick())
            assertEquals(1, h.port.updateCalls)

            clock.offset = Duration.ofMinutes(59)
            assertEquals(waiting, tick())
            assertEquals(1, h.port.updateCalls)

            clock.offset = Duration.ofMinutes(61)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_NOT_OBSERVED"), tick())
            assertEquals(2, h.port.updateCalls)

            clock.offset = Duration.ofMinutes(122)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_NOT_OBSERVED"), tick())
            assertEquals(3, h.port.updateCalls)

            // The third attempt is the last one for this pull request, however much time passes.
            clock.offset = Duration.ofMinutes(183)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_LIMIT_TOTAL"), tick())
            assertEquals(3, h.port.updateCalls)
            assertEquals(3, db.rebaseRows(runId).count { it.first == "PR_REBASE_ATTEMPTED" })
        }
    }

    @Test
    fun aFailedRebindKeepsTheOldHeadInOneTransaction() = runBlocking {
        TestDb().use { db ->
            // The audit row of the rebind fails after the head was written in the same transaction: both are rolled back.
            val failingAudit = object : AuditRepository() {
                override fun append(tx: Transaction, runId: RunId, entry: AuditEntry): String {
                    if (entry.action == PR_REBASE_REBOUND) error("injected audit failure")
                    return super.append(tx, runId, entry)
                }
            }
            val runs = DevelopmentRunRepository(db.database, db.outbox, failingAudit, db.prAuthority)
            val h = Harness(db, runs = runs)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_REBIND_FAILED"), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(DevelopmentRunState.PULL_REQUEST, db.runs.get(runId)!!.state)
            assertEquals(listOf("PR_REBASE_ATTEMPTED"), db.rebaseRows(runId).map { it.first })
        }
    }

    @Test
    fun withTheSwitchOffNothingIsAskedOfTheProviderAndNothingIsWritten() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, withRebaser = false)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.port.mergeableState = "unstable"

            repeat(2) { assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId)) }
            assertEquals(0, h.port.totalCalls)
            assertEquals(0, h.port.updateCalls)
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(old, h.binding(runId))
        }
    }

    private fun Harness.lastExpected(): String? = port.lastExpectedHead

    private companion object {
        const val MAIN = "1111111111111111111111111111111111111111"
        const val MERGE = "2222222222222222222222222222222222222222"
        const val PERSON = "3333333333333333333333333333333333333333"
        const val OTHER = "4444444444444444444444444444444444444444"
        const val MAIN2 = "5555555555555555555555555555555555555555"
        const val BLOB_NEW = "blob-new"

        val BASE_TREE: Map<String, Pair<String, String>> = mapOf(
            "README.md" to ("100644" to "blob-readme"),
            "docs/a.md" to ("100644" to "blob-old"),
        )
    }
}
