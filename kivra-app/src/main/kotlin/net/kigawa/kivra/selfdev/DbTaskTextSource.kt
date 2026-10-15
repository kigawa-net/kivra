package net.kigawa.kivra.selfdev

import net.kigawa.kivra.db.DevelopmentRun

/**
 * The task text of a self-development run, read from the run row (`task_title`, written by the Task API at submission).
 *
 * MVP: the title is the whole task text, so the description is the title too. Input from an Issue URL is a later step.
 * The text is untrusted input written by an authenticated operator; it is never logged or put in a reason code.
 */
object DbTaskTextSource : TaskTextSource {
    override fun taskText(run: DevelopmentRun): TaskText? {
        val title = run.taskTitle?.takeIf { it.isNotBlank() } ?: return null
        return TaskText(title = title, description = title)
    }
}
