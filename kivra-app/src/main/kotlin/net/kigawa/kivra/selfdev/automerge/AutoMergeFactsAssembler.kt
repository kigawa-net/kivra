package net.kigawa.kivra.selfdev.automerge

import java.time.Clock
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.github.CheckConclusion
import net.kigawa.kivra.github.CheckRun
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PullRequestFile
import net.kigawa.kivra.github.PullRequestState

/** The result of the main branch's CI for one tick, with the reads that failed to produce it. */
data class MainCiRead(val result: MainCiResult, val readFailures: List<String>)

/**
 * Builds the facts of [decideAutoMerge] from GitHub and the database, then calls it. A read that fails becomes a missing fact, so the
 * judgement fails closed. The PR's body is read for the head marker only: it is never stored or logged, and no other part of it is used.
 */
internal class AutoMergeFactsAssembler(
    private val port: AutoMergeShadowPort,
    private val store: AutoMergeShadowStore,
    private val settings: AutoMergeShadowSettings,
    private val clock: Clock,
) {
    /** The latest push CI of the base branch: the check runs of its head commit, for the required checks. Read once per tick. */
    suspend fun mainCi(meter: ShadowReadMeter): MainCiRead {
        val unknown = MainCiRead(MainCiResult.UNKNOWN, listOf(ShadowReadCode.MAIN_CI_UNREADABLE))
        val head = when (val b = meter.read { port.branchHead(settings.repo, settings.baseBranch) }) {
            is GitHubResult.Success -> b.value ?: return MainCiRead(MainCiResult.UNKNOWN, emptyList())
            else -> return unknown
        }
        val summary = when (val c = meter.read { port.checkRuns(settings.repo, head.headSha) }) {
            is GitHubResult.Success -> c.value
            else -> return unknown
        }
        if (!summary.complete || summary.sha != head.headSha) return unknown
        return MainCiRead(deriveMainCi(summary.runs, settings.requiredChecks), emptyList())
    }

    /**
     * Judges one Run. Null when the Run is gone or has no binding (there is no PR to read, so it is not judged). A PR that cannot be read
     * is a HOLD with a read code, since the PR's facts are needed for any judgement.
     */
    suspend fun judge(runId: RunId, meter: ShadowReadMeter, main: MainCiRead, global: ShadowGlobalSnapshot): ShadowJudgement? {
        val snapshot = store.runSnapshot(runId) ?: return null
        val binding = snapshot.binding ?: return null
        val number = binding.prNumber

        val view = when (val r = meter.read { port.pullRequest(settings.repo, number) }) {
            is GitHubResult.Success -> r.value
            else -> return unjudged(number, binding.headSha, "HOLD", ShadowReadCode.PR_UNREADABLE)
        }
        val pr = view.pullRequest
        val labels = pr.labels ?: return unjudged(number, pr.headSha, "HOLD", ShadowReadCode.PR_UNREADABLE)
        if (pr.state != PullRequestState.OPEN || pr.merged) {
            return unjudged(number, pr.headSha, "REFUSE", ShadowReadCode.PR_NOT_OPEN)
        }

        val readFailures = mutableListOf<String>()
        readFailures += main.readFailures

        val files = when (val f = meter.read { port.pullRequestFiles(settings.repo, number) }) {
            is GitHubResult.Success -> f.value.files
            else -> {
                readFailures += ShadowReadCode.FILES_UNREADABLE
                emptyList()
            }
        }
        val decisionSettings = decisionSettings()
        // Modes are read only for a small diff. A larger one is refused on its file count, so the tree is not read at all.
        val modes: Map<String, String> = if (files.isEmpty() || files.size > decisionSettings.maxFiles) {
            emptyMap()
        } else {
            when (val t = meter.read { port.tree(settings.repo, pr.headSha) }) {
                is GitHubResult.Success -> t.value.entries.associate { it.path to it.mode }
                else -> {
                    readFailures += ShadowReadCode.TREE_UNREADABLE
                    emptyMap()
                }
            }
        }

        val checks = when (val c = meter.read { port.checkRuns(settings.repo, pr.headSha) }) {
            is GitHubResult.Success -> if (c.value.complete && c.value.sha == pr.headSha) {
                c.value.runs.map { it.toFact() }
            } else {
                readFailures += ShadowReadCode.CHECKS_UNREADABLE
                emptyList()
            }
            else -> {
                readFailures += ShadowReadCode.CHECKS_UNREADABLE
                emptyList()
            }
        }

        val input = AutoMergeInput(
            pr = AutoMergePrFacts(
                repo = settings.repo.toString(),
                number = number,
                nodeId = pr.nodeId,
                authorId = pr.authorId,
                headRef = pr.headRef,
                headSha = pr.headSha,
                draft = pr.draft,
                labels = labels,
                mergeable = view.mergeable,
                mergeableState = view.mergeableState,
            ),
            run = AutoMergeRunFacts(
                state = snapshot.state,
                runId = runId.value,
                binding = binding,
                markerHeadSha = headMarkerOf(pr.body),
                approval = snapshot.approval,
            ),
            files = files.map { it.toFact(modes[it.path]) },
            checks = checks,
            global = AutoMergeGlobalFacts(
                // The shadow runs only when self-development and the autopilot are on (see buildAutoMergeShadowWatch). Its own switch
                // is on, because the judgement is what the switch is for. The watch skips the judgement while the per-feature pause
                // is on (ADR 0014 U-10), so this is false whenever a judgement is made.
                selfDevEnabled = true,
                autopilotEnabled = true,
                autoMergeEnabled = true,
                autoMergePaused = global.autoMergePaused,
                selfDevPaused = global.selfDevPaused,
                breakerOpen = global.breakerOpen,
                mainCi = main.result,
                inFlight = 0,
                mergedToday = 0,
            ),
        )
        val decision = decideAutoMerge(input, decisionSettings)
        val (verdict, reason) = when (decision) {
            AutoMergeDecision.Eligible -> "ELIGIBLE" to ""
            is AutoMergeDecision.Hold -> "HOLD" to decision.code.name
            is AutoMergeDecision.Refuse -> "REFUSE" to decision.code.name
        }
        return ShadowJudgement(
            prNumber = number,
            headSha = pr.headSha,
            verdict = verdict,
            reason = reason,
            files = files.size,
            lines = files.sumOf { it.additions + it.deletions },
            checks = settings.requiredChecks.sorted(),
            readFailures = readFailures.toList(),
        )
    }

    private fun decisionSettings(): AutoMergeSettings = AutoMergeSettings(
        now = clock.instant(),
        botAuthorId = settings.botAuthorId,
        requiredChecks = settings.requiredChecks,
    )

    private fun unjudged(number: Int, headSha: String?, verdict: String, reason: String) = ShadowJudgement(
        prNumber = number,
        headSha = headSha.orEmpty(),
        verdict = verdict,
        reason = reason,
        files = 0,
        lines = 0,
        checks = emptyList(),
        readFailures = if (reason == ShadowReadCode.PR_UNREADABLE) listOf(reason) else emptyList(),
    )

    private fun PullRequestFile.toFact(mode: String?) = AutoMergeFileFact(
        path = path,
        status = status,
        additions = additions,
        deletions = deletions,
        mode = mode,
    )

    private fun CheckRun.toFact() = AutoMergeCheckFact(
        name = name,
        status = when (status) {
            "completed" -> AutoMergeCheckStatus.COMPLETED
            "in_progress" -> AutoMergeCheckStatus.IN_PROGRESS
            else -> AutoMergeCheckStatus.QUEUED
        },
        conclusion = when (conclusion) {
            null -> null
            CheckConclusion.SUCCESS -> AutoMergeCheckConclusion.SUCCESS
            CheckConclusion.NEUTRAL -> AutoMergeCheckConclusion.NEUTRAL
            CheckConclusion.SKIPPED -> AutoMergeCheckConclusion.SKIPPED
            CheckConclusion.CANCELLED -> AutoMergeCheckConclusion.CANCELLED
            CheckConclusion.FAILURE -> AutoMergeCheckConclusion.FAILURE
            else -> AutoMergeCheckConclusion.OTHER
        },
        obtainedForHeadSha = true,
        pendingSince = startedAt,
    )

    companion object {
        private val MARKER = Regex("kivra-head-sha:([0-9a-f]{40})")

        /**
         * The `kivra-head-sha:` marker of a PR body: a line of exactly this form with a 40-hex SHA. Null when there is no such line, or when
         * the lines name more than one value. Nothing else in the body is read.
         */
        internal fun headMarkerOf(body: String): String? = body.lineSequence()
            .map { it.trim() }
            .mapNotNull { MARKER.matchEntire(it)?.groupValues?.get(1) }
            .toSet()
            .singleOrNull()

        /**
         * The base branch's CI from the check runs of its head commit. A completed required check that did not succeed is FAILURE; a run
         * still going is PENDING; a required check with no run is UNKNOWN; otherwise SUCCESS.
         */
        internal fun deriveMainCi(runs: List<CheckRun>, required: Set<String>): MainCiResult {
            val relevant = runs.filter { it.name in required }
            if (relevant.any { it.status == "completed" && it.conclusion != CheckConclusion.SUCCESS }) return MainCiResult.FAILURE
            if (relevant.any { it.status != "completed" }) return MainCiResult.PENDING
            if (required.any { name -> relevant.none { it.name == name } }) return MainCiResult.UNKNOWN
            return MainCiResult.SUCCESS
        }
    }
}
