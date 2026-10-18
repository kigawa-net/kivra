package net.kigawa.kivra.db

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The V18 constraints, checked by the database itself (not by the repositories): a row the repositories would never write must
 * still be refused. Runs on H2 by default and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 */
class SelfDevAutopilotSchemaTest {

    private fun TestDb.raw(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private val goalInsert = """
        INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, body, target_repo, state, revision, version, created_at, updated_at)
        VALUES (?, 'op', ?, ?, ?, 'r/r', ?, 0, 0, NOW(6), NOW(6))
    """.trimIndent()

    @Test
    fun `the control row is seeded once, and only the single row with id 1 is allowed`() = TestDb().use { db ->
        assertEquals(1, db.count("selfdev_autopilot_control", "control_id = 1 AND paused = FALSE AND breaker_open = FALSE"))
        assertFailsWith<SQLException> {
            db.raw(
                "INSERT INTO selfdev_autopilot_control (control_id, paused, breaker_open, consecutive_failures, runs_today, version) VALUES (2, FALSE, FALSE, 0, 0, 0)",
            )
        }
        assertFailsWith<SQLException> {
            db.raw(
                "INSERT INTO selfdev_autopilot_control (control_id, paused, breaker_open, consecutive_failures, runs_today, version) VALUES (1, FALSE, FALSE, 0, 0, 0)",
            )
        }
    }

    @Test
    fun `a goal state outside the table, a revision over the cap, and an over-long title are refused`() = TestDb().use { db ->
        assertFailsWith<SQLException> { db.raw(goalInsert, "01AAAAAAAAAAAAAAAAAAAAAAAA", "k1", "t", null, "BOGUS") }
        assertFailsWith<SQLException> {
            db.raw(
                "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, revision, version, created_at, updated_at) " +
                    "VALUES ('01AAAAAAAAAAAAAAAAAAAAAAAB', 'op', 'k2', 't', 'r/r', 'GOAL_SUBMITTED', 4, 0, NOW(6), NOW(6))",
            )
        }
        assertFailsWith<SQLException> {
            db.raw(goalInsert, "01AAAAAAAAAAAAAAAAAAAAAAAC", "k3", "t".repeat(201), null, "GOAL_SUBMITTED")
        }
        assertFailsWith<SQLException> {
            db.raw(goalInsert, "01AAAAAAAAAAAAAAAAAAAAAAAD", "k4", "t", "b".repeat(8001), "GOAL_SUBMITTED")
        }
    }

    @Test
    fun `a paused goal can record only the states it may return to`() = TestDb().use { db ->
        assertFailsWith<SQLException> {
            db.raw(
                "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, resume_state, revision, version, created_at, updated_at) " +
                    "VALUES ('01AAAAAAAAAAAAAAAAAAAAAAAE', 'op', 'k5', 't', 'r/r', 'PAUSED', 'DONE', 0, 0, NOW(6), NOW(6))",
            )
        }
    }

    @Test
    fun `a task cannot point at a Run that does not exist`() = TestDb().use { db ->
        db.raw(goalInsert, "01AAAAAAAAAAAAAAAAAAAAAAAF", "k6", "t", null, "GOAL_SUBMITTED")
        db.raw(
            "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) " +
                "VALUES ('01AAAAAAAAAAAAAAAAAAAAAAAG', '01AAAAAAAAAAAAAAAAAAAAAAAF', 1, 'PROPOSED', 'm', '${"a".repeat(64)}', NOW(6))",
        )
        assertFailsWith<SQLException> {
            db.raw(
                "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, target_paths, size_estimate, depends_on, requires_approval, state, idempotency_key, run_id, attempts, version, created_at, updated_at) " +
                    "VALUES ('01AAAAAAAAAAAAAAAAAAAAAAAG', 1, 't', '[]', 'S', '[]', FALSE, 'SUBMITTED', 'ap:x:1', '01AAAAAAAAAAAAAAAAAAAAAAZZ', 0, 0, NOW(6), NOW(6))",
            )
        }
    }
}
