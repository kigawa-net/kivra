package net.kigawa.kivra.db

import net.kigawa.kivra.auth.ProjectMemberLookup
import net.kigawa.kivra.auth.Role
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction

/** The `projectId` value that names the rows with no project (ADR 0022 PR-D). Never a project id. */
const val UNASSIGNED_PROJECT_ID = "unassigned"

/**
 * The project a list is narrowed to (ADR 0022 PR-D). [All] is the list as it was before the project scope: every row, the rows with no
 * project included. [Unassigned] is the rows with no project (`project_id` NULL). [Only] is the rows of one project.
 *
 * Only the human-facing lists use it. The autonomous loops (the planner, the dispatcher, the stop checks, the cycles, the automatic merge)
 * never narrow, so a row with no project is still processed.
 */
sealed interface ProjectFilter {
    data object All : ProjectFilter
    data object Unassigned : ProjectFilter
    data class Only(val projectId: String) : ProjectFilter

    /** The Exposed condition on [column] (a nullable project id column), or null for [All]. */
    fun condition(column: Column<String?>): Op<Boolean>? = when (this) {
        All -> null
        Unassigned -> column.isNull()
        is Only -> column eq projectId
    }

    /** The SQL condition on [column] as an `AND` clause and its parameters, for a raw query. Empty for [All]. */
    fun sql(column: String): Pair<String, List<Any?>> = when (this) {
        All -> "" to emptyList()
        Unassigned -> " AND $column IS NULL" to emptyList()
        is Only -> " AND $column = ?" to listOf(projectId)
    }
}

/**
 * The project membership store (`project_members`, V51) as the permission service reads it (ADR 0022 PR-D). Read only: nothing in this
 * change writes the table, so with no members a project check refuses every caller (see the runbook).
 */
class ProjectMemberStore(private val database: Database) : ProjectMemberLookup {
    override fun roleIn(projectId: String, assigneeId: String): Role? = transaction(database) {
        execQuery("SELECT role FROM project_members WHERE project_id = ? AND assignee_id = ?", projectId, assigneeId) { it.getString(1) }
            .firstOrNull()
            ?.let { Role.valueOf(it) }
    }
}
