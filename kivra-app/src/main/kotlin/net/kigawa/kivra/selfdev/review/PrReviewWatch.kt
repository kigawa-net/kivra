package net.kigawa.kivra.selfdev.review

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PullRequest
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.Part
import org.slf4j.LoggerFactory

/**
 * The AI review of self-development pull requests (comment only). Every tick it looks at up to [MAX_RUNS_PER_TICK] Runs in PULL_REQUEST and,
 * for a pull request opened by the bot on the open state, reviews its current head once: the model reads the diff and the Run's task, and
 * the result is posted as a comment and recorded (the `selfdev_pr_reviews` row and the `PR_AI_REVIEW` audit event).
 *
 * It never approves, requests changes, merges, labels, edits or moves a branch. The only write is [GitHubPort.createPullRequestComment].
 * The result is not an input of the automatic merge decision (ADR 0014); it is only recorded.
 *
 * Idempotency: the row for (repository, pull request, head) is inserted before the model call. A head that has a row is never reviewed
 * again, whatever the row's state. Limits: per pull request and per hour (counted from the rows), and the diff size. The model's budget is the
 * gateway's (ADR 0004). A failure never stops the other Runs. Logs carry IDs and fixed codes only.
 */
class PrReviewWatch(
    private val settings: PrReviewSettings,
    private val store: PrReviewStore,
    private val github: GitHubPort,
    private val gateway: ModelGateway,
    private val repo: RepoRef,
    private val botUserId: Long,
    private val budgetScopes: List<String>,
    private val clock: Clock = Clock.systemUTC(),
    private val callTimeout: Duration = CALL_TIMEOUT,
    private val interval: Duration = INTERVAL,
    private val nonce: () -> String = PrReviewPrompt::newNonce,
    /** The difficulty router (ADR 0023 追補 2). Null keeps the review on the hosted model, as before. */
    private val router: net.kigawa.kivra.modelgateway.DifficultyRouter? = null,
) {
    /** What one Run's look came to: a comment posted, a review that did not post, a skip, or the hourly limit (ends the pass). */
    private enum class Step { POSTED, NOT_POSTED, SKIPPED, HOURLY_LIMIT }

    /** One pass. Returns how many comments were posted. A disabled watch does nothing and calls nothing. */
    suspend fun tick(): Int {
        if (!settings.enabled) return 0
        var posted = 0
        for (runId in store.pullRequestRunIds(MAX_RUNS_PER_TICK)) {
            try {
                when (reviewOne(runId)) {
                    Step.POSTED -> posted++
                    Step.NOT_POSTED -> Unit
                    Step.SKIPPED -> Unit
                    Step.HOURLY_LIMIT -> {
                        logger.info("pr review: hourly limit reached, pass ends")
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("pr review: review failed, exception={}", e.javaClass.simpleName)
            }
        }
        return posted
    }

    /** Runs [tick] every [interval] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("pr review: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(interval.toMillis())
        }
    }

    private suspend fun reviewOne(runId: RunId): Step {
        val bound = store.binding(runId) ?: return skip(runId, "NOT_BOUND")
        if (!bound.repository.equals(repo.toString(), ignoreCase = true)) return skip(runId, "OTHER_REPOSITORY")
        val number = bound.prNumber
        if (store.reviewsOfPr(repo, number) >= settings.maxPerPr) return skip(runId, "PR_LIMIT")
        if (store.reviewsSince(clock.instant().minus(Duration.ofHours(1))) >= settings.maxPerHour) return Step.HOURLY_LIMIT

        val pr = when (val r = github.getPullRequest(repo, number)) {
            is GitHubResult.Success -> r.value
            else -> return skip(runId, "PR_UNREADABLE")
        }
        if (!reviewable(pr)) return skip(runId, "NOT_BOT_OPEN_PR")
        val head = pr.headSha
        if (!SHA.matches(head)) return skip(runId, "HEAD_INVALID")
        if (!store.claim(repo, number, head, runId)) return skip(runId, "ALREADY_REVIEWED")
        return if (review(runId, number, head)) Step.POSTED else Step.NOT_POSTED
    }

    private fun reviewable(pr: PullRequest): Boolean =
        pr.state == PullRequestState.OPEN && !pr.merged && pr.authorId == botUserId

    /** Reads the diff, asks the model, and posts the comment. Every outcome is recorded once. True when the comment was posted. */
    private suspend fun review(runId: RunId, number: Int, head: String): Boolean {
        val diff = when (val r = github.getPullRequestDiff(repo, number, settings.maxDiffBytes)) {
            is GitHubResult.Success -> r.value
            is GitHubResult.Failure ->
                if (r.kind == GitHubFailureKind.TOO_LARGE) {
                    return finish(runId, number, head, PrReviewState.SKIPPED, "DIFF_TOO_LARGE", null, 0)
                } else {
                    return finish(runId, number, head, PrReviewState.FAILED, "DIFF_UNREADABLE", null, 0)
                }
            is GitHubResult.Unknown -> return finish(runId, number, head, PrReviewState.FAILED, "DIFF_UNREADABLE", null, 0)
        }
        val model = checkNotNull(settings.model) { "enabled review needs a model" }
        val task = store.task(runId) ?: PrReviewTask(title = "", body = null)
        val text = try {
            val base = PrReviewPrompt.request(number, head, model, budgetScopes, task, diff, nonce(), callTimeout)
            // The classifier reads the pull request's title and body only (never the diff).
            val routed = router?.let { r -> r.apply(base, r.decide("selfdev.review", task.title + "\n" + (task.body ?: ""), base)) } ?: base
            val response = gateway.invoke(routed)
            response.message.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            return finish(runId, number, head, PrReviewState.FAILED, "CALL_${e.error.kind.name}", null, 0)
        } catch (e: Exception) {
            return finish(runId, number, head, PrReviewState.UNKNOWN, "CALL_UNKNOWN", null, 0)
        }

        val result = PrReviewOutputParser.parse(text)
            ?: return finish(runId, number, head, PrReviewState.INVALID, "OUTPUT_INVALID", null, 0)
        val comment = PrReviewRenderer.comment(result, head)
        // The comment is posted once. A failure or an unknown outcome is recorded and never re-sent for this head.
        return when (val c = github.createPullRequestComment(repo, number, comment)) {
            is GitHubResult.Success -> finish(runId, number, head, PrReviewState.POSTED, null, result.verdict, result.findings.size)
            is GitHubResult.Failure ->
                finish(runId, number, head, PrReviewState.FAILED, "COMMENT_${c.kind.name}", result.verdict, result.findings.size)
            is GitHubResult.Unknown ->
                finish(runId, number, head, PrReviewState.UNKNOWN, "COMMENT_UNKNOWN", result.verdict, result.findings.size)
        }
    }

    /** Records the outcome in the row and the audit. True only for a posted comment. */
    private fun finish(
        runId: RunId,
        number: Int,
        head: String,
        state: PrReviewState,
        reason: String?,
        verdict: PrReviewVerdict?,
        findingCount: Int,
    ): Boolean {
        store.finish(repo, number, head, state, verdict, findingCount)
        recordResult(runId, number, head, state, reason, verdict, findingCount)
        return state == PrReviewState.POSTED
    }

    private fun recordResult(
        runId: RunId,
        number: Int,
        head: String,
        state: PrReviewState,
        reason: String?,
        verdict: PrReviewVerdict?,
        findingCount: Int,
    ) {
        val payload = buildJsonObject {
            put("prNumber", number)
            put("headSha", head)
            put("state", state.name)
            put("reason", reason ?: "NONE")
            put("verdict", verdict?.name ?: "NONE")
            put("findings", findingCount)
        }
        store.audit(runId, state.name, payload)
        logger.info("pr review: state={} prNumber={} reason={}", state.name, number, reason ?: "NONE")
    }

    private fun skip(runId: RunId, reason: String): Step {
        logger.debug("pr review: skipped run={} reason={}", runId.value, reason)
        return Step.SKIPPED
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PrReviewWatch::class.java)
        private val SHA = Regex("[0-9a-f]{40}")

        /** One review pass every five minutes. Each pass looks at the oldest [MAX_RUNS_PER_TICK] Runs in PULL_REQUEST (one GitHub read each). */
        val INTERVAL: Duration = Duration.ofSeconds(300)
        const val MAX_RUNS_PER_TICK = 10
        val CALL_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
