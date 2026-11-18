package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.PR_ABANDON_CLOSED
import net.kigawa.kivra.db.PR_ABANDON_CLOSE_ATTEMPTED
import net.kigawa.kivra.db.PR_ABANDON_CLOSE_GAVE_UP
import net.kigawa.kivra.db.PR_ABANDON_COMMENTED
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PrClosePort
import net.kigawa.kivra.github.PullRequest
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RepoRef
import org.slf4j.LoggerFactory

/**
 * Closes the pull request of a run whose review timed out (ADR 0008 追補 2026-11-18), before the run is abandoned. Built only while
 * `KIVRA_SELFDEV_PR_ABANDON_CLOSE_ENABLED` is true; without it the runner abandons the run and leaves the pull request open, as before.
 *
 * Only a pull request that kivra's App bot opened and that is bound to the run (the same number and repository) is touched, and never a
 * merged one. Anything else is [Outcome.NotApplicable]: the run is abandoned as before, and the pull request is left alone.
 *
 * Order: GitHub first, then the run. One attempt, under the run's lease (renewed before each write):
 * 1. audits [PR_ABANDON_CLOSE_ATTEMPTED] (the attempts are counted from these rows, so a crash after the write still counts);
 * 2. closes the pull request (the branch stays). Refused or unknown: [Outcome.Retry], the run stays in PULL_REQUEST, and the next call reads
 *    the pull request again. A close that landed although its answer was lost is then seen as closed, and is not sent again;
 * 3. once it is closed (now, or seen closed after an earlier attempt): audits [PR_ABANDON_CLOSED] and posts the fixed comment, unless the
 *    trail already has [PR_ABANDON_COMMENTED]. A comment that was posted, or whose outcome is unknown, is audited as commented and never
 *    posted again. A refused comment is logged and not retried: the run is abandoned anyway (the comment only explains the close).
 *
 * After [maxAttempts] attempts that did not close it, [Outcome.GaveUp]: [PR_ABANDON_CLOSE_GAVE_UP] is audited and the run is abandoned with
 * the pull request left open (as before this feature), so a lasting refusal (for example a missing permission) never keeps a run waiting
 * forever. Logs and audit rows carry the run id, the pull request number and fixed codes only.
 */
class PrAbandonCloser(
    private val repo: RepoRef,
    /** The App bot's user id. A pull request opened by anyone else is never closed. */
    private val botUserId: Long,
    private val github: GitHubPort,
    /** The one close call (kept out of [GitHubPort] on purpose). */
    private val closer: PrClosePort,
    private val runs: DevelopmentRunStore,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(botUserId > 0) { "botUserId must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    sealed interface Outcome {
        /** Not kivra's bound pull request, merged, or closed by a person: nothing was written. The run is abandoned as before. */
        data object NotApplicable : Outcome

        /** The pull request is closed. The run is abandoned now. */
        data object Closed : Outcome

        /** This attempt did not close it. The run stays; the next call tries again. [code] is a fixed code. */
        data class Retry(val code: String) : Outcome

        /** The attempts ran out. The run is abandoned with the pull request left as it is. */
        data object GaveUp : Outcome
    }

    suspend fun closeTimedOut(lease: Lease, run: DevelopmentRun, pullRequest: PullRequest, renew: () -> Unit): Outcome {
        if (pullRequest.merged) return Outcome.NotApplicable
        if (pullRequest.repo != repo || pullRequest.authorId != botUserId) return Outcome.NotApplicable
        val binding = runs.loadBinding(run.runId) ?: return Outcome.NotApplicable
        if (binding.prNumber != pullRequest.number.toLong() || !binding.repository.equals(repo.toString(), ignoreCase = true)) {
            return Outcome.NotApplicable
        }
        val number = binding.prNumber
        val trail = runs.prAbandonCloseTrail(run.runId)
        if (pullRequest.state != PullRequestState.OPEN) {
            // Closed by an earlier attempt whose answer was lost: finish it. Closed by a person: kivra never tried, nothing to do.
            if (trail.attempts == 0) return Outcome.NotApplicable
            return closed(lease, run, pullRequest, number, trail.commented, renew)
        }
        if (trail.attempts >= maxAttempts) {
            renew()
            runs.recordPrAbandonClose(lease, PR_ABANDON_CLOSE_GAVE_UP, number, GAVE_UP)
            logger.warn("timed-out pull request left open: runId={} prNumber={} code={}", run.runId.value, number, GAVE_UP)
            return Outcome.GaveUp
        }
        renew()
        runs.recordPrAbandonClose(lease, PR_ABANDON_CLOSE_ATTEMPTED, number, null)
        renew()
        return when (val r = closer.closePullRequest(repo, pullRequest.number)) {
            is GitHubResult.Success -> closed(lease, run, pullRequest, number, trail.commented, renew)
            is GitHubResult.Failure -> retry(run, number, r.kind.code)
            is GitHubResult.Unknown -> retry(run, number, CLOSE_UNKNOWN)
        }
    }

    private suspend fun closed(lease: Lease, run: DevelopmentRun, pullRequest: PullRequest, number: Long, commented: Boolean, renew: () -> Unit): Outcome {
        renew()
        runs.recordPrAbandonClose(lease, PR_ABANDON_CLOSED, number, CLOSED_TIMEOUT)
        logger.info("timed-out pull request closed: runId={} prNumber={} code={}", run.runId.value, number, CLOSED_TIMEOUT)
        if (!commented) {
            renew()
            when (val c = github.createPullRequestComment(repo, pullRequest.number, commentBody(run.runId.value))) {
                is GitHubResult.Success -> runs.recordPrAbandonClose(lease, PR_ABANDON_COMMENTED, number, null)
                // The comment may have been posted: it is never posted again.
                is GitHubResult.Unknown -> runs.recordPrAbandonClose(lease, PR_ABANDON_COMMENTED, number, COMMENT_UNKNOWN)
                is GitHubResult.Failure ->
                    logger.warn("timed-out pull request comment not posted: runId={} prNumber={} code={}", run.runId.value, number, c.kind.code)
            }
        }
        return Outcome.Closed
    }

    private fun retry(run: DevelopmentRun, number: Long, code: String): Outcome {
        logger.warn("timed-out pull request not closed yet: runId={} prNumber={} code={}", run.runId.value, number, code)
        return Outcome.Retry(code)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PrAbandonCloser::class.java)

        /** Attempts before the run is abandoned with the pull request left open. One attempt per runner call. */
        const val DEFAULT_MAX_ATTEMPTS = 5

        const val CLOSED_TIMEOUT = "PR_CLOSED_TIMEOUT"
        const val GAVE_UP = "PR_CLOSE_GAVE_UP"
        const val CLOSE_UNKNOWN = "PR_CLOSE_UNKNOWN"
        const val COMMENT_UNKNOWN = "PR_COMMENT_UNKNOWN"

        /** The fixed comment. It names the run only: never the task, the change, or a reason text. */
        fun commentBody(runId: String): String =
            "レビュー待ちの期限が過ぎたため、kivra がこの PR を閉じました(Run $runId)。\n" +
                "ブランチは残しています。続ける場合は、新しい Run として依頼してください。"
    }
}
