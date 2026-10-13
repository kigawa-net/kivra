package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId

/** Task API persistence on DevelopmentRunRepository (Issue #112, V9). Runs on H2, or MariaDB with KIVRA_TEST_DB=mariadb. */
class DevTaskIntakeTest {
    /** A repository instance over the same database, as after a process restart. */
    private fun TestDb.freshRepository(clock: Clock = Clock.systemUTC()) =
        DevelopmentRunRepository(database, OutboxRepository(database), AuditRepository(), FakeTrustedPrMergeAuthority(), clock)

    @Test
    fun `submit creates a CREATED run that is never executed`() = TestDb().use { db ->
        val result = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice@example.com", "key-1", "Write docs"))
        val run = result.run
        assertEquals(DevelopmentRunState.CREATED, run.state)
        assertEquals(0L, run.version)
        assertEquals("Write docs", run.taskTitle)
        assertEquals("alice@example.com", run.principal)
        assertEquals("key-1", run.idempotencyKey)
        assertTrue(run.taskId.startsWith("api:"))
        // No execution, lease, agent or stage was created.
        assertEquals(0, db.count("stage_executions"))
        assertEquals(0, db.count("agents"))
        assertEquals(0, db.count("execution_sessions"))
        assertEquals(1, db.count("audit_events", "run_id = '${run.runId}' AND action = 'RUN_CREATED'"))
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${run.runId}' AND aggregate_version = 0"))
    }

    @Test
    fun `the same principal and key replay the same run and a different title is a conflict`() = TestDb().use { db ->
        val first = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "k", "Same title")).run
        val replay = assertIs<TaskSubmitResult.Replayed>(db.runs.submitTask("alice", "k", "Same title"))
        assertEquals(first.runId, replay.run.runId)
        val conflict = assertIs<TaskSubmitResult.ConflictingTitle>(db.runs.submitTask("alice", "k", "Other title"))
        assertEquals(first.runId, conflict.run.runId)
        assertEquals(1, db.count("development_runs", "principal = 'alice'"))
    }

    @Test
    fun `keys are scoped per principal`() = TestDb().use { db ->
        val alice = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "shared", "Task")).run
        val bob = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("bob", "shared", "Task")).run
        assertTrue(alice.runId != bob.runId)
        assertEquals(2, db.count("development_runs", "idempotency_key = 'shared'"))
    }

    @Test
    fun `a run survives a restart and is readable only by its principal`() = TestDb().use { db ->
        val created = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "k", "Persist me")).run
        val restarted = db.freshRepository()
        val loaded = restarted.getTask(created.runId, "alice")
        assertEquals("Persist me", loaded?.taskTitle)
        assertEquals(DevelopmentRunState.CREATED, loaded?.state)
        assertEquals(null, restarted.getTask(created.runId, "mallory"))
        assertEquals(null, restarted.getTask(RunId("01UNKNOWNUNKNOWNUNKNOWN00"), "alice"))
    }

    @Test
    fun `list is newest first, limited to 50 and scoped to the principal`() = TestDb().use { db ->
        val base = Clock.systemUTC()
        val ids = (0 until 55).map { i ->
            // One submission per second of clock offset, so created_at orders the runs deterministically.
            val clock = Clock.offset(base, Duration.ofSeconds(i.toLong()))
            (db.freshRepository(clock).submitTask("alice", "k$i", "Task $i") as TaskSubmitResult.Created).run.runId.value
        }
        db.runs.submitTask("bob", "k", "Bob's task")
        val listed = db.runs.listTasks("alice")
        assertEquals(50, listed.size)
        assertEquals(ids.reversed().take(50), listed.map { it.runId.value })
        assertTrue(listed.all { it.principal == "alice" })
    }

    @Test
    fun `ten concurrent identical submissions converge on one row`() = TestDb().use { db ->
        val pool = Executors.newFixedThreadPool(10)
        try {
            val start = CountDownLatch(1)
            val futures = (1..10).map {
                pool.submit(Callable {
                    start.await()
                    db.runs.submitTask("alice", "same-key", "Concurrent title")
                })
            }
            start.countDown()
            val outcomes = futures.map { it.get() }
            assertEquals(1, db.count("development_runs", "principal = 'alice' AND idempotency_key = 'same-key'"))
            assertEquals(1, outcomes.count { it is TaskSubmitResult.Created })
            assertEquals(1, outcomes.map { it.run.runId }.toSet().size)
            assertTrue(outcomes.all { it.run.taskTitle == "Concurrent title" })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `pre-V9 runs without a principal are never listed`() = TestDb().use { db ->
        db.runs.create("workflow-task", "test", net.kigawa.kivra.core.RunCompletionMode.PR_ONLY)
        assertEquals(emptyList(), db.runs.listTasks("alice"))
    }
}
