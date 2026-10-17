package net.kigawa.kivra.github

/**
 * Read of one issue (Issue #229 addendum). Only what the task-reference resolver needs: the number, title, body and state, and whether
 * GitHub reports it as a pull request (the issues endpoint answers for pull requests too). Comments, labels and the author are not read.
 */
data class GitHubIssue(
    val repo: RepoRef,
    val number: Long,
    val title: String,
    /** Empty when the issue has no body (GitHub sends null). */
    val body: String,
    val open: Boolean,
    val isPullRequest: Boolean,
) {
    override fun toString(): String = "GitHubIssue(repo=$repo, number=$number, open=$open, pullRequest=$isPullRequest, chars=${body.length})"
}

/**
 * Read-only access to one issue. Separate from [GitHubPort] so that the write port and its fakes stay as they are. The implementation
 * is bounded: a response above the size limit is `Failure(TOO_LARGE)`, redirects are never followed, and a 5xx or I/O failure is retried a
 * fixed number of times before it is returned.
 */
interface GitHubIssueReader {
    suspend fun getIssue(repo: RepoRef, number: Long): GitHubResult<GitHubIssue>
}
