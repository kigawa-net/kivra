package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.SelfDevTaskRepository
import org.slf4j.LoggerFactory

/**
 * The re-check of the actual change set of an auto-approved task (ADR 0011 section 6.2). The plan's own estimate is not trusted, so the
 * diff is checked again before it is committed. Logs carry fixed codes and counts only, never a path or a line of content.
 */
interface AutoScopeGuard {
    /**
     * Judges [patch] for the Run [runId]. [sizeOf] gives the size in bytes of a file of the base tree (null when unknown). The check runs
     * after PatchPolicy accepted the patch and before anything is committed.
     */
    fun check(runId: RunId, patch: PatchSet, sizeOf: (String) -> Long?): AutoScopeVerdict

    /**
     * Records an [AutoScopeVerdict.OutOfScope] verdict on the task and its goal, before the runner ends the Run. The task then waits for
     * the operator, and the Run's failure is not counted (ADR 0011 section 6.2 and section 5.2).
     */
    fun settle(runId: RunId)
}

/** The outcome of the re-check for one Run. Counts only. */
sealed interface AutoScopeVerdict {
    /** The Run is not an auto-approved task (an operator-approved task, a task of another kind, or a Run outside the autopilot). */
    data object NotSubject : AutoScopeVerdict

    /** The change set is inside the stage-2 scope. */
    data object InScope : AutoScopeVerdict

    /** The change set is outside the scope. [paths] and [lines] are the counts the rule compared with the limits. */
    data class OutOfScope(val paths: Int, val lines: Long) : AutoScopeVerdict
}

/**
 * The rule of section 6.2 on a change set. In scope when every path is in the stage-2 scope ([PlanValidator.isAutoScopePath]: docs,
 * kivra test sources, README.md, not denylisted), there are at most [MAX_PATHS] distinct paths, and the changed lines are at most
 * [MAX_LINES].
 *
 * Lines are counted from what the patch carries: a write counts the lines of its new content, and a delete counts the bytes of the
 * deleted file (a file has at least one byte per line, so this is an upper bound). The old content of a written file is not in the
 * patch, so the lines it removed are not counted. The file count and the path rules still apply to it.
 */
internal fun autoScopeOf(patch: PatchSet, sizeOf: (String) -> Long?): AutoScopeVerdict {
    val paths = patch.files.map { it.path }.toSet()
    val lines = patch.files.sumOf { file ->
        when (file) {
            is Write -> lineCount(file.content)
            is Delete -> sizeOf(file.path) ?: (AutoScopeRule.MAX_LINES + 1)
        }
    }
    val inScope = paths.size <= AutoScopeRule.MAX_PATHS &&
        paths.all { PlanValidator.isAutoScopePath(it) } &&
        lines <= AutoScopeRule.MAX_LINES
    return if (inScope) AutoScopeVerdict.InScope else AutoScopeVerdict.OutOfScope(paths.size, lines)
}

internal object AutoScopeRule {
    /** Stage 2 (ADR 0011 section 6.1): at most three paths in the diff. */
    const val MAX_PATHS = 3

    /** The changed-line cap of the re-check (the value of the Issue's example). */
    const val MAX_LINES = 200L
}

private fun lineCount(content: String): Long {
    if (content.isEmpty()) return 0
    val newlines = content.count { it == '\n' }.toLong()
    return if (content.endsWith('\n')) newlines else newlines + 1
}

/** The guard backed by the plan tables. A Run with no auto-approved task is [AutoScopeVerdict.NotSubject] and costs one lookup. */
class PlanTaskAutoScopeGuard(private val tasks: SelfDevTaskRepository) : AutoScopeGuard {

    override fun check(runId: RunId, patch: PatchSet, sizeOf: (String) -> Long?): AutoScopeVerdict {
        tasks.autoTaskOfRun(runId.value) ?: return AutoScopeVerdict.NotSubject
        val verdict = autoScopeOf(patch, sizeOf)
        if (verdict is AutoScopeVerdict.OutOfScope) {
            logger.warn(
                "autopilot scope exceeded: runId={} code={} paths={} lines={}",
                runId.value, REASON_CODE, verdict.paths, verdict.lines,
            )
        }
        return verdict
    }

    override fun settle(runId: RunId) {
        if (!tasks.settleAutoScopeExceeded(runId.value)) {
            logger.warn("autopilot scope not settled: runId={} code={}", runId.value, REASON_CODE)
        } else {
            logger.info("autopilot scope settled: runId={} code={}", runId.value, REASON_CODE)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PlanTaskAutoScopeGuard::class.java)
        const val REASON_CODE = "AUTO_SCOPE_EXCEEDED"
    }
}
