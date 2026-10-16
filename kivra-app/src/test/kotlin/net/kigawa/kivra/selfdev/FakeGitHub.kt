package net.kigawa.kivra.selfdev

import net.kigawa.kivra.github.BranchInfo
import net.kigawa.kivra.github.CheckRunsSummary
import net.kigawa.kivra.github.CombinedStatus
import net.kigawa.kivra.github.CommitFilesRequest
import net.kigawa.kivra.github.CommittedFiles
import net.kigawa.kivra.github.CreatePullRequest
import net.kigawa.kivra.github.EnsuredBranch
import net.kigawa.kivra.github.EnsuredPullRequest
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PullRequest
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RepoFileContent
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RepoTree
import net.kigawa.kivra.github.Review
import net.kigawa.kivra.github.RunBranch

/** Thrown by [FakeGitHub] to simulate a process crash in the middle of a step. */
class CrashInjected(val op: String) : RuntimeException("crash injected at $op")

/** What the fake does with a commitFiles call when the test asks for a fault. */
enum class CommitFault {
    /** Confirmed not applied: the ref is not moved. Reported as the adapter reports it: Failure(NOT_APPLIED). */
    NOT_APPLIED,

    /** The ref is moved, but the response is lost. Reported as Unknown with an unrelated cause. */
    UNKNOWN_MOVED,

    /** The ref is not moved, but the read-back failed, so the outcome is unknown. Reported as Unknown, never NOT_APPLIED. */
    UNKNOWN_NOT_CONFIRMED,

    /** Another writer moved the branch. Reported as Failure(CONFLICT) with no write. */
    CONFLICT,
}

/**
 * In-memory GitHub double for the runner tests. It keeps branches, commits and pull requests, and records every write in [writes]
 * so that the tests can count provider side effects. There is no merge operation, as in [GitHubPort].
 * Any call with a repository other than [repo] returns NOT_FOUND. Shas are sequential, so runs are deterministic.
 */
/** The App bot's numeric user id as the fake reports it for pull requests it creates. */
const val BOT_USER_ID = 9001L

class FakeGitHub(val repo: RepoRef = RepoRef("kigawa-net", "kivra"), val baseSha: String = sha(1)) : GitHubPort {
    private val branches = LinkedHashMap<String, BranchInfo>()
    private val commits = ArrayList<String>()
    private val pulls = ArrayList<PullRequest>()
    private var counter = 100

    /** Author id given to pull requests created from now on (the App bot by default). Null simulates a response without an author. */
    var prAuthorId: Long? = BOT_USER_ID

    /** Changes the author of an existing pull request, to simulate a PR opened by someone else. */
    fun setPullAuthor(number: Int, authorId: Long?) = replacePull(number) { it.copy(authorId = authorId) }

    /** Write operations in call order: createRef, commit, updateRef, createPr. */
    val writes = ArrayList<String>()

    /** Every call of every kind, reads included. Used to prove a disabled runner does not touch the provider at all. */
    var totalCalls = 0
        private set

    /** Injected faults, consumed one per commitFiles call. */
    val commitFaults = ArrayDeque<CommitFault>()

    /** Number of commitFiles calls, faulted or not. */
    var commitCalls = 0
        private set

    /** When set, the next write of that operation performs the write and then throws [CrashInjected]. */
    var crashAfter: String? = null

    /** When set, the next call of that operation throws [CrashInjected] before doing anything. */
    var crashBefore: String? = null

    /** Commit shas, in order, for the branch-level assertions. */
    val commitsOnBranch: List<String> get() = commits.toList()

    init {
        branches[BASE_BRANCH] = BranchInfo(repo, BASE_BRANCH, baseSha)
    }

    fun pullRequests(): List<PullRequest> = pulls.toList()

    /** The human review step: marks the pull request as merged, the way GitHub would after a person presses merge. */
    fun humanMerges(number: Int) = replacePull(number) { it.copy(state = PullRequestState.CLOSED, merged = true, mergeCommitSha = sha(900 + number)) }

    /** The human review step: closes the pull request without merging it. */
    fun humanCloses(number: Int) = replacePull(number) { it.copy(state = PullRequestState.CLOSED, merged = false) }

    private fun replacePull(number: Int, f: (PullRequest) -> PullRequest) {
        val index = pulls.indexOfFirst { it.number == number }
        pulls[index] = f(pulls[index])
    }

    private fun touch(op: String) {
        totalCalls++
        if (crashBefore == op) {
            crashBefore = null
            throw CrashInjected(op)
        }
    }

    private fun afterWrite(op: String) {
        if (crashAfter == op) {
            crashAfter = null
            throw CrashInjected(op)
        }
    }

    private fun foreign(): GitHubResult<Nothing> = GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, GitHubFailureKind.NOT_FOUND.code)

    override suspend fun getBranch(repo: RepoRef, branch: String): GitHubResult<BranchInfo?> {
        touch("getBranch")
        if (repo != this.repo) return foreign()
        return GitHubResult.Success(branches[branch])
    }

    override suspend fun createBranch(repo: RepoRef, branch: String, baseSha: String): GitHubResult<BranchInfo> {
        touch("createBranch")
        if (repo != this.repo) return foreign()
        if (branches.containsKey(branch)) return GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)
        val info = BranchInfo(repo, branch, baseSha)
        branches[branch] = info
        writes += "createRef"
        afterWrite("createRef")
        return GitHubResult.Success(info)
    }

    override suspend fun ensureRunBranch(repo: RepoRef, runId: String, baseSha: String): GitHubResult<EnsuredBranch> {
        touch("ensureRunBranch")
        if (repo != this.repo) return foreign()
        val name = RunBranch.name(runId)
        branches[name]?.let { return GitHubResult.Success(EnsuredBranch(it, created = false)) }
        val info = BranchInfo(repo, name, baseSha)
        branches[name] = info
        writes += "createRef"
        afterWrite("createRef")
        return GitHubResult.Success(EnsuredBranch(info, created = true))
    }

    override suspend fun createPullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<PullRequest> =
        when (val r = ensurePullRequest(repo, request)) {
            is GitHubResult.Success -> GitHubResult.Success(r.value.pullRequest)
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }

    override suspend fun getPullRequest(repo: RepoRef, number: Int): GitHubResult<PullRequest> {
        touch("getPullRequest")
        if (repo != this.repo) return foreign()
        return pulls.firstOrNull { it.number == number }?.let { GitHubResult.Success(it) } ?: foreign()
    }

    override suspend fun findPullRequestsByHead(repo: RepoRef, headBranch: String): GitHubResult<List<PullRequest>> {
        touch("findPullRequestsByHead")
        if (repo != this.repo) return foreign()
        return GitHubResult.Success(pulls.filter { it.headRef == headBranch })
    }

    override suspend fun ensurePullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<EnsuredPullRequest> {
        touch("ensurePullRequest")
        if (repo != this.repo) return foreign()
        val existing = pulls.filter { it.headRef == request.head && it.state == PullRequestState.OPEN }
        if (existing.size > 1) return GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)
        existing.singleOrNull()?.let { pr ->
            return if (pr.hasMarker(request.runId) && pr.headSha == request.headSha && pr.baseRef == request.base) {
                GitHubResult.Success(EnsuredPullRequest(pr, created = false))
            } else {
                GitHubResult.Failure(GitHubFailureKind.IDENTITY_MISMATCH, GitHubFailureKind.IDENTITY_MISMATCH.code)
            }
        }
        val number = ++counter
        val pr = PullRequest(
            repo = repo, number = number, state = PullRequestState.OPEN, merged = false, draft = request.draft,
            headRef = request.head, headSha = request.headSha, baseRef = request.base,
            body = request.renderedBody(), htmlUrl = "https://example.invalid/pull/$number",
            nodeId = "PR_node_$number", baseRepositoryId = 42L, authorId = prAuthorId,
        )
        writes += "createPr"
        pulls += pr
        afterWrite("createPr")
        return GitHubResult.Success(EnsuredPullRequest(pr, created = true))
    }

    override suspend fun getCheckRuns(repo: RepoRef, sha: String): GitHubResult<CheckRunsSummary> = unused()

    override suspend fun getCombinedStatus(repo: RepoRef, sha: String): GitHubResult<CombinedStatus> = unused()

    override suspend fun getReviews(repo: RepoRef, number: Int): GitHubResult<List<Review>> = unused()

    override suspend fun listTree(repo: RepoRef, ref: String): GitHubResult<RepoTree> = unused()

    override suspend fun readFile(repo: RepoRef, ref: String, path: String, maxBytes: Int): GitHubResult<RepoFileContent?> = unused()

    override suspend fun commitFiles(repo: RepoRef, request: CommitFilesRequest): GitHubResult<CommittedFiles> {
        touch("commitFiles")
        if (repo != this.repo) return foreign()
        val head = branches[request.branch]?.headSha ?: return foreign()
        val fault = commitFaults.removeFirstOrNull()
        commitCalls++
        when (fault) {
            CommitFault.NOT_APPLIED -> return GitHubResult.Failure(GitHubFailureKind.NOT_APPLIED, GitHubFailureKind.NOT_APPLIED.code)
            CommitFault.UNKNOWN_NOT_CONFIRMED -> return GitHubResult.Unknown("updateRef", "reconcile read failed")
            CommitFault.CONFLICT -> return GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)
            else -> Unit
        }
        if (head != request.expectedHeadSha) return GitHubResult.Failure(GitHubFailureKind.CONFLICT, GitHubFailureKind.CONFLICT.code)
        val commitSha = sha(++counter)
        commits += commitSha
        branches[request.branch] = BranchInfo(repo, request.branch, commitSha)
        writes += "commit"
        if (fault == CommitFault.UNKNOWN_MOVED) return GitHubResult.Unknown("updateRef", "response lost")
        afterWrite("commit")
        return GitHubResult.Success(CommittedFiles(commitSha, sha(++counter), created = true))
    }

    private fun <T> unused(): GitHubResult<T> = GitHubResult.Failure(GitHubFailureKind.VALIDATION, "unused in self-development tests")

    companion object {
        const val BASE_BRANCH = "main"

        /** A 40-character lowercase hex sha built from [n]. */
        fun sha(n: Int): String = n.toString(16).padStart(40, '0')
    }
}
