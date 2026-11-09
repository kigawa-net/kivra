package net.kigawa.kivra.db

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.selfdev.autofix.INCIDENT_FIX_PRINCIPAL
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * The backfill of ADR 0022 (PR-B) on H2 with the real repositories: a dry run writes nothing, a real run assigns only the NULL rows whose
 * repository maps to a project, a row that has a project keeps it, a row with no mapping stays NULL, and a second run changes nothing.
 */
class ProjectBackfillTest {
    private val operator = "1:i:oper"

    private fun TestDb.projectOf(table: String, pk: String, id: String): String? = transaction(database) {
        execQuery("SELECT project_id FROM $table WHERE $pk = ?", id) { it.getString(1) }.first()
    }

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }

    /**
     * A Task of the legacy shape (ADR 0022 PR-B): with no [projectId] it has NULL project. Since PR-F the write refuses NULL, so it is written
     * with a placeholder project and the column is set back to NULL (the schema is before V52, where NULL is still allowed).
     */
    private fun TestDb.task(key: String, repo: String, projectId: String? = null): String {
        val id = assertIs<SelfDevSubmitResult.Created>(
            SelfDevOperatorRepository(database).submitTask(operator, key, "Task $key", null, repo, projectId ?: placeholderProject()),
        ).task.taskId
        if (projectId == null) exec("UPDATE selfdev_tasks SET project_id = NULL WHERE task_id = ?", id)
        return id
    }

    private fun TestDb.placeholderProject(): String = ProjectRepository(database, Clock.systemUTC()).create("Legacy", null).projectId

    private fun TestDb.order(title: String, repo: String): String =
        assertIs<net.kigawa.kivra.db.CreateRootResult.Created>(
            SelfDevOrderRepository(database).createRoot(operator, title, "policy", repo),
        ).order.orderId

    private fun TestDb.runOf(key: String): String = (runs.submitTask(operator, key, "Run $key") as TaskSubmitResult.Created).run.runId.value

    /** Two projects, one mapped repository, and rows of each kind: mapped, unmapped, already assigned, and a mapping that disagrees. */
    private class Scenario(
        val alpha: String,
        val beta: String,
        val mappedTask: String,
        val unmappedTask: String,
        val explicitTask: String,
        val mappedOrder: String,
        val unmappedOrder: String,
        val linkedRun: String,
        val unlinkedRun: String,
        val memoryRecord: String,
    )

    private fun TestDb.scenario(): Scenario {
        val projects = ProjectRepository(database, Clock.systemUTC())
        val alpha = projects.create("Alpha", null).projectId
        val beta = projects.create("Beta", null).projectId
        assertEquals(RepoAddResult.ADDED, projects.addRepo(alpha, "kigawa-net/alpha"))
        val mappedTask = task("mapped", "kigawa-net/alpha")
        val unmappedTask = task("unmapped", "kigawa-net/unmapped")
        // Already assigned to beta although its repository maps to alpha: the backfill must not move it.
        val explicitTask = task("explicit", "kigawa-net/alpha", projectId = beta)
        val mappedOrder = order("mapped order", "kigawa-net/alpha")
        val unmappedOrder = order("unmapped order", "kigawa-net/unmapped")
        val linkedRun = runOf("linked")
        exec("UPDATE selfdev_tasks SET run_id = ? WHERE task_id = ?", linkedRun, mappedTask)
        val unlinkedRun = runOf("unlinked")
        // A goal memory of the mapped Task. A record needs its context's project to be written, so the goal's Task has one for the save,
        // and both the Task and the record are set back to NULL afterwards (the legacy shape).
        exec("UPDATE selfdev_tasks SET project_id = ? WHERE task_id = ?", beta, mappedTask)
        val memory = SelfDevMemoryRepository(database, enabled = true).save(
            MemoryCandidate(
                kind = "GOAL_TITLE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operator,
                goalId = mappedTask, body = "CI を速くする",
            ),
        )
        val memoryRecord = assertIs<MemorySaveResult.Written>(memory).recordId
        exec("UPDATE selfdev_tasks SET project_id = NULL WHERE task_id = ?", mappedTask)
        exec("UPDATE selfdev_memory_records SET project_id = NULL WHERE record_id = ?", memoryRecord)
        assertNull(projectOf("selfdev_memory_records", "record_id", memoryRecord))
        return Scenario(alpha, beta, mappedTask, unmappedTask, explicitTask, mappedOrder, unmappedOrder, linkedRun, unlinkedRun, memoryRecord)
    }

    private fun TestDb.assertStateAfterExecute(s: Scenario) {
        assertEquals(s.alpha, projectOf("selfdev_tasks", "task_id", s.mappedTask))
        assertNull(projectOf("selfdev_tasks", "task_id", s.unmappedTask))
        assertEquals(s.beta, projectOf("selfdev_tasks", "task_id", s.explicitTask))
        assertEquals(s.alpha, projectOf("selfdev_orders", "order_id", s.mappedOrder))
        assertNull(projectOf("selfdev_orders", "order_id", s.unmappedOrder))
        assertEquals(s.alpha, projectOf("development_runs", "run_id", s.linkedRun))
        assertNull(projectOf("development_runs", "run_id", s.unlinkedRun))
        assertEquals(s.alpha, projectOf("selfdev_memory_records", "record_id", s.memoryRecord))
    }

    @Test
    fun `a dry run counts what a real run would assign and writes nothing`() = TestDb(::migrateBeforeContract).use { db ->
        val s = db.scenario()
        val report = ProjectBackfillRepository(db.database).run(dryRun = true)

        assertTrue(report.dryRun)
        assertEquals(BackfillTableReport(matched = 1, updated = 0, unmatched = 1), report.tables["selfdev_tasks"])
        assertEquals(BackfillTableReport(matched = 1, updated = 0, unmatched = 1), report.tables["selfdev_orders"])
        assertEquals(BackfillTableReport(matched = 1, updated = 0, unmatched = 1), report.tables["development_runs"])
        assertEquals(BackfillTableReport(matched = 1, updated = 0, unmatched = 0), report.tables["selfdev_memory_records"])
        assertNull(db.projectOf("selfdev_tasks", "task_id", s.mappedTask))
        assertNull(db.projectOf("selfdev_orders", "order_id", s.mappedOrder))
        assertNull(db.projectOf("development_runs", "run_id", s.linkedRun))
        assertNull(db.projectOf("selfdev_memory_records", "record_id", s.memoryRecord))
    }

    @Test
    fun `a real run assigns the mapped rows, keeps assigned rows and leaves unmapped rows NULL`() = TestDb(::migrateBeforeContract).use { db ->
        val s = db.scenario()
        val report = ProjectBackfillRepository(db.database).run(dryRun = false)

        assertEquals(BackfillTableReport(matched = 1, updated = 1, unmatched = 1), report.tables["selfdev_tasks"])
        assertEquals(BackfillTableReport(matched = 1, updated = 1, unmatched = 1), report.tables["selfdev_orders"])
        assertEquals(BackfillTableReport(matched = 1, updated = 1, unmatched = 1), report.tables["development_runs"])
        assertEquals(BackfillTableReport(matched = 1, updated = 1, unmatched = 0), report.tables["selfdev_memory_records"])
        db.assertStateAfterExecute(s)
    }

    @Test
    fun `a second run changes nothing and reports the same unmatched rows`() = TestDb(::migrateBeforeContract).use { db ->
        val s = db.scenario()
        val backfill = ProjectBackfillRepository(db.database)
        backfill.run(dryRun = false)
        val again = backfill.run(dryRun = false)

        again.tables.values.forEach { assertEquals(0L, it.updated) }
        assertEquals(1L, again.tables.getValue("selfdev_tasks").unmatched)
        assertEquals(0L, again.tables.getValue("selfdev_memory_records").unmatched)
        assertEquals(0L, backfill.run(dryRun = true).tables.getValue("selfdev_tasks").matched)
        db.assertStateAfterExecute(s)
    }

    @Test
    fun `a row keeps its project when its repository now maps to another one`() = TestDb(::migrateBeforeContract).use { db ->
        val projects = ProjectRepository(db.database, Clock.systemUTC())
        val alpha = projects.create("Alpha", null).projectId
        val beta = projects.create("Beta", null).projectId
        assertEquals(RepoAddResult.ADDED, projects.addRepo(beta, "kigawa-net/beta"))
        val assigned = db.task("assigned", "kigawa-net/beta", projectId = alpha)
        val run = db.runOf("assigned-run")
        db.exec("UPDATE selfdev_tasks SET run_id = ? WHERE task_id = ?", run, assigned)
        db.exec("UPDATE development_runs SET project_id = ? WHERE run_id = ?", alpha, run)

        ProjectBackfillRepository(db.database).run(dryRun = false)

        assertEquals(alpha, db.projectOf("selfdev_tasks", "task_id", assigned))
        assertEquals(alpha, db.projectOf("development_runs", "run_id", run))
    }

    @Test
    fun `every mapped row is assigned when the batch is one row`() = TestDb(::migrateBeforeContract).use { db ->
        val alpha = ProjectRepository(db.database, Clock.systemUTC()).create("Alpha", null).projectId
        ProjectRepository(db.database, Clock.systemUTC()).addRepo(alpha, "kigawa-net/batch")
        val ids = (1..3).map { db.task("batch-$it", "kigawa-net/batch") }

        val report = ProjectBackfillRepository(db.database, batchSize = 1).run(dryRun = false)

        assertEquals(BackfillTableReport(matched = 3, updated = 3, unmatched = 0), report.tables["selfdev_tasks"])
        ids.forEach { assertEquals(alpha, db.projectOf("selfdev_tasks", "task_id", it)) }
    }

    @Test
    fun `a task filed by the incident fix takes the project of its repository and keeps it on replay`() = TestDb(::migrateBeforeContract).use { db ->
        val alpha = ProjectRepository(db.database, Clock.systemUTC()).create("Alpha", null).projectId
        ProjectRepository(db.database, Clock.systemUTC()).addRepo(alpha, "kigawa-net/alpha")
        val operations = SelfDevOperatorRepository(db.database)

        val mapped = assertIs<SelfDevSubmitResult.Created>(
            operations.submitTask(INCIDENT_FIX_PRINCIPAL, "incident:fp-mapped", "Fix", null, "kigawa-net/alpha", null),
        ).task
        assertEquals(alpha, mapped.projectId)
        // A repository with no mapping gives no project, so nothing is written (ADR 0022 PR-F: no NULL row).
        assertFailsWith<ProjectRequiredException> {
            operations.submitTask(INCIDENT_FIX_PRINCIPAL, "incident:fp-unmapped", "Fix", null, "kigawa-net/unmapped", null)
        }

        // The same key again (a retry after a lost response) is the same task, not a conflict, although the project is not in the request.
        assertIs<SelfDevSubmitResult.Replayed>(
            operations.submitTask(INCIDENT_FIX_PRINCIPAL, "incident:fp-mapped", "Fix", null, "kigawa-net/alpha", null),
        )
        // An operator's task without a project is refused: only the system's tasks take their repository's project.
        assertFailsWith<ProjectRequiredException> {
            operations.submitTask(operator, "operator-no-project", "Task", null, "kigawa-net/alpha", null)
        }
        assertNotNull(db.projectOf("selfdev_tasks", "task_id", mapped.taskId))
    }
}
