package net.kigawa.kivra.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The backfill of the `_pre_v40` archives into selfdev_tasks. The seed is V39-shaped; [runMigrations] archives it and applies V40 and later.
 * Then the backfill runs. Nothing it imports may be dispatchable by the autopilot.
 */
class LegacyGoalPlanBackfillTest {

    private val projectId = "0".repeat(25) + "P"
    private val goalA = "0".repeat(25) + "A"
    private val goalB = "0".repeat(25) + "B"
    private val goalC = "0".repeat(25) + "C"
    private val goalD = "0".repeat(25) + "D"
    private val planA1 = "0".repeat(25) + "1"
    private val planA2 = "0".repeat(25) + "2"
    private val planB = "0".repeat(25) + "3"
    private val planC = "0".repeat(25) + "4"

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    private fun TestDb.countRows(table: String): Long = transaction<Long>(database) {
        execQuery("SELECT COUNT(*) FROM $table") { it.getLong(1) }.single()
    }

    private fun TestDb.strings(sql: String): List<String> = transaction<List<String>>(database) {
        execQuery(sql) { it.getString(1) }
    }

    private fun TestDb.taskIdOf(title: String): String = strings("SELECT task_id FROM selfdev_tasks WHERE title = '$title'").single()

    /** Goal A (live plan rev 2, one superseded plan rev 1), B (DONE), C (unmapped repo), D (no plan). Plan A2 has seven tasks. */
    private fun TestDb.seedLegacyRows() {
        val ts = "TIMESTAMP '2026-01-01 00:00:00'"
        exec(
            "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, revision, created_at, updated_at) " +
                "VALUES (?, 'op', 'g-a', 'goal A', 'owner/repo-a', 'PLAN_PROPOSED', 2, $ts, $ts)",
            goalA,
        )
        exec(
            "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, revision, created_at, updated_at) " +
                "VALUES (?, 'op', 'g-b', 'goal B', 'owner/repo-a', 'DONE', 1, $ts, $ts)",
            goalB,
        )
        exec(
            "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, revision, created_at, updated_at) " +
                "VALUES (?, 'op', 'g-c', 'goal C', 'owner/unmapped', 'PLAN_PROPOSED', 1, $ts, $ts)",
            goalC,
        )
        exec(
            "INSERT INTO selfdev_goals (goal_id, created_by, idempotency_key, title, target_repo, state, revision, created_at, updated_at) " +
                "VALUES (?, 'op', 'g-d', 'goal D', 'owner/repo-a', 'GOAL_SUBMITTED', 0, $ts, $ts)",
            goalD,
        )
        exec(
            "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) " +
                "VALUES (?, ?, 1, 'SUPERSEDED', 'model', ?, $ts)",
            planA1, goalA, "a".repeat(64),
        )
        exec(
            "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) " +
                "VALUES (?, ?, 2, 'PROPOSED', 'model', ?, $ts)",
            planA2, goalA, "b".repeat(64),
        )
        exec(
            "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) " +
                "VALUES (?, ?, 1, 'PROPOSED', 'model', ?, $ts)",
            planB, goalB, "c".repeat(64),
        )
        exec(
            "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) " +
                "VALUES (?, ?, 1, 'PROPOSED', 'model', ?, $ts)",
            planC, goalC, "d".repeat(64),
        )
        // Superseded plan: its one task is not live.
        seedTask(planA1, 1, "superseded task", "PENDING_APPROVAL", "[]")
        // Live plan A2: 1 pending, 2 queued (depends on 1), 3 completed, 4 in flight, 5 failed, 6 depends on 5, 7 depends on 3.
        seedTask(planA2, 1, "task 1", "PENDING_APPROVAL", "[]")
        seedTask(planA2, 2, "task 2", "QUEUED", "[1]")
        seedTask(planA2, 3, "task 3 done", "COMPLETED", "[]")
        seedTask(planA2, 4, "task 4 in flight", "SUBMITTING", "[]")
        seedTask(planA2, 5, "task 5 failed", "FAILED", "[]")
        seedTask(planA2, 6, "task 6 after failed", "PENDING_APPROVAL", "[5]")
        seedTask(planA2, 7, "task 7 after done", "PENDING_APPROVAL", "[3]")
        seedTask(planB, 1, "task B1", "PENDING_APPROVAL", "[]")
        seedTask(planC, 1, "task C1", "PENDING_APPROVAL", "[]")
    }

    private fun TestDb.seedTask(planId: String, ordinal: Int, title: String, state: String, dependsOn: String) {
        exec(
            "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval, " +
                "state, idempotency_key, created_at, updated_at) VALUES (?, ?, ?, 'body', '[]', 'S', ?, TRUE, ?, ?, " +
                "TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00')",
            planId, ordinal, title, dependsOn, state, "ap:$planId:$ordinal",
        )
    }

    private fun TestDb.seedProject() {
        val ts = "TIMESTAMP '2026-01-01 00:00:00'"
        exec("INSERT INTO projects (project_id, name, created_at, updated_at) VALUES (?, 'p', $ts, $ts)", projectId)
        exec("INSERT INTO project_repos (project_id, repo_key, created_at) VALUES (?, 'owner/repo-a', $ts)", projectId)
    }

    /** The V39 database with the seed, archived and migrated to the latest version, with the project mapping in place. */
    private fun withSeededDb(block: (TestDb) -> Unit) {
        TestDb(migrate = { migrateOnly(it, *MIGRATION_FILES.keys.filter { v -> v <= 39 }.toIntArray()) }).use { db ->
            db.seedLegacyRows()
            runMigrations(db.dataSource)
            db.seedProject()
            block(db)
        }
    }

    @Test
    fun `dry run counts every archived row and writes nothing`() {
        withSeededDb { db ->
            val report = runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.DRY_RUN)

            assertEquals(4, report.archivedGoals)
            assertEquals(4, report.archivedPlans)
            assertEquals(10, report.archivedPlanTasks)
            assertEquals(
                mapOf("COMPLETED" to 1, "FAILED" to 1, "PENDING_APPROVAL" to 6, "QUEUED" to 1, "SUBMITTING" to 1),
                report.planTasksByLegacyState,
            )
            assertEquals(1, report.insertedRoots)
            assertEquals(0, report.alreadyPresentRoots)
            assertEquals(3, report.insertedTasks)
            assertEquals(0, report.alreadyPresentTasks)
            assertEquals(1, report.satisfiedDependenciesDropped)
            assertEquals(
                mapOf(
                    LegacyNotImported.PLAN_NOT_LIVE to 1,
                    LegacyNotImported.TASK_COMPLETED to 1,
                    LegacyNotImported.TASK_TERMINAL to 1,
                    LegacyNotImported.TASK_IN_FLIGHT to 1,
                    LegacyNotImported.DEPENDENCY_NOT_IMPORTED to 1,
                    LegacyNotImported.GOAL_ENDED to 1,
                    LegacyNotImported.NO_PROJECT_MAPPING to 1,
                ),
                report.notImportedTasks,
            )
            assertEquals(3, report.goalsNotImported)
            assertEquals(0L, db.countRows("selfdev_tasks"))
        }
    }

    @Test
    fun `apply imports only the safe rows into a non-dispatchable state`() {
        withSeededDb { db ->
            val report = runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.APPLY)

            assertEquals(1, report.insertedRoots)
            assertEquals(3, report.insertedTasks)
            assertEquals(4L, db.countRows("selfdev_tasks"))

            // The root is a SKIPPED plan root: never planned again, never approved, never dispatched.
            assertEquals(listOf("SKIPPED"), db.strings("SELECT state FROM selfdev_tasks WHERE title = 'goal A'"))
            assertEquals(
                listOf("PENDING_APPROVAL", "PENDING_APPROVAL", "PENDING_APPROVAL"),
                db.strings("SELECT state FROM selfdev_tasks WHERE parent_task_id IS NOT NULL ORDER BY title"),
            )
            // Every row is in the project of its repository; the children hang off the root.
            assertEquals(
                listOf(projectId, projectId, projectId, projectId),
                db.strings("SELECT project_id FROM selfdev_tasks ORDER BY title"),
            )
            val rootId = db.taskIdOf("goal A")
            assertEquals(
                listOf(rootId, rootId, rootId),
                db.strings("SELECT parent_task_id FROM selfdev_tasks WHERE parent_task_id IS NOT NULL ORDER BY title"),
            )
            // Task 2 depends on task 1 (new id); task 7's dependency on the completed task 3 is dropped.
            assertEquals(listOf("[\"${db.taskIdOf("task 1")}\"]"), db.strings("SELECT dependencies FROM selfdev_tasks WHERE title = 'task 2'"))
            assertEquals(listOf("[]"), db.strings("SELECT dependencies FROM selfdev_tasks WHERE title = 'task 7 after done'"))
            // Nothing carries a title or body that was not in the archive.
            assertEquals(listOf("task 1", "task 2", "task 7 after done"), db.strings("SELECT title FROM selfdev_tasks WHERE parent_task_id IS NOT NULL ORDER BY title"))
            assertEquals(0L, db.countRows("selfdev_tasks WHERE state IN ('QUEUED', 'SUBMITTING', 'SUBMITTED', 'TASK_SUBMITTED', 'PLANNING', 'BLOCKED')"))
        }
    }

    @Test
    fun `a second apply inserts nothing and keeps the rows as they are`() {
        withSeededDb { db ->
            runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.APPLY)
            val before = db.strings("SELECT CONCAT(task_id, state, dependencies) FROM selfdev_tasks ORDER BY task_id")

            val report = runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.APPLY)

            assertEquals(0, report.insertedRoots)
            assertEquals(1, report.alreadyPresentRoots)
            assertEquals(0, report.insertedTasks)
            assertEquals(3, report.alreadyPresentTasks)
            assertEquals(4L, db.countRows("selfdev_tasks"))
            assertEquals(before, db.strings("SELECT CONCAT(task_id, state, dependencies) FROM selfdev_tasks ORDER BY task_id"))
        }
    }

    @Test
    fun `a dry run after apply reports the rows as present and writes nothing`() {
        withSeededDb { db ->
            runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.APPLY)

            val report = runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.DRY_RUN)

            assertEquals(0, report.insertedTasks)
            assertEquals(3, report.alreadyPresentTasks)
            assertEquals(4L, db.countRows("selfdev_tasks"))
        }
    }

    @Test
    fun `no imported row is dispatchable by the autopilot`() {
        withSeededDb { db ->
            runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.APPLY)
            val ops = SelfDevOperatorRepository(db.database)

            assertTrue(ops.queuedTaskIds(100).isEmpty(), "the dispatcher runs only QUEUED")
            assertTrue(ops.submittedTaskIds(100).isEmpty(), "the Planner takes only TASK_SUBMITTED")
            assertTrue(ops.interruptedSubmissionIds(100).isEmpty(), "resubmission takes only SUBMITTING")
            assertTrue(ops.blockedTasks(100).isEmpty(), "nothing is blocked and cleared back to QUEUED")
            assertEquals(0, ops.settleFinishedPlans(100), "no plan root is settled by the dispatcher")
            assertEquals(0L, db.countRows("selfdev_tasks WHERE state = 'QUEUED'"))
        }
    }

    @Test
    fun `the archives are left as they were`() {
        withSeededDb { db ->
            runLegacyGoalPlanBackfill(db.dataSource, LegacyBackfillMode.APPLY)

            assertEquals(4L, db.countRows("selfdev_goals_pre_v40"))
            assertEquals(4L, db.countRows("selfdev_plans_pre_v40"))
            assertEquals(10L, db.countRows("selfdev_plan_tasks_pre_v40"))
        }
    }
}
