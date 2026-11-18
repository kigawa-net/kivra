package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.SelfDevOperatorRepository
import org.slf4j.LoggerFactory

/**
 * The re-check of the actual change set of an auto-approved task (ADR 0011 section 6.2). The plan's own estimate is not trusted, so the
 * diff is checked again before it is committed. Logs carry fixed codes and counts only, never a path or a line of content.
 */
interface AutoScopeGuard {
    /**
     * Judges [patch] for the Run [runId]. [sizeOf] gives the size in bytes of a file of the base tree (null when unknown). [baseOf] gives
     * the content of a file of the base tree (null when unknown: a new file, or a read that failed). The check runs after PatchPolicy
     * accepted the patch and before anything is committed.
     */
    fun check(runId: RunId, patch: PatchSet, sizeOf: (String) -> Long?, baseOf: (String) -> String?): AutoScopeVerdict

    /**
     * Records an [AutoScopeVerdict.OutOfScope] verdict on the task and its goal, before the runner ends the Run. The task then waits for
     * the operator, and the Run's failure is not counted (ADR 0011 section 6.2 and section 5.2).
     */
    fun settle(runId: RunId, verdict: AutoScopeVerdict.OutOfScope)
}

/** The outcome of the re-check for one Run. Counts and fixed codes only. */
sealed interface AutoScopeVerdict {
    /** The Run is not an auto-approved task (an operator-approved task, a task of another kind, or a Run outside the autopilot). */
    data object NotSubject : AutoScopeVerdict

    /** The change set is inside the stage-2 scope. */
    data object InScope : AutoScopeVerdict

    /**
     * The change set is outside the scope. [paths] and [lines] are the counts the rule compared with the limits. [reason] is the first
     * rule that failed, in the order of [AutoScopeReason].
     */
    data class OutOfScope(val paths: Int, val lines: Long, val reason: AutoScopeReason) : AutoScopeVerdict
}

/** Why a change set is out of scope. A fixed code: it names the kind of rule, never a path. */
enum class AutoScopeReason {
    /** More than [AutoScopeRule.MAX_PATHS] distinct paths. */
    PATH_COUNT,

    /** A path outside the allowlist of the approval mode (and not denylisted). */
    PATH_SCOPE,

    /** A path on the denylist (ADR 0011 section 4). Under every approval mode. */
    DENYLIST,

    /** The changed lines are more than [AutoScopeRule.MAX_LINES]. */
    LINE_COUNT,
}

/**
 * The rule of section 6.2 on a change set. In scope when every path is in the scope of [approval]: the stage-2 scope
 * ([PlanValidator.isAutoScopePath]: docs, kivra test sources, README.md, not denylisted) for `required` and `auto`, and the stage-3 scope
 * ([PlanValidator.isFullScopePath]: the PatchPolicy allowlist, not denylisted) for `full`. Under every mode there are at most [MAX_PATHS]
 * distinct paths, and the changed lines are at most [MAX_LINES]. The line and path caps are guards of section 5.2, so `full` keeps them.
 *
 * Lines are counted by [changedLines] for a write: the lines added plus the lines removed against the base content (see there for the
 * fail-closed rule when the base is unknown). A delete counts the bytes of the deleted file (a file has at least one byte per line, so this
 * is an upper bound).
 */
internal fun autoScopeOf(
    patch: PatchSet,
    sizeOf: (String) -> Long?,
    baseOf: (String) -> String?,
    approval: AutopilotApproval = AutopilotApproval.AUTO,
): AutoScopeVerdict {
    val paths = patch.files.map { it.path }.toSet()
    val lines = patch.files.sumOf { file ->
        when (file) {
            is Write -> changedLines(baseOf(file.path), file.content)
            is Delete -> sizeOf(file.path) ?: (AutoScopeRule.MAX_LINES + 1)
        }
    }
    // `required` never reaches this rule with an auto-approved task, so it keeps the stage-2 rule, as before.
    val pathsInScope = when (approval) {
        AutopilotApproval.FULL -> paths.all { PlanValidator.isFullScopePath(it) }
        AutopilotApproval.REQUIRED, AutopilotApproval.AUTO -> paths.all { PlanValidator.isAutoScopePath(it) }
    }
    val reason = when {
        paths.size > AutoScopeRule.MAX_PATHS -> AutoScopeReason.PATH_COUNT
        paths.any { PlanValidator.isDenylistedPath(it) } -> AutoScopeReason.DENYLIST
        !pathsInScope -> AutoScopeReason.PATH_SCOPE
        lines > AutoScopeRule.MAX_LINES -> AutoScopeReason.LINE_COUNT
        else -> return AutoScopeVerdict.InScope
    }
    return AutoScopeVerdict.OutOfScope(paths.size, lines, reason)
}

internal object AutoScopeRule {
    /** Stage 2 (ADR 0011 section 6.1): at most three paths in the diff. */
    const val MAX_PATHS = 3

    /** The changed-line cap of the re-check (the value of the Issue's example). */
    const val MAX_LINES = 200L

    /**
     * The largest input (base plus new content, in characters) that is diffed. Above it, the whole new content is counted instead (fail-
     * closed), so the cost of the diff stays bounded. The runner reads at most 1 MiB per file, so this is only a second limit.
     */
    const val MAX_DIFF_CHARS = 2_000_000
}

/**
 * The changed lines of one write: the lines added plus the lines removed against [base] (the same as the `+` and `-` counts of a line diff,
 * summed). Fail-closed: when [base] is null (a new file, or a read that failed), when the input is over [AutoScopeRule.MAX_DIFF_CHARS], or
 * when the diff is over [AutoScopeRule.MAX_LINES], the count is the full new content (or one over the cap), so the rule never passes a
 * change it could not measure.
 */
internal fun changedLines(base: String?, content: String): Long {
    if (base == null || base.length + content.length > AutoScopeRule.MAX_DIFF_CHARS) return lineCount(content)
    val distance = LineDiff.indelDistance(splitLines(base), splitLines(content), AutoScopeRule.MAX_LINES.toInt())
    return distance?.toLong() ?: (AutoScopeRule.MAX_LINES + 1)
}

private fun lineCount(content: String): Long {
    if (content.isEmpty()) return 0
    val newlines = content.count { it == '\n' }.toLong()
    return if (content.endsWith('\n')) newlines else newlines + 1
}

/** The lines of [text], as [lineCount] counts them: a final newline ends the last line and does not start another. */
private fun splitLines(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

/**
 * The insertions plus deletions between two line lists, by Myers' O(ND) greedy algorithm, with the search stopped at [maxD]. Only the
 * furthest-reaching point of each diagonal is kept, so the memory is O(maxD) and the time is O((N + M) * D) with D at most [maxD].
 */
internal object LineDiff {
    /** The edit distance of [a] and [b] when it is at most [maxD]; null when it is larger. */
    fun indelDistance(a: List<String>, b: List<String>, maxD: Int): Int? {
        val n = a.size
        val m = b.size
        val offset = maxD + 1
        val v = IntArray(2 * maxD + 3)
        for (d in 0..maxD) {
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) return d
                k += 2
            }
        }
        return null
    }
}

/**
 * The guard backed by the task table (V40). A Run is in scope of this guard only when its task was approved by the stage rules: no
 * `approved_by`. An operator-approved task, and a Run of no task, are [AutoScopeVerdict.NotSubject].
 */
class PlanTaskAutoScopeGuard(
    private val operations: SelfDevOperatorRepository,
    /** The autopilot's approval setting. Chooses the path scope of the re-check (stage 2 or stage 3). */
    private val approval: AutopilotApproval = AutopilotApproval.AUTO,
) : AutoScopeGuard {

    override fun check(runId: RunId, patch: PatchSet, sizeOf: (String) -> Long?, baseOf: (String) -> String?): AutoScopeVerdict {
        val task = operations.taskOfRun(runId.value) ?: return AutoScopeVerdict.NotSubject
        if (task.approvedBy != null) return AutoScopeVerdict.NotSubject
        return autoScopeOf(patch, sizeOf, baseOf, approval)
    }

    override fun settle(runId: RunId, verdict: AutoScopeVerdict.OutOfScope) {
        // The runner calls this only after an OutOfScope verdict. The task is moved before the Run ends (ADR 0011 section 2.8d).
        if (operations.settleScopeExceeded(runId.value)) {
            logger.info(
                "autopilot scope exceeded: runId={} state=PENDING_APPROVAL reason=AUTO_SCOPE_EXCEEDED cause={} paths={} lines={}",
                runId.value, verdict.reason.name, verdict.paths, verdict.lines,
            )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PlanTaskAutoScopeGuard::class.java)
    }
}
