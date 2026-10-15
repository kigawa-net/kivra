package net.kigawa.kivra.github

/**
 * GitHub Port (issue #34, design #9 section 5.3 / #11 section 4.4).
 *
 * Deliberately absent from this interface: merge, force push, deleting refs, and any write to the default branch.
 * Those are human-only in M3 (merge by SHA is M8). A test pins this by reflection; adding such a method here is a
 * design change, not a refactor. The one ref update, [commitFiles], is restricted to `kivra/run-<runId>` branches
 * and never forces (`force=false`).
 *
 * Idempotency contract: GitHub accepts no idempotency key. Branch name `kivra/run-<runId>` is deterministic,
 * and a PR is matched by its head branch. A POST whose outcome is unknown is NEVER re-sent by the adapter:
 * it returns [GitHubResult.Unknown] and the caller reconciles via [getBranch] / [findPullRequestsByHead].
 */
interface GitHubPort {
    /** Reads a branch. `Success(null)` when it does not exist. */
    suspend fun getBranch(repo: RepoRef, branch: String): GitHubResult<BranchInfo?>

    /** Creates a new branch at [baseSha]. An existing branch yields `Failure(CONFLICT)`; use [ensureRunBranch]. */
    suspend fun createBranch(repo: RepoRef, branch: String, baseSha: String): GitHubResult<BranchInfo>

    /**
     * Creates `kivra/run-<runId>` at [baseSha], or reuses it if it already exists (design #9 section 5.2).
     * An existing branch is returned as-is with `created=false`; the caller compares `headSha` itself.
     */
    suspend fun ensureRunBranch(repo: RepoRef, runId: String, baseSha: String): GitHubResult<EnsuredBranch>

    suspend fun createPullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<PullRequest>

    suspend fun getPullRequest(repo: RepoRef, number: Int): GitHubResult<PullRequest>

    /** Lists open and closed PRs whose head is [headBranch] in the same repository. */
    suspend fun findPullRequestsByHead(repo: RepoRef, headBranch: String): GitHubResult<List<PullRequest>>

    /**
     * Idempotent PR creation: search by deterministic head branch first, create only if none exists, and on an
     * unknown create outcome search again once. A PR on that head not carrying this run's marker is
     * `Failure(FOREIGN_OBJECT)` (isolate, never adopt). An open PR carrying the marker is adopted only when its head
     * sha equals `request.headSha` and its base ref equals `request.base`; otherwise `Failure(IDENTITY_MISMATCH)`,
     * and no PR is created. Several open PRs that match are a `Failure(CONFLICT)`.
     */
    suspend fun ensurePullRequest(repo: RepoRef, request: CreatePullRequest): GitHubResult<EnsuredPullRequest>

    /** Check runs for [sha]. `headSha` of every returned run is verified to equal [sha]. */
    suspend fun getCheckRuns(repo: RepoRef, sha: String): GitHubResult<CheckRunsSummary>

    /** Combined commit status for [sha]. */
    suspend fun getCombinedStatus(repo: RepoRef, sha: String): GitHubResult<CombinedStatus>

    suspend fun getReviews(repo: RepoRef, number: Int): GitHubResult<List<Review>>

    /**
     * Writes [CommitFilesRequest.changes] as one commit on `kivra/run-<runId>` (Git Data API: ref, commit, tree, blobs,
     * commit, ref update). Only run branches are accepted; anything else is `Failure(VALIDATION)` before any request.
     * `expectedHeadSha` differing from the live head is `Failure(CONFLICT)` with no write. A failed ref update whose
     * outcome is unknown is reconciled by reading the ref back; it is never re-sent. See [HttpGitHubAdapter.commitFiles].
     */
    suspend fun commitFiles(repo: RepoRef, request: CommitFilesRequest): GitHubResult<CommittedFiles>
}

/**
 * A GitHub owner/repo pair. Values are interpolated into API paths, so dot segments must never get through:
 * `.`, `..`, any value containing `..`, and a `.git` suffix are rejected (issue #138).
 */
data class RepoRef(val owner: String, val repo: String) {
    init {
        require(OWNER.matches(owner)) { "invalid owner: $owner" }
        require(REPO.matches(repo) && !repo.isUnsafeRepoName()) { "invalid repo: $repo" }
    }

    override fun toString() = "$owner/$repo"

    private companion object {
        /** GitHub user and organization names: alphanumerics and hyphens only. */
        val OWNER = Regex("[A-Za-z0-9-]{1,39}")
        /** GitHub repository names: alphanumerics, `.`, `_` and `-`. */
        val REPO = Regex("[A-Za-z0-9_.-]{1,100}")

        fun String.isUnsafeRepoName(): Boolean =
            this == "." || contains("..") || endsWith(".git", ignoreCase = true)
    }
}

object RunBranch {
    private val RUN_ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun name(runId: String): String {
        require(RUN_ID.matches(runId)) { "invalid runId: $runId" }
        return "kivra/run-$runId"
    }

    /** True only for a name that [name] could have produced. The only branch kind [GitHubPort.commitFiles] may write. */
    fun isRunBranch(branch: String): Boolean =
        branch.startsWith(PREFIX) && RUN_ID.matches(branch.removePrefix(PREFIX))

    private const val PREFIX = "kivra/run-"

    /** Machine-readable marker embedded in PR bodies; used to tell our PR from a foreign one on the same head. */
    fun marker(runId: String): String {
        require(RUN_ID.matches(runId)) { "invalid runId: $runId" }
        return "kivra-run:$runId"
    }
}

data class BranchInfo(val repo: RepoRef, val name: String, val headSha: String)

data class EnsuredBranch(val branch: BranchInfo, val created: Boolean)

/** [runId] determines head branch and body marker; [headSha] is recorded in the body (design #9 section 5.3). */
data class CreatePullRequest(
    val runId: String,
    val base: String,
    val title: String,
    val body: String,
    val headSha: String,
    val draft: Boolean = false,
) {
    val head: String get() = RunBranch.name(runId)
    fun renderedBody(): String = "$body\n\n${RunBranch.marker(runId)}\nkivra-head-sha:$headSha"
}

enum class PullRequestState { OPEN, CLOSED }

data class PullRequest(
    val repo: RepoRef,
    val number: Int,
    val state: PullRequestState,
    val merged: Boolean,
    val draft: Boolean,
    val headRef: String,
    val headSha: String,
    val baseRef: String,
    val body: String,
    val htmlUrl: String,
    /** GraphQL node id (`node_id`). Null when the response did not carry it; merge verification then fails closed. */
    val nodeId: String? = null,
    /** Immutable id of the base repository (`base.repo.id`). Null when absent; merge verification then fails closed. */
    val baseRepositoryId: Long? = null,
    /** Merge commit SHA (`merge_commit_sha`). Set by GitHub for merged PRs; never equated with [headSha]. */
    val mergeCommitSha: String? = null,
) {
    fun hasMarker(runId: String): Boolean = body.lines().any { it.trim() == RunBranch.marker(runId) }
}

data class EnsuredPullRequest(val pullRequest: PullRequest, val created: Boolean)

enum class CheckConclusion { SUCCESS, FAILURE, NEUTRAL, CANCELLED, SKIPPED, TIMED_OUT, ACTION_REQUIRED, STALE, OTHER }

data class CheckRun(
    val id: Long,
    val name: String,
    val headSha: String,
    val status: String,
    val conclusion: CheckConclusion?,
)

/** [complete] is false when pagination hit the safety cap, so callers must not treat the list as authoritative. */
data class CheckRunsSummary(val sha: String, val runs: List<CheckRun>, val totalCount: Int, val complete: Boolean) {
    /**
     * All runs finished and none failed. Empty is NOT success: Required Checks must be matched by the caller.
     *
     * `neutral` and `skipped` count as passed, which matches GitHub's branch protection: GitHub treats them as success.
     * This is a deliberate choice, not an oversight. For a merge decision, evaluate the Required Checks by name instead
     * of relying on this aggregate. Pinned by `CheckRunsConclusionTest`.
     */
    val allPassed: Boolean
        get() = complete && runs.isNotEmpty() && runs.all {
            it.status == "completed" &&
                (it.conclusion == CheckConclusion.SUCCESS || it.conclusion == CheckConclusion.NEUTRAL || it.conclusion == CheckConclusion.SKIPPED)
        }
}

data class CombinedStatus(val sha: String, val state: String, val contexts: Map<String, String>)

data class Review(val id: Long, val reviewer: String, val state: String, val commitId: String?)

enum class GitHubFailureKind {
    /** 401, or token could not be obtained. */
    AUTH,
    /** 403 without rate-limit signals: missing App permission / Ruleset. */
    PERMISSION,
    RATE_LIMITED,
    NOT_FOUND,
    /** 409, or 422 "already exists". */
    CONFLICT,
    /** Other 4xx (invalid request). */
    VALIDATION,
    /** 5xx on a GET after retries. */
    SERVER_ERROR,
    /** I/O failure on a GET after retries. */
    NETWORK,
    /** Response was about a different owner/repo/number/sha/branch than requested. */
    IDENTITY_MISMATCH,
    /** An existing PR/branch on our deterministic name is not ours. */
    FOREIGN_OBJECT,
    /** Response body unparseable. */
    PROTOCOL,
    /**
     * 3xx. Redirects are never followed (a bearer token must not reach another origin). On a POST this is
     * reported as [GitHubResult.Unknown] instead, because the server did receive the request.
     */
    REDIRECT,
    ;

    /** Fixed machine-readable code stored in [GitHubResult.Failure.message]. Never provider text. */
    val code: String get() = "GITHUB_$name"
}

sealed interface GitHubResult<out T> {
    data class Success<T>(val value: T) : GitHubResult<T>

    data class Failure(
        val kind: GitHubFailureKind,
        val message: String,
        val status: Int? = null,
        /** Seconds, when GitHub supplied `Retry-After` or `X-RateLimit-Reset`. */
        val retryAfterSeconds: Long? = null,
    ) : GitHubResult<Nothing>

    /**
     * A POST was sent (or may have been) but the outcome is unknown: timeout, I/O failure, or 5xx.
     * Never auto-resent. Reconcile by deterministic branch name before any retry.
     */
    data class Unknown(val operation: String, val cause: String) : GitHubResult<Nothing>
}

inline fun <T, R> GitHubResult<T>.map(f: (T) -> R): GitHubResult<R> = when (this) {
    is GitHubResult.Success -> GitHubResult.Success(f(value))
    is GitHubResult.Failure -> this
    is GitHubResult.Unknown -> this
}
