package net.kigawa.kivra.selfdev.automerge

import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.BranchInfo
import net.kigawa.kivra.github.CheckRunsSummary
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PrBranchPort
import net.kigawa.kivra.github.PrRebaseView
import net.kigawa.kivra.github.PullRequestFiles
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RepoTree

/*
 * ADR 0014 S1 (the shadow judgement). These types are read only: the port has no method that writes, merges, labels, comments or moves a
 * branch, and the watch that uses it only records the judgement in the audit. Nothing here calls the merge API.
 */

/** The GitHub reads of the shadow judgement. Every method is a read (GET). Failures are values, never exceptions. */
interface AutoMergeShadowPort {
    /** The pull request with its `mergeable` and `mergeable_state`, and its labels. */
    suspend fun pullRequest(repo: RepoRef, number: Int): GitHubResult<PrRebaseView>

    /** The files of the pull request, at most 300 (see [PullRequestFiles.truncated]). */
    suspend fun pullRequestFiles(repo: RepoRef, number: Int): GitHubResult<PullRequestFiles>

    /** The recursive tree of a commit, for the file modes. */
    suspend fun tree(repo: RepoRef, commitSha: String): GitHubResult<RepoTree>

    /** The check runs of one commit (the caller compares the SHA it asked for). */
    suspend fun checkRuns(repo: RepoRef, sha: String): GitHubResult<CheckRunsSummary>

    /** The head of a branch, or null when the branch does not exist. */
    suspend fun branchHead(repo: RepoRef, branch: String): GitHubResult<BranchInfo?>
}

/**
 * The production reads, over the GitHub adapter. It calls only the read methods of [GitHubPort] and [PrBranchPort]. Those ports also
 * carry writes; this class never calls them.
 */
class GitHubAutoMergeShadowPort(private val github: GitHubPort, private val prs: PrBranchPort) : AutoMergeShadowPort {
    override suspend fun pullRequest(repo: RepoRef, number: Int): GitHubResult<PrRebaseView> = prs.getPrRebaseView(repo, number)

    override suspend fun pullRequestFiles(repo: RepoRef, number: Int): GitHubResult<PullRequestFiles> =
        prs.listPullRequestFiles(repo, number)

    override suspend fun tree(repo: RepoRef, commitSha: String): GitHubResult<RepoTree> = prs.listTreeAt(repo, commitSha)

    override suspend fun checkRuns(repo: RepoRef, sha: String): GitHubResult<CheckRunsSummary> = github.getCheckRuns(repo, sha)

    override suspend fun branchHead(repo: RepoRef, branch: String): GitHubResult<BranchInfo?> = github.getBranch(repo, branch)
}

/** The database side of the shadow judgement: the reads, and the one audit write. */
interface AutoMergeShadowStore {
    /** The ids of the self-development Runs in PULL_REQUEST, oldest first, at most [limit]. */
    fun pullRequestRunIds(limit: Int): List<RunId>

    /** The state, binding and task approval of [runId]; null when the Run does not exist. */
    fun runSnapshot(runId: RunId): ShadowRunSnapshot?

    /** The whole-system pause and breaker. */
    fun globalSnapshot(): ShadowGlobalSnapshot

    /** Appends one `AUTO_MERGE_JUDGED` event to the Run's audit chain. [payload] carries fixed codes and counts only. */
    fun recordJudgement(runId: RunId, verdict: String, payload: String)

    /** Appends one `AUTO_MERGE_SHADOW_PAUSED` event: the judgement was skipped because the automatic merge is paused (ADR 0014 U-10). */
    fun recordPaused(runId: RunId)
}

data class ShadowRunSnapshot(val state: DevelopmentRunState, val binding: AutoMergeBinding?, val approval: AutoMergeTaskApproval?)

/** [autoMergePaused] is the per-feature pause (V31, ADR 0014 U-10). It defaults to false for the callers that do not read it. */
data class ShadowGlobalSnapshot(val selfDevPaused: Boolean, val breakerOpen: Boolean, val autoMergePaused: Boolean = false)

/** The settings of the shadow judgement. [requiredChecks] is the configured name list (ADR 0014 U-3). */
data class AutoMergeShadowSettings(
    val repo: RepoRef,
    val baseBranch: String,
    val botAuthorId: Long,
    val requiredChecks: Set<String>,
)

/** The audit action of one judgement (ADR 0014 section 4). */
const val AUTO_MERGE_JUDGED = "AUTO_MERGE_JUDGED"

/** The audit action of a Run whose judgement was skipped while the automatic merge is paused (ADR 0014 U-10). Result is `PAUSED`. */
const val AUTO_MERGE_SHADOW_PAUSED = "AUTO_MERGE_SHADOW_PAUSED"

/** The fixed codes of a read that failed. They are recorded in the audit, and they are never a GitHub message. */
object ShadowReadCode {
    const val PR_UNREADABLE = "PR_UNREADABLE"
    const val PR_NOT_OPEN = "PR_NOT_OPEN"
    const val FILES_UNREADABLE = "FILES_UNREADABLE"
    const val TREE_UNREADABLE = "TREE_UNREADABLE"
    const val CHECKS_UNREADABLE = "CHECKS_UNREADABLE"
    const val MAIN_CI_UNREADABLE = "MAIN_CI_UNREADABLE"
    const val READ_BUDGET_EXHAUSTED = "READ_BUDGET_EXHAUSTED"
}

/**
 * Counts the GitHub reads of one tick. Past [limit] every read fails at once, which the judgement treats as unreadable (fail-closed).
 * The count is of port calls; one port call may be more than one HTTP request.
 */
class ShadowReadMeter(private val limit: Int) {
    var used: Int = 0
        private set

    suspend fun <T> read(call: suspend () -> GitHubResult<T>): GitHubResult<T> {
        if (used >= limit) return GitHubResult.Failure(GitHubFailureKind.RATE_LIMITED, ShadowReadCode.READ_BUDGET_EXHAUSTED)
        used++
        return call()
    }
}

/**
 * One judgement, as the audit records it. [verdict] is `ELIGIBLE`, `HOLD` or `REFUSE`. [reason] is an [net.kigawa.kivra.selfdev.automerge.AutoMergeReason]
 * name, a [ShadowReadCode] or a shadow code (`PR_NOT_OPEN`), and empty for `ELIGIBLE`. Carries no title, body, path or content.
 */
data class ShadowJudgement(
    val prNumber: Int,
    val headSha: String,
    val verdict: String,
    val reason: String,
    val files: Int,
    val lines: Long,
    val checks: List<String>,
    val readFailures: List<String>,
) {
    /** The part of the judgement that decides whether it is new (see [AutoMergeShadowWatch]). */
    val code: String get() = "$verdict:$reason"
}
