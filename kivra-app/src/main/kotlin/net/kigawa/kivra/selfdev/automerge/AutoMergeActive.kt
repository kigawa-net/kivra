package net.kigawa.kivra.selfdev.automerge

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PrBranchPort
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.AutopilotSettings
import net.kigawa.kivra.selfdev.review.PrReviewSettings
import org.slf4j.LoggerFactory

/*
 * ADR 0014 section 6 (S2): the automatic merge itself. It is the only code in the automerge package that calls the merge API, and it is
 * off unless `KIVRA_SELFDEV_AUTOMERGE_ACTIVE_ENABLED=true` (the wiring does not build it otherwise).
 *
 * Each tick: a PR that the shadow judgement marks ELIGIBLE is judged once more, with fresh reads (C10), then its head SHA is claimed in
 * `selfdev_automerge_attempts`, and only then is the squash merge sent with that SHA. One merge request per tick, one in flight at a time
 * (the database enforces both), and one attempt per head SHA, ever. A refusal by GitHub is recorded and never retried. An unknown outcome,
 * an unexpected response, or two refusals in a row pauses the automatic merge (the operator resumes it through the existing endpoint).
 *
 * The audit carries fixed codes, numbers, the PR number, the head SHA and the merge commit SHA. Never a title, a body, a path or content.
 */

/** The audit action of a merge request, sent after the claim. Result `REQUESTED`. */
const val AUTO_MERGE_REQUESTED = "AUTO_MERGE_REQUESTED"

/** The audit action of a merge that GitHub confirmed (the merge commit SHA is recorded). Result `MERGED`. */
const val AUTO_MERGE_MERGED = "AUTO_MERGE_MERGED"

/** The audit action of a judgement that changed to a refusal before the call, or of a merge that GitHub refused. Result `REFUSED`. */
const val AUTO_MERGE_REFUSED = "AUTO_MERGE_REFUSED"

/** The audit action of the automatic halt (the automatic merge is paused). Result `PAUSED`. The payload has the reason code only. */
const val AUTO_MERGE_HALTED = "AUTO_MERGE_HALTED"

/** The states of one attempt row (V48). */
object AutoMergeAttemptState {
    const val CLAIMED = "CLAIMED"
    const val MERGED = "MERGED"
    const val REFUSED = "REFUSED"
    const val UNKNOWN = "UNKNOWN"
}

/** The fixed reason codes of an automatic halt. */
object AutoMergeHaltCode {
    /** Two attempts in a row were refused or unknown. */
    const val MERGE_FAILURES = "MERGE_FAILURES"

    /** The outcome of a merge request is not known (a transport failure, a 5xx, or a refusal of another kind). Never re-sent. */
    const val MERGE_UNKNOWN = "MERGE_UNKNOWN"

    /** The merge response did not carry the merge commit SHA, so the merge cannot be confirmed. */
    const val MERGE_UNCONFIRMED = "MERGE_UNCONFIRMED"

    /** An attempt has been in flight for longer than [AutoMergeActiveWatch.STALE_CLAIM]: a crash between the claim and the result. */
    const val ATTEMPT_UNFINISHED = "ATTEMPT_UNFINISHED"
}

/**
 * The switch of the automatic merge (`KIVRA_SELFDEV_AUTOMERGE_ACTIVE_ENABLED`). Off unless it is exactly `true`; unset, empty and `false`
 * are off and read nothing else. `true` needs the shadow switch and the autopilot (the shadow is built only with both), and the PR AI review
 * (its verdict is one of the conditions). Any other combination is a startup failure. The message names variables, never values.
 */
data class AutoMergeActiveSettings(val enabled: Boolean) {
    companion object {
        const val ENV_ACTIVE = "KIVRA_SELFDEV_AUTOMERGE_ACTIVE_ENABLED"

        val DISABLED = AutoMergeActiveSettings(enabled = false)

        /**
         * [shadowOn] is true when the shadow judgement is built, which needs the autopilot and its shadow switch. [reviewOn] is the PR AI
         * review switch (`KIVRA_SELFDEV_PR_REVIEW_ENABLED`).
         */
        fun fromEnv(env: Map<String, String>, shadowOn: Boolean, reviewOn: Boolean): AutoMergeActiveSettings {
            when (env[ENV_ACTIVE]?.trim()) {
                null, "", "false" -> return DISABLED
                "true" -> Unit
                else -> throw IllegalStateException("$ENV_ACTIVE must be true or false")
            }
            check(shadowOn) {
                "$ENV_ACTIVE=true requires ${AutopilotSettings.ENV_ENABLED}=true and ${AutopilotSettings.ENV_AUTOMERGE_SHADOW}=true"
            }
            check(reviewOn) { "$ENV_ACTIVE=true requires ${PrReviewSettings.ENV_ENABLED}=true" }
            return AutoMergeActiveSettings(enabled = true)
        }
    }
}

/**
 * The one write of the automatic merge: a squash merge with the head SHA (`PUT /pulls/{n}/merge`, `sha` set). It is the only merge call of
 * the application. A mismatch of the head is a CONFLICT (nothing merged). A refusal by GitHub (405, 409, 422) is VALIDATION or CONFLICT.
 * A transport failure or a 5xx is UNKNOWN, and it is never re-sent. The merge commit SHA is returned.
 */
interface AutoMergeActivePort {
    suspend fun squashMerge(repo: RepoRef, number: Int, expectedHeadSha: String): GitHubResult<String?>
}

/** The production merge port: the squash merge of [PrBranchPort], which takes the head SHA and is squash-only. */
class GitHubAutoMergeActivePort(private val prs: PrBranchPort) : AutoMergeActivePort {
    override suspend fun squashMerge(repo: RepoRef, number: Int, expectedHeadSha: String): GitHubResult<String?> =
        prs.squashMerge(repo, number, expectedHeadSha)
}

/** The database side of the automatic merge: the attempts (one per head SHA, one in flight), the audit, and the halt. */
interface AutoMergeActiveStore {
    /** The state of the attempt for (repo, pull request, head SHA), or null when none was started. */
    fun attemptState(repo: String, number: Int, headSha: String): String?

    /** Inserts the attempt in CLAIMED, holding the single slot. False when the head already has one or another attempt holds the slot. */
    fun claim(repo: String, number: Int, headSha: String, runId: RunId): Boolean

    /** Sets the final state of the attempt and releases the slot. */
    fun finish(repo: String, number: Int, headSha: String, state: String)

    /** The states of the most recent attempts, newest first, at most [limit]. */
    fun recentStates(limit: Int): List<String>

    /** The Run of an attempt that is still CLAIMED and was last updated before [olderThan], or null. */
    fun staleClaim(olderThan: Instant): RunId?

    /** Appends one event to the Run's audit chain. [payload] carries fixed codes, numbers and SHAs only. */
    fun audit(runId: RunId, action: String, result: String, payload: JsonObject)

    /** Pauses the automatic merge (the existing per-feature pause, ADR 0014 U-10) and records [reasonCode] on the Run's audit chain. */
    fun halt(runId: RunId, reasonCode: String)
}

/**
 * The automatic merge (ADR 0014 active). Each tick it looks at up to [MAX_PRS_PER_TICK] PULL_REQUEST Runs, oldest first. A PR is merged only
 * when the judgement is ELIGIBLE twice in a row (the second judgement is the C10 re-check, with fresh reads), its head has no attempt yet, and
 * the claim succeeds. At most one merge request is sent per tick.
 */
class AutoMergeActiveWatch(
    private val shadowStore: AutoMergeShadowStore,
    private val activeStore: AutoMergeActiveStore,
    port: AutoMergeShadowPort,
    private val merge: AutoMergeActivePort,
    private val settings: AutoMergeShadowSettings,
    private val clock: Clock = Clock.systemUTC(),
    /** How often a tick runs. The default is one minute, as the shadow judgement. */
    private val interval: Duration = INTERVAL,
) {
    private val assembler = AutoMergeFactsAssembler(port, shadowStore, settings, clock)

    /**
     * One pass. Returns 1 when a merge request was sent or the automatic merge was halted (the pass then ends), and 0 otherwise. Nothing is
     * written to GitHub unless a Run passes every condition twice. A paused, a breaker-open or a self-development-paused system reads nothing and sends nothing.
     */
    suspend fun tick(): Int {
        val global = shadowStore.globalSnapshot()
        if (global.selfDevPaused || global.autoMergePaused || global.breakerOpen) return 0

        // A claim still in flight from before (a crash in between) has an unknown outcome: stop, and let a person look.
        activeStore.staleClaim(clock.instant().minus(STALE_CLAIM))?.let { runId ->
            halt(runId, AutoMergeHaltCode.ATTEMPT_UNFINISHED)
            return 0
        }

        for (runId in shadowStore.pullRequestRunIds(MAX_PRS_PER_TICK)) {
            try {
                if (mergeOne(runId)) return 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("automerge active: judgement failed, exception={}", e.javaClass.simpleName)
            }
        }
        return 0
    }

    /** Runs [tick] every [interval] in [scope]. It stops when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("automerge active: tick failed, exception={}", e.javaClass.simpleName)
            }
            delay(interval.toMillis())
        }
    }

    /** True when a merge request was sent or the automatic merge was halted, so the pass ends. */
    private suspend fun mergeOne(runId: RunId): Boolean {
        val judged = judgeNow(runId) ?: return false
        if (judged.verdict != VERDICT_ELIGIBLE) return false
        val repo = settings.repo.toString()
        val number = judged.prNumber
        val head = judged.headSha
        // Idempotent: a head SHA is attempted once, whatever its outcome.
        if (activeStore.attemptState(repo, number, head) != null) return false

        // C10: the same judgement again, with fresh reads, right before the call.
        val fresh = judgeNow(runId) ?: return false
        if (fresh.prNumber != number || fresh.headSha != head) return false
        if (fresh.verdict != VERDICT_ELIGIBLE) {
            if (fresh.verdict == VERDICT_REFUSE) {
                activeStore.audit(runId, AUTO_MERGE_REFUSED, RESULT_REFUSED, payload(fresh, reason = fresh.reason))
            }
            return false
        }

        if (!activeStore.claim(repo, number, head, runId)) return false
        activeStore.audit(runId, AUTO_MERGE_REQUESTED, RESULT_REQUESTED, payload(fresh))
        return settle(runId, repo, number, head, fresh, merge.squashMerge(settings.repo, number, head))
    }

    /** Records the outcome of the one merge request, and decides whether the automatic merge stops. */
    private fun settle(
        runId: RunId,
        repo: String,
        number: Int,
        head: String,
        judged: ShadowJudgement,
        outcome: GitHubResult<String?>,
    ): Boolean {
        when (outcome) {
            is GitHubResult.Success -> {
                val mergeCommitSha = outcome.value
                if (mergeCommitSha == null) {
                    activeStore.finish(repo, number, head, AutoMergeAttemptState.UNKNOWN)
                    halt(runId, AutoMergeHaltCode.MERGE_UNCONFIRMED)
                    return true
                }
                activeStore.finish(repo, number, head, AutoMergeAttemptState.MERGED)
                activeStore.audit(runId, AUTO_MERGE_MERGED, RESULT_MERGED, payload(judged, mergeCommitSha = mergeCommitSha))
                logger.info("automerge active: merged count=1")
                return true
            }
            is GitHubResult.Failure -> {
                // GitHub refused: recorded, never retried. Two refusals or unknowns in a row stop the automatic merge.
                if (outcome.kind == GitHubFailureKind.CONFLICT || outcome.kind == GitHubFailureKind.VALIDATION) {
                    activeStore.finish(repo, number, head, AutoMergeAttemptState.REFUSED)
                    activeStore.audit(runId, AUTO_MERGE_REFUSED, RESULT_REFUSED, payload(judged, reason = outcome.kind.code))
                    val last = activeStore.recentStates(2)
                    val failing = last.size == 2 && last.all { it == AutoMergeAttemptState.REFUSED || it == AutoMergeAttemptState.UNKNOWN }
                    if (failing) halt(runId, AutoMergeHaltCode.MERGE_FAILURES)
                    return true
                }
                // Anything else: the request may or may not have been applied. Stop, and do not send it again.
                activeStore.finish(repo, number, head, AutoMergeAttemptState.UNKNOWN)
                halt(runId, AutoMergeHaltCode.MERGE_UNKNOWN)
                return true
            }
            is GitHubResult.Unknown -> {
                activeStore.finish(repo, number, head, AutoMergeAttemptState.UNKNOWN)
                halt(runId, AutoMergeHaltCode.MERGE_UNKNOWN)
                return true
            }
        }
    }

    private suspend fun judgeNow(runId: RunId): ShadowJudgement? {
        val meter = ShadowReadMeter(MAX_READS_PER_TICK)
        val global = shadowStore.globalSnapshot()
        return assembler.judge(runId, meter, assembler.mainCi(meter), global)
    }

    private fun halt(runId: RunId, reasonCode: String) {
        activeStore.halt(runId, reasonCode)
        logger.warn("automerge active: halted code={}", reasonCode)
    }

    private fun payload(judged: ShadowJudgement, reason: String? = null, mergeCommitSha: String? = null): JsonObject = buildJsonObject {
        put("prNumber", judged.prNumber)
        put("headSha", judged.headSha)
        put("files", judged.files)
        put("lines", judged.lines)
        if (reason != null) put("reasonCode", reason)
        if (mergeCommitSha != null) put("mergeCommitSha", mergeCommitSha)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AutoMergeActiveWatch::class.java)

        const val VERDICT_ELIGIBLE = "ELIGIBLE"
        const val VERDICT_REFUSE = "REFUSE"

        const val RESULT_REQUESTED = "REQUESTED"
        const val RESULT_MERGED = "MERGED"
        const val RESULT_REFUSED = "REFUSED"

        /** One minute, as the shadow judgement. */
        val INTERVAL: Duration = Duration.ofSeconds(60)

        /** The most PULL_REQUEST Runs one pass looks at (oldest first), as the shadow judgement. */
        const val MAX_PRS_PER_TICK = 5

        /** The most GitHub port calls one judgement makes, as the shadow judgement. The second judgement has its own budget. */
        const val MAX_READS_PER_TICK = 40

        /** An attempt still CLAIMED after this long is a crash, not a merge in progress. */
        val STALE_CLAIM: Duration = Duration.ofMinutes(10)
    }
}

/**
 * The automatic merge watch, or null when the switch is off. It needs the shadow judgement (the autopilot with its shadow switch), which is
 * checked here again so that the watch cannot be built without it.
 */
fun buildAutoMergeActiveWatch(
    active: AutoMergeActiveSettings,
    autopilot: AutopilotSettings,
    shadowStore: AutoMergeShadowStore,
    activeStore: AutoMergeActiveStore,
    port: AutoMergeShadowPort,
    merge: AutoMergeActivePort,
    repo: RepoRef,
    baseBranch: String,
    botAuthorId: Long,
): AutoMergeActiveWatch? {
    if (!active.enabled) return null
    val config = checkNotNull(autopilot.config?.takeIf { autopilot.enabled && it.autoMergeShadow }) {
        "${AutoMergeActiveSettings.ENV_ACTIVE} requires the shadow judgement"
    }
    return AutoMergeActiveWatch(
        shadowStore = shadowStore,
        activeStore = activeStore,
        port = port,
        merge = merge,
        settings = AutoMergeShadowSettings(
            repo = repo,
            baseBranch = baseBranch,
            botAuthorId = botAuthorId,
            requiredChecks = config.autoMergeRequiredChecks,
        ),
    )
}
