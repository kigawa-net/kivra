package net.kigawa.kivra.db

import java.time.LocalDateTime
import net.kigawa.kivra.core.DevelopmentRunState
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The rows of each kind that a drain moves (or, for a dry run, would move). [repos] are re-pointed to the target. [members] and [budget]
 * are removed from the source (they are not moved). Counts only: no ids and no names.
 */
data class DrainCounts(
    val repos: Long,
    val members: Long,
    val budget: Long,
    val tasks: Long,
    val orders: Long,
    val memory: Long,
    val runs: Long,
    val assignees: Long,
) {
    /** The kinds in a fixed order, for the API and the audit line. */
    fun toMap(): Map<String, Long> = linkedMapOf(
        "repos" to repos,
        "members" to members,
        "budget" to budget,
        "tasks" to tasks,
        "orders" to orders,
        "memory" to memory,
        "runs" to runs,
        "assignees" to assignees,
    )
}

/** What keeps a drain from running: the source's Runs that are not terminal, and the RESERVED budget reservations under its scope. */
data class DrainBusy(val activeRuns: Long, val reservedBudget: Long)

/** The result of [ProjectDrainRepository.drain]. */
sealed interface ProjectDrainResult {
    /** A dry run (nothing written) or a drain (written). [counts] is what was, or would be, moved. */
    data class Done(val dryRun: Boolean, val counts: DrainCounts) : ProjectDrainResult

    /** The source or the target does not exist. */
    data object NotFound : ProjectDrainResult

    /** The drain is refused, and nothing is written. */
    data class Busy(val busy: DrainBusy) : ProjectDrainResult
}

/**
 * Empties a project into another one (ADR 0022, the drain). Every row of the source gets the target's id: tasks, orders, memory records,
 * Runs and assignees. The repositories are re-pointed (a repo_key is UNIQUE, so none of them can collide with the target). The source's
 * members and its budget row are removed; the target's members and budget are not touched. Nothing is deleted, so after a drain the
 * source has no rows and `DELETE /api/projects/{id}` succeeds.
 *
 * Refused with [ProjectDrainResult.Busy] while the source has a Run that is not terminal, or a RESERVED budget reservation under its scope.
 * A reservation keeps the scopes it was made with, but a Run that is still running would book its later spend to the target, and its
 * admission would be judged against the wrong limit. The target's own Runs do not block: they keep their project.
 *
 * Locks, in this order (see the ADR): the two project rows FOR UPDATE, by id; then the rows of the moved tables, each batch FOR UPDATE; then
 * the tenant budget rows (the ADR 0015 order: order rows before the tenant budget rows, so no cycle waits on it); then the source's budget
 * row. The whole drain is one transaction: it either moves everything or nothing. A dry run takes no lock and writes nothing, so its busy
 * answer can be stale; the drain itself is the authority.
 */
class ProjectDrainRepository(
    private val database: Database,
    private val budgets: DbBudgetStore,
    private val batchSize: Int = ProjectBackfillRepository.DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    /** Drains [from] into [to]. The caller checks that they differ and are well formed; [dryRun] true counts only. */
    fun drain(from: String, to: String, dryRun: Boolean): ProjectDrainResult {
        require(from != to) { "a drain needs two different projects" }
        return try {
            transaction(database) {
                if (lockProjects(from, to, lock = !dryRun).size < 2) return@transaction ProjectDrainResult.NotFound
                if (dryRun) {
                    val busy = DrainBusy(activeRunsOf(from, lock = false), reservedBudgetOf(from))
                    if (busy.activeRuns > 0 || busy.reservedBudget > 0) return@transaction ProjectDrainResult.Busy(busy)
                    return@transaction ProjectDrainResult.Done(dryRun = true, counts = countsOf(from))
                }
                ProjectDrainResult.Done(dryRun = false, counts = moveAll(from, to))
            }
        } catch (e: DrainBlocked) {
            ProjectDrainResult.Busy(e.busy)
        }
    }

    /** The real drain, in the caller's transaction. Throws [DrainBlocked] (which rolls every write back) when it is refused. */
    private fun Transaction.moveAll(from: String, to: String): DrainCounts {
        val activeRuns = activeRunsOf(from, lock = true)
        if (activeRuns > 0) throw DrainBlocked(DrainBusy(activeRuns, reservedBudgetOf(from)))
        val repos = moveRepos(from, to)
        val members = execUpdate("DELETE FROM project_members WHERE project_id = ?", from).toLong()
        val tasks = moveProjectRows("selfdev_tasks", "task_id", from, to, batchSize)
        val orders = moveProjectRows("selfdev_orders", "order_id", from, to, batchSize)
        val memory = moveMemoryRecordsOfProject(from, to, batchSize)
        val runs = moveProjectRows("development_runs", "run_id", from, to, batchSize)
        val assignees = moveProjectRows("selfdev_assignees", "assignee_id", from, to, batchSize)
        // The budget comes after the rows (ADR 0015 order). Under the tenant lock the expired reservations are settled first, so the
        // RESERVED count below is the current one.
        budgets.lockTenantForDrain(this)
        val reserved = reservedBudgetOf(from)
        if (reserved > 0) throw DrainBlocked(DrainBusy(activeRuns = 0, reservedBudget = reserved))
        val budget = execUpdate("DELETE FROM project_budgets WHERE project_id = ?", from).toLong()
        return DrainCounts(
            repos = repos,
            members = members,
            budget = budget,
            tasks = tasks,
            orders = orders,
            memory = memory,
            runs = runs,
            assignees = assignees,
        )
    }

    /**
     * Re-points the repositories of [from] to [to]. A repo_key is UNIQUE, so each row is removed from [from] before it is inserted for [to]
     * (in this transaction, so no other project sees it missing). created_at is kept. Returns the rows moved.
     */
    private fun Transaction.moveRepos(from: String, to: String): Long {
        val rows = execQuery("SELECT repo_key, created_at FROM project_repos WHERE project_id = ? FOR UPDATE", from) {
            it.getString(1) to it.getObject(2, LocalDateTime::class.java)
        }
        if (rows.isEmpty()) return 0L
        val removed = execUpdate("DELETE FROM project_repos WHERE project_id = ?", from)
        check(removed == rows.size) { "a repository of the drain changed under its lock" }
        rows.forEach { (repoKey, createdAt) ->
            execUpdate("INSERT INTO project_repos (project_id, repo_key, created_at) VALUES (?, ?, ?)", to, repoKey, createdAt)
        }
        return rows.size.toLong()
    }

    /** The two project rows, in id order (FOR UPDATE when [lock]). The ids that exist. */
    private fun Transaction.lockProjects(from: String, to: String, lock: Boolean): List<String> {
        val (first, second) = listOf(from, to).sorted()
        return execQuery(
            "SELECT project_id FROM projects WHERE project_id IN (?, ?) ORDER BY project_id" + if (lock) " FOR UPDATE" else "",
            first,
            second,
        ) { it.getString(1) }
    }

    /** The Runs of [projectId] that are not terminal. With [lock], the rows are locked (a locking read sees the latest committed version). */
    private fun Transaction.activeRunsOf(projectId: String, lock: Boolean): Long = execQuery(
        "SELECT run_id FROM development_runs WHERE project_id = ? AND state NOT IN ($TERMINAL_RUN_STATES)" + if (lock) " FOR UPDATE" else "",
        projectId,
    ) { it.getString(1) }.size.toLong()

    /** The RESERVED reservations under the project's scope: in progress and not charged yet. */
    private fun Transaction.reservedBudgetOf(projectId: String): Long = countOf(
        "SELECT COUNT(*) FROM budget_reservation_scopes s JOIN budget_reservations r ON r.reservation_key = s.reservation_key " +
            "WHERE s.scope_id = ? AND r.state = ?",
        DbBudgetStore.projectScope(projectId),
        DbBudgetStore.RESERVED,
    )

    /** The rows that refer to [projectId], by kind (the dry run's counts). */
    private fun Transaction.countsOf(projectId: String) = DrainCounts(
        repos = countOf("SELECT COUNT(*) FROM project_repos WHERE project_id = ?", projectId),
        members = countOf("SELECT COUNT(*) FROM project_members WHERE project_id = ?", projectId),
        budget = countOf("SELECT COUNT(*) FROM project_budgets WHERE project_id = ?", projectId),
        tasks = countOf("SELECT COUNT(*) FROM selfdev_tasks WHERE project_id = ?", projectId),
        orders = countOf("SELECT COUNT(*) FROM selfdev_orders WHERE project_id = ?", projectId),
        memory = countMemoryRecordsOfProject(projectId),
        runs = countOf("SELECT COUNT(*) FROM development_runs WHERE project_id = ?", projectId),
        assignees = countOf("SELECT COUNT(*) FROM selfdev_assignees WHERE project_id = ?", projectId),
    )

    private fun Transaction.countOf(sql: String, vararg params: Any?): Long = execQuery(sql, *params) { it.getLong(1) }.first()

    private companion object {
        /** The SQL list of the terminal Run states (COMPLETED, FAILED, CANCELLED), from the state machine. */
        val TERMINAL_RUN_STATES: String = DevelopmentRunState.terminalNames.joinToString(",") { "'$it'" }
    }
}

/** Aborts a drain that is refused after some rows were written: the transaction rolls back, and the caller gets [busy]. */
private class DrainBlocked(val busy: DrainBusy) : RuntimeException(null, null, false, false)

/**
 * Moves the rows of [table] whose project is [from] to [to], in batches of [batchSize] (ADR 0022, the drain). Each batch selects its rows
 * FOR UPDATE, so it sees the latest committed version, and the UPDATE must match exactly those rows; otherwise the transaction fails and
 * rolls back. The loop ends when no row of [from] is left. Returns the rows moved. The table name is the caller's; the callers pass
 * constants only.
 */
internal fun Transaction.moveProjectRows(table: String, pk: String, from: String, to: String, batchSize: Int): Long {
    var moved = 0L
    while (true) {
        val ids = execQuery("SELECT $pk FROM $table WHERE project_id = ? ORDER BY $pk LIMIT ? FOR UPDATE", from, batchSize) { it.getString(1) }
        if (ids.isEmpty()) return moved
        val updated = execUpdate(
            "UPDATE $table SET project_id = ? WHERE project_id = ? AND $pk IN (${ids.joinToString(",") { "?" }})",
            to,
            from,
            *ids.toTypedArray(),
        )
        check(updated == ids.size) { "a row of the drain changed under its lock" }
        moved += updated
    }
}
