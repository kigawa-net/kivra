package net.kigawa.kivra.db

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.TaskRunState

private val TTL = Duration.ofSeconds(30)

class LeaseStoreTest {
    @Test
    fun `acquire assigns generation 1 and blocks a second owner while valid`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = assertNotNull(db.leases.acquire(run.runId, "worker-a", TTL))
        assertEquals(1, a.generation)
        assertNull(db.leases.acquire(run.runId, "worker-b", TTL))
        assertNull(db.leases.acquire(run.runId, "worker-a", TTL), "even the owner must renew, not re-acquire")
    }

    @Test
    fun `renew extends a valid lease without changing the generation`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        assertTrue(db.leases.renew(a, TTL))
        assertEquals(1, db.runs.get(run.runId)!!.leaseGeneration)
    }

    @Test
    fun `renew is rejected for wrong owner or wrong generation`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        assertFalse(db.leases.renew(a.copy(owner = "worker-b"), TTL))
        assertFalse(db.leases.renew(a.copy(generation = a.generation + 1), TTL))
    }

    @Test
    fun `an expired lease cannot be revived by renew even if nobody took it over`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        db.expireLease(run.runId.value)
        assertFalse(db.leases.renew(a, TTL))
        assertEquals(1, db.runs.get(run.runId)!!.leaseGeneration, "renew must not change the generation")
    }

    @Test
    fun `an expired owner cannot transition the run even before a new owner acquires`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        val current = db.runs.get(run.runId)!!
        db.expireLease(run.runId.value)
        assertFailsWith<LeaseLostException> { db.runs.transition(a, current.version, TaskRunState.PLANNING, "worker-a") }
        assertEquals(TaskRunState.ACCEPTED, db.runs.get(run.runId)!!.state)
    }

    @Test
    fun `takeover after expiry bumps the generation and fences the old worker`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        db.expireLease(run.runId.value)
        val b = assertNotNull(db.leases.acquire(run.runId, "worker-b", TTL))
        assertEquals(2, b.generation)

        val version = db.runs.get(run.runId)!!.version
        assertFailsWith<LeaseLostException> { db.runs.transition(a, version, TaskRunState.PLANNING, "worker-a") }
        assertFalse(db.leases.renew(a, TTL))
        assertEquals(TaskRunState.PLANNING, db.runs.transition(b, version, TaskRunState.PLANNING, "worker-b").state)
    }

    @Test
    fun `grace delays takeover of a recently expired lease`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        db.leases.acquire(run.runId, "worker-a", TTL)!!
        db.expireLease(run.runId.value, seconds = 2)
        assertNull(db.leases.acquire(run.runId, "worker-b", TTL, grace = Duration.ofSeconds(5)))
        assertNotNull(db.leases.acquire(run.runId, "worker-b", TTL, grace = Duration.ofSeconds(1)))
    }

    @Test
    fun `release keeps the generation monotonic`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        assertTrue(db.leases.release(a))
        assertFalse(db.leases.release(a), "already released")
        val b = assertNotNull(db.leases.acquire(run.runId, "worker-b", TTL))
        assertEquals(2, b.generation)
    }

    @Test
    fun `terminal runs cannot be leased or renewed`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val cancelled = db.runs.transition(lease, db.runs.get(run.runId)!!.version, TaskRunState.CANCELLED, "w")
        assertEquals(TaskRunState.CANCELLED, cancelled.state)
        assertFalse(db.leases.renew(lease, TTL))
        db.expireLease(run.runId.value)
        assertNull(db.leases.acquire(run.runId, "other", TTL))
    }

    @Test
    fun `a run on hold cannot be acquired`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test")
        transactionalSetHold(db, run.runId.value)
        assertNull(db.leases.acquire(run.runId, "w", TTL))
    }

    private fun transactionalSetHold(db: TestDb, runId: String) =
        org.jetbrains.exposed.sql.transactions.transaction(db.database) {
            execUpdate("UPDATE task_runs SET hold_reason = 'AUTH' WHERE run_id = ?", runId)
        }
}
