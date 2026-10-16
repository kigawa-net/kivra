package net.kigawa.kivra.db

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode

/** The self-development submission path of [DevelopmentRunRepository.submitTask] (the cap and the run kind). H2 by default, MariaDB with KIVRA_TEST_DB=mariadb. */
class SelfDevSubmitTest {
    @Test
    fun `a self-development run is marked by its task id prefix, is PR_ONLY and belongs to the caller`() = TestDb().use { db ->
        val created = db.runs.submitTask("alice", "k1", "title", selfDev = true, maxActiveSelfDev = 5) as TaskSubmitResult.Created
        assertTrue(created.run.taskId.startsWith(SELF_DEV_TASK_PREFIX))
        assertEquals(RunCompletionMode.PR_ONLY, created.run.completionMode)
        assertEquals(DevelopmentRunState.CREATED, created.run.state)
        assertTrue(db.runs.getTask(created.run.runId, "alice") != null)
        assertNull(db.runs.getTask(created.run.runId, "bob"))
        assertEquals(listOf(created.run.runId), db.runs.activeSelfDevRunIds())
    }

    @Test
    fun `the Task API run is not a self-development run and is never listed for the worker`() = TestDb().use { db ->
        db.runs.submitTask("alice", "api-1", "plain")
        assertTrue(db.runs.activeSelfDevRunIds().isEmpty())
        assertTrue(db.runs.listTasks("alice", selfDev = true).isEmpty())
        assertEquals(1, db.runs.listTasks("alice", selfDev = false).size)
    }

    @Test
    fun `the cap counts only non-terminal self-development runs`() = TestDb().use { db ->
        db.runs.submitTask("alice", "k1", "a", selfDev = true, maxActiveSelfDev = 1)
        assertFailsWith<ActiveSelfDevLimitException> { db.runs.submitTask("alice", "k2", "b", selfDev = true, maxActiveSelfDev = 1) }
        // A refused submission writes nothing.
        assertEquals(1, db.runs.listTasks("alice", selfDev = true).size)
    }

    @Test
    fun `concurrent submissions never exceed the cap`() = TestDb().use { db ->
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { i ->
                pool.submit(Callable {
                    try {
                        db.runs.submitTask("alice", "key-$i", "title $i", selfDev = true, maxActiveSelfDev = 1)
                        "created"
                    } catch (_: ActiveSelfDevLimitException) {
                        "refused"
                    }
                })
            }.map { it.get() }
            assertEquals(1, results.count { it == "created" }, results.toString())
            assertEquals(7, results.count { it == "refused" }, results.toString())
            assertEquals(1, db.runs.listTasks("alice", selfDev = true).size)
        } finally {
            pool.shutdownNow()
        }
    }
}
