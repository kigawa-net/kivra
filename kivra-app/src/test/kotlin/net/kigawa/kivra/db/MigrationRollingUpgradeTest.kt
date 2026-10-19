package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Rolling-update shape of the migration chain (Issue #88): a database that is still at an older version (the schema the
 * old Writer runs against) gets the later migrations applied, and its existing rows are kept. Runs on H2 by default and on
 * MariaDB with `KIVRA_TEST_DB=mariadb`. The full chain on an empty database is covered by [MigrationChainTest].
 */
class MigrationRollingUpgradeTest {

    @Test
    fun `a database at every version from V2 to V10 upgrades to V24 and keeps its rows`() {
        for (current in 2..10) {
            TestDb { ds -> migrateOnly(ds, *(1..current).toList().toIntArray()) }.use { db ->
                val runId = db.insertRun(task = "task-at-v$current")
                val opId = db.insertOperation(runId, key = "key-at-v$current")
                val runBefore = db.runRow(runId)
                val opBefore = db.opRow(opId)

                runMigrations(db.dataSource)
                classpathFlyway(db.dataSource).validate()
                assertEquals(((1..21).map { it.toString() } + "24"), appliedVersions(db.dataSource), "from V$current")

                // Rows are kept as written. New columns stay NULL, nothing is backfilled.
                assertEquals(runBefore, db.runRow(runId), "run row changed upgrading from V$current")
                assertEquals(opBefore, db.opRow(opId), "operation row changed upgrading from V$current")
                assertNull(db.runNullableColumn(runId, "principal"), "from V$current")
                assertNull(db.runNullableColumn(runId, "pr_number"), "from V$current")

                // The mapped repositories still read the upgraded rows.
                assertNotNull(db.runs.get(RunId(runId)), "from V$current")
                assertNotNull(db.journal.get(opId), "from V$current")
                assertEquals(0, db.count("budget_periods"), "budget ledger exists from V$current")
            }
        }
    }

    @Test
    fun `a database at V1 upgrades to V24 without any rows`() = TestDb { ds -> migrateOnly(ds, 1) }.use { db ->
        runMigrations(db.dataSource)
        classpathFlyway(db.dataSource).validate()
        assertEquals(((1..21).map { it.toString() } + "24"), appliedVersions(db.dataSource))
    }

    /** The later migrations' indexes, keys and NOT NULL columns exist on the full chain (names are the contract for runbooks). */
    @Test
    fun `the full chain creates the indexes, keys and NOT NULL columns of V2 to V15`() = TestDb().use { db ->
        val indexes = db.names(
            "SELECT LOWER(INDEX_NAME) FROM INFORMATION_SCHEMA.${if (TestDb.isMariaDb) "STATISTICS" else "INDEXES"} WHERE TABLE_SCHEMA = ${TestDb.schemaSql}",
        )
        val constraints = db.names(
            "SELECT LOWER(CONSTRAINT_NAME) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = ${TestDb.schemaSql}",
        )
        for (name in listOf(
            "uk_dev_runs_active_task", "ix_dev_runs_recover", "ix_dev_runs_retry", "uk_dev_runs_principal_idem",
            "ix_budget_reservations_state_expiry", "ix_budget_reservation_scopes_period",
        )) {
            assertTrue(name in indexes || name in constraints, "missing index $name")
        }
        // MariaDB always names a primary key PRIMARY and ignores the declared name, so primary keys are checked per table.
        val primaryKeyTables = db.names(
            "SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = ${TestDb.schemaSql} " +
                "AND CONSTRAINT_TYPE = 'PRIMARY KEY'",
        )
        for (table in listOf("budget_periods", "budget_reservations", "budget_reservation_scopes", "evidence_authority_epochs")) {
            assertTrue(table in primaryKeyTables, "missing primary key on $table")
        }
        assertTrue("fk_budget_reservation_scopes_key" in constraints, "missing foreign key fk_budget_reservation_scopes_key")
        assertEquals("NO", db.nullable("budget_periods", "hard_micro_yen"))
        assertEquals("NO", db.nullable("budget_periods", "committed_micro_yen"))
        assertEquals("NO", db.nullable("development_runs", "completion_mode"))
        assertEquals("YES", db.nullable("external_operations", "tenant_id"))
        assertEquals("YES", db.nullable("development_runs", "principal"))
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** Inserts a run with the columns that exist from V2 on (completion_mode is NOT NULL since V2). */
    private fun TestDb.insertRun(task: String): String {
        val runId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO development_runs
                    (run_id, task_id, state, completion_mode, attempt, plan_revision, lease_generation, cancel_requested, version, created_at, updated_at)
                VALUES (?, ?, 'CREATED', ?, 0, 0, 0, FALSE, 0, NOW(6), NOW(6))
                """.trimIndent(),
                runId, task, RunCompletionMode.RELEASE.name,
            )
        }
        return runId
    }

    /** Inserts an external_operations row with the V1 columns only (what the pre-V4 Writer writes). */
    private fun TestDb.insertOperation(runId: String, key: String): String {
        val opId = Ulid.generate()
        transaction(database) {
            execUpdate(
                """
                INSERT INTO external_operations
                    (op_id, run_id, op_type, idempotency_key, external_system, stable_ref, request_hash, status,
                     attempt, max_attempts, lease_generation, version, created_at, updated_at)
                VALUES (?, ?, 'EXKES_CREATE_WORKSPACE', ?, 'EXKES', 'kivra-run-rolling', ?, 'IN_FLIGHT', 1, 3, 1, 1, NOW(6), NOW(6))
                """.trimIndent(),
                opId, runId, key, "b".repeat(64),
            )
        }
        return opId
    }

    /** The columns that exist in every version: a stable image of the row. */
    private fun TestDb.runRow(runId: String): List<String?> = transaction(database) {
        execQuery(
            "SELECT task_id, state, completion_mode, version FROM development_runs WHERE run_id = ?", runId,
        ) { rs -> (1..4).map { rs.getString(it) } }.single()
    }

    private fun TestDb.opRow(opId: String): List<String?> = transaction(database) {
        execQuery(
            "SELECT op_type, idempotency_key, status, request_hash, version FROM external_operations WHERE op_id = ?", opId,
        ) { rs -> (1..5).map { rs.getString(it) } }.single()
    }

    /** A column added after V2: must be NULL on a row written before it existed. */
    private fun TestDb.runNullableColumn(runId: String, column: String): String? = transaction(database) {
        execQuery("SELECT $column FROM development_runs WHERE run_id = ?", runId) { it.getString(1) }.single()
    }

    private fun TestDb.count(table: String): Int = transaction(database) {
        execQuery("SELECT COUNT(*) FROM $table") { it.getInt(1) }.single()
    }

    private fun TestDb.names(sql: String): Set<String> = transaction(database) {
        execQuery(sql) { it.getString(1) }.filterNotNull().toSet()
    }

    private fun TestDb.nullable(table: String, column: String): String = transaction(database) {
        execQuery(
            "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ${TestDb.schemaSql} " +
                "AND LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?",
            table, column,
        ) { it.getString(1) }.single()
    }
}
