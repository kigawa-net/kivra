package net.kigawa.kivra.db

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.core.AdmissionPolicy
import net.kigawa.kivra.core.AdmissionResult
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.routes.toTaskRunResponse
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Self-development states of a DevelopmentRun (ADR 0008 item 6): PATCH_READY, ABANDONED, UNKNOWN, plus CREATED -> DEVELOPING.
 * Runs on H2 by default and on MariaDB with `KIVRA_TEST_DB=mariadb`.
 */
class DevelopmentRunSelfDevStatesTest {
    private val ttl = Duration.ofSeconds(30)
    private val t0: Instant = Instant.parse("2026-10-15T00:00:00Z")

    private fun TestDb.leaseFor(runId: RunId) = assertNotNull(leases.acquire(runId, "worker", ttl))

    /** Takes the lease and moves the run along [path], one fenced transition per step. Returns the lease for further steps. */
    private fun TestDb.walk(runId: RunId, vararg path: DevelopmentRunState): Lease {
        val lease = leaseFor(runId)
        for (to in path) {
            runs.transition(lease, runs.get(runId)!!.version, to, "worker")
        }
        return lease
    }

    @Test
    fun `the self-development path persists each step with a version bump`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        val before = db.runs.get(run.runId)!!.version
        db.walk(
            run.runId,
            DevelopmentRunState.DEVELOPING,
            DevelopmentRunState.PATCH_READY,
            DevelopmentRunState.PULL_REQUEST,
        )
        val after = db.runs.get(run.runId)!!
        assertEquals(DevelopmentRunState.PULL_REQUEST, after.state)
        // Each step was fenced on the version read just before it, so every step bumped the version.
        assertTrue(after.version >= before + 3)
    }

    @Test
    fun `an illegal step on the self-development path is refused and writes nothing`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        val lease = db.leaseFor(run.runId)
        val version = db.runs.get(run.runId)!!.version
        assertFailsWith<InvalidTransitionException> {
            db.runs.transition(lease, version, DevelopmentRunState.PULL_REQUEST, "worker")
        }
        val after = db.runs.get(run.runId)!!
        assertEquals(DevelopmentRunState.CREATED, after.state)
        assertEquals(version, after.version)
    }

    @Test
    fun `ABANDONED is terminal, frees the task for a new run, and holds no lease`() = TestDb().use { db ->
        val first = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        val lease = db.walk(
            first.runId,
            DevelopmentRunState.DEVELOPING, DevelopmentRunState.PATCH_READY, DevelopmentRunState.PULL_REQUEST,
        )
        db.runs.transition(lease, db.runs.get(first.runId)!!.version, DevelopmentRunState.ABANDONED, "worker", reason = "PR_CLOSED")

        assertEquals(DevelopmentRunState.ABANDONED, db.runs.get(first.runId)!!.state)
        assertEquals(1, db.count("development_runs", "run_id = '${first.runId}' AND active_flag IS NULL"), "ABANDONED is not active")
        // The one-active-run key is released, so the task can get a new run.
        val second = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        assertTrue(second.runId != first.runId)
        // Nothing leaves a terminal state: no lease can be taken on it.
        assertNull(db.leases.acquire(first.runId, "other", ttl))
    }

    @Test
    fun `UNKNOWN keeps the task active and has no evidence-free exit`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        val lease = db.walk(run.runId, DevelopmentRunState.DEVELOPING, DevelopmentRunState.UNKNOWN)
        val version = db.runs.get(run.runId)!!.version

        // Still active: a second run for the same task is refused by uk_dev_runs_active_task.
        assertFailsWith<DuplicateActiveRunException> { db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY) }
        assertEquals(1, db.count("development_runs", "task_id = 'task-1' AND active_flag = 1"))

        // Every evidence-gated target and every other target is refused, and nothing is written.
        for (to in DevelopmentRunState.entries.filter { it != DevelopmentRunState.UNKNOWN }) {
            assertFailsWith<InvalidTransitionException>("UNKNOWN -> $to") {
                db.runs.transition(lease, version, to, "worker")
            }
        }
        val after = db.runs.get(run.runId)!!
        assertEquals(DevelopmentRunState.UNKNOWN, after.state)
        assertEquals(version, after.version)
    }

    @Test
    fun `an unknown stored state is refused by writers, hidden from reads, and counts as active for admission`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY)
        val lease = db.leaseFor(run.runId)
        // A state name written by a newer image: this binary does not know it.
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", "FUTURE_STATE", run.runId.value)
        }

        assertNull(db.runs.get(run.runId), "get() does not return a run it cannot classify")
        assertTrue(db.runs.listTasks("nobody").isEmpty())
        val submitted = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "key-1", "title"))
        assertEquals(1, db.runs.listTasks("alice").size)
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", "FUTURE_STATE", submitted.run.runId.value)
        }
        assertTrue(db.runs.listTasks("alice").isEmpty(), "listTasks skips a run it cannot classify")
        assertFailsWith<InvalidTransitionException> { db.runs.transition(lease, run.version, DevelopmentRunState.FAILED, "worker") }

        val policy = AdmissionPolicy(
            statusField = "status",
            admittableStatuses = setOf("ready"),
            cancelledStatuses = setOf("cancelled"),
            dependenciesField = "dependencies",
            targetField = "target",
            allowedTargets = setOf("A"),
            repositoryField = "repository",
        )
        val admission = DbRunAdmission(db.database, db.runs, db.audit, policy)
        val first = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(1), "req-1") })
        transaction(db.database) {
            execUpdate("UPDATE development_runs SET state = ? WHERE run_id = ?", "FUTURE_STATE", first.runId.value)
        }
        // Fail-closed: no new run is created over an unknown active run.
        assertEquals(AdmissionResult.AlreadyActive(first.runId), runBlocking { admission.admit(task(1), "req-2") })
        assertEquals(1, db.count("development_runs", "task_id = 'fusha:test:1'"))
    }

    @Test
    fun `admission classifies a self-development run by its state`() = TestDb().use { db ->
        val policy = AdmissionPolicy(
            statusField = "status",
            admittableStatuses = setOf("ready"),
            cancelledStatuses = setOf("cancelled"),
            dependenciesField = "dependencies",
            targetField = "target",
            allowedTargets = setOf("A"),
            repositoryField = "repository",
        )
        val admission = DbRunAdmission(db.database, db.runs, db.audit, policy)
        val runId = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(2), "req-1") }).runId

        db.walk(runId, DevelopmentRunState.DEVELOPING, DevelopmentRunState.PATCH_READY, DevelopmentRunState.UNKNOWN)
        assertEquals(AdmissionResult.AlreadyActive(runId), runBlocking { admission.admit(task(2), "req-2") }, "UNKNOWN is active")

        // The terminal case uses a second task: PULL_REQUEST -> ABANDONED under one lease.
        val other = assertIs<AdmissionResult.Accepted>(runBlocking { admission.admit(task(3), "req-3") }).runId
        db.walk(
            other,
            DevelopmentRunState.DEVELOPING, DevelopmentRunState.PATCH_READY, DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.ABANDONED,
        )
        assertEquals(AdmissionResult.AlreadyTerminal(other), runBlocking { admission.admit(task(3), "req-4") })
        assertEquals(1, db.count("development_runs", "task_id = 'fusha:test:3'"), "no new run is created for the abandoned task")
    }

    @Test
    fun `the Task API response carries a self-development state as its plain name`() = TestDb().use { db ->
        val submitted = assertIs<TaskSubmitResult.Created>(db.runs.submitTask("alice", "key-2", "title"))
        db.walk(submitted.run.runId, DevelopmentRunState.DEVELOPING, DevelopmentRunState.PATCH_READY)
        // The UI shows the state string as it is (ui/app.js stateLabel), so the name is what it receives.
        assertEquals("PATCH_READY", db.runs.getTask(submitted.run.runId, "alice")!!.toTaskRunResponse().state)
    }

    private fun task(n: Long) = TaskSnapshot(
        id = TaskId("fusha:test:$n"),
        title = "title $n",
        description = "body $n",
        assigneeId = null,
        dueDate = null,
        fields = mapOf("status" to "\"ready\"", "dependencies" to "[]", "target" to "\"A\"", "repository" to "\"kigawa/app\""),
        nextTaskIds = emptyList(),
        relatedTaskIds = emptyList(),
        updatedAt = t0,
        fetchedAt = t0,
    )
}
