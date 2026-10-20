package net.kigawa.kivra.github

/**
 * Port for updating the branch of a self-development pull request (issue #276, the base-follow feature).
 *
 * It is deliberately NOT part of [GitHubPort]. [GitHubPort] is pinned by reflection to have no update, merge or force operation, and
 * adding one there is a design change. This port exists for one call, `PUT /pulls/{n}/update-branch`, and the caller may use it only for
 * a run's own pull request, and only after it verifies the new head (see `PrBranchRebaser`). Its other methods are read-only.
 */
interface PrBranchPort {
    /**
     * Read-only. The pull request (see [GitHubPort.getPullRequest]) with its `mergeable` and `mergeable_state` as GitHub reports them.
     * `mergeable_state` is `behind` only when the base branch has the "require up to date" protection; use [getBehindBy] for the
     * distance to the base.
     */
    suspend fun getPrRebaseView(repo: RepoRef, number: Int): GitHubResult<PrRebaseView>

    /**
     * Read-only. How many commits [base] has that [headSha] does not have (`behind_by` of `GET /compare/{base}...{head}`). Zero means
     * the head already contains the base. A response whose `behind_by` is not a non-negative integer is a protocol failure.
     */
    suspend fun getBehindBy(repo: RepoRef, base: String, headSha: String): GitHubResult<Int>

    /**
     * Read-only. How many commits [headSha] has that [baseSha] does not have (`ahead_by` of `GET /compare/{base}...{head}`, both 40-hex
     * commit ids). Zero means [headSha] is [baseSha] or one of its ancestors. A response whose `ahead_by` is not a non-negative integer is a
     * protocol failure.
     */
    suspend fun getAheadBy(repo: RepoRef, baseSha: String, headSha: String): GitHubResult<Int>

    /**
     * `PUT /pulls/{n}/update-branch` with `expected_head_sha`. GitHub answers 202 and performs the update asynchronously, so a success
     * does not yet show the new head: the caller reads the pull request again. A 409 or 422 (the head moved, or a merge conflict) is
     * [GitHubFailureKind.CONFLICT]. A transport failure or 5xx is [GitHubResult.Unknown] and is never re-sent.
     */
    suspend fun updatePrBranch(repo: RepoRef, number: Int, expectedHeadSha: String): GitHubResult<Unit>

    /**
     * Read-only. The changed files of `GET /compare/{base}...{head}` (both 40-hex commit ids), in the order GitHub lists them. [ComparedFile.patch]
     * is null when GitHub sent no patch (a binary or a very large diff). A response whose `files` is not an array, whose entries lack a
     * string `filename` or `status`, or whose `patch` is neither a string nor absent, is a protocol failure. A response over the size limit is
     * [GitHubFailureKind.TOO_LARGE].
     */
    suspend fun getCompareFiles(repo: RepoRef, base: String, head: String): GitHubResult<List<ComparedFile>>

    /** Read-only. The parents of [sha] (a 40-hex commit id), in git order. */
    suspend fun getCommitParents(repo: RepoRef, sha: String): GitHubResult<List<String>>

    /** Read-only. The merge base of two commits (40-hex ids). */
    suspend fun mergeBaseOf(repo: RepoRef, a: String, b: String): GitHubResult<String>

    /** Read-only. The recursive tree of a commit (40-hex id). A truncated tree is [GitHubFailureKind.TREE_TRUNCATED]. */
    suspend fun listTreeAt(repo: RepoRef, commitSha: String): GitHubResult<RepoTree>
}

/**
 * A pull request with `mergeable` and `mergeable_state` as GitHub reports them. [mergeable] is null while GitHub is still computing it,
 * and false on a conflict. [mergeableState] is null when the response does not carry it.
 */
data class PrRebaseView(val pullRequest: PullRequest, val mergeableState: String?, val mergeable: Boolean?)

/** One changed file of a compare answer: its path, its status as GitHub names it (for example `modified`), and its patch when sent. */
data class ComparedFile(val filename: String, val status: String, val patch: String?)
