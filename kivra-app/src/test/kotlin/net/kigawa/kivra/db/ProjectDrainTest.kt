package net.kigawa.kivra.db

import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The drain at the repository level (ADR 0022) on H2 or MariaDB (see [TestDb]). The batch size is small here, so a drain of more rows than
 * one batch runs the batch loop several times; the rows still all move, and a second drain moves nothing.
 */
class ProjectDrainTest {
    private val clock: Clock = Clock.systemUTC()
    private val operator = "1:i:ops"

    private fun TestDb.projectOf(name: String): String = ProjectRepository(database, clock).create(name, null).projectId

    private fun TestDb.task(key: String, project: String) {
        assertIs<SelfDevSubmitResult.Created>(
            SelfDevOperatorRepository(database).submitTask(operator, key, "Task $key", null, "kigawa-net/$key", project),
        )
    }

    @Test
    fun `a drain larger than one batch moves every row in several batches, and a second drain moves nothing`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val target = db.projectOf("Target")
        repeat(5) { db.task("batch-$it", source) }
        val drain = ProjectDrainRepository(db.database, newDbStore(db, clock), batchSize = 2)

        val first = assertIs<ProjectDrainResult.Done>(drain.drain(source, target, dryRun = false))
        assertEquals(5L, first.counts.tasks)
        assertEquals(5, db.count("selfdev_tasks", "project_id = '$target'"))
        assertEquals(0, db.count("selfdev_tasks", "project_id = '$source'"))

        val second = assertIs<ProjectDrainResult.Done>(drain.drain(source, target, dryRun = false))
        assertTrue(second.counts.toMap().values.all { it == 0L }, "nothing left: ${second.counts}")
    }

    @Test
    fun `a project that does not exist is NotFound, and nothing is written`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        db.task("kept", source)
        val drain = ProjectDrainRepository(db.database, newDbStore(db, clock))

        assertEquals(ProjectDrainResult.NotFound, drain.drain(source, "01ZZZZZZZZZZZZZZZZZZZZZZZZ", dryRun = false))
        assertEquals(ProjectDrainResult.NotFound, drain.drain("01ZZZZZZZZZZZZZZZZZZZZZZZZ", source, dryRun = true))
        assertEquals(1, db.count("selfdev_tasks", "project_id = '$source'"))
    }

    @Test
    fun `the same project is a programming error, not a result`() = TestDb().use { db ->
        val source = db.projectOf("Source")
        val drain = ProjectDrainRepository(db.database, newDbStore(db, clock))
        assertFailsWith<IllegalArgumentException> { drain.drain(source, source, dryRun = false) }
    }

    @Test
    fun `the batch size must be positive`() = TestDb().use { db ->
        assertFailsWith<IllegalArgumentException> { ProjectDrainRepository(db.database, newDbStore(db, clock), batchSize = 0) }
    }
}
