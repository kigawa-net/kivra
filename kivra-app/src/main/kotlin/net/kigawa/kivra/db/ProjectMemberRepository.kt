package net.kigawa.kivra.db

import java.time.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/** The roles a project member may hold (V51 `project_members`, the same set as selfdev_assignees.role minus AI_APPROVER). */
val PROJECT_MEMBER_ROLES: Set<String> = setOf("ADMIN", "OPERATOR", "APPROVER", "AI", "VIEWER")

/** A member of a project: the assignee, its display name, its role in the project and its state (ACTIVE or DISABLED). */
data class ProjectMemberRow(val assigneeId: String, val displayName: String, val role: String, val state: String)

/** The result of [ProjectMemberRepository.put]. Only [CREATED] and [UPDATED] write. */
enum class MemberPutResult { CREATED, UPDATED, UNCHANGED, PROJECT_NOT_FOUND, ASSIGNEE_NOT_FOUND, LAST_ADMIN }

/** The result of [ProjectMemberRepository.remove]. */
enum class MemberRemoveResult { REMOVED, NOT_MEMBER, PROJECT_NOT_FOUND, LAST_ADMIN }

/**
 * The counts of [ProjectMemberRepository.importAll]. [candidates]: the ACTIVE assignees that are not members yet (what a real run adds).
 * [added]: the rows written (0 for a dry run). [alreadyMember]: ACTIVE assignees that already are members, whose role is left as it is.
 * [disabled]: the DISABLED assignees, which are never added.
 */
data class ImportAllResult(val dryRun: Boolean, val candidates: Long, val added: Long, val alreadyMember: Long, val disabled: Long)

/**
 * Persistence of project members (V51 `project_members`, ADR 0022 PR-D2). Writes are raw SQL through [execUpdate] and [execQuery].
 *
 * Invariant kept here (not in the database): a project with members keeps at least one member ADMIN. A change that would leave none is
 * refused ([MemberPutResult.LAST_ADMIN], [MemberRemoveResult.LAST_ADMIN]). Every write first locks the project's row with FOR UPDATE, so two
 * concurrent demotions of the two ADMINs cannot both pass the count. The check is about the project's own members only: a global ADMIN
 * is not exempt, and a project with no members has no ADMIN to protect.
 *
 * No display name, owner key or principal is ever logged or returned from here except by [list] (for the operator's screen).
 */
class ProjectMemberRepository(
    private val database: Database,
    private val clock: Clock,
) {

    /** The members of [projectId], oldest assignee first. Empty when the project has none or does not exist. Read only. */
    fun list(projectId: String): List<ProjectMemberRow> = transaction(database) {
        execQuery(
            """
            SELECT a.assignee_id, a.display_name, pm.role, a.state
              FROM project_members pm
              JOIN selfdev_assignees a ON a.assignee_id = pm.assignee_id
             WHERE pm.project_id = ?
             ORDER BY a.created_at, a.assignee_id
            """.trimIndent(),
            projectId,
        ) { ProjectMemberRow(assigneeId = it.getString(1), displayName = it.getString(2), role = it.getString(3), state = it.getString(4)) }
    }

    /** Whether [projectId] exists. Read only. */
    fun projectExists(projectId: String): Boolean = transaction(database) { projectExists(projectId, lock = false) }

    /**
     * Adds [assigneeId] to [projectId] with [role], or changes the role of an existing member. Idempotent: the same role again is
     * [MemberPutResult.UNCHANGED] and writes nothing.
     */
    fun put(projectId: String, assigneeId: String, role: String): MemberPutResult = transaction(database) {
        if (!projectExists(projectId, lock = true)) return@transaction MemberPutResult.PROJECT_NOT_FOUND
        if (!assigneeExists(assigneeId)) return@transaction MemberPutResult.ASSIGNEE_NOT_FOUND
        val current = roleOf(projectId, assigneeId)
        when {
            current == null -> {
                execUpdate(
                    "INSERT INTO project_members (project_id, assignee_id, role, created_at) VALUES (?, ?, ?, ?)",
                    projectId, assigneeId, role, utcNow(clock),
                )
                MemberPutResult.CREATED
            }
            current == role -> MemberPutResult.UNCHANGED
            current == "ADMIN" && adminCount(projectId) <= 1 -> MemberPutResult.LAST_ADMIN
            else -> {
                execUpdate("UPDATE project_members SET role = ? WHERE project_id = ? AND assignee_id = ?", role, projectId, assigneeId)
                MemberPutResult.UPDATED
            }
        }
    }

    /** Removes [assigneeId] from [projectId]. [MemberRemoveResult.NOT_MEMBER] when it is not a member. */
    fun remove(projectId: String, assigneeId: String): MemberRemoveResult = transaction(database) {
        if (!projectExists(projectId, lock = true)) return@transaction MemberRemoveResult.PROJECT_NOT_FOUND
        val current = roleOf(projectId, assigneeId) ?: return@transaction MemberRemoveResult.NOT_MEMBER
        if (current == "ADMIN" && adminCount(projectId) <= 1) return@transaction MemberRemoveResult.LAST_ADMIN
        execUpdate("DELETE FROM project_members WHERE project_id = ? AND assignee_id = ?", projectId, assigneeId)
        MemberRemoveResult.REMOVED
    }

    /**
     * Adds every ACTIVE assignee that is not a member yet, with [role]. A member keeps its role. DISABLED assignees are never added.
     * A dry run counts and writes nothing (it does not lock). Null when the project does not exist.
     */
    fun importAll(projectId: String, role: String, dryRun: Boolean): ImportAllResult? = transaction(database) {
        if (!projectExists(projectId, lock = !dryRun)) return@transaction null
        val missing = execQuery(
            """
            SELECT a.assignee_id FROM selfdev_assignees a
             WHERE a.state = 'ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM project_members pm WHERE pm.project_id = ? AND pm.assignee_id = a.assignee_id)
             ORDER BY a.created_at, a.assignee_id
            """.trimIndent(),
            projectId,
        ) { it.getString(1) }
        val alreadyMember = count(
            "SELECT COUNT(*) FROM selfdev_assignees a WHERE a.state = 'ACTIVE' AND EXISTS " +
                "(SELECT 1 FROM project_members pm WHERE pm.project_id = ? AND pm.assignee_id = a.assignee_id)",
            projectId,
        )
        val disabled = count("SELECT COUNT(*) FROM selfdev_assignees WHERE state = 'DISABLED'")
        var added = 0L
        if (!dryRun) {
            val now = utcNow(clock)
            missing.forEach { assigneeId ->
                execUpdate(
                    "INSERT INTO project_members (project_id, assignee_id, role, created_at) VALUES (?, ?, ?, ?)",
                    projectId, assigneeId, role, now,
                )
                added++
            }
        }
        ImportAllResult(dryRun = dryRun, candidates = missing.size.toLong(), added = added, alreadyMember = alreadyMember, disabled = disabled)
    }

    /** Locks the project's row when [lock] (FOR UPDATE serialises the writes of one project). True when the project exists. */
    private fun Transaction.projectExists(projectId: String, lock: Boolean): Boolean =
        execQuery("SELECT project_id FROM projects WHERE project_id = ?" + if (lock) " FOR UPDATE" else "", projectId) { it.getString(1) }
            .isNotEmpty()

    private fun Transaction.assigneeExists(assigneeId: String): Boolean =
        count("SELECT COUNT(*) FROM selfdev_assignees WHERE assignee_id = ?", assigneeId) > 0L

    private fun Transaction.roleOf(projectId: String, assigneeId: String): String? =
        execQuery("SELECT role FROM project_members WHERE project_id = ? AND assignee_id = ?", projectId, assigneeId) { it.getString(1) }
            .firstOrNull()

    private fun Transaction.adminCount(projectId: String): Long =
        count("SELECT COUNT(*) FROM project_members WHERE project_id = ? AND role = 'ADMIN'", projectId)

    private fun Transaction.count(sql: String, vararg params: Any?): Long =
        execQuery(sql, *params) { it.getLong(1) }.first()
}
