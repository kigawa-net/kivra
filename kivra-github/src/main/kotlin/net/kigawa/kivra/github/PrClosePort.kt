package net.kigawa.kivra.github

/**
 * Port for closing a self-development pull request whose review timed out (ADR 0008 追補 2026-11-18,
 * `KIVRA_SELFDEV_PR_ABANDON_CLOSE_ENABLED`).
 *
 * It is deliberately NOT part of [GitHubPort], which is pinned by reflection to have no close, update, merge or force operation (see
 * [PrBranchPort] for the same reason). This port has one call, and the caller may use it only for a run's own bound pull request opened by
 * kivra's App bot (see `PrAbandonCloser`).
 */
interface PrClosePort {
    /**
     * Closes pull request [number] without merging it. The branch is left as it is. PATCH is sent once; an unknown outcome is `Unknown`
     * and is not re-sent here (the caller reads the pull request again). Closing a pull request that is already closed is harmless. The
     * answer must name the same pull request and say `closed`, or it is a failure.
     */
    suspend fun closePullRequest(repo: RepoRef, number: Int): GitHubResult<Unit>
}
