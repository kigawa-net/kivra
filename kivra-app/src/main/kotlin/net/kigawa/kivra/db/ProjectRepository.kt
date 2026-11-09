package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * The ledger cost of a set of Runs, in micro-yen (1 JPY = 1,000,000), by reservation state. RELEASED rows are not counted.
 * See [ProjectRepository.runSpend] for what is included.
 */
data class ProjectSpend(
    /** COMMITTED and ACKNOWLEDGED: the settled cost. */
    val committedMicroYen: Long,
    /** RESERVED: in progress, not charged yet. */
    val reservedMicroYen: Long,
    /** LAPSED: charged at the estimate, waiting for the true-up. */
    val lapsedMicroYen: Long,
    /** UNPRICED: charged at the estimate, not verified until an operator acknowledges it. */
    val unpricedMicroYen: Long,
)

/** A project that groups Runs. */
data class ProjectRow(
    val projectId: String,
    val name: String,
    val description: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/** The result of [ProjectRepository.addRepo]. */
enum class RepoAddResult { ADDED, CONFLICT, PROJECT_NOT_FOUND }

/** How many rows of each kind still refer to a project (ADR 0022). Counts only: no ids and no names. */
data class ProjectChildCounts(
    val repos: Long,
    val members: Long,
    val budget: Long,
    val tasks: Long,
    val orders: Long,
    val memory: Long,
    val runs: Long,
    val assignees: Long,
) {
    val total: Long get() = repos + members + budget + tasks + orders + memory + runs + assignees

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

/** The result of [ProjectRepository.deleteIfEmpty]. */
sealed interface ProjectDeleteResult {
    data object Deleted : ProjectDeleteResult
    data object NotFound : ProjectDeleteResult
    data class NotEmpty(val counts: ProjectChildCounts) : ProjectDeleteResult
}

/** Repository for projects (V38). Provides CRUD and the association between Runs and projects. */
class ProjectRepository(private val database: Database, private val clock: Clock) {

    /** Create a new project. Returns the project row. */
    fun create(name: String, description: String?): ProjectRow = transaction(database) {
        val id = Ulid.generate(clock)
        val now = utcNow(clock)
        ProjectsTable.insert {
            it[ProjectsTable.projectId] = id
            it[ProjectsTable.name] = name
            it[ProjectsTable.description] = description
            it[ProjectsTable.createdAt] = now
            it[ProjectsTable.updatedAt] = now
        }
        ProjectRow(projectId = id, name = name, description = description, createdAt = now, updatedAt = now)
    }

    /** Update an existing project. Returns the updated row, or null if not found. */
    fun update(projectId: String, name: String, description: String?): ProjectRow? = transaction(database) {
        val now = utcNow(clock)
        val updated = ProjectsTable.update({ ProjectsTable.projectId eq projectId }) {
            it[ProjectsTable.name] = name
            it[ProjectsTable.description] = description
            it[ProjectsTable.updatedAt] = now
        }
        if (updated == 0) return@transaction null
        val row = ProjectsTable.selectAll().where { ProjectsTable.projectId eq projectId }.firstOrNull()
            ?: return@transaction null
        toProject(row).copy(name = name, description = description, updatedAt = now)
    }

    /**
     * Deletes [projectId] only when no row refers to it (ADR 0022). Nothing is cascaded or unassigned: a repository, a member, a budget,
     * or a row of tasks, orders, memory, runs or assignees makes the answer [ProjectDeleteResult.NotEmpty], with the count of each kind.
     * The project row is locked (FOR UPDATE, as the member writes are) while the counts are read, so a child added by a concurrent write
     * waits for the outcome. The foreign keys stay the last defence: a violation at the delete is [ProjectDeleteResult.NotEmpty] too.
     */
    fun deleteIfEmpty(projectId: String): ProjectDeleteResult = try {
        transaction<ProjectDeleteResult>(database) {
            if (!projectRowExists(projectId, lock = true)) return@transaction ProjectDeleteResult.NotFound
            val counts = childCounts(projectId)
            if (counts.total > 0) return@transaction ProjectDeleteResult.NotEmpty(counts)
            ProjectsTable.deleteWhere { ProjectsTable.projectId eq projectId }
            ProjectDeleteResult.Deleted
        }
    } catch (e: ExposedSQLException) {
        if (!e.isForeignKeyViolation()) throw e
        // The transaction is rolled back. Read the answer again: the project may be gone, or a child row was added after the count.
        transaction<ProjectDeleteResult>(database) {
            if (!projectRowExists(projectId, lock = false)) ProjectDeleteResult.NotFound
            else ProjectDeleteResult.NotEmpty(childCounts(projectId))
        }
    }

    /** Get a project by id. */
    fun get(projectId: String): ProjectRow? = transaction(database) {
        readProject(projectId)
    }

    /** List all projects, ordered by name. */
    fun listAll(): List<ProjectRow> = transaction(database) {
        ProjectsTable.selectAll().orderBy(ProjectsTable.name).map { toProject(it) }
    }

    /**
     * The ledger cost of [projectId]'s Runs, in the dashboard's scope ([principal] null: every principal's Run). Read only.
     * A Run's cost is its CLI attempts (`coding:<attempt_id>:<lease_generation>`, via coding_attempts.run_id) and its API calls
     * (the Developer's `developer-<run_id>-` key prefix). The Planner's cost belongs to a cycle, not to one Run, so it is not
     * counted here.
     */
    fun runSpend(projectId: String, principal: String?): ProjectSpend = transaction(database) {
        val scope = if (principal == null) "" else " AND r.principal = ?"
        val principalParams = listOfNotNull(principal)
        val params = listOf<Any?>(projectId) + principalParams + listOf<Any?>(projectId) + principalParams
        val rows = execQuery(
            """
            SELECT res.state, res.amount_micro_yen
              FROM budget_reservations res
             WHERE res.reservation_key IN (
                   SELECT CONCAT('${DbBudgetStore.CODING_KEY_PREFIX}', a.attempt_id, ':', a.lease_generation)
                     FROM coding_attempts a
                     JOIN development_runs r ON r.run_id = a.run_id
                    WHERE r.project_id = ?$scope)
                OR EXISTS (
                   SELECT 1 FROM development_runs r
                    WHERE r.project_id = ?$scope
                      AND res.reservation_key LIKE CONCAT('${SelfDevCycleSpendReader.DEVELOPER_PREFIX}', r.run_id, '-%'))
            """.trimIndent(),
            *params.toTypedArray(),
        ) { rs -> rs.getString(1) to rs.getLong(2) }
        var committed = 0L
        var reserved = 0L
        var lapsed = 0L
        var unpriced = 0L
        rows.forEach { (state, amount) ->
            when (state) {
                DbBudgetStore.COMMITTED, DbBudgetStore.ACKNOWLEDGED -> committed += amount
                DbBudgetStore.RESERVED -> reserved += amount
                DbBudgetStore.LAPSED -> lapsed += amount
                DbBudgetStore.UNPRICED -> unpriced += amount
                DbBudgetStore.RELEASED -> Unit
                // As in SelfDevCycleSpendReader: an unknown state is not counted as zero.
                else -> error("unknown reservation state in the budget ledger")
            }
        }
        ProjectSpend(committedMicroYen = committed, reservedMicroYen = reserved, lapsedMicroYen = lapsed, unpricedMicroYen = unpriced)
    }

    /** Assign a run to a project. Returns true if the run existed. */
    fun assignRunToProject(runId: String, projectId: String): Boolean = transaction(database) {
        if (readProject(projectId) == null) return@transaction false
        val updated = DevelopmentRunsTable.update({ DevelopmentRunsTable.runId eq runId }) {
            it[this.projectId] = projectId
        }
        updated > 0
    }

    /** Remove a run from its project (unassign). Returns true if the run existed. */
    fun unassignRun(runId: String): Boolean = transaction(database) {
        val updated = DevelopmentRunsTable.update({ DevelopmentRunsTable.runId eq runId }) {
            it[this.projectId] = null
        }
        updated > 0
    }

    /** The repositories (owner/name) of [projectId], ordered. Empty when the project has none or does not exist. Read only. */
    fun listRepos(projectId: String): List<String> = transaction(database) {
        ProjectReposTable.selectAll().where { ProjectReposTable.projectId eq projectId }
            .orderBy(ProjectReposTable.repoKey)
            .map { it[ProjectReposTable.repoKey] }
    }

    /**
     * Adds [repoKey] to [projectId] (V51). [RepoAddResult.CONFLICT] when the repository already belongs to a project (the same one
     * included), [RepoAddResult.PROJECT_NOT_FOUND] when the project does not exist.
     */
    fun addRepo(projectId: String, repoKey: String): RepoAddResult {
        val exists = transaction(database) { readProject(projectId) != null }
        if (!exists) return RepoAddResult.PROJECT_NOT_FOUND
        return try {
            transaction(database) {
                ProjectReposTable.insert {
                    it[ProjectReposTable.projectId] = projectId
                    it[ProjectReposTable.repoKey] = repoKey
                    it[createdAt] = utcNow(clock)
                }
            }
            RepoAddResult.ADDED
        } catch (e: ExposedSQLException) {
            // The UNIQUE on repo_key (or the project's row, deleted in between: a foreign key violation) decides the answer.
            if (e.isUniqueViolation()) RepoAddResult.CONFLICT else throw e
        }
    }

    /** Removes [repoKey] from [projectId]. Returns true if the mapping existed. */
    fun removeRepo(projectId: String, repoKey: String): Boolean = transaction(database) {
        ProjectReposTable.deleteWhere {
            (ProjectReposTable.projectId eq projectId) and (ProjectReposTable.repoKey eq repoKey)
        } > 0
    }

    /** Whether the project row exists; with [lock], the row is locked until the transaction ends. */
    private fun Transaction.projectRowExists(projectId: String, lock: Boolean): Boolean =
        execQuery("SELECT project_id FROM projects WHERE project_id = ?" + if (lock) " FOR UPDATE" else "", projectId) { it.getString(1) }
            .isNotEmpty()

    /** The rows that refer to [projectId], by kind: the RESTRICT foreign keys and the SET NULL ones (runs, orders, assignees) alike. */
    private fun Transaction.childCounts(projectId: String) = ProjectChildCounts(
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

    private fun readProject(projectId: String): ProjectRow? =
        ProjectsTable.selectAll().where { ProjectsTable.projectId eq projectId }.firstOrNull()?.let { toProject(it) }

    private fun toProject(row: org.jetbrains.exposed.sql.ResultRow) = ProjectRow(
        projectId = row[ProjectsTable.projectId],
        name = row[ProjectsTable.name],
        description = row[ProjectsTable.description],
        createdAt = row[ProjectsTable.createdAt],
        updatedAt = row[ProjectsTable.updatedAt],
    )
}
