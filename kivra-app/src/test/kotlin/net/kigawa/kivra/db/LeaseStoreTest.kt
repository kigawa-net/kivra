package net.kigawa.kivra.db

import net.kigawa.kivra.core.RunCompletionMode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.DevelopmentRunState

private val TTL = Duration.ofSeconds(30)

class LeaseStoreTest {
    @Test
    fun `acquire assigns generation 1 and blocks a second owner while valid`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = assertNotNull(db.leases.acquire(run.runId, "worker-a", TTL))
        assertEquals(1, a.generation)
        assertNull(db.leases.acquire(run.runId, "worker-b", TTL))
        assertNull(db.leases.acquire(run.runId, "worker-a", TTL), "even the owner must renew, not re-acquire")
    }

    @Test
    fun `renew extends a valid lease without changing the generation`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        assertTrue(db.leases.renew(a, TTL))
        assertEquals(1, db.runs.get(run.runId)!!.leaseGeneration)
    }

    @Test
    fun `renew is rejected for wrong owner or wrong generation`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        assertFalse(db.leases.renew(a.copy(owner = "worker-b"), TTL))
        assertFalse(db.leases.renew(a.copy(generation = a.generation + 1), TTL))
    }

    @Test
    fun `an expired lease cannot be revived by renew even if nobody took it over`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        db.expireLease(run.runId.value)
        assertFalse(db.leases.renew(a, TTL))
        assertEquals(1, db.runs.get(run.runId)!!.leaseGeneration, "renew must not change the generation")
    }

    @Test
    fun `an expired owner cannot transition the run even before a new owner acquires`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        val current = db.runs.get(run.runId)!!
        db.expireLease(run.runId.value)
        assertFailsWith<LeaseLostException> { db.runs.transition(a, current.version, DevelopmentRunState.PLANNING, "worker-a") }
        assertEquals(DevelopmentRunState.CREATED, db.runs.get(run.runId)!!.state)
    }

    @Test
    fun `takeover after expiry bumps the generation and fences the old worker`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        db.expireLease(run.runId.value)
        val b = assertNotNull(db.leases.acquire(run.runId, "worker-b", TTL))
        assertEquals(2, b.generation)

        val version = db.runs.get(run.runId)!!.version
        assertFailsWith<LeaseLostException> { db.runs.transition(a, version, DevelopmentRunState.PLANNING, "worker-a") }
        assertFalse(db.leases.renew(a, TTL))
        assertEquals(DevelopmentRunState.PLANNING, db.runs.transition(b, version, DevelopmentRunState.PLANNING, "worker-b").state)
    }

    @Test
    fun `grace delays takeover of a recently expired lease`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        db.leases.acquire(run.runId, "worker-a", TTL)!!
        db.expireLease(run.runId.value, seconds = 2)
        assertNull(db.leases.acquire(run.runId, "worker-b", TTL, grace = Duration.ofSeconds(5)))
        assertNotNull(db.leases.acquire(run.runId, "worker-b", TTL, grace = Duration.ofSeconds(1)))
    }

    @Test
    fun `release keeps the generation monotonic`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val a = db.leases.acquire(run.runId, "worker-a", TTL)!!
        assertTrue(db.leases.release(a))
        assertFalse(db.leases.release(a), "already released")
        val b = assertNotNull(db.leases.acquire(run.runId, "worker-b", TTL))
        assertEquals(2, b.generation)
    }

    @Test
    fun `terminal runs cannot be leased or renewed`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val cancelled = db.runs.transition(lease, db.runs.get(run.runId)!!.version, DevelopmentRunState.CANCELLED, "w")
        assertEquals(DevelopmentRunState.CANCELLED, cancelled.state)
        assertFalse(db.leases.renew(lease, TTL))
        db.expireLease(run.runId.value)
        assertNull(db.leases.acquire(run.runId, "other", TTL))
    }

    @Test
    fun `a run on hold cannot be acquired`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        db.runs.hold(run.runId, "AUTH", "admin")
        assertNull(db.leases.acquire(run.runId, "w", TTL))
    }

    @Test
    fun `hold after acquire stops the running worker everywhere and clearing it resumes`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "old-worker", TTL)!!
        val op = db.journal.prepare(lease, PrepareOperation("EXKES_CREATE_WORKSPACE", "k1", "EXKES", "ref", "a".repeat(64)))
        val v = db.runs.get(run.runId)!!.version

        val held = db.runs.hold(run.runId, "ADMIN_HOLD", "admin")
        assertEquals("ADMIN_HOLD", held.holdReason)

        assertFalse(db.leases.renew(lease, TTL), "renew must fail while on hold")
        assertFailsWith<RunHaltedException> { db.runs.transition(lease, held.version, DevelopmentRunState.PLANNING, "old-worker") }
        assertFailsWith<RunHaltedException> { db.journal.transition(lease, op.opId, op.version, OperationStatus.IN_FLIGHT) }
        assertFailsWith<RunHaltedException> { db.journal.prepare(lease, PrepareOperation("EXKES_CREATE_WORKSPACE", "k2", "EXKES", "ref2", "a".repeat(64))) }
        assertFailsWith<RunHaltedException> { db.outbox.appendFenced(lease, 99, "Custom", "{}") }
        assertEquals(OperationStatus.PREPARED, db.journal.get(op.opId)!!.status, "no side effect started")
        assertEquals(DevelopmentRunState.CREATED, db.runs.get(run.runId)!!.state)
        assertEquals(0, db.count("outbox_events", "aggregate_version = 99"))
        assertTrue(v < held.version)

        val cleared = db.runs.clearHold(run.runId, "admin")
        assertNull(cleared.holdReason)
        assertTrue(db.leases.renew(lease, TTL))
        assertEquals(DevelopmentRunState.PLANNING, db.runs.transition(lease, cleared.version + 1, DevelopmentRunState.PLANNING, "old-worker").state)
    }

    @Test
    fun `cancel request stops the worker except for acknowledging the cancel`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        val lease = db.leases.acquire(run.runId, "w", TTL)!!
        val requested = db.runs.requestCancel(run.runId, "admin")
        assertTrue(requested.cancelRequested)

        assertFalse(db.leases.renew(lease, TTL))
        assertFailsWith<RunHaltedException> { db.runs.transition(lease, requested.version, DevelopmentRunState.PLANNING, "w") }
        assertFailsWith<RunHaltedException> {
            db.journal.prepare(lease, PrepareOperation("EXKES_CREATE_WORKSPACE", "k", "EXKES", "ref", "a".repeat(64)))
        }
        val done = db.runs.transition(lease, requested.version, DevelopmentRunState.CANCELLED, "w", reason = "ACK")
        assertEquals(DevelopmentRunState.CANCELLED, done.state)
    }

    @Test
    fun `cancel of a run whose worker is gone is completed by the administrator path`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        db.leases.acquire(run.runId, "w", TTL)!!
        db.runs.requestCancel(run.runId, "admin")
        assertFalse(db.runs.cancelUnowned(run.runId, "admin"), "the worker still holds a valid lease")
        db.expireLease(run.runId.value)
        assertTrue(db.runs.cancelUnowned(run.runId, "admin"))
        assertEquals(DevelopmentRunState.CANCELLED, db.runs.get(run.runId)!!.state)
    }

    @Test
    fun `cancel cannot be requested once the run is releasing`() = TestDb().use { db ->
        val run = db.runs.create("task-1", "test", RunCompletionMode.RELEASE)
        db.leases.acquire(run.runId, "w", TTL)!!
        db.forceState(run.runId.value, DevelopmentRunState.RELEASING)
        assertFailsWith<InvalidTransitionException> { db.runs.requestCancel(run.runId, "admin") }
    }
}
