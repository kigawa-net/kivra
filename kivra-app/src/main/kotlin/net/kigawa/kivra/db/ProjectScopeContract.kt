package net.kigawa.kivra.db

import javax.sql.DataSource

/**
 * ADR 0022 PR-F (Contract). From V52, the project column of the tasks and of the memory records is NOT NULL. The write paths
 * refuse a row with no project before they reach the database, so the same rule holds on V51 (while the gate holds V52) and on V52.
 */
internal const val PROJECT_CONTRACT_PREVIOUS_VERSION = "51"

/** Raised instead of writing a task or a plan row that has no project. Nothing is written when it is thrown (the transaction rolls back). */
class ProjectRequiredException(message: String) : IllegalStateException(message)

/**
 * The NULL-project counts of the two tables V52 makes NOT NULL, keyed `tasks` and `memory`. Counts only: no ids and no text.
 * The memory count lives in SelfDevMemoryRepository.kt, the only file that names the memory tables.
 */
internal fun countUnassignedScopedRows(dataSource: DataSource): Map<String, Long> = mapOf(
    "tasks" to countUnassignedTasks(dataSource),
    "memory" to countUnassignedMemoryRecords(dataSource),
)

private fun countUnassignedTasks(dataSource: DataSource): Long =
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM selfdev_tasks WHERE project_id IS NULL").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }
