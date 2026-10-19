package net.kigawa.kivra.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.MemoryKind
import net.kigawa.kivra.selfdev.memory.MemoryRefusal
import net.kigawa.kivra.selfdev.memory.MemorySource
import net.kigawa.kivra.selfdev.memory.MemoryTier
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryValidator
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * ADR 0016 P0 on H2 (default) or MariaDB (`KIVRA_TEST_DB=mariadb`): the single write function, the refusals and their audit, duplicates,
 * quotas, references, deletion (single, conditional, dry run, the 500 limit), the retention steps, the derived references, the default
 * off, and that no log line carries a text.
 */
class SelfDevMemoryRepositoryTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    private fun note(body: String, goalId: String? = null, operator: String = "operator-1") = MemoryCandidate(
        kind = "OPERATOR_NOTE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = operator,
        goalId = goalId, body = body,
    )

    private fun planFact(count: Int, goalId: String? = null) = MemoryCandidate(
        kind = "PLAN_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = goalId, code = "PLAN_PROPOSED", countValue = count,
    )

    private fun rows(db: TestDb, sql: String): List<List<String?>> = transaction(db.database) {
        execQuery(sql) { rs -> (1..rs.metaData.columnCount).map { rs.getString(it) } }
    }

    private fun repo(db: TestDb, at: Instant = t0, enabled: Boolean = true) =
        SelfDevMemoryRepository(db.database, enabled = enabled, clock = clockAt(at))

    private fun goal(db: TestDb, key: String): String {
        val result = SelfDevGoalRepository(db.database).submit("operator-1", key, "題名", "本文", "kigawa-net/kivra")
        return (result as SubmitResult.Created).goal.goalId
    }

    /** One plan with one task, so that a record can reference them. Raw SQL: the plan lifecycle is not under test here. */
    private fun plan(db: TestDb, goalId: String): String {
        val planId = Ulid.generate()
        transaction(db.database) {
            execUpdate(
                "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) VALUES (?, ?, 1, 'PROPOSED', 'test-model', ?, ?)",
                planId, goalId, "b".repeat(64), t0.atZone(ZoneOffset.UTC).toLocalDateTime(),
            )
            execUpdate(
                "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval, state, idempotency_key, attempts, version, created_at, updated_at) " +
                    "VALUES (?, 1, 'task', NULL, '[]', 'S', '[]', FALSE, 'PENDING_APPROVAL', ?, 0, 0, ?, ?)",
                planId, "ap:$planId:1", t0.atZone(ZoneOffset.UTC).toLocalDateTime(), t0.atZone(ZoneOffset.UTC).toLocalDateTime(),
            )
        }
        return planId
    }

    @Test
    fun `off by default, nothing is written or read, and the result says so`() = TestDb().use { db ->
        val off = SelfDevMemoryRepository(db.database, enabled = false)
        assertEquals(net.kigawa.kivra.db.MemorySaveResult.Disabled, off.save(note("覚えてほしいこと")))
        assertTrue(off.search(MemoryFilter(), 10).isEmpty())
        assertTrue(off.events(10).isEmpty())
        assertEquals(0, db.count("selfdev_memory_records"))
        assertEquals(0, db.count("selfdev_memory_events"))
    }

    @Test
    fun `a valid note is written once with its NFC text, its hash and its operator hash, and the audit holds no text`() = TestDb().use { db ->
        val r = repo(db)
        val written = assertIs<MemorySaveResult.Written>(r.save(note("é の確認")))
        val view = r.search(MemoryFilter(), 10).single()
        assertEquals(written.recordId, view.recordId)
        assertEquals("é の確認", view.body)
        val row = rows(db, "SELECT body_sha256, body_chars, created_by_hash, status FROM selfdev_memory_records").single()
        assertEquals(SelfDevMemoryValidator.sha256Hex("é の確認"), row[0])
        assertEquals("5", row[1])
        assertEquals(SelfDevMemoryValidator.actorHash("operator-1"), row[2])
        assertEquals("ACTIVE", row[3])
        val event = rows(db, "SELECT kind, count_value, actor_hash FROM selfdev_memory_events").single()
        assertEquals(listOf("WRITTEN", "5", SelfDevMemoryValidator.actorHash("operator-1")), event)
    }

    @Test
    fun `a refused write leaves no record, and the audit keeps the code, the length and the secret kind only`() = TestDb().use { db ->
        val r = repo(db)
        val secret = "sk-ant-api03-Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1Gs0YaE9uTxQ"
        val refused = r.save(note("認証に $secret を使う"))
        assertEquals(MemorySaveResult.Refused(MemoryRefusal.SECRET_DETECTED), refused)
        assertEquals(0, db.count("selfdev_memory_records"))
        val event = rows(db, "SELECT kind, code, detail, count_value FROM selfdev_memory_events").single()
        assertEquals(listOf("REJECTED", "SECRET_DETECTED", "API_KEY", event[3]), event)
        assertNotNull(event[3])
        // Nothing stored anywhere in the two tables carries the secret, its fragment, or the text's hash.
        val everything = (rows(db, "SELECT * FROM selfdev_memory_events") + rows(db, "SELECT * FROM selfdev_memory_records")).flatten().joinToString("|")
        assertFalse(everything.contains("Zk3Qm9"))
        assertFalse(everything.contains(SelfDevMemoryValidator.sha256Hex("認証に $secret を使う")))
    }

    @Test
    fun `the same note twice returns the first id, and writes only a duplicate audit row`() = TestDb().use { db ->
        val r = repo(db)
        val first = assertIs<MemorySaveResult.Written>(r.save(note("同じ文章")))
        val again = r.save(note("同じ文章"))
        assertEquals(MemorySaveResult.Duplicate(first.recordId), again)
        assertEquals(1, db.count("selfdev_memory_records"))
        assertEquals(1, db.count("selfdev_memory_events", "kind = 'DUPLICATE'"))
    }

    @Test
    fun `a goal reference must exist, and a note on an existing goal is written`() = TestDb().use { db ->
        val r = repo(db)
        assertEquals(MemorySaveResult.Refused(MemoryRefusal.REF_NOT_FOUND), r.save(note("題名に関係", goalId = "01MISSING000000000000000000")))
        val goalId = goal(db, "k-ref")
        assertIs<MemorySaveResult.Written>(r.save(note("題名に関係", goalId = goalId)))
    }

    @Test
    fun `a goal takes at most ten notes, and the eleventh is refused as a quota`() = TestDb().use { db ->
        val r = repo(db)
        val goalId = goal(db, "k-quota-text")
        for (i in 1..10) assertIs<MemorySaveResult.Written>(r.save(note("メモ $i", goalId = goalId)))
        assertEquals(MemorySaveResult.Refused(MemoryRefusal.QUOTA_EXCEEDED), r.save(note("メモ 11", goalId = goalId)))
        assertEquals(10, db.count("selfdev_memory_records", "goal_id = '$goalId'"))
    }

    @Test
    fun `a fact is stored with its code and count, and a fact refers to nothing it does not need`() = TestDb().use { db ->
        val r = repo(db)
        assertIs<MemorySaveResult.Written>(r.save(planFact(3)))
        val row = rows(db, "SELECT kind, source_kind, trust_tier, code, count_value, body FROM selfdev_memory_records").single()
        assertEquals(listOf("PLAN_FACT", "SYSTEM", "UNTRUSTED", "PLAN_PROPOSED", "3", null), row)
    }

    @Test
    fun `a planner note is UNTRUSTED and an operator cannot be imitated by the planner channel`() = TestDb().use { db ->
        val r = repo(db)
        val planner = MemoryCandidate(kind = "PLAN_RATIONALE", channel = MemoryChannel.PLANNER_AUTO, body = "計画の理由")
        assertIs<MemorySaveResult.Written>(r.save(planner))
        assertEquals("UNTRUSTED", rows(db, "SELECT trust_tier FROM selfdev_memory_records").single().single())
        assertEquals(MemorySaveResult.Refused(MemoryRefusal.TIER_MISMATCH), r.save(note("x").copy(channel = MemoryChannel.PLANNER_AUTO)))
    }

    @Test
    fun `a deleted note keeps a tombstone without text, leaves the search, and the audit records the scope and the count`() = TestDb().use { db ->
        val r = repo(db)
        val id = assertIs<MemorySaveResult.Written>(r.save(note("消す文章"))).recordId
        assertEquals(MemoryDeleteResult.Deleted(id), r.deleteOne(id, "operator-1"))
        val tomb = rows(db, "SELECT status, body, body_sha256, body_chars, delete_reason_code, kind FROM selfdev_memory_records").single()
        assertEquals(listOf("DELETED", null, null, null, "OPERATOR_DELETE", "OPERATOR_NOTE"), tomb)
        assertTrue(r.search(MemoryFilter(), 10).isEmpty())
        assertEquals(MemoryDeleteResult.NotFound, r.deleteOne(id, "operator-1"))
        val event = rows(db, "SELECT kind, code, scope, count_value FROM selfdev_memory_events WHERE kind = 'DELETED'").single()
        assertEquals(listOf("DELETED", "OPERATOR_DELETE", "ID", "1"), event)
        assertFalse(rows(db, "SELECT * FROM selfdev_memory_events").flatten().joinToString("|").contains("消す文章"))
    }

    @Test
    fun `a deleted fact is removed, not tombstoned`() = TestDb().use { db ->
        val r = repo(db)
        val id = assertIs<MemorySaveResult.Written>(r.save(planFact(2))).recordId
        assertEquals(MemoryDeleteResult.Deleted(id), r.deleteOne(id, "operator-1"))
        assertEquals(0, db.count("selfdev_memory_records"))
    }

    @Test
    fun `an empty filter is refused, a dry run changes nothing, and more than 500 matches are refused whole`() = TestDb().use { db ->
        val r = repo(db)
        assertEquals(MemoryConditionDeleteResult.Refused("EMPTY_FILTER", 0), r.deleteByCondition(MemoryFilter(), "operator-1", dryRun = false))

        for (i in 1..3) assertIs<MemorySaveResult.Written>(r.save(note("文 $i")))
        assertEquals(MemoryConditionDeleteResult.Done(matched = 3, deleted = 0, dryRun = true), r.deleteByCondition(MemoryFilter(kind = MemoryKind.OPERATOR_NOTE), "operator-1", dryRun = true))
        assertEquals(3, db.count("selfdev_memory_records", "status = 'ACTIVE'"))

        for (i in 1..501) assertIs<MemorySaveResult.Written>(r.save(planFact(i)))
        assertEquals(
            MemoryConditionDeleteResult.Refused("LIMIT_EXCEEDED", 501),
            r.deleteByCondition(MemoryFilter(kind = MemoryKind.PLAN_FACT), "operator-1", dryRun = false),
        )
        assertEquals(501, db.count("selfdev_memory_records", "kind = 'PLAN_FACT'"))
    }

    @Test
    fun `a conditional delete removes the matches, tombstones the notes, and audits the scope and the count only`() = TestDb().use { db ->
        val r = repo(db)
        val goalId = goal(db, "k-cond")
        for (i in 1..2) assertIs<MemorySaveResult.Written>(r.save(note("目標の文 $i", goalId = goalId)))
        assertIs<MemorySaveResult.Written>(r.save(note("別の文")))
        val done = r.deleteByCondition(MemoryFilter(goalId = goalId), "operator-1", dryRun = false)
        assertEquals(MemoryConditionDeleteResult.Done(matched = 2, deleted = 2, dryRun = false), done)
        assertEquals(1, r.search(MemoryFilter(), 10).size)
        assertEquals(2, db.count("selfdev_memory_records", "status = 'DELETED'"))
        val event = rows(db, "SELECT code, scope, count_value FROM selfdev_memory_events WHERE kind = 'DELETED'").single()
        assertEquals(listOf("CONDITION_DELETE", "CONDITION:goal_id", "2"), event)
    }

    @Test
    fun `search never returns a deleted record, and a deleted record stays out of a later search`() = TestDb().use { db ->
        val r = repo(db)
        val a = assertIs<MemorySaveResult.Written>(r.save(note("検索される文"))).recordId
        val b = assertIs<MemorySaveResult.Written>(r.save(note("消される文"))).recordId
        r.deleteOne(b, "operator-1")
        val found = r.search(MemoryFilter(), 10).map { it.recordId }
        assertEquals(listOf(a), found)
        assertTrue(r.search(MemoryFilter(kind = MemoryKind.OPERATOR_NOTE, tier = MemoryTier.TRUSTED_OPERATOR), 10).all { it.recordId != b })
        assertTrue(r.search(MemoryFilter(source = MemorySource.OPERATOR), 10).none { it.body == "消される文" })
    }

    @Test
    fun `deleting a record removes the plan references that name it`() = TestDb().use { db ->
        val r = repo(db)
        val goalId = goal(db, "k-refs")
        val planId = plan(db, goalId)
        val kept = assertIs<MemorySaveResult.Written>(r.save(planFact(1))).recordId
        val gone = assertIs<MemorySaveResult.Written>(r.save(planFact(2))).recordId
        transaction(db.database) {
            execUpdate("INSERT INTO selfdev_plan_memory_refs (plan_id, record_id) VALUES (?, ?)", planId, kept)
            execUpdate("INSERT INTO selfdev_plan_memory_refs (plan_id, record_id) VALUES (?, ?)", planId, gone)
        }
        r.deleteOne(gone, "operator-1")
        assertEquals(listOf(kept), rows(db, "SELECT record_id FROM selfdev_plan_memory_refs").map { it.single() })
    }

    @Test
    fun `the retention steps purge texts after 180 days, tombstones after 30 more, facts and events after 365 days`() = TestDb().use { db ->
        val writer = repo(db, at = t0)
        val text = assertIs<MemorySaveResult.Written>(writer.save(note("古い文"))).recordId
        assertIs<MemorySaveResult.Written>(writer.save(planFact(1)))

        // Day 181: the note is past its 180 days and becomes a tombstone; the fact is still kept.
        val day181 = repo(db, at = t0.plusSeconds(181L * 86400))
        val first = day181.purgeRetention()
        assertEquals(1, first.tombstoned)
        assertEquals(0, first.factsPurged)
        assertEquals("DELETED", rows(db, "SELECT status FROM selfdev_memory_records WHERE record_id = '$text'").single().single())

        // Day 212: the tombstone is 31 days old (deleted at day 181), so it is removed; the fact is 212 days old, kept.
        val day212 = repo(db, at = t0.plusSeconds(212L * 86400))
        assertEquals(1, day212.purgeRetention().tombstonesPurged)
        assertEquals(0, db.count("selfdev_memory_records", "record_id = '$text'"))
        assertEquals(1, db.count("selfdev_memory_records", "kind = 'PLAN_FACT'"))

        // Day 366: the fact and the events of day 0 are past 365 days.
        val day366 = repo(db, at = t0.plusSeconds(366L * 86400))
        val last = day366.purgeRetention()
        assertEquals(1, last.factsPurged)
        assertTrue(last.eventsPurged > 0)
        assertEquals(0, db.count("selfdev_memory_records"))
    }

    @Test
    fun `the retention batch is bounded`() = TestDb().use { db ->
        val writer = repo(db, at = t0)
        for (i in 1..3) assertIs<MemorySaveResult.Written>(writer.save(note("古い $i")))
        val later = repo(db, at = t0.plusSeconds(181L * 86400))
        assertEquals(2, later.purgeRetention(batchSize = 2).tombstoned)
        assertEquals(1, later.purgeRetention(batchSize = 2).tombstoned)
        assertEquals(0, later.purgeRetention(batchSize = 2).tombstoned)
    }

    /** ADR 0016 section 3.1: nothing in the memory writes a log line that carries a text or a secret. */
    @Test
    fun `no log line carries a note text, a secret or a refusal's text`() = TestDb().use { db ->
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val previous = root.level
        val appender = ListAppender<ILoggingEvent>().also { it.start(); root.addAppender(it) }
        root.level = Level.TRACE
        try {
            val r = repo(db)
            val goalId = goal(db, "k-log")
            val marker = "MARKER-LOG-7731"
            r.save(note("$marker 覚える文", goalId = goalId))
            r.save(note("$marker sk-ant-api03-Zk3Qm9Xp2Lw8Vt5Rb7Nc4Hd6Jf1Gs0YaE9uTxQ"))
            r.save(note("$marker 2 件目", goalId = goalId))
            r.deleteByCondition(MemoryFilter(goalId = goalId), "operator-1", dryRun = false)
            r.purgeRetention()
            val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + it.throwableProxy?.message.orEmpty() }
            assertFalse(logged.contains(marker), "a log line carries the note text")
            assertFalse(logged.contains("Zk3Qm9"), "a log line carries the secret")
        } finally {
            root.detachAppender(appender)
            root.level = previous
        }
    }

    @Test
    fun `the refusal and the audit never name a text in the verdict or the rows`() = TestDb().use { db ->
        val r = repo(db)
        val marker = "MARKER-REFUSE-4412 ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        assertIs<MemorySaveResult.Refused>(r.save(note(marker)))
        val dump = (rows(db, "SELECT * FROM selfdev_memory_events") + rows(db, "SELECT * FROM selfdev_memory_records")).flatten().joinToString("|")
        assertFalse(dump.contains("MARKER-REFUSE"))
        assertFalse(dump.contains("ghp_"))
        assertNull(rows(db, "SELECT record_id FROM selfdev_memory_events").single().single())
    }
}
