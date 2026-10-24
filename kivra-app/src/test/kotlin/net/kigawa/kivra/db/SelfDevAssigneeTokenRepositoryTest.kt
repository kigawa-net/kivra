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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.kigawa.kivra.selfdev.isOwnerKeyShape
import org.jetbrains.exposed.sql.transactions.transaction

private const val ADMIN_KEY = "5:oidc:alice"
private const val OTHER_ADMIN_KEY = "3:oidc:bob"
private val START: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** Moves forward 1 ms on every read, so ULIDs and timestamps are monotonic (see SelfDevAssigneeRepositoryTest). */
private class TokenSteppingClock(private val start: Instant) : Clock() {
    private val reads = AtomicLong()
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = start.plusMillis(reads.getAndIncrement())
}

/** The assignee token repository (V34) on H2 (MariaDB with `KIVRA_TEST_DB=mariadb`). */
class SelfDevAssigneeTokenRepositoryTest {

    private val db = TestDb()
    private val clock = TokenSteppingClock(START)
    private val seen = java.util.Collections.synchronizedList(mutableListOf<InvalidReason>())
    private val assignees = SelfDevAssigneeRepository(db.database, clock)
    private val tokens = SelfDevAssigneeTokenRepository(db.database, clock) { seen += it }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun assignee(role: AssigneeRole, name: String = "Robot"): Assignee =
        assertIs<AssigneeResult.Moved<Assignee>>(assignees.create(name, role, ADMIN_KEY)).value

    private fun issued(a: Assignee, ttl: Int = 90): IssuedToken =
        assertIs<TokenResult.Ok<IssuedToken>>(tokens.issue(a.assigneeId, ttl, ADMIN_KEY)).value

    private fun refused(r: TokenResult<*>, reason: TokenRefusal) = assertEquals(TokenResult.Refused(reason), r)

    private fun valid(t: IssuedToken, now: Instant = START.plusSeconds(60)) = tokens.verify(t.token, now)

    private val dumpTables = listOf("selfdev_assignees", "selfdev_assignee_members", "selfdev_assignee_events", "selfdev_assignee_tokens")

    /** Every value of every assignee table, as text. */
    private fun dump(): String = transaction(db.database) {
        dumpTables.joinToString("\n") { table ->
            execQuery("SELECT * FROM $table") { rs ->
                (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it) ?: "null" }
            }.joinToString("\n")
        }
    }

    private fun count(sql: String, vararg p: Any?): Int = transaction(db.database) { execQuery(sql, *p) { it.getInt(1) }.first() }

    @Test
    fun `issue then verify gives Valid with a synthetic owner key`() {
        val a = assignee(AssigneeRole.AI)
        val t = issued(a)
        assertTrue(Regex("kvt_[A-Za-z0-9_-]{43}").matches(t.token))
        val v = assertIs<TokenVerification.Valid>(valid(t))
        assertEquals(a.assigneeId, v.assigneeId)
        assertEquals(AssigneeRole.AI, v.role)
        assertEquals(t.tokenRef, v.tokenRef)
        assertEquals("14:kivra-assignee:${a.assigneeId}", v.ownerKey)
        assertTrue(isOwnerKeyShape(v.ownerKey))
        // The owner key leads to the same assignee through the existing lookup.
        assertEquals(a.assigneeId, assignees.accessOf(v.ownerKey)?.assigneeId)
        assertEquals(java.time.Duration.ofDays(90), java.time.Duration.between(java.time.LocalDateTime.ofInstant(START, ZoneOffset.UTC), t.expiresAt).let { it.minusMillis(it.toMillis() % 1000) })
    }

    @Test
    fun `VIEWER can hold a token`() {
        val t = issued(assignee(AssigneeRole.VIEWER))
        assertEquals(AssigneeRole.VIEWER, assertIs<TokenVerification.Valid>(valid(t)).role)
    }

    @Test
    fun `the plaintext is nowhere in the database, the events or any toString`() {
        val a = assignee(AssigneeRole.AI)
        val t = issued(a)
        tokens.verify(t.token, START.plusSeconds(60))
        tokens.revoke(t.tokenRef, ADMIN_KEY)
        val all = dump()
        assertFalse(all.contains(t.token))
        assertFalse(all.contains(t.token.removePrefix("kvt_")))
        var i = 0
        while (i + 12 <= t.token.length) {
            assertFalse(all.contains(t.token.substring(i, i + 12)), "a part of the token is stored at $i")
            i += 4
        }
        for (s in listOf(t.toString(), valid(t).toString(), tokens.list(a.assigneeId).toString(), TokenVerification.Invalid.toString())) {
            assertFalse(s.contains(t.token.removePrefix("kvt_").take(12)), s)
        }
        assertTrue(t.toString().contains("<redacted>"))
        val second = issued(a)
        assertFalse(assertIs<TokenVerification.Valid>(tokens.verify(second.token, START.plusSeconds(60))).toString().contains("kivra-assignee"))
    }

    @Test
    fun `the stored hash and ref have the documented form and the events carry only refs`() {
        val a = assignee(AssigneeRole.AI)
        val t = issued(a)
        val row = transaction(db.database) {
            execQuery("SELECT token_hash, token_ref, created_by FROM selfdev_assignee_tokens") { listOf(it.getString(1), it.getString(2), it.getString(3)) }.single()
        }
        assertTrue(Regex("[0-9a-f]{64}").matches(row[0]!!))
        assertEquals(row[0]!!.take(16), row[1])
        assertEquals(t.tokenRef, row[1])
        assertEquals(SelfDevAssigneeRepository.actorRef(ADMIN_KEY), row[2])
        val ev = transaction(db.database) {
            execQuery("SELECT kind, actor_ref, token_ref, member_ref FROM selfdev_assignee_events WHERE kind LIKE 'TOKEN%'") {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
            }
        }
        assertEquals(listOf(listOf("TOKEN_ISSUED", SelfDevAssigneeRepository.actorRef(ADMIN_KEY), t.tokenRef, null)), ev)
    }

    @Test
    fun `an assignee that is not AI or VIEWER gets no token`() {
        for (role in listOf(AssigneeRole.ADMIN, AssigneeRole.OPERATOR, AssigneeRole.APPROVER)) {
            refused(tokens.issue(assignee(role, "n-$role").assigneeId, 90, ADMIN_KEY), TokenRefusal.ROLE_NOT_ALLOWED)
        }
        refused(tokens.issue("01ARZ3NDEKTSV4RRFFQ69G5FAV", 90, ADMIN_KEY), TokenRefusal.ASSIGNEE_NOT_FOUND)
        val d = assignee(AssigneeRole.AI, "disabled")
        assertIs<AssigneeResult.Moved<Assignee>>(assignees.setState(d.assigneeId, AssigneeState.DISABLED, 0, OTHER_ADMIN_KEY))
        refused(tokens.issue(d.assigneeId, 90, ADMIN_KEY), TokenRefusal.ROLE_NOT_ALLOWED)
        refused(tokens.issue(d.assigneeId, 90, "not-an-owner-key"), TokenRefusal.INVALID_ACTOR)
        assertEquals(0, count("SELECT COUNT(*) FROM selfdev_assignee_tokens"))
        assertEquals(0, count("SELECT COUNT(*) FROM selfdev_assignee_members"))
    }

    @Test
    fun `a disabled assignee or a changed role makes the same token Invalid`() {
        assignee(AssigneeRole.ADMIN, "root") // another ACTIVE ADMIN, so the token's assignee can be moved to and from ADMIN
        val a = assignee(AssigneeRole.AI)
        val t = issued(a)
        assertIs<TokenVerification.Valid>(valid(t))
        assertIs<AssigneeResult.Moved<Assignee>>(assignees.setState(a.assigneeId, AssigneeState.DISABLED, 0, OTHER_ADMIN_KEY))
        assertSame(TokenVerification.Invalid, valid(t))
        assertIs<AssigneeResult.Moved<Assignee>>(assignees.setState(a.assigneeId, AssigneeState.ACTIVE, 1, OTHER_ADMIN_KEY))
        assertIs<TokenVerification.Valid>(valid(t))
        assertIs<AssigneeResult.Moved<Assignee>>(assignees.changeRole(a.assigneeId, AssigneeRole.ADMIN, 2, OTHER_ADMIN_KEY))
        assertSame(TokenVerification.Invalid, valid(t))
        assertEquals(1, count("SELECT COUNT(*) FROM selfdev_assignee_tokens WHERE revoked_at IS NULL"))
        assertIs<AssigneeResult.Moved<Assignee>>(assignees.changeRole(a.assigneeId, AssigneeRole.VIEWER, 3, OTHER_ADMIN_KEY))
        assertEquals(AssigneeRole.VIEWER, assertIs<TokenVerification.Valid>(valid(t)).role)
        assertEquals(listOf(InvalidReason.ASSIGNEE_DISABLED, InvalidReason.ROLE_NOT_ALLOWED), seen.toList())
    }

    @Test
    fun `an expired token is Invalid`() {
        val t = issued(assignee(AssigneeRole.AI), 10)
        assertIs<TokenVerification.Valid>(tokens.verify(t.token, START.plusSeconds(9L * 86400)))
        assertSame(TokenVerification.Invalid, tokens.verify(t.token, START.plusSeconds(11L * 86400)))
        assertEquals(listOf(InvalidReason.EXPIRED), seen.toList())
    }

    @Test
    fun `a revoked token is Invalid at once, by ref or by id, and only once`() {
        val a = assignee(AssigneeRole.AI)
        val t1 = issued(a)
        val t2 = issued(a)
        assertEquals(TokenResult.Ok(Unit), tokens.revoke(t1.tokenRef, ADMIN_KEY))
        assertSame(TokenVerification.Invalid, valid(t1))
        assertIs<TokenVerification.Valid>(valid(t2))
        refused(tokens.revoke(t1.tokenRef, ADMIN_KEY), TokenRefusal.ALREADY_REVOKED)
        assertEquals(TokenResult.Ok(Unit), tokens.revoke(t2.tokenId, OTHER_ADMIN_KEY))
        assertSame(TokenVerification.Invalid, valid(t2))
        refused(tokens.revoke("0123456789abcdef", ADMIN_KEY), TokenRefusal.NOT_FOUND)
        refused(tokens.revoke("nonsense", ADMIN_KEY), TokenRefusal.NOT_FOUND)
        refused(tokens.revoke(t1.tokenId, "bad"), TokenRefusal.INVALID_ACTOR)
        assertEquals(listOf(InvalidReason.REVOKED, InvalidReason.REVOKED), seen.toList())
        val events = transaction(db.database) {
            execQuery("SELECT actor_ref, token_ref FROM selfdev_assignee_events WHERE kind = 'TOKEN_REVOKED' ORDER BY event_id") {
                listOf(it.getString(1), it.getString(2))
            }
        }
        assertEquals(
            listOf(
                listOf(SelfDevAssigneeRepository.actorRef(ADMIN_KEY), t1.tokenRef),
                listOf(SelfDevAssigneeRepository.actorRef(OTHER_ADMIN_KEY), t2.tokenRef),
            ),
            events,
        )
    }

    @Test
    fun `a third valid token is refused and a revoked or expired one frees its place`() {
        val a = assignee(AssigneeRole.AI)
        val t1 = issued(a, 5)
        issued(a)
        refused(tokens.issue(a.assigneeId, 90, ADMIN_KEY), TokenRefusal.TOO_MANY_ACTIVE)
        tokens.revoke(t1.tokenId, ADMIN_KEY)
        issued(a)
        refused(tokens.issue(a.assigneeId, 90, ADMIN_KEY), TokenRefusal.TOO_MANY_ACTIVE)
        // Another assignee has its own two.
        val b = assignee(AssigneeRole.VIEWER, "other")
        issued(b)
        // Expired tokens do not count: issue 'later' than the 5-day token expiry for a fresh assignee.
        val c = assignee(AssigneeRole.AI, "third")
        issued(c, 5)
        issued(c, 5)
        refused(tokens.issue(c.assigneeId, 90, ADMIN_KEY, START.plusSeconds(60)), TokenRefusal.TOO_MANY_ACTIVE)
        assertIs<TokenResult.Ok<IssuedToken>>(tokens.issue(c.assigneeId, 90, ADMIN_KEY, START.plusSeconds(6L * 86400)))
    }

    @Test
    fun `two concurrent issues cannot pass the limit`() {
        val a = assignee(AssigneeRole.AI)
        issued(a)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val jobs = (1..2).map { pool.submit(Callable { go.await(); tokens.issue(a.assigneeId, 90, ADMIN_KEY) }) }
            go.countDown()
            val results = jobs.map { it.get() }
            assertEquals(1, results.count { it is TokenResult.Ok<*> })
            assertEquals(1, results.count { it == TokenResult.Refused(TokenRefusal.TOO_MANY_ACTIVE) })
            assertEquals(2, count("SELECT COUNT(*) FROM selfdev_assignee_tokens WHERE assignee_id = ?", a.assigneeId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a malformed token is refused without touching the database`() {
        val other = TestDb()
        val orphan = SelfDevAssigneeTokenRepository(other.database, clock) { seen += it }
        other.close()
        val bad = listOf(
            "", "kvt_", "kvt_short", "x".repeat(47), "KVT_" + "a".repeat(43), "kvt_" + "a".repeat(42), "kvt_" + "a".repeat(44),
            "kvt_" + "a".repeat(42) + "=", "kvt_" + "a".repeat(42) + "+", "kvt_" + "a".repeat(42) + "\n", " kvt_" + "a".repeat(43),
        )
        for (b in bad) assertSame(TokenVerification.Invalid, orphan.verify(b), "[$b]")
        assertEquals(bad.size, seen.count { it == InvalidReason.MALFORMED })
        // A well-formed one does reach the (closed) database.
        assertFailsWith<Exception> { orphan.verify("kvt_" + "a".repeat(43)) }
    }

    @Test
    fun `an unknown well-formed token is Invalid`() {
        assignee(AssigneeRole.AI)
        assertSame(TokenVerification.Invalid, tokens.verify("kvt_" + "A".repeat(43)))
        assertEquals(listOf(InvalidReason.UNKNOWN), seen.toList())
    }

    @Test
    fun `the first issue binds the owner key once, later issues do not bind again`() {
        val a = assignee(AssigneeRole.AI)
        assertEquals(0, count("SELECT COUNT(*) FROM selfdev_assignee_members WHERE assignee_id = ?", a.assigneeId))
        issued(a)
        issued(a)
        assertEquals(1, count("SELECT COUNT(*) FROM selfdev_assignee_members WHERE assignee_id = ?", a.assigneeId))
        assertEquals(1, count("SELECT COUNT(*) FROM selfdev_assignee_events WHERE kind = 'MEMBER_BOUND' AND assignee_id = ?", a.assigneeId))
        assertEquals(2, count("SELECT COUNT(*) FROM selfdev_assignee_events WHERE kind = 'TOKEN_ISSUED' AND assignee_id = ?", a.assigneeId))
        assertEquals(listOf(SelfDevAssigneeRepository.memberRef(SelfDevAssigneeTokenRepository.ownerKeyOf(a.assigneeId))), assignees.memberRefs(a.assigneeId))
    }

    @Test
    fun `a refused issue binds nothing`() {
        val a = assignee(AssigneeRole.AI)
        refused(tokens.issue(a.assigneeId, 0, ADMIN_KEY), TokenRefusal.TTL_OUT_OF_RANGE)
        assertEquals(0, count("SELECT COUNT(*) FROM selfdev_assignee_members"))
        // If the synthetic key is bound to another assignee, the issue is refused and writes nothing.
        val other = assignee(AssigneeRole.VIEWER, "other")
        assertIs<AssigneeResult.Moved<Unit>>(assignees.bindMember(other.assigneeId, SelfDevAssigneeTokenRepository.ownerKeyOf(a.assigneeId), ADMIN_KEY))
        refused(tokens.issue(a.assigneeId, 90, ADMIN_KEY), TokenRefusal.OWNER_KEY_CONFLICT)
        assertEquals(0, count("SELECT COUNT(*) FROM selfdev_assignee_tokens"))
    }

    @Test
    fun `the ttl is 1 to 365 days`() {
        val a = assignee(AssigneeRole.AI)
        refused(tokens.issue(a.assigneeId, 0, ADMIN_KEY), TokenRefusal.TTL_OUT_OF_RANGE)
        refused(tokens.issue(a.assigneeId, -1, ADMIN_KEY), TokenRefusal.TTL_OUT_OF_RANGE)
        refused(tokens.issue(a.assigneeId, 366, ADMIN_KEY), TokenRefusal.TTL_OUT_OF_RANGE)
        assertEquals(0, count("SELECT COUNT(*) FROM selfdev_assignee_tokens"))
        val one = assertIs<TokenResult.Ok<IssuedToken>>(tokens.issue(a.assigneeId, 1, ADMIN_KEY, START)).value
        val max = assertIs<TokenResult.Ok<IssuedToken>>(tokens.issue(a.assigneeId, 365, ADMIN_KEY, START)).value
        assertEquals(java.time.LocalDateTime.ofInstant(START.plusSeconds(86400), ZoneOffset.UTC), one.expiresAt)
        assertEquals(java.time.LocalDateTime.ofInstant(START.plusSeconds(365L * 86400), ZoneOffset.UTC), max.expiresAt)
        val d = assignee(AssigneeRole.AI, "default")
        val def = assertIs<TokenResult.Ok<IssuedToken>>(tokens.issue(d.assigneeId, actorKey = ADMIN_KEY, now = START)).value
        assertEquals(java.time.LocalDateTime.ofInstant(START.plusSeconds(90L * 86400), ZoneOffset.UTC), def.expiresAt)
    }

    @Test
    fun `list shows refs and dates only`() {
        val a = assignee(AssigneeRole.AI)
        val t1 = issued(a)
        val t2 = issued(a)
        tokens.revoke(t1.tokenId, ADMIN_KEY)
        val l = tokens.list(a.assigneeId)
        assertEquals(listOf(t1.tokenRef, t2.tokenRef), l.map { it.tokenRef })
        assertEquals(listOf(true, false), l.map { it.revoked })
        assertEquals(SelfDevAssigneeRepository.actorRef(ADMIN_KEY), l[0].createdBy)
        val hash = transaction(db.database) { execQuery("SELECT token_hash FROM selfdev_assignee_tokens") { it.getString(1) } }
        for (s in l.map { it.toString() }) {
            assertTrue(hash.none { h -> s.contains(h) })
            assertFalse(s.contains(t1.token) || s.contains(t2.token))
        }
        assertEquals(emptyList(), tokens.list("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        assertEquals(setOf("tokenId", "tokenRef", "createdBy", "createdAt", "expiresAt", "revoked"), TokenInfo::class.java.declaredFields.map { it.name }.toSet())
    }

    @Test
    fun `every failure looks the same to the caller`() {
        val a = assignee(AssigneeRole.AI)
        val revoked = issued(a)
        val expired = issued(a, 1)
        tokens.revoke(revoked.tokenId, ADMIN_KEY)
        val late = START.plusSeconds(3L * 86400)
        val results = listOf(
            tokens.verify("bad"), tokens.verify("kvt_" + "B".repeat(43)), tokens.verify(revoked.token, START.plusSeconds(60)), tokens.verify(expired.token, late),
        )
        assertTrue(results.all { it === TokenVerification.Invalid })
        assertEquals(
            listOf(InvalidReason.MALFORMED, InvalidReason.UNKNOWN, InvalidReason.REVOKED, InvalidReason.EXPIRED), seen.toList(),
        )
        assertEquals(emptyList(), TokenVerification.Invalid::class.java.declaredFields.filter { !it.isSynthetic && it.name != "INSTANCE" })
        assertNotEquals(TokenVerification.Invalid.toString(), "")
    }
}
