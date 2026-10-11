package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.TaskRunState
import net.kigawa.kivra.core.VersionConflictException
import org.jetbrains.exposed.sql.transactions.transaction

private val TTL = Duration.ofSeconds(30)

class TaskRunRepositoryTest {
    @Test
    fun `create writes the run, an audit event and an outbox event`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", traceId = "trace-1")
        assertEquals(TaskRunState.ACCEPTED, run.state)
        assertEquals(0, run.version)
        assertEquals(1, db.count("audit_events"))
        assertEquals(1, db.count("outbox_events", "aggregate_id = '${run.runId}' AND aggregate_version = 0"))
    }

    @Test
    fun `only one active run per task but a new run is allowed after a terminal one`() = TestDb().use { db ->
        val first = db.runs.create("task-1", "test")
        assertFailsWith<DuplicateActiveRunException> { db.runs.create("task-1", "test") }
        db.runs.create("task-2", "test") // other tasks are unaffected

        val lease = db.leases.acquire(first.runId, "w", TTL)!!
        db.runs.transition(lease, db.runs.get(first.runId)!!.version, TaskRunState.CANCELLED, "w")
        val second = db.runs.create("task-1", "test")
        assertFalse(second.runId == first.runId)
    }

    @Test
    fun `transition bumps version and writes audit and outbox in the same transaction`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val before = db.runs.get(run.runId)!!
        val after = db.runs.transition(lease, before.version, TaskRunState.PLANNING, "w", reason = "picked")
        assertEquals(TaskRunState.PLANNING, after.state)
        assertEquals(before.version + 1, after.version)
        assertEquals(1, db.count("outbox_events", "aggregate_version = ${after.version} AND event_type = 'RunStateChanged'"))
        assertEquals(2, db.count("audit_events"))
        assertTrue(transaction(db.database) { db.audit.verifyChain(this, run.runId) })
    }

    @Test
    fun `invalid transitions and stale versions are rejected without side effects`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val v = db.runs.get(run.runId)!!.version
        assertFailsWith<InvalidTransitionException> { db.runs.transition(lease, v, TaskRunState.RUNNING, "w") }
        assertFailsWith<VersionConflictException> { db.runs.transition(lease, v - 1, TaskRunState.PLANNING, "w") }
        assertEquals(TaskRunState.ACCEPTED, db.runs.get(run.runId)!!.state)
        assertEquals(1, db.count("outbox_events"))
        assertEquals(1, db.count("audit_events"))
    }

    @Test
    fun `terminal states have no way out`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.runs.transition(lease, db.runs.get(run.runId)!!.version, TaskRunState.CANCELLED, "w")
        // The lease is useless on a terminal run (requireLease rejects non-terminal-only writes).
        assertFailsWith<LeaseLostException> {
            db.runs.transition(lease, db.runs.get(run.runId)!!.version, TaskRunState.PLANNING, "w")
        }
    }

    @Test
    fun `a stale worker rolls back everything including audit and outbox`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "a", TTL)!!
        db.expireLease(run.runId.value)
        db.leases.acquire(run.runId, "b", TTL)!!
        val v = db.runs.get(run.runId)!!.version
        assertFailsWith<LeaseLostException> { db.runs.transition(a, v, TaskRunState.PLANNING, "a") }
        assertEquals(1, db.count("outbox_events"))
        assertEquals(1, db.count("audit_events"))
    }

    @Test
    fun `audit chain detects tampering`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        db.runs.transition(lease, db.runs.get(run.runId)!!.version, TaskRunState.PLANNING, "w")
        assertTrue(transaction(db.database) { db.audit.verifyChain(this, run.runId) })
        transaction(db.database) { execUpdate("UPDATE audit_events SET actor = 'mallory' WHERE seq = 1") }
        assertFalse(transaction(db.database) { db.audit.verifyChain(this, run.runId) })
    }
}
