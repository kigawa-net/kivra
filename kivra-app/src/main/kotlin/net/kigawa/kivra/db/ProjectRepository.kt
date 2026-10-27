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
