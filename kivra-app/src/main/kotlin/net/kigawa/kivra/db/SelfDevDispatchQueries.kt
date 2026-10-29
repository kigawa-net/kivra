package net.kigawa.kivra.db

import net.kigawa.kivra.core.autopilot.TaskState
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal class SelfDevDispatchQueries(private val database: Database) {
    fun taskIdsInStates(states: Set<TaskState>): List<String> = transaction(database) {
        SelfDevTasksTable.selectAll()
            .where { SelfDevTasksTable.state inList states.map { it.name } }
            .orderBy(SelfDevTasksTable.createdAt to SortOrder.ASC, SelfDevTasksTable.taskId to SortOrder.ASC)
            .map { it[SelfDevTasksTable.taskId] }
    }
}
