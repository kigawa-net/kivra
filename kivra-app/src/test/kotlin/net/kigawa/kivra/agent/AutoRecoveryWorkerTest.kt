package net.kigawa.kivra.agent

import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Auto-recovery worker tests: RETRY_WAITING runs are moved to DEVELOPING,
 * UNKNOWN operations are moved to RECONCILIATION_REQUIRED,
 * RECONCILIATION_REQUIRED operations are moved to FAILED.
 */
class AutoRecoveryWorkerTest {

    /**
     * A RETRY_WAITING run that has been waiting long enough is moved to DEVELOPING.
     */
    @Test
    fun `retries a RETRY_WAITING run that has been waiting long enough`() = TestDb().use { db ->
        val run = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY)
        // Force the run to RETRY_WAITING and age it
        db.exec("UPDATE development_runs SET state = 'RETRY_WAITING', updated_at = TIMESTAMPADD(SECOND, -600, NOW(6)) WHERE run_id = ?", run.runId.value)

        val worker = AutoRecoveryWorker(
            runs = db.runs,
            journal = db.journal,
            leases = db.leases,
            config = AutoRecoveryConfig(retryWaitDelay = Duration.ofMinutes(5)),
        )
        val pass = worker.tick()

        assertEquals(1, pass.runsRetried)
        assertEquals(DevelopmentRunState.DEVELOPING, db.runs.get(run.runId)!!.state)
    }

    /**
     * A RETRY_WAITING run that hasn't been waiting long enough is not retried.
     */
    @Test
    fun `does not retry a RETRY_WAITING run that is too fresh`() = TestDb().use { db ->
        val run = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY)
        // Force the run to RETRY_WAITING but keep it fresh
        db.exec("UPDATE development_runs SET state = 'RETRY_WAITING' WHERE run_id = ?", run.runId.value)

        val worker = AutoRecoveryWorker(
            runs = db.runs,
            journal = db.journal,
            leases = db.leases,
            config = AutoRecoveryConfig(retryWaitDelay = Duration.ofMinutes(5)),
        )
        val pass = worker.tick()

        assertEquals(0, pass.runsRetried)
        assertEquals(DevelopmentRunState.RETRY_WAITING, db.runs.get(run.runId)!!.state)
    }

    /**
     * A RETRY_WAITING run on hold is not retried.
     */
    @Test
    fun `does not retry a RETRY_WAITING run that is on hold`() = TestDb().use { db ->
        val run = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY)
        db.exec("UPDATE development_runs SET state = 'RETRY_WAITING', updated_at = TIMESTAMPADD(SECOND, -600, NOW(6)), hold_reason = 'test-hold' WHERE run_id = ?", run.runId.value)

        val worker = AutoRecoveryWorker(
            runs = db.runs,
            journal = db.journal,
            leases = db.leases,
            config = AutoRecoveryConfig(retryWaitDelay = Duration.ofMinutes(5)),
        )
        val pass = worker.tick()

        assertEquals(0, pass.runsRetried)
        assertEquals(DevelopmentRunState.RETRY_WAITING, db.runs.get(run.runId)!!.state)
    }

    /**
     * A RETRY_WAITING run with cancel requested is not retried.
     */
    @Test
    fun `does not retry a RETRY_WAITING run with cancel requested`() = TestDb().use { db ->
        val run = db.runs.create("task-${System.nanoTime()}", "test", RunCompletionMode.PR_ONLY)
        db.exec("UPDATE development_runs SET state = 'RETRY_WAITING', updated_at = TIMESTAMPADD(SECOND, -600, NOW(6)), cancel_requested = TRUE WHERE run_id = ?", run.runId.value)

        val worker = AutoRecoveryWorker(
            runs = db.runs,
            journal = db.journal,
            leases = db.leases,
            config = AutoRecoveryConfig(retryWaitDelay = Duration.ofMinutes(5)),
        )
        val pass = worker.tick()

        assertEquals(0, pass.runsRetried)
        assertEquals(DevelopmentRunState.RETRY_WAITING, db.runs.get(run.runId)!!.state)
    }

    /**
     * Multiple RETRY_WAITING runs are retried up to the limit.
     */
    @Test
    fun `retries multiple RETRY_WAITING runs up to the limit`() = TestDb().use { db ->
        val runs = List(5) {
            db.runs.create("task-${System.nanoTime()}-$it", "test", RunCompletionMode.PR_ONLY)
        }
        for (run in runs) {
            db.exec("UPDATE development_runs SET state = 'RETRY_WAITING', updated_at = TIMESTAMPADD(SECOND, -600, NOW(6)) WHERE run_id = ?", run.runId.value)
        }

        val worker = AutoRecoveryWorker(
            runs = db.runs,
            journal = db.journal,
            leases = db.leases,
            config = AutoRecoveryConfig(retryWaitDelay = Duration.ofMinutes(5), maxRetryRunsPerTick = 3),
        )
        val pass = worker.tick()

        assertEquals(3, pass.runsRetried)
        // First 3 should be DEVELOPING, last 2 should still be RETRY_WAITING
        val states = runs.map { db.runs.get(it.runId)!!.state }
        assertEquals(3, states.count { it == DevelopmentRunState.DEVELOPING })
        assertEquals(2, states.count { it == DevelopmentRunState.RETRY_WAITING })
    }

    /**
     * A pass with nothing to do returns zero counts.
     */
    @Test
    fun `pass with nothing to do returns zero counts`() = TestDb().use { db ->
        val worker = AutoRecoveryWorker(
            runs = db.runs,
            journal = db.journal,
            leases = db.leases,
            config = AutoRecoveryConfig(),
        )
        val pass = worker.tick()

        assertEquals(AutoRecoveryPass(0, 0, 0), pass)
    }

    /**
     * Non-positive config values are refused.
     */
    @Test
    fun `non-positive config values are refused`() {
        assertFailsWith<IllegalArgumentException> { AutoRecoveryConfig(interval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { AutoRecoveryConfig(maxRetryRunsPerTick = 0) }
        assertFailsWith<IllegalArgumentException> { AutoRecoveryConfig(maxReconcileOpsPerTick = 0) }
    }

    private fun TestDb.exec(sql: String, vararg params: Any?) = transaction(database) { execUpdate(sql, *params) }
}
