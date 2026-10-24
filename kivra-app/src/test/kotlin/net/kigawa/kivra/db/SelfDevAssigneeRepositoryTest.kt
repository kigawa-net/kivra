package net.kigawa.kivra.db

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.transactions.transaction

private const val ALICE = "5:oidc:alice"
private const val BOB = "3:oidc:bob"
private const val CAROL = "5:oidc:carol"
private const val ROBOT = "5:oidc:robot"
private const val NAME_MARKER = "NAME-MARKER-XYZ"

/**
 * A clock that moves forward 1 ms on every read. Event IDs are ULIDs whose ordering within one millisecond is random, so the events
 * of one operation must not share a timestamp for `ORDER BY event_id` to give the order they were written in.
 */
private class SteppingClock(private val start: Instant) : Clock() {
    private val reads = AtomicLong()

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = start.plusMillis(reads.getAndIncrement())
}

/** The assignee repository (V33) on H2 (MariaDB with `KIVRA_TEST_DB=mariadb`). */
class SelfDevAssigneeRepositoryTest {

    private val db = TestDb()
    private val repo = SelfDevAssigneeRepository(
        org.jetbrains.exposed.sql.Database.connect(db.dataSource),
        SteppingClock(Instant.parse("2026-01-01T00:00:00Z")),
    )

    @AfterTest
    fun close() = db.close()

    private fun created(name: String, role: AssigneeRole, key: String = ALICE): Assignee {
        val r = repo.create(name, role, key)
        return assertIs<AssigneeResult.Moved<Assignee>>(r).value
    }

    private fun moved(r: AssigneeResult<*>) = assertIs<AssigneeResult.Moved<*>>(r)

    private fun refused(r: AssigneeResult<*>, reason: AssigneeRefusal) = assertEquals(AssigneeResult.Refused(reason), r)

    /** An ACTIVE ADMIN assignee with [key] bound. */
    private fun admin(name: String, key: String): Assignee {
        val a = created(name, AssigneeRole.ADMIN, "9:seed:seed")
        moved(repo.bindMember(a.assigneeId, key, "9:seed:seed"))
        return a
    }

    private fun events(): List<List<String?>> = transaction(org.jetbrains.exposed.sql.Database.connect(db.dataSource)) {
        execQuery("SELECT kind, actor_ref, from_role, to_role, member_ref FROM selfdev_assignee_events ORDER BY event_id") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
    }

    @Test
    fun `create stores an ACTIVE assignee at version 0 and one CREATED event`() {
        val a = created("  Alice  ", AssigneeRole.OPERATOR)
        assertEquals("Alice", a.displayName)
        assertEquals(AssigneeState.ACTIVE, a.state)
        assertEquals(0, a.version)
        assertEquals(SelfDevAssigneeRepository.actorRef(ALICE), a.createdBy)
        assertEquals(a, repo.find(a.assigneeId))
        assertEquals(listOf(a), repo.list())
        assertEquals(listOf(listOf("CREATED", SelfDevAssigneeRepository.actorRef(ALICE), null, "OPERATOR", null)), events())
        assertNull(repo.find("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
    }

    @Test
    fun `display name is NFC, trimmed, 1 to 64 code points, with no control or bidi character`() {
        val decomposed = "e\u0301"
        assertEquals("\u00e9", created(decomposed, AssigneeRole.VIEWER).displayName)
        refused(repo.create("   ", AssigneeRole.VIEWER, ALICE), AssigneeRefusal.INVALID_DISPLAY_NAME)
        refused(repo.create("a".repeat(65), AssigneeRole.VIEWER, ALICE), AssigneeRefusal.INVALID_DISPLAY_NAME)
        moved(repo.create("a".repeat(64), AssigneeRole.VIEWER, ALICE))
        for (bad in listOf("a\u0000b", "a\nb", "a\u202Eb", "a\u2066b", "a\u200Fb", "a\u061Cb")) {
            refused(repo.create(bad, AssigneeRole.VIEWER, ALICE), AssigneeRefusal.INVALID_DISPLAY_NAME)
        }
        refused(repo.seedIfAbsent("a\u202Eb", AssigneeRole.ADMIN, ALICE), AssigneeRefusal.INVALID_DISPLAY_NAME)
    }

    @Test
    fun `the schema refuses an unknown role, state or event kind`() {
        val database = org.jetbrains.exposed.sql.Database.connect(db.dataSource)
        assertFailsWith<Exception> {
            transaction(database) {
                execUpdate(
                    "INSERT INTO selfdev_assignees (assignee_id, display_name, role, state, version, created_by, created_at, updated_at) " +
                        "VALUES ('A', 'x', 'ROOT', 'ACTIVE', 0, 'bootstrap', NOW(6), NOW(6))",
                )
            }
        }
        val a = created("x", AssigneeRole.VIEWER)
        assertFailsWith<Exception> {
            transaction(database) { execUpdate("UPDATE selfdev_assignees SET state = 'GONE' WHERE assignee_id = ?", a.assigneeId) }
        }
        assertFailsWith<Exception> {
            transaction(database) {
                execUpdate(
                    "INSERT INTO selfdev_assignee_events (event_id, assignee_id, kind, occurred_at) VALUES ('E', ?, 'DELETED', NOW(6))",
                    a.assigneeId,
                )
            }
        }
    }

    @Test
    fun `bind, accessOf and a duplicate owner key`() {
        val op = created("Op", AssigneeRole.OPERATOR)
        val other = created("Other", AssigneeRole.APPROVER)
        assertNull(repo.accessOf(ALICE))
        moved(repo.bindMember(op.assigneeId, ALICE, BOB))
        assertEquals(AssigneeAccess(op.assigneeId, AssigneeRole.OPERATOR, AssigneeState.ACTIVE), repo.accessOf(ALICE))
        // The same key twice, in the same or another assignee, is refused: one owner key holds one role.
        refused(repo.bindMember(op.assigneeId, ALICE, BOB), AssigneeRefusal.OWNER_KEY_ALREADY_BOUND)
        refused(repo.bindMember(other.assigneeId, ALICE, BOB), AssigneeRefusal.OWNER_KEY_ALREADY_BOUND)
        assertEquals(AssigneeResult.NotFound, repo.bindMember("01ARZ3NDEKTSV4RRFFQ69G5FAV", CAROL, BOB))
        refused(repo.bindMember(op.assigneeId, "not-an-owner-key", BOB), AssigneeRefusal.INVALID_OWNER_KEY)
        refused(repo.bindMember(op.assigneeId, "5:oidc:ab\u0000", BOB), AssigneeRefusal.INVALID_OWNER_KEY)
        assertEquals(listOf(SelfDevAssigneeRepository.memberRef(ALICE)), repo.memberRefs(op.assigneeId))
        assertNull(repo.accessOf("garbage"))
    }

    @Test
    fun `owner keys that differ only in case are two principals`() {
        val a = created("A", AssigneeRole.VIEWER)
        val b = created("B", AssigneeRole.OPERATOR)
        moved(repo.bindMember(a.assigneeId, "5:oidc:Alice", BOB))
        moved(repo.bindMember(b.assigneeId, "5:oidc:alice", BOB))
        assertEquals(a.assigneeId, repo.accessOf("5:oidc:Alice")?.assigneeId)
        assertEquals(b.assigneeId, repo.accessOf("5:oidc:alice")?.assigneeId)
        assertNull(repo.accessOf("5:oidc:ALICE"))
    }

    @Test
    fun `concurrent binds of the same owner key let exactly one through`() {
        val a = created("A", AssigneeRole.VIEWER)
        val b = created("B", AssigneeRole.AI)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val jobs = listOf(a, b).map { target ->
                pool.submit(Callable { go.await(); repo.bindMember(target.assigneeId, ROBOT, BOB) })
            }
            go.countDown()
            val results = jobs.map { it.get() }
            assertEquals(1, results.count { it is AssigneeResult.Moved<*> })
            assertEquals(1, results.count { it == AssigneeResult.Refused(AssigneeRefusal.OWNER_KEY_ALREADY_BOUND) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `changeRole is a compare and set on version`() {
        val a = created("A", AssigneeRole.VIEWER)
        val next = assertIs<AssigneeResult.Moved<Assignee>>(repo.changeRole(a.assigneeId, AssigneeRole.APPROVER, 0, BOB)).value
        assertEquals(AssigneeRole.APPROVER, next.role)
        assertEquals(1, next.version)
        assertEquals(AssigneeResult.StaleVersion, repo.changeRole(a.assigneeId, AssigneeRole.OPERATOR, 0, BOB))
        assertEquals(AssigneeRole.APPROVER, repo.find(a.assigneeId)?.role)
        refused(repo.changeRole(a.assigneeId, AssigneeRole.APPROVER, 1, BOB), AssigneeRefusal.NO_CHANGE)
        assertEquals(AssigneeResult.NotFound, repo.changeRole("01ARZ3NDEKTSV4RRFFQ69G5FAV", AssigneeRole.AI, 0, BOB))
        assertTrue(events().contains(listOf("ROLE_CHANGED", SelfDevAssigneeRepository.actorRef(BOB), "VIEWER", "APPROVER", null)))
    }

    @Test
    fun `setState disables and enables, and accessOf reports DISABLED`() {
        val a = created("A", AssigneeRole.OPERATOR)
        moved(repo.bindMember(a.assigneeId, CAROL, BOB))
        moved(repo.setState(a.assigneeId, AssigneeState.DISABLED, 0, BOB))
        assertEquals(AssigneeAccess(a.assigneeId, AssigneeRole.OPERATOR, AssigneeState.DISABLED), repo.accessOf(CAROL))
        refused(repo.setState(a.assigneeId, AssigneeState.DISABLED, 1, BOB), AssigneeRefusal.NO_CHANGE)
        assertEquals(AssigneeResult.StaleVersion, repo.setState(a.assigneeId, AssigneeState.ACTIVE, 0, BOB))
        moved(repo.setState(a.assigneeId, AssigneeState.ACTIVE, 1, BOB))
        assertEquals(AssigneeState.ACTIVE, repo.accessOf(CAROL)?.state)
        assertEquals(listOf("CREATED", "MEMBER_BOUND", "DISABLED", "ENABLED"), events().map { it[0] })
    }

    @Test
    fun `the last ACTIVE ADMIN cannot be demoted, disabled or unbound`() {
        val only = admin("Only", ALICE)
        refused(repo.changeRole(only.assigneeId, AssigneeRole.OPERATOR, 0, BOB), AssigneeRefusal.LAST_ADMIN)
        refused(repo.setState(only.assigneeId, AssigneeState.DISABLED, 0, BOB), AssigneeRefusal.LAST_ADMIN)
        refused(repo.unbindMember(only.assigneeId, ALICE, BOB), AssigneeRefusal.LAST_ADMIN)
        assertEquals(AssigneeRole.ADMIN, repo.find(only.assigneeId)?.role)
        assertNotNull(repo.accessOf(ALICE))
        // With a second ADMIN the first may be demoted, and then the second is the last.
        val second = admin("Second", CAROL)
        moved(repo.changeRole(only.assigneeId, AssigneeRole.OPERATOR, 0, CAROL))
        refused(repo.changeRole(second.assigneeId, AssigneeRole.VIEWER, 0, ALICE), AssigneeRefusal.LAST_ADMIN)
        // A change that keeps an ADMIN an ADMIN is not a demotion: promoting a third makes the second free to go.
        val third = admin("Third", BOB)
        moved(repo.changeRole(second.assigneeId, AssigneeRole.VIEWER, 0, BOB))
        assertEquals(AssigneeRole.ADMIN, repo.find(third.assigneeId)?.role)
    }

    @Test
    fun `an ADMIN with two members may drop one, but the last member of the last ADMIN stays`() {
        val a = admin("A", ALICE)
        moved(repo.bindMember(a.assigneeId, CAROL, ALICE))
        moved(repo.unbindMember(a.assigneeId, CAROL, BOB))
        refused(repo.unbindMember(a.assigneeId, ALICE, BOB), AssigneeRefusal.LAST_ADMIN)
        refused(repo.unbindMember(a.assigneeId, CAROL, BOB), AssigneeRefusal.NOT_BOUND)
        // A non-admin's member can always be removed.
        val v = created("V", AssigneeRole.VIEWER)
        moved(repo.bindMember(v.assigneeId, ROBOT, ALICE))
        moved(repo.unbindMember(v.assigneeId, ROBOT, ALICE))
        assertNull(repo.accessOf(ROBOT))
        assertTrue(events().any { it[0] == "MEMBER_UNBOUND" && it[4] == SelfDevAssigneeRepository.memberRef(ROBOT) })
    }

    @Test
    fun `a caller cannot demote or disable the assignee it belongs to`() {
        val a = admin("A", ALICE)
        val b = admin("B", BOB)
        refused(repo.changeRole(a.assigneeId, AssigneeRole.OPERATOR, 0, ALICE), AssigneeRefusal.SELF_CHANGE)
        refused(repo.setState(a.assigneeId, AssigneeState.DISABLED, 0, ALICE), AssigneeRefusal.SELF_CHANGE)
        assertEquals(AssigneeRole.ADMIN, repo.find(a.assigneeId)?.role)
        // Another ADMIN can do it.
        moved(repo.setState(a.assigneeId, AssigneeState.DISABLED, 0, BOB))
        assertEquals(AssigneeState.ACTIVE, repo.find(b.assigneeId)?.state)
    }

    @Test
    fun `two concurrent demotions of the only two ADMINs let exactly one through`() {
        repeat(5) {
            val fresh = TestDb()
            try {
                val r = SelfDevAssigneeRepository(org.jetbrains.exposed.sql.Database.connect(fresh.dataSource))
                val a = (r.create("A", AssigneeRole.ADMIN, "9:seed:seed") as AssigneeResult.Moved).value
                val b = (r.create("B", AssigneeRole.ADMIN, "9:seed:seed") as AssigneeResult.Moved).value
                // The callers are outside both assignees, so the self rule does not decide this.
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val go = CountDownLatch(1)
                    val jobs = listOf(a, b).map { target ->
                        pool.submit(Callable { go.await(); r.changeRole(target.assigneeId, AssigneeRole.VIEWER, 0, CAROL) })
                    }
                    go.countDown()
                    val results = jobs.map { it.get() }
                    assertEquals(1, results.count { it is AssigneeResult.Moved<*> }, "results: $results")
                    assertEquals(1, results.count { it == AssigneeResult.Refused(AssigneeRefusal.LAST_ADMIN) }, "results: $results")
                    assertEquals(1, r.list().count { it.role == AssigneeRole.ADMIN })
                } finally {
                    pool.shutdownNow()
                }
            } finally {
                fresh.close()
            }
        }
    }

    @Test
    fun `seedIfAbsent makes the row once and never undoes a demotion`() {
        val first = assertIs<AssigneeResult.Moved<Assignee>>(repo.seedIfAbsent("Root", AssigneeRole.ADMIN, ALICE)).value
        assertEquals(SelfDevAssigneeRepository.BOOTSTRAP, first.createdBy)
        assertEquals(AssigneeRole.ADMIN, repo.accessOf(ALICE)?.role)
        val again = assertIs<AssigneeResult.Moved<Assignee>>(repo.seedIfAbsent("Root", AssigneeRole.ADMIN, ALICE)).value
        assertEquals(first.assigneeId, again.assigneeId)
        assertEquals(1, repo.list().size)
        // Demoted by another ADMIN, then disabled: a restart's seeding must not restore either.
        admin("Other", BOB)
        moved(repo.changeRole(first.assigneeId, AssigneeRole.VIEWER, 0, BOB))
        moved(repo.setState(first.assigneeId, AssigneeState.DISABLED, 1, BOB))
        val third = assertIs<AssigneeResult.Moved<Assignee>>(repo.seedIfAbsent("Root", AssigneeRole.ADMIN, ALICE)).value
        assertEquals(first.assigneeId, third.assigneeId)
        assertEquals(AssigneeAccess(first.assigneeId, AssigneeRole.VIEWER, AssigneeState.DISABLED), repo.accessOf(ALICE))
        refused(repo.seedIfAbsent("Root", AssigneeRole.ADMIN, "bad"), AssigneeRefusal.INVALID_OWNER_KEY)
        // The seeding events have no actor.
        assertTrue(events().filter { it[1] == null }.map { it[0] }.containsAll(listOf("CREATED", "MEMBER_BOUND")))
    }

    @Test
    fun `events hold neither the owner key nor the display name`() {
        val a = created("$NAME_MARKER one", AssigneeRole.ADMIN, ALICE)
        moved(repo.bindMember(a.assigneeId, BOB, ALICE))
        created("Spare admin", AssigneeRole.ADMIN, ALICE)
        moved(repo.changeRole(a.assigneeId, AssigneeRole.OPERATOR, 0, CAROL))
        moved(repo.setState(a.assigneeId, AssigneeState.DISABLED, 1, CAROL))
        moved(repo.unbindMember(a.assigneeId, BOB, CAROL))
        repo.seedIfAbsent("$NAME_MARKER two", AssigneeRole.AI, ROBOT)
        val database = org.jetbrains.exposed.sql.Database.connect(db.dataSource)
        val dump = transaction(database) {
            execQuery("SELECT * FROM selfdev_assignee_events") { rs ->
                (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() }
            }
        }
        assertTrue(dump.size >= 6)
        for (line in dump) {
            for (secret in listOf(ALICE, BOB, CAROL, ROBOT, "oidc", NAME_MARKER)) assertFalse(line.contains(secret), "event leaks $secret")
            assertNotEquals("", line)
        }
        // And the columns are the fixed ones.
        val columns = transaction(database) {
            execQuery("SELECT * FROM selfdev_assignee_events") { rs -> (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it).lowercase() } }
        }.first()
        assertEquals(listOf("event_id", "assignee_id", "kind", "actor_ref", "from_role", "to_role", "member_ref", "occurred_at", "token_ref"), columns)
    }
}
