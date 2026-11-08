package net.kigawa.kivra.selfdev.automerge

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.AuditRepository
import net.kigawa.kivra.db.SelfDevAutopilotControlRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.TestDb

/**
 * The attempts table (V48) on H2 with the real repositories: the idempotency key (one row per head), the single slot (one CLAIMED row at a
 * time), the stale check, and the halt. The shadow store's counts read the same rows.
 */
class DbAutoMergeActiveStoreTest {
    private val db = TestDb()
    private val start: Instant = Instant.parse("2026-11-08T10:00:00Z")
    private var now: Instant = start
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private val control = SelfDevAutopilotControlRepository(db.database, clock)
    private val store = DbAutoMergeActiveStore(db.database, control, AuditRepository(), clock)
    private val shadow = DbAutoMergeShadowStore(
        db.database, db.runs, SelfDevOperatorRepository(db.database), control, AuditRepository(),
    )
    private val repo = "kigawa-net/kivra"
    private val headA = "a".repeat(40)
    private val headB = "b".repeat(40)

    @AfterTest
    fun close() = db.close()

    private fun newRun(): RunId = db.runs.create("task-1", "test", RunCompletionMode.PR_ONLY).runId

    @Test
    fun `a head is claimed once, and only one attempt holds the slot at a time`() {
        val run = newRun()
        assertTrue(store.claim(repo, 7, headA, run))
        // The same head again: never a second attempt, whatever its state.
        assertFalse(store.claim(repo, 7, headA, run))
        // Another head while one is in flight: the single slot refuses it.
        assertFalse(store.claim(repo, 8, headB, run))
        assertEquals("CLAIMED", store.attemptState(repo, 7, headA))
        assertEquals(1, shadow.inFlightAttempts())

        store.finish(repo, 7, headA, "REFUSED")
        assertEquals("REFUSED", store.attemptState(repo, 7, headA))
        assertEquals(0, shadow.inFlightAttempts())
        // The head stays used after the finish: an attempt is never repeated for that head.
        assertFalse(store.claim(repo, 7, headA, run))
        assertTrue(store.claim(repo, 8, headB, run))
    }

    @Test
    fun `the counts since an instant and the stale check read the attempts`() {
        val run = newRun()
        assertEquals(0, shadow.attemptsSince(start.minusSeconds(3600)))
        assertTrue(store.claim(repo, 7, headA, run))
        assertEquals(1, shadow.attemptsSince(start.minusSeconds(3600)))
        assertEquals(0, shadow.attemptsSince(start.plusSeconds(1)))

        // A claim older than the threshold is stale; a newer one is not.
        assertEquals(run, store.staleClaim(start.plusSeconds(60)))
        assertNull(store.staleClaim(start.minusSeconds(60)))
        store.finish(repo, 7, headA, "MERGED")
        assertNull(store.staleClaim(start.plusSeconds(60)))
    }

    @Test
    fun `a halt pauses the automatic merge and records the reason code on the Run`() {
        val run = newRun()
        assertEquals(false, control.read().autoMergePaused)
        store.halt(run, "MERGE_FAILURES")
        assertTrue(control.read().autoMergePaused)
        // The halt does not touch the whole-autopilot pause.
        assertEquals(false, control.read().paused)
    }

    @Test
    fun `the recent states are the states of the last attempts`() {
        val run = newRun()
        assertTrue(store.claim(repo, 7, headA, run))
        store.finish(repo, 7, headA, "MERGED")
        assertEquals(listOf("MERGED"), store.recentStates(2))
        assertTrue(store.recentStates(2).size <= 2)
    }
}
