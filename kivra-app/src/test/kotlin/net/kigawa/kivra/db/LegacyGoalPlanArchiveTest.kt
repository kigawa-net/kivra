package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * V40 drops selfdev_goals, selfdev_plans and selfdev_plan_tasks. On a database at V39 with Goal/Plan rows, [runMigrations] must copy
 * those rows into the `_pre_v40` archives first, so the data is still there after V40. A re-run must not touch an existing archive.
 */
class LegacyGoalPlanArchiveTest {

    private val goalId = "0".repeat(25) + "1"
    private val planId = "0".repeat(25) + "2"

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun TestDb.count(table: String): Long = transaction(database) {
        execQuery("SELECT COUNT(*) FROM $table") { it.getLong(1) }.single()
    }

    private fun TestDb.titles(table: String): List<String> = transaction(database) {
        execQuery("SELECT title FROM $table ORDER BY title") { it.getString(1) }
    }

    /** Old-shape rows at V39: one goal, one proposed plan, two plan tasks (the plan task rows are the ones V40 would lose). */
    private fun TestDb.seedLegacyRows() {
        exec(
            "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, created_at, updated_at) " +
                "VALUES (?, 'op', 'goal-1', 'legacy goal', 'repo', 'DONE', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
            goalId,
        )
        exec(
            "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) " +
                "VALUES (?, ?, 1, 'PROPOSED', 'model', ?, TIMESTAMP '2026-01-01 00:00:00')",
            planId, goalId, "a".repeat(64),
        )
        for (ordinal in 1..2) {
            exec(
                "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, target_paths, size_estimate, depends_on, requires_approval, " +
                    "state, idempotency_key, created_at, updated_at) VALUES (?, ?, ?, '[]', 'S', '[]', TRUE, 'PENDING_APPROVAL', ?, " +
                    "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
                planId, ordinal, "legacy task $ordinal", "ap:$planId:$ordinal",
            )
        }
    }

    @Test
    fun `rows of the Goal and Plan tables survive V40 in the pre_v40 archives`() {
        TestDb(migrate = { migrateOnly(it, *MIGRATION_FILES.keys.filter { v -> v <= 39 }.toIntArray()) }).use { db ->
            db.seedLegacyRows()

            runMigrations(db.dataSource)

            assertEquals(listOf("legacy goal"), db.titles("selfdev_goals_pre_v40"))
            assertEquals(1, db.count("selfdev_plans_pre_v40"))
            assertEquals(listOf("legacy task 1", "legacy task 2"), db.titles("selfdev_plan_tasks_pre_v40"))
            assertEquals(0, db.count("selfdev_tasks"))
            assertTrue(appliedVersions(db.dataSource).contains("40"))
        }
    }

    @Test
    fun `a second run keeps the archives as they are and V40 stays applied once`() {
        TestDb(migrate = { migrateOnly(it, *MIGRATION_FILES.keys.filter { v -> v <= 39 }.toIntArray()) }).use { db ->
            db.seedLegacyRows()
            runMigrations(db.dataSource)

            runMigrations(db.dataSource)

            assertEquals(1, db.count("selfdev_goals_pre_v40"))
            assertEquals(2, db.count("selfdev_plan_tasks_pre_v40"))
            assertTrue(appliedVersions(db.dataSource).contains("52"))
        }
    }
}
