package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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

    /** Delete a project. Runs are unassigned (FK ON DELETE SET NULL). Returns true if deleted. */
    fun delete(projectId: String): Boolean = transaction(database) {
        ProjectsTable.deleteWhere { ProjectsTable.projectId eq projectId } > 0
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
