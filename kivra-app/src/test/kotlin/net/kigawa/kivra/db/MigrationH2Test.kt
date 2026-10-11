package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.transactions.transaction

class MigrationH2Test {
    @Test
    fun `flyway applies V1 on h2 and creates every core table`() = TestDb().use { db ->
        val tables = transaction(db.database) {
            execQuery("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'") { it.getString(1) }
        }.toSet()
        val expected = setOf(
            "task_runs", "agents", "execution_sessions", "operation_journal",
            "outbox_events", "inbox_events", "audit_events",
        )
        assertEquals(expected, tables.intersect(expected))
    }

    @Test
    fun `exposed table definitions match the migrated columns`() = TestDb().use { db ->
        // Selecting every mapped column fails if a definition drifts from the DDL.
        transaction(db.database) {
            listOf(
                TaskRunsTable, AgentsTable, ExecutionSessionsTable, OperationJournalTable,
                OutboxEventsTable, InboxEventsTable, AuditEventsTable,
            ).forEach { table ->
                val cols = table.columns.joinToString(", ") { it.name }
                execQuery("SELECT $cols FROM ${table.tableName}") { 1 }
            }
        }
    }
}
