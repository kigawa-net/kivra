package net.kigawa.kivra.selfdev

import net.kigawa.kivra.db.DevelopmentRun

/**
 * The task text of a self-development run, read from the run row (`task_title`, written by the Task API at submission).
 *
 * The description is the stored task body (manual, and the resolved reference, see [TaskBodyText]) when there is one, and the title otherwise.
 * The text is untrusted input written by an authenticated operator or taken from an issue; it is never logged or put in a reason code.
 */
object DbTaskTextSource : TaskTextSource {
    override fun taskText(run: DevelopmentRun): TaskText? {
        val title = run.taskTitle?.takeIf { it.isNotBlank() } ?: return null
        val description = run.taskBody?.takeIf { it.isNotBlank() } ?: title
        return TaskText(title = title, description = description)
    }
}
