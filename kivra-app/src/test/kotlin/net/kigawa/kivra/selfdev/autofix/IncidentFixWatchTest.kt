package net.kigawa.kivra.selfdev.autofix

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.db.execQuery
import net.kigawa.kivra.db.execUpdate
import org.jetbrains.exposed.sql.transactions.transaction

private val T0: Instant = Instant.parse("2026-11-08T00:00:00Z")
private val ON = IncidentFixSettings(enabled = true)

/** A clock the test moves by hand. The store and the watch read the same one. */
private class StepClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/**
 * The task port of the test: a plan root per key, the same root for the same key (as the operator's submission does), a root state the test
 * sets, and failures on demand. Counts every call, so the tests can show what was and was not submitted.
 */
private class FakeTasks : IncidentTasks {
    val calls = ArrayList<Pair<String, String>>()   // fingerprint, title
    val bodies = ArrayList<String>()
    val rootByKey = HashMap<String, String>()
    val states = HashMap<String, TaskState>()
    var failNext = 0
    var conflict = false

    override fun submit(fingerprint: String, title: String, body: String): String? {
        calls += fingerprint to title
        bodies += body
        if (failNext > 0) {
            failNext--
            throw IllegalStateException("provider down")
        }
        if (conflict) return null
        return rootByKey.getOrPut(fingerprint) { "ROOT${rootByKey.size + 1}".padEnd(26, '0') }.also { states.putIfAbsent(it, TaskState.PENDING_APPROVAL) }
    }

    override fun rootState(taskId: String): TaskState? = states[taskId]

    fun end(fingerprint: String, state: TaskState = TaskState.DONE) {
        states[rootByKey.getValue(fingerprint)] = state
    }
}

private fun fp(n: Int): String = "%064x".format(n)

private fun ldt(at: Instant): LocalDateTime = LocalDateTime.ofInstant(at, ZoneOffset.UTC)

/** One opened incident row, as the detector writes it (V46). */
private fun incident(db: TestDb, n: Int, state: String = "OPENED", issue: Long? = 100L + n, at: Instant = T0, reason: String = "MERGE_CONFLICT") {
    transaction(db.database) {
        execUpdate(
            "INSERT INTO selfdev_incident_issues (fingerprint, incident_code, reason_code, state, issue_number, created_at, updated_at) " +
                "VALUES (?, 'RUN_FAILED', ?, ?, ?, ?, ?)",
            fp(n), reason, state, issue, ldt(at), ldt(at),
        )
    }
}

private fun fixRows(db: TestDb): Int = transaction(db.database) {
    execQuery("SELECT COUNT(*) FROM selfdev_incident_fixes") { it.getLong(1) }.single().toInt()
}

private fun fixState(db: TestDb, n: Int): String? = transaction(db.database) {
    execQuery("SELECT state FROM selfdev_incident_fixes WHERE fingerprint = ?", fp(n)) { it.getString(1) }.firstOrNull()
}

private fun holders(db: TestDb): Int = transaction(db.database) {
    execQuery("SELECT COUNT(*) FROM selfdev_incident_fixes WHERE active_slot = 'A'") { it.getLong(1) }.single().toInt()
}

private class Harness(val db: TestDb, val clock: StepClock, val tasks: FakeTasks, val settings: IncidentFixSettings = ON) {
    /** A second Pod is a second watch on the same database. */
    fun watch(): IncidentFixWatch = IncidentFixWatch(settings, DbIncidentFixStore(db.database, clock), tasks, clock)
}

class IncidentFixWatchTest {

    @Test
    fun `the setting is off by default and only the exact value true turns it on`() {
        assertEquals(false, IncidentFixSettings.fromEnv(emptyMap()).enabled)
        assertEquals(true, IncidentFixSettings.fromEnv(mapOf(IncidentFixSettings.ENV_ENABLED to "true")).enabled)
        assertEquals(false, IncidentFixSettings.fromEnv(mapOf(IncidentFixSettings.ENV_ENABLED to "false")).enabled)
    }

    @Test
    fun `a disabled watch reads nothing, submits nothing and writes nothing`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        val h = Harness(db, clock, FakeTasks(), IncidentFixSettings.DISABLED)
        repeat(3) { h.watch().tick() }
        assertTrue(h.tasks.calls.isEmpty())
        assertEquals(0, fixRows(db))
    }

    @Test
    fun `an opened incident becomes one task with fixed text and holds the slot until its root ends`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        val h = Harness(db, clock, FakeTasks())
        h.watch().tick()
        assertEquals(1, h.tasks.calls.size)
        assertEquals("[kivra-incident] RUN_FAILED MERGE_CONFLICT", h.tasks.calls.single().second)
        val body = h.tasks.bodies.single()
        assertTrue(body.contains("incident issue: #101"), body)
        assertTrue(body.contains(fp(1).take(12)), body)
        assertEquals("SUBMITTED", fixState(db, 1))
        assertEquals(1, holders(db))

        // The root waits for the operator: nothing else is submitted, and the same incident is never submitted again.
        repeat(3) { h.watch().tick() }
        assertEquals(1, h.tasks.calls.size)
        assertEquals(1, fixRows(db))
    }

    @Test
    fun `one fix at a time - the next incident waits until the root of the first has ended`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        incident(db, 2, at = T0.plusSeconds(60))
        val h = Harness(db, clock, FakeTasks())
        h.watch().tick()
        h.watch().tick()
        assertEquals(listOf(fp(1)), h.tasks.calls.map { it.first })

        h.tasks.end(fp(1), TaskState.STOPPED)
        h.watch().tick()
        assertEquals("ENDED", fixState(db, 1))
        // The same pass hands the freed slot to the second incident: exactly one slot is held, by it.
        assertEquals(1, holders(db))
        assertEquals(listOf(fp(1), fp(2)), h.tasks.calls.map { it.first })
        assertEquals("SUBMITTED", fixState(db, 2))
    }

    @Test
    fun `two pods on one database submit one task, and an ended cause is never submitted again`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        val h = Harness(db, clock, FakeTasks())
        h.watch().tick()
        h.watch().tick()
        assertEquals(1, h.tasks.calls.size)

        h.tasks.end(fp(1))
        h.watch().tick()
        h.watch().tick()
        assertEquals(1, h.tasks.calls.size)
        assertEquals("ENDED", fixState(db, 1))
        assertEquals(1, fixRows(db))
    }

    @Test
    fun `only opened, recent incidents the detector filed are candidates`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1, state = "CLAIMED")
        incident(db, 2, state = "FAILED")
        incident(db, 3, issue = null)
        incident(db, 4, at = T0.minusSeconds(25 * 3600L))
        val h = Harness(db, clock, FakeTasks())
        repeat(2) { h.watch().tick() }
        assertTrue(h.tasks.calls.isEmpty())
        assertEquals(0, fixRows(db))
    }

    @Test
    fun `a failed submission is retried in place and gives up after three attempts`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        val tasks = FakeTasks().apply { failNext = 2 }
        val h = Harness(db, clock, tasks)
        h.watch().tick()
        assertEquals("CLAIMED", fixState(db, 1))
        assertEquals(0, holders(db))
        h.watch().tick()
        assertEquals("CLAIMED", fixState(db, 1))
        h.watch().tick()
        assertEquals("SUBMITTED", fixState(db, 1))
        assertEquals(3, tasks.calls.size)
    }

    @Test
    fun `after three failed attempts the fix is FAILED, never retried, and the slot goes to the next incident`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        incident(db, 2, at = T0.plusSeconds(60))
        val tasks = FakeTasks().apply { failNext = 3 }
        val h = Harness(db, clock, tasks)
        repeat(3) { h.watch().tick() }
        assertEquals("FAILED", fixState(db, 1))
        assertEquals(0, holders(db))
        repeat(3) { h.watch().tick() }
        assertEquals(3, tasks.calls.count { it.first == fp(1) })
        assertEquals("SUBMITTED", fixState(db, 2))
    }

    @Test
    fun `a key conflict is FAILED and frees the slot`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        incident(db, 2, at = T0.plusSeconds(60))
        val tasks = FakeTasks().apply { conflict = true }
        val h = Harness(db, clock, tasks)
        h.watch().tick()
        assertEquals("FAILED", fixState(db, 1))
        tasks.conflict = false
        h.watch().tick()
        assertEquals("SUBMITTED", fixState(db, 2))
    }

    @Test
    fun `a crash after the claim resumes the same submission with the same key and does not start a second fix`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        // The state a crash leaves: the slot is held in SUBMITTING, the root is not recorded yet.
        transaction(db.database) {
            execUpdate(
                "INSERT INTO selfdev_incident_fixes (fingerprint, incident_code, reason_code, issue_number, state, root_task_id, attempts, " +
                    "active_slot, created_at, updated_at) VALUES (?, 'RUN_FAILED', 'MERGE_CONFLICT', 101, 'SUBMITTING', NULL, 1, 'A', ?, ?)",
                fp(1), ldt(T0), ldt(T0),
            )
        }
        incident(db, 2, at = T0.plusSeconds(60))
        val tasks = FakeTasks()
        val h = Harness(db, clock, tasks)
        h.watch().tick()
        assertEquals(listOf(fp(1)), tasks.calls.map { it.first })
        assertEquals("SUBMITTED", fixState(db, 1))
        assertEquals(1, holders(db))
        assertEquals(1, fixRows(db), "the second incident waits behind the held slot")
    }

    @Test
    fun `a root that disappears releases the slot as FAILED, and a stopped root as ENDED`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        val tasks = FakeTasks()
        val h = Harness(db, clock, tasks)
        h.watch().tick()
        tasks.states.clear()
        h.watch().tick()
        assertEquals("FAILED", fixState(db, 1))
        assertEquals(0, holders(db))
    }

    @Test
    fun `a root that waits for the operator holds the slot, and a skipped root releases it`() = TestDb().use { db ->
        val clock = StepClock(T0)
        incident(db, 1)
        val tasks = FakeTasks()
        val h = Harness(db, clock, tasks)
        h.watch().tick()
        tasks.states[tasks.rootByKey.getValue(fp(1))] = TaskState.BLOCKED
        h.watch().tick()
        assertEquals("SUBMITTED", fixState(db, 1))
        tasks.end(fp(1), TaskState.SKIPPED)
        h.watch().tick()
        assertEquals("ENDED", fixState(db, 1))
        assertEquals(0, holders(db))
    }

    @Test
    fun `the task text is built from fixed codes and the number only`() {
        val text = renderIncidentFixTask("RUN_FAILED", "a secret\nwith spaces", 42L, fp(3))
        assertEquals("[kivra-incident] RUN_FAILED OTHER", text.title)
        assertTrue(text.body.contains("incident issue: #42"))
        assertTrue(!text.body.contains("secret"), text.body)
    }
}
