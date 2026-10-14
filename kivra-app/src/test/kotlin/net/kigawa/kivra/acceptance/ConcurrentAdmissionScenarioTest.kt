package net.kigawa.kivra.acceptance

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.AdmissionResult
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.db.DbRunAdmission
import net.kigawa.kivra.db.PrepareOperation
import net.kigawa.kivra.db.connectDatabase

/**
 * Scenario 2 (issue #70): concurrent admission of one task, and fencing of a superseded worker.
 */
class ConcurrentAdmissionScenarioTest {
    private val policy = AdmissionPolicy(
        statusField = "status",
        admittableStatuses = setOf("ready"),
        cancelledStatuses = setOf("cancelled"),
        dependenciesField = "dependencies",
        targetField = "target",
        allowedTargets = setOf("A", "B"),
        repositoryField = "repository",
    )

    private fun task(updatedAt: Instant = T0) = TaskSnapshot(
        id = TaskId("fusha:test:7"),
        title = "title 7",
        description = "body 7",
        assigneeId = null,
        dueDate = null,
        fields = mapOf("status" to "\"ready\"", "dependencies" to "[]", "target" to "\"A\"", "repository" to "\"kigawa/app\""),
        nextTaskIds = emptyList(),
        relatedTaskIds = emptyList(),
        updatedAt = updatedAt,
        fetchedAt = T0,
    )

    @Test
    fun `two and ten concurrent admissions of one task leave exactly one active run`() {
        for (workers in listOf(2, 10)) {
            deterministicScenario("concurrent-admission-$workers") {
                // Each worker has its own Database connection, like a separate replica.
                val replicas = (1..workers).map { DbRunAdmission(connectDatabase(db.dataSource), db.runs, db.audit, policy) }
                val barrier = CyclicBarrier(workers)
                val pool = Executors.newFixedThreadPool(workers)
                val results = try {
                    (0 until workers).map { i ->
                        pool.submit(Callable {
                            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            runBlocking { replicas[i].admit(task(), "req-$i") }
                        })
                    }.map { it.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdownNow()
                }

                val accepted = results.filterIsInstance<AdmissionResult.Accepted>()
                val active = results.filterIsInstance<AdmissionResult.AlreadyActive>()
                trace.record("admission: accepted=${accepted.size} alreadyActive=${active.size} total=${results.size}")
                assertEquals(1, accepted.size, "exactly one admission creates the run: $results")
                assertEquals(workers - 1, active.size, "every other admission sees the active run: $results")
                val runIds = (accepted.map { it.runId } + active.map { it.runId }).toSet()
                assertEquals(1, runIds.size, "all admissions agree on one run")
                val runId = accepted.single().runId

                val runsForTask = db.count("development_runs", "task_id = 'fusha:test:7'")
                trace.record("runs for task=$runsForTask")
                assertEquals(1, runsForTask, "TaskBinding has at most one run")

                fencingOfSupersededWorker(runId)
            }
        }
    }

    /** A worker whose lease was replaced by a newer generation cannot write Journal or Outbox rows any more. */
    private fun AcceptanceRun.fencingOfSupersededWorker(runId: RunId) {
        val old = acquire(runId, "worker-old")
        expireLease(runId)
        val new = acquire(runId, "worker-new")
        trace.record("lease generations: old=${old.generation} new=${new.generation}")
        assertTrue(new.generation > old.generation)

        val outboxBefore = db.count("outbox_events")
        assertFailsWith<LeaseLostException> { db.outbox.appendFenced(old, 500, "STAGE_UPDATED", "{}") }
        assertFailsWith<LeaseLostException> {
            db.journal.prepare(old, PrepareOperation("EXKES_CREATE_RUNTIME", "run:${runId.value}:runtime", "EXKES", runId.value, "f".repeat(64), AcceptanceRun.TENANT, AcceptanceRun.CONTRACT, AcceptanceRun.MAX_ATTEMPTS))
        }
        assertEquals(false, db.leases.renew(old, AcceptanceRun.TTL), "old generation cannot renew")
        assertEquals(outboxBefore, db.count("outbox_events"), "the superseded worker wrote nothing")

        db.outbox.appendFenced(new, 501, "STAGE_UPDATED", "{}")
        assertEquals(outboxBefore + 1, db.count("outbox_events"), "the current owner can still write")
        trace.record("fencing: superseded writes rejected, current writes accepted")
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private val T0: Instant = Instant.parse("2026-10-15T00:00:00Z")
    }
}
