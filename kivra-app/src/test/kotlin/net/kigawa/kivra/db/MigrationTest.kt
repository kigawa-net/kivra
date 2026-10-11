package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.sql.transactions.transaction

class MigrationTest {
    @Test
    fun `flyway applies V1 and V2 and creates every core table`() = TestDb().use { db ->
        val tables = transaction(db.database) {
            execQuery("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ${TestDb.schemaSql}") { it.getString(1) }
        }.toSet()
        val expected = setOf(
            "development_runs", "stage_executions", "agents", "execution_sessions", "external_operations",
            "outbox_events", "inbox_events", "audit_events",
        )
        assertEquals(expected, tables.intersect(expected))
    }

    @Test
    fun `there is a single run state table and no task_runs`() = TestDb().use { db ->
        val tables = transaction(db.database) {
            execQuery("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ${TestDb.schemaSql}") { it.getString(1) }
        }.toSet()
        assertEquals(false, "task_runs" in tables)
        assertEquals(true, "development_runs" in tables)
    }

    @Test
    fun `stage attempts are unique per run, stage and attempt number`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        fun insert() = transaction(db.database) {
            execUpdate(
                "INSERT INTO stage_executions (stage_exec_id, run_id, stage, attempt_no, lease_generation, status, started_at) VALUES (?, ?, 'DEVELOP', 1, 1, 'RUNNING', NOW(6))",
                Ulid.generate(), run.runId.value,
            )
        }
        insert()
        val ex = kotlin.test.assertFailsWith<Exception> { insert() }
        assertEquals(true, generateSequence(ex as Throwable) { it.cause }.any { (it as? java.sql.SQLException)?.let { s -> s.errorCode == 1062 || s.sqlState == "23505" } == true })
    }

    @Test
    fun `exposed table definitions match the migrated columns`() = TestDb().use { db ->
        // Selecting every mapped column fails if a definition drifts from the DDL.
        transaction(db.database) {
            listOf(
                DevelopmentRunsTable, StageExecutionsTable, AgentsTable, ExecutionSessionsTable, ExternalOperationsTable,
                OutboxEventsTable, InboxEventsTable, AuditEventsTable,
            ).forEach { table ->
                val cols = table.columns.joinToString(", ") { it.name }
                execQuery("SELECT $cols FROM ${table.tableName}") { 1 }
            }
        }
    }
}
