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
import net.kigawa.kivra.github.ComparedFile
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
        maxReads: Int = PrRebaseSettings.DEFAULT_MAX_READS_PER_TICK,
    ) {
        val github = FakeGitHub()
        val git = FakeGitObjects()
        val port = FakePrBranch(github, git)

        init {
            // The base commit the run branches from: a root commit with the base tree.
            git.commit(github.baseSha, emptyList(), BASE_TREE)
        }

        private val developer = FakeDeveloper.returning(DevFixtures.proposed("docs/a.md" to "new\n"))

        private fun rebaserWith(limit: Int) = PrBranchRebaser(
            repo = repo,
            baseBranch = "main",
            botUserId = BOT_USER_ID,
            port = port,
            github = github,
            runs = DbDevelopmentRunStore(runs),
            settings = PrRebaseSettings(maxReadsPerTick = limit),
            clock = clock,
            pause = {},
        )

        private var rebaser = rebaserWith(maxReads)

        /** Replaces the rebaser with one whose tick budget is [limit] (a new instance, so the overlap cache starts empty). */
        fun setReadLimit(limit: Int) {
            rebaser = rebaserWith(limit)
        }

        /** Each runner is one worker tick here: the look of the run gets a fresh read budget, as a tick does in production. */
        fun runner(): SelfDevRunner {
            rebaser.beginTick()
            return SelfDevRunner(
                config = SelfDevConfig(enabled = true),
                runs = DbDevelopmentRunStore(runs),
                leases = db.leases,
                developer = developer,
                github = github,
                repoReader = InMemoryRepoReader(files, github.baseSha),
                taskTexts = FixedTaskTextSource(DevFixtures.taskText),
                prRebaser = if (withRebaser) rebaser else null,
            )
        }

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

        /** Moves `main` past the base with a change to `docs/a.md`, the file the pull request changes too. Returns its id. */
        fun mainChangesSamePath(): String {
            git.commit(MAIN, listOf(github.baseSha), BASE_TREE + ("docs/a.md" to ("100644" to "blob-main")))
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

        /** Registers the compare answer of a commit pair (base to head), as the second step of (b) reads it. */
        fun compareOf(base: String, head: String, files: List<ComparedFile>) {
            port.compareFiles[base to head] = files
        }
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
            // The same change set (first step): the compare API is not read.
            assertEquals(0, h.port.compareFilesCalls)

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
            // The changed lines differ too: the same path, the same mode, but a different line in the pull request's own change.
            h.compareOf(h.github.baseSha, old, listOf(modified("docs/a.md", BEFORE_PATCH)))
            h.compareOf(MAIN, MERGE, listOf(modified("docs/a.md", "@@ -1 +1 @@\n-old\n+tampered\n")))
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
        // Each case: the tree of the new head, and the compare answer of main to the new head (the pull request's change as main sees it).
        val tampered = mapOf(
            "a blob" to Pair(
                mapOf("README.md" to ("100644" to "blob-readme"), "docs/a.md" to ("100644" to "blob-tampered"), "other.md" to ("100644" to "blob-other")),
                listOf(modified("docs/a.md", "@@ -1 +1 @@\n-old\n+tampered\n")),
            ),
            "a mode" to Pair(
                mapOf("README.md" to ("100644" to "blob-readme"), "docs/a.md" to ("100755" to "blob-new"), "other.md" to ("100644" to "blob-other")),
                listOf(modified("docs/a.md", BEFORE_PATCH)),
            ),
            "a path" to Pair(
                mapOf(
                    "README.md" to ("100644" to "blob-readme"), "docs/a.md" to ("100644" to "blob-new"),
                    "other.md" to ("100644" to "blob-other"), "extra.md" to ("100644" to "blob-extra"),
                ),
                listOf(modified("docs/a.md", BEFORE_PATCH), ComparedFile("extra.md", "added", "@@ -0,0 +1 @@\n+extra\n")),
            ),
        )
        for ((name, case) in tampered) {
            val (tree, after) = case
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.git.commit(MERGE, listOf(old, MAIN), tree)
                h.compareOf(h.github.baseSha, old, listOf(modified("docs/a.md", BEFORE_PATCH)))
                h.compareOf(MAIN, MERGE, after)
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

    @Test
    fun aPathThatBothThePullRequestAndMainChangedIsNotUpdated() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainChangesSamePath()
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
            val held = Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_OVERLAPPING_PATHS")

            // Not requested, not an attempt, and not counted against the limits, however often the run is looked at.
            repeat(4) { assertEquals(held, h.runner().advance(runId)) }
            assertEquals(0, h.port.updateCalls)
            assertEquals(null, h.lastExpected())
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(old, h.pr().headSha)
        }
    }

    @Test
    fun aFailedOrTruncatedOverlapCheckIsNotAReasonToUpdate() = runBlocking {
        // main moves on another path, so the check would pass if it could be read.
        val failures: List<Pair<String, (Harness) -> Unit>> = listOf(
            "merge base" to { h -> h.port.mergeBaseFailure = GitHubFailureKind.PROTOCOL },
            "pull request tree truncated" to { h -> h.port.failingTrees[h.head()] = GitHubFailureKind.TREE_TRUNCATED },
            "main tree truncated" to { h -> h.port.failingTrees[MAIN] = GitHubFailureKind.TREE_TRUNCATED },
        )
        for ((name, breakCheck) in failures) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.registerMerge(MERGE, "blob-new")
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
                breakCheck(h)

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId), name)
                assertEquals(0, h.port.updateCalls, name)
                assertEquals(emptyList(), db.rebaseRows(runId), name)
                assertEquals(old, h.binding(runId), name)
            }
        }
    }

    @Test
    fun theOverlapCheckIsReadOncePerPairOfHeads() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            h.mainChangesSamePath()
            val held = Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_OVERLAPPING_PATHS")

            assertEquals(held, h.runner().advance(runId))
            assertEquals(1, h.port.mergeBaseCalls)
            assertEquals(3, h.port.treeCalls)

            // The same pair of heads: the next looks read the base head only, not the merge base or the trees again.
            repeat(3) { assertEquals(held, h.runner().advance(runId)) }
            assertEquals(1, h.port.mergeBaseCalls)
            assertEquals(3, h.port.treeCalls)
        }
    }

    @Test
    fun aChangedLineThatDiffersInContentOrOrderOrCountIsRefused() = runBlocking {
        // The pull request's own patch, as the compare API reports it before the update.
        val before = "@@ -1,2 +1,2 @@\n-x\n-old\n+new\n+y\n"
        val after = mapOf(
            "a + line differs" to "@@ -1,2 +1,2 @@\n-x\n-old\n+new\n+z\n",
            "the - lines are in another order" to "@@ -1,2 +1,2 @@\n-old\n-x\n+new\n+y\n",
            "the + lines are in another order" to "@@ -1,2 +1,2 @@\n-x\n-old\n+y\n+new\n",
            "a + line is added" to "@@ -1,2 +1,3 @@\n-x\n-old\n+new\n+y\n+w\n",
            "a - line is removed" to "@@ -1,2 +1,1 @@\n-x\n+new\n+y\n",
            "a no-newline marker is added" to "@@ -1,2 +1,2 @@\n-x\n-old\n+new\n+y\n\\ No newline at end of file\n",
        )
        for ((name, patch) in after) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.registerMerge(MERGE, "blob-tampered")
                h.compareOf(h.github.baseSha, old, listOf(modified("docs/a.md", before)))
                h.compareOf(MAIN, MERGE, listOf(modified("docs/a.md", patch)))
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_DIFF_CHANGED"), h.runner().advance(runId), name)
                assertEquals(old, h.binding(runId), name)
            }
        }
    }

    @Test
    fun aChangeWhoseStatusPatchOrSizeIsNotComparableIsRefused() = runBlocking {
        val big = "x".repeat(140_000)
        val cases = mapOf(
            "a renamed file" to Pair(
                listOf(ComparedFile("docs/a.md", "renamed", BEFORE_PATCH)),
                listOf(ComparedFile("docs/a.md", "renamed", BEFORE_PATCH)),
            ),
            "a copied file" to Pair(
                listOf(ComparedFile("docs/a.md", "copied", BEFORE_PATCH)),
                listOf(ComparedFile("docs/a.md", "copied", BEFORE_PATCH)),
            ),
            "a status that differs between the two sides" to Pair(
                listOf(modified("docs/a.md", BEFORE_PATCH)),
                listOf(ComparedFile("docs/a.md", "removed", BEFORE_PATCH)),
            ),
            "no patch before" to Pair(listOf(modified("docs/a.md", null)), listOf(modified("docs/a.md", BEFORE_PATCH))),
            "no patch after" to Pair(listOf(modified("docs/a.md", BEFORE_PATCH)), listOf(modified("docs/a.md", null))),
            "the patches are too large in total" to Pair(
                listOf(modified("docs/a.md", "-old\n+$big\n")),
                listOf(modified("docs/a.md", "-old\n+$big\n")),
            ),
            "300 files (the list may be cut)" to Pair(
                List(300) { ComparedFile("f$it.md", "added", "+x\n") },
                List(300) { ComparedFile("f$it.md", "added", "+x\n") },
            ),
        )
        for ((name, sides) in cases) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.registerMerge(MERGE, "blob-tampered")
                h.compareOf(h.github.baseSha, old, sides.first)
                h.compareOf(MAIN, MERGE, sides.second)
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_DIFF_CHANGED"), h.runner().advance(runId), name)
                assertEquals(old, h.binding(runId), name)
            }
        }
    }

    @Test
    fun aCompareThatCannotBeReadIsVerifyFailedNotAccepted() = runBlocking {
        for (failure in listOf<GitHubFailureKind?>(GitHubFailureKind.NETWORK, GitHubFailureKind.TOO_LARGE, GitHubFailureKind.PROTOCOL, null)) {
            TestDb().use { db ->
                val h = Harness(db)
                val runId = h.openedRun()
                val old = h.head()
                h.mainMoves()
                h.registerMerge(MERGE, "blob-tampered")
                // With null nothing is registered for the pair: the read answers not found.
                if (failure != null) h.port.compareFilesFailure = failure
                h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

                assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFY_FAILED"), h.runner().advance(runId), "$failure")
                assertEquals(old, h.binding(runId))
            }
        }
    }

    @Test
    fun aMergedPullRequestWhoseChangedLinesAreTheSameIsRecordedAndCompleted() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            // main changed the same file in another place: the blobs differ, but the pull request's own lines do not.
            h.mainChangesSamePath()
            h.git.commit(MERGE, listOf(old, MAIN), BASE_TREE + ("docs/a.md" to ("100644" to "blob-merged")))
            h.compareOf(h.github.baseSha, old, listOf(modified("docs/a.md", BEFORE_PATCH)))
            // The same two lines, under another hunk header with a context line that main added.
            h.compareOf(MAIN, MERGE, listOf(modified("docs/a.md", "@@ -1,2 +1,2 @@\n main-line\n-old\n+new\n")))
            h.github.setPullHead(h.pr().number, MERGE)
            h.humanMerges()
            db.prAuthority.answer = { b -> FakeTrustedPrMergeAuthority.merged(b).copy(headSha = h.pr().headSha) }

            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(0, h.port.updateCalls)
            assertEquals(listOf(PR_REBASE_REBOUND), db.rebaseRows(runId).map { it.first })
            assertEquals(2, h.port.compareFilesCalls)
        }
    }

    @Test
    fun aMergedPullRequestWhoseChangedLinesDifferIsNotRecorded() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db)
            val runId = h.openedRun()
            val old = h.head()
            h.mainChangesSamePath()
            h.git.commit(MERGE, listOf(old, MAIN), BASE_TREE + ("docs/a.md" to ("100644" to "blob-merged")))
            h.compareOf(h.github.baseSha, old, listOf(modified("docs/a.md", BEFORE_PATCH)))
            h.compareOf(MAIN, MERGE, listOf(modified("docs/a.md", "@@ -1 +1 @@\n-main\n+new\n")))
            h.github.setPullHead(h.pr().number, MERGE)
            h.humanMerges()
            db.prAuthority.answer = { b -> FakeTrustedPrMergeAuthority.merged(b).copy(headSha = h.pr().headSha) }

            repeat(2) { assertEquals(Waiting(WaitReason.MERGE_NOT_VERIFIED, "HEAD_SHA_MISMATCH"), h.runner().advance(runId)) }
            assertEquals(old, h.binding(runId))
            assertEquals(emptyList(), db.rebaseRows(runId))
        }
    }

    private fun Harness.lastExpected(): String? = port.lastExpectedHead

    @Test
    fun aLookWithTheMinimalReadLimitStillReboundsAsBefore() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, maxReads = PrRebaseSettings.MIN_MAX_READS_PER_TICK)
            val runId = h.openedRun()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(1, h.port.updateCalls)
        }
    }

    @Test
    fun aLookThatCannotFinishItsUpdateInTheTickIsDeferredAndWritesNothing() = runBlocking {
        TestDb().use { db ->
            // Ten reads cover the seven before the request, but not the polls and the verification that must follow it.
            val h = Harness(db, maxReads = 10)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
            val waiting = Waiting(WaitReason.AWAITING_HUMAN_REVIEW)

            repeat(2) { assertEquals(waiting, h.runner().advance(runId)) }
            assertEquals(0, h.port.updateCalls)
            assertEquals(emptyList(), db.rebaseRows(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(old, h.pr().headSha)
        }
    }

    @Test
    fun aDeferredLookIsDoneInALaterTickWithItsOwnBudget() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, maxReads = 10)
            val runId = h.openedRun()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new")
            h.port.onUpdate = { number, _ -> h.github.setPullHead(number, MERGE) }
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))
            assertEquals(0, h.port.updateCalls)

            // The next tick has its own budget, and a budget that covers the whole update lets the same look finish it.
            h.setReadLimit(PrRebaseSettings.MIN_MAX_READS_PER_TICK)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(1, h.port.updateCalls)
        }
    }

    @Test
    fun aDeferredCheckOfAHeadMovedByAPersonRecordsNothingAndIsDoneLater() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, maxReads = 3)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new", parents = listOf(old, MAIN))
            h.github.setPullHead(h.pr().number, MERGE)

            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(emptyList(), db.rebaseRows(runId))

            h.setReadLimit(PrRebaseSettings.MIN_MAX_READS_PER_TICK)
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW, "PR_REBASE_VERIFIED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
            assertEquals(listOf(PR_REBASE_REBOUND), db.rebaseRows(runId).map { it.first })
        }
    }

    @Test
    fun aMergedPullRequestWhoseCheckIsDeferredWaitsAndIsCompletedInALaterTick() = runBlocking {
        TestDb().use { db ->
            val h = Harness(db, maxReads = 3)
            val runId = h.openedRun()
            val old = h.head()
            h.mainMoves()
            h.registerMerge(MERGE, "blob-new", parents = listOf(old, MAIN))
            h.github.setPullHead(h.pr().number, MERGE)
            h.humanMerges()
            db.prAuthority.answer = { b -> FakeTrustedPrMergeAuthority.merged(b).copy(headSha = h.pr().headSha) }

            // Deferred: the run is not completed on the old head, and nothing is recorded.
            assertEquals(Waiting(WaitReason.AWAITING_HUMAN_REVIEW), h.runner().advance(runId))
            assertEquals(old, h.binding(runId))
            assertEquals(emptyList(), db.rebaseRows(runId))

            h.setReadLimit(PrRebaseSettings.MIN_MAX_READS_PER_TICK)
            assertEquals(Advanced(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.COMPLETED, "PR_MERGED"), h.runner().advance(runId))
            assertEquals(MERGE, h.binding(runId))
        }
    }

    private fun modified(path: String, patch: String?) = ComparedFile(path, "modified", patch)

    private companion object {
        /** The pull request's own change to `docs/a.md`: one line replaced. */
        const val BEFORE_PATCH = "@@ -1 +1 @@\n-old\n+new\n"

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
