package net.kigawa.kivra.db

import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import net.kigawa.kivra.core.DevelopmentRunState

/**
 * Concurrency and failure boundaries of Task Run admission (issue #26), on the DevelopmentRunRepository path that
 * exists on main (V9 `(principal, idempotency_key)` UNIQUE). Runs on H2, or MariaDB with KIVRA_TEST_DB=mariadb.
 *
 * Each worker builds its own repository instance over the same database, which models separate Orchestrator
 * processes rather than one shared object. Waits use latches with timeouts, never sleeps.
 */
class DevTaskIntakeConcurrencyTest {
    private fun TestDb.repositoryFor(clock: Clock = Clock.systemUTC()) =
        DevelopmentRunRepository(database, OutboxRepository(database), AuditRepository(), FakeTrustedPrMergeAuthority(), clock)

    @Test
    fun `2 to 10 concurrent admissions of the same task converge on one run`() {
        for (workers in listOf(2, 3, 5, 10)) {
            TestDb().use { db -> assertConvergesOnOneRun(db, workers) }
        }
    }

    private fun assertConvergesOnOneRun(db: TestDb, workers: Int) {
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val start = CountDownLatch(1)
            val futures = (1..workers).map {
                pool.submit(Callable {
                    val repository = db.repositoryFor()
                    check(start.await(30, TimeUnit.SECONDS)) { "start latch not released" }
                    repository.submitTask("alice", "task-admission-key", "Same task")
                })
            }
            start.countDown()
            val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.map { it.run.runId }.toSet().size, "every worker must see the same run")
            assertEquals(1, outcomes.count { it is TaskSubmitResult.Created }, "exactly one worker creates the run")
            assertEquals(workers - 1, outcomes.count { it is TaskSubmitResult.Replayed })
            assertEquals(1, db.count("development_runs", "principal = 'alice' AND idempotency_key = 'task-admission-key'"))
            assertEquals(1, db.count("audit_events", "action = 'RUN_CREATED' AND resource LIKE 'task:%'"))
            assertEquals(1, db.count("outbox_events", "event_type = 'RunCreated'"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a failed admission leaves no partial rows and a retry converges`() = TestDb().use { db ->
        val repository = db.repositoryFor()
        // A title over the VARCHAR(200) column fails inside the admission transaction, before anything is committed.
        assertFails { repository.submitTask("alice", "retry-key", "x".repeat(201)) }
        assertEquals(0, db.count("development_runs", "principal = 'alice'"))
        assertEquals(0, db.count("audit_events", "action = 'RUN_CREATED'"))
        assertEquals(0, db.count("outbox_events", "event_type = 'RunCreated'"))

        // The same key is still free: the retry after the failure creates exactly one run.
        val retried = assertIs<TaskSubmitResult.Created>(repository.submitTask("alice", "retry-key", "Recovered title"))
        assertEquals(DevelopmentRunState.CREATED, retried.run.state)
        assertEquals(1, db.count("development_runs", "principal = 'alice' AND idempotency_key = 'retry-key'"))
        assertEquals(1, db.count("audit_events", "run_id = '${retried.run.runId}' AND action = 'RUN_CREATED'"))
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${retried.run.runId}'"))
    }

    @Test
    fun `a replay after a restart returns the committed run without creating another`() = TestDb().use { db ->
        val first = assertIs<TaskSubmitResult.Created>(db.repositoryFor().submitTask("alice", "restart-key", "Task")).run
        val afterRestart = assertIs<TaskSubmitResult.Replayed>(db.repositoryFor().submitTask("alice", "restart-key", "Task"))
        assertEquals(first.runId, afterRestart.run.runId)
        assertEquals(1, db.count("development_runs", "principal = 'alice'"))
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${first.runId}'"))
    }
}
