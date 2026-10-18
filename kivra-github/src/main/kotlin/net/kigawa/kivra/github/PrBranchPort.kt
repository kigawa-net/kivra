package net.kigawa.kivra.github

/**
 * Port for updating the branch of a self-development pull request (issue #276, the base-follow feature).
 *
 * It is deliberately NOT part of [GitHubPort]. [GitHubPort] is pinned by reflection to have no update, merge or force operation, and
 * adding one there is a design change. This port exists for one call, `PUT /pulls/{n}/update-branch`, and the caller may use it only for
 * a run's own pull request, and only after it verifies the new head (see `PrBranchRebaser`). Its other methods are read-only.
 */
interface PrBranchPort {
    /** Read-only. The pull request (see [GitHubPort.getPullRequest]) with its `mergeable_state` (`behind`, `dirty`, `clean`, ...). */
    suspend fun getPrRebaseView(repo: RepoRef, number: Int): GitHubResult<PrRebaseView>

    /**
     * `PUT /pulls/{n}/update-branch` with `expected_head_sha`. GitHub answers 202 and performs the update asynchronously, so a success
     * does not yet show the new head: the caller reads the pull request again. A 409 or 422 (the head moved, or a merge conflict) is
     * [GitHubFailureKind.CONFLICT]. A transport failure or 5xx is [GitHubResult.Unknown] and is never re-sent.
     */
    suspend fun updatePrBranch(repo: RepoRef, number: Int, expectedHeadSha: String): GitHubResult<Unit>

    /** Read-only. The parents of [sha] (a 40-hex commit id), in git order. */
    suspend fun getCommitParents(repo: RepoRef, sha: String): GitHubResult<List<String>>

    /** Read-only. The merge base of two commits (40-hex ids). */
    suspend fun mergeBaseOf(repo: RepoRef, a: String, b: String): GitHubResult<String>

    /** Read-only. The recursive tree of a commit (40-hex id). A truncated tree is [GitHubFailureKind.TREE_TRUNCATED]. */
    suspend fun listTreeAt(repo: RepoRef, commitSha: String): GitHubResult<RepoTree>
}

/** A pull request with its `mergeable_state` as GitHub reports it. Null when GitHub has not computed it yet. */
data class PrRebaseView(val pullRequest: PullRequest, val mergeableState: String?)
