package net.kigawa.kivra.selfdev.automerge

import java.time.Duration
import java.time.Instant
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.github.RunBranch

/*
 * ADR 0014 part 1: the pure judgement of whether a self-development PR may be merged automatically (C1 to C9). It reads nothing,
 * writes nothing and calls nothing. The caller reads the facts first and passes them in. It is not wired into the application yet.
 *
 * Reasons are a fixed enum. They never carry a PR title or body, a path, or file content. The input has no PR title or body at all.
 */

/** The approval kind of the task behind the Run (ADR 0011 section 2.8d). */
enum class AutoMergeTaskApproval { AUTO_APPROVED, OPERATOR_APPROVED }

/** The result of the main branch's latest push CI (ADR 0014 C8). */
enum class MainCiResult { SUCCESS, PENDING, FAILURE, UNKNOWN }

/** Why a PR was held or refused. Fixed codes only. */
enum class AutoMergeReason {
    /** The input is malformed (a blank field, a negative count, a duplicate path, a bad setting). Fail-closed. */
    INVALID_FACTS,

    // C1: origin
    NOT_BOT_AUTHOR,
    BRANCH_NOT_RUN,
    RUN_NOT_PULL_REQUEST,
    BINDING_MISMATCH,

    // C2: approval
    OPERATOR_APPROVED_TASK,
    NOT_AUTO_APPROVED,

    // C3: actual diff
    FILES_OUT_OF_RANGE,
    TOO_MANY_FILES,
    TOO_MANY_LINES,
    STATUS_NOT_ALLOWED,
    PATH_UNSAFE,
    PATH_OUT_OF_SCOPE,
    MODE_NOT_REGULAR,

    // C4: head
    HEAD_MISMATCH,

    // C5: required checks
    CHECK_MISSING,
    CHECK_NOT_SUCCESS,
    CHECK_PENDING,
    CHECK_PENDING_TIMEOUT,

    // C6: mergeable state and draft
    DRAFT,
    NOT_MERGEABLE,
    MERGEABLE_UNKNOWN,

    // C7: operator hold label
    HOLD_LABEL,

    // C8: whole-system state
    FLAG_OFF,
    PAUSED,
    BREAKER_OPEN,
    MAIN_CI_NOT_GREEN,

    // C9: rate
    CONCURRENCY_LIMIT,
    DAILY_LIMIT,
}

/** The outcome of one judgement. */
sealed interface AutoMergeDecision {
    /** The PR may be merged now. Carries no reason. */
    data object Eligible : AutoMergeDecision

    /** Not now. Not counted as a failure. The next tick judges again. */
    data class Hold(val code: AutoMergeReason) : AutoMergeDecision

    /** A person decides. Not handled automatically from here on, or not this time. */
    data class Refuse(val code: AutoMergeReason) : AutoMergeDecision
}

/** The facts of the PR, read by the caller from GitHub. */
data class AutoMergePrFacts(
    val repo: String,
    val number: Int,
    /** GraphQL node id. Null when the response did not carry it, which fails the binding check. */
    val nodeId: String?,
    /** Numeric id of the PR author. Null when absent. */
    val authorId: Long?,
    val headRef: String,
    /** The current head SHA of the PR. */
    val headSha: String,
    val draft: Boolean,
    val labels: List<String>,
    /** GitHub's `mergeable`. Null while GitHub is still computing it. */
    val mergeable: Boolean?,
    /** GitHub's `mergeable_state`, such as `clean`, `dirty`, `blocked`, `behind`, `unstable`, `unknown`. Null when absent. */
    val mergeableState: String?,
)

/** The binding of the Run to its PR, as stored at approval time. */
data class AutoMergeBinding(val repo: String, val prNumber: Int, val nodeId: String?, val headSha: String?)

/** The facts of the Run. [markerHeadSha] is the value of the `kivra-head-sha:` marker in the PR body, null when absent. */
data class AutoMergeRunFacts(
    val state: DevelopmentRunState,
    val runId: String,
    val binding: AutoMergeBinding?,
    val markerHeadSha: String?,
    /** Null when the Run is not linked to a task, which fails C2. */
    val approval: AutoMergeTaskApproval?,
)

/** One entry of the PR's files API, plus the mode the caller read from the head tree. */
data class AutoMergeFileFact(
    val path: String,
    /** The files API status, such as `added`, `modified`, `removed`, `renamed`. */
    val status: String,
    val additions: Long,
    val deletions: Long,
    /** The git mode from the head tree, such as `100644`. Null when unknown, which fails C3. */
    val mode: String?,
)

enum class AutoMergeCheckStatus { QUEUED, IN_PROGRESS, COMPLETED }

enum class AutoMergeCheckConclusion { SUCCESS, NEUTRAL, SKIPPED, CANCELLED, FAILURE, OTHER }

/**
 * One check run. [obtainedForHeadSha] is true only when the caller got this run for the PR's current head SHA. [pendingSince] is the
 * time the run was created or started, used for the pending limit (C5). Null means the time is unknown, which holds the PR.
 */
data class AutoMergeCheckFact(
    val name: String,
    val status: AutoMergeCheckStatus,
    val conclusion: AutoMergeCheckConclusion?,
    val obtainedForHeadSha: Boolean,
    val pendingSince: Instant?,
)

/** The whole-system facts. The counts exclude the PR being judged. */
data class AutoMergeGlobalFacts(
    val selfDevEnabled: Boolean,
    val autopilotEnabled: Boolean,
    /** The automatic merge flag, from the environment and the DB flag together. */
    val autoMergeEnabled: Boolean,
    /** The per-feature pause `auto_merge_paused` (ADR 0014 U-10). */
    val autoMergePaused: Boolean,
    /** The whole autopilot pause (`/api/selfdev/autopilot/pause`). */
    val selfDevPaused: Boolean,
    val breakerOpen: Boolean,
    val mainCi: MainCiResult,
    /** Automatic merges in progress, not counting this PR. */
    val inFlight: Int,
    /** Automatic merges on the current UTC day. */
    val mergedToday: Int,
)

data class AutoMergeInput(
    val pr: AutoMergePrFacts,
    val run: AutoMergeRunFacts,
    val files: List<AutoMergeFileFact>,
    val checks: List<AutoMergeCheckFact>,
    val global: AutoMergeGlobalFacts,
)

/** The settings of the judgement. The defaults are the provisional values of ADR 0014 (U-5, U-8). */
data class AutoMergeSettings(
    /** The current time (UTC instant). Used for the pending limit. */
    val now: Instant,
    /** The numeric id of the kivra GitHub App's bot, the only author of a Run's PR. */
    val botAuthorId: Long,
    /** The check names that must all succeed on the head SHA (U-3). */
    val requiredChecks: Set<String>,
    /** The scope patterns of U-1. */
    val scope: List<String> = AutoMergePaths.DEFAULT_SCOPE,
    val maxFiles: Int = 3,
    val maxLines: Long = 200,
    val maxConcurrent: Int = 1,
    val maxPerDay: Int = 3,
    val maxPendingMinutes: Long = 60,
)

private const val HOLD_LABEL = "kivra:hold"

/**
 * Judges one PR under [settings]. Pure: the same input gives the same output, and it never throws.
 *
 * The steps run in order. A [AutoMergeDecision.Refuse] returns at once. A [AutoMergeDecision.Hold] is kept, and the first one is
 * returned only when no step refuses. The order puts the cheap whole-system stops first, then the origin, the approval and the diff
 * (C1 to C3), then the head, the PR state, the checks, and the rates.
 */
fun decideAutoMerge(input: AutoMergeInput, settings: AutoMergeSettings): AutoMergeDecision {
    if (hasInvalidFacts(input, settings)) return AutoMergeDecision.Refuse(AutoMergeReason.INVALID_FACTS)

    val steps: List<() -> AutoMergeDecision?> = listOf(
        { globalStop(input.global) },
        { origin(input, settings) },
        { approval(input.run) },
        { diff(input.files, settings) },
        { head(input) },
        { prState(input.pr) },
        { mergeable(input.pr) },
        { requiredChecks(input, settings) },
        { mainCi(input.global) },
        { rates(input.global, settings) },
    )

    var firstHold: AutoMergeDecision.Hold? = null
    for (step in steps) {
        when (val verdict = step()) {
            null -> Unit
            is AutoMergeDecision.Refuse -> return verdict
            is AutoMergeDecision.Hold -> if (firstHold == null) firstHold = verdict
            AutoMergeDecision.Eligible -> Unit
        }
    }
    return firstHold ?: AutoMergeDecision.Eligible
}

private fun hasInvalidFacts(input: AutoMergeInput, settings: AutoMergeSettings): Boolean {
    val pr = input.pr
    val global = input.global
    return settings.maxFiles < 1 ||
        settings.maxLines < 1 ||
        settings.maxConcurrent < 0 ||
        settings.maxPerDay < 0 ||
        settings.maxPendingMinutes < 0 ||
        pr.repo.isBlank() ||
        pr.number <= 0 ||
        pr.headSha.isBlank() ||
        input.run.runId.isBlank() ||
        input.files.any { it.additions < 0 || it.deletions < 0 } ||
        input.files.map { it.path }.toSet().size != input.files.size ||
        global.inFlight < 0 ||
        global.mergedToday < 0
}

/** Whole-system stops (C8). Each one is a refusal, so the operator decides when to resume. */
private fun globalStop(global: AutoMergeGlobalFacts): AutoMergeDecision? {
    if (!global.selfDevEnabled || !global.autopilotEnabled || !global.autoMergeEnabled) {
        return AutoMergeDecision.Refuse(AutoMergeReason.FLAG_OFF)
    }
    if (global.autoMergePaused || global.selfDevPaused) return AutoMergeDecision.Refuse(AutoMergeReason.PAUSED)
    if (global.breakerOpen) return AutoMergeDecision.Refuse(AutoMergeReason.BREAKER_OPEN)
    return null
}

/** C1: the PR and the Run belong together. The PR is the bot's, on the Run's branch, bound to this Run. */
private fun origin(input: AutoMergeInput, settings: AutoMergeSettings): AutoMergeDecision? {
    val pr = input.pr
    val run = input.run
    if (pr.authorId == null || pr.authorId != settings.botAuthorId) {
        return AutoMergeDecision.Refuse(AutoMergeReason.NOT_BOT_AUTHOR)
    }
    // RunBranch.name throws for an id it cannot name. Treat that as no matching branch.
    val expectedBranch = runCatching { RunBranch.name(run.runId) }.getOrNull()
    if (expectedBranch == null || pr.headRef != expectedBranch) return AutoMergeDecision.Refuse(AutoMergeReason.BRANCH_NOT_RUN)
    if (run.state != DevelopmentRunState.PULL_REQUEST) return AutoMergeDecision.Refuse(AutoMergeReason.RUN_NOT_PULL_REQUEST)
    val binding = run.binding
    val bound = binding != null &&
        binding.repo == pr.repo &&
        binding.prNumber == pr.number &&
        binding.nodeId != null &&
        binding.nodeId == pr.nodeId
    if (!bound) return AutoMergeDecision.Refuse(AutoMergeReason.BINDING_MISMATCH)
    return null
}

/** C2: only a task approved by the auto-approval rule. An operator-approved task is never merged automatically (U-8). */
private fun approval(run: AutoMergeRunFacts): AutoMergeDecision? =
    when (run.approval) {
        null -> AutoMergeDecision.Refuse(AutoMergeReason.NOT_AUTO_APPROVED)
        AutoMergeTaskApproval.OPERATOR_APPROVED -> AutoMergeDecision.Refuse(AutoMergeReason.OPERATOR_APPROVED_TASK)
        AutoMergeTaskApproval.AUTO_APPROVED -> null
    }

/** C3: the actual diff from the files API, in scope, plain, and small. */
private fun diff(files: List<AutoMergeFileFact>, settings: AutoMergeSettings): AutoMergeDecision? {
    if (files.isEmpty()) return AutoMergeDecision.Refuse(AutoMergeReason.FILES_OUT_OF_RANGE)
    if (files.size > settings.maxFiles) return AutoMergeDecision.Refuse(AutoMergeReason.TOO_MANY_FILES)

    // Each count is clamped above the limit, so the sum cannot overflow.
    val cap = settings.maxLines + 1
    val lines = files.sumOf { minOf(it.additions, cap) + minOf(it.deletions, cap) }
    val withinLines = lines <= settings.maxLines
    if (!withinLines) return AutoMergeDecision.Refuse(AutoMergeReason.TOO_MANY_LINES)

    for (file in files) {
        if (!AutoMergePaths.isSafe(file.path)) return AutoMergeDecision.Refuse(AutoMergeReason.PATH_UNSAFE)
        if (!AutoMergePaths.isInScope(file.path, settings.scope)) return AutoMergeDecision.Refuse(AutoMergeReason.PATH_OUT_OF_SCOPE)
        if (file.status != "added" && file.status != "modified") return AutoMergeDecision.Refuse(AutoMergeReason.STATUS_NOT_ALLOWED)
        if (file.mode != "100644") return AutoMergeDecision.Refuse(AutoMergeReason.MODE_NOT_REGULAR)
    }
    return null
}

/** C4: the head SHA of the PR, the binding, and the body marker are the same SHA. */
private fun head(input: AutoMergeInput): AutoMergeDecision? {
    val sha = input.pr.headSha
    val same = input.run.markerHeadSha == sha && input.run.binding?.headSha == sha
    return if (same) null else AutoMergeDecision.Refuse(AutoMergeReason.HEAD_MISMATCH)
}

/** C7 and the draft rule of C6. */
private fun prState(pr: AutoMergePrFacts): AutoMergeDecision? {
    if (pr.draft) return AutoMergeDecision.Refuse(AutoMergeReason.DRAFT)
    if (pr.labels.any { it.equals(HOLD_LABEL, ignoreCase = true) }) return AutoMergeDecision.Refuse(AutoMergeReason.HOLD_LABEL)
    return null
}

/** C6: GitHub reports the PR as mergeable and clean. Null or `unknown` is still computing, so it is held. */
private fun mergeable(pr: AutoMergePrFacts): AutoMergeDecision? {
    if (pr.mergeable == null || pr.mergeableState == "unknown") return AutoMergeDecision.Hold(AutoMergeReason.MERGEABLE_UNKNOWN)
    if (pr.mergeable != true || pr.mergeableState != "clean") return AutoMergeDecision.Refuse(AutoMergeReason.NOT_MERGEABLE)
    return null
}

/**
 * C5: every required check name has a run for the head SHA that completed with success. A run for another SHA does not count. Any
 * completed run that is not a success refuses. A run still queued or in progress holds, until it exceeds the pending limit.
 */
private fun requiredChecks(input: AutoMergeInput, settings: AutoMergeSettings): AutoMergeDecision? {
    if (settings.requiredChecks.isEmpty()) return AutoMergeDecision.Refuse(AutoMergeReason.CHECK_MISSING)
    val forHead = input.checks.filter { it.obtainedForHeadSha }
    var hold = false
    for (name in settings.requiredChecks.sorted()) {
        val runs = forHead.filter { it.name == name }
        if (runs.isEmpty()) return AutoMergeDecision.Refuse(AutoMergeReason.CHECK_MISSING)

        val finished = runs.filter { it.status == AutoMergeCheckStatus.COMPLETED }
        if (finished.any { it.conclusion != AutoMergeCheckConclusion.SUCCESS }) {
            return AutoMergeDecision.Refuse(AutoMergeReason.CHECK_NOT_SUCCESS)
        }

        val pending = runs.filter { it.status != AutoMergeCheckStatus.COMPLETED }
        if (pending.isNotEmpty()) {
            val since = pending.map { it.pendingSince }
            if (since.any { it == null }) {
                hold = true
            } else {
                val oldest = since.filterNotNull().min()
                val waited = Duration.between(oldest, settings.now)
                if (waited > Duration.ofMinutes(settings.maxPendingMinutes)) {
                    return AutoMergeDecision.Refuse(AutoMergeReason.CHECK_PENDING_TIMEOUT)
                }
                hold = true
            }
        }
    }
    return if (hold) AutoMergeDecision.Hold(AutoMergeReason.CHECK_PENDING) else null
}

/** C8: the main branch's latest push CI. Pending or unknown holds; a failure refuses. */
private fun mainCi(global: AutoMergeGlobalFacts): AutoMergeDecision? =
    when (global.mainCi) {
        MainCiResult.SUCCESS -> null
        MainCiResult.PENDING, MainCiResult.UNKNOWN -> AutoMergeDecision.Hold(AutoMergeReason.MAIN_CI_NOT_GREEN)
        MainCiResult.FAILURE -> AutoMergeDecision.Refuse(AutoMergeReason.MAIN_CI_NOT_GREEN)
    }

/** C9: one automatic merge in progress at a time, and a daily cap. Both wait for the next tick. */
private fun rates(global: AutoMergeGlobalFacts, settings: AutoMergeSettings): AutoMergeDecision? {
    if (global.inFlight >= settings.maxConcurrent) return AutoMergeDecision.Hold(AutoMergeReason.CONCURRENCY_LIMIT)
    if (global.mergedToday >= settings.maxPerDay) return AutoMergeDecision.Hold(AutoMergeReason.DAILY_LIMIT)
    return null
}
