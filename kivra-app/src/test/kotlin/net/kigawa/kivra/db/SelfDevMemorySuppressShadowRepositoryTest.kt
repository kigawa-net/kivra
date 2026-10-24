package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryRecorder.Companion.identityOf
import net.kigawa.kivra.selfdev.memory.ShadowFacts
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * ADR 0016 P3 (shadow) on the repository: the reads the suppression shadow makes (identities and fixed codes, the windows, the
 * consecutive failures) and the one audit row it writes. H2 by default, MariaDB with `KIVRA_TEST_DB=mariadb`.
 */
class SelfDevMemorySuppressShadowRepositoryTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val now: Instant = t0.plus(Duration.ofHours(5))
    private val idA = identityOf(listOf("docs/a.md"))
    private val idB = identityOf(listOf("docs/b.md"))
    private val idC = identityOf(listOf("docs/c.md"))
    private val idD = identityOf(listOf("docs/d.md"))

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)
    private fun repo(db: TestDb, at: Instant = now, enabled: Boolean = true) =
        SelfDevMemoryRepository(db.database, enabled = enabled, clock = clockAt(at))
    private fun local(instant: Instant) = instant.atZone(ZoneOffset.UTC).toLocalDateTime()
    private fun days(n: Long): Instant = t0.minus(Duration.ofDays(n))

    private fun goal(db: TestDb): String {
        val result = SelfDevGoalRepository(db.database).submit("operator-1", "key-${System.nanoTime()}", "題名", "本文", "kigawa-net/kivra")
        return (result as SubmitResult.Created).goal.goalId
    }

    /** A plan with one task per entry of [paths], created at [at]. Raw SQL: the plan lifecycle is not under test here. */
    private fun plan(db: TestDb, goalId: String, at: Instant, vararg paths: List<String>): String {
        val planId = Ulid.generate()
        transaction(db.database) {
            // One plan per revision of a goal (a unique key), so each plan of the goal takes the next revision.
            val revision = execQuery("SELECT COUNT(*) FROM selfdev_plans WHERE goal_id = ?", goalId) { it.getInt(1) }.single() + 1
            execUpdate(
                "INSERT INTO selfdev_plans (plan_id, goal_id, revision, state, planner_model, output_sha256, created_at) VALUES (?, ?, ?, 'PROPOSED', 'test-model', ?, ?)",
                planId, goalId, revision, "b".repeat(64), local(at),
            )
            paths.forEachIndexed { index, p ->
                execUpdate(
                    "INSERT INTO selfdev_plan_tasks (plan_id, ordinal, title, body, target_paths, size_estimate, depends_on, requires_approval, state, idempotency_key, attempts, version, created_at, updated_at) " +
                        "VALUES (?, ?, 'task', NULL, ?, 'S', '[]', FALSE, 'PENDING_APPROVAL', ?, 0, 0, ?, ?)",
                    planId, index + 1, p.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\""), "ap:$planId:${index + 1}",
                    local(at), local(at),
                )
            }
        }
        return planId
    }

    /** A TASK_FACT of task [ordinal] of [planId], written at [at] by the repository's own write function. */
    private fun fact(db: TestDb, goalId: String, planId: String, ordinal: Int, code: String, identity: String, at: Instant) {
        val result = repo(db, at).save(
            MemoryCandidate(
                kind = "TASK_FACT", channel = MemoryChannel.SYSTEM_AUTO, goalId = goalId, planId = planId, taskOrdinal = ordinal,
                code = code, identitySha256 = identity,
            ),
        )
        assertIs<MemorySaveResult.Written>(result)
    }

    private fun identitiesOf(facts: ShadowFacts) =
        listOf(facts.recentPlanIdentities, facts.skippedIdentities, facts.repeatedFailureIdentities).flatten()

    @Test
    fun `off reads nothing and writes nothing`() = TestDb().use { db ->
        val goalId = goal(db)
        fact(db, goalId, plan(db, goalId, t0, listOf("docs/a.md")), 1, "OPERATOR_SKIPPED", idA, t0)
        val off = repo(db, enabled = false)
        assertEquals(ShadowFacts.EMPTY, off.shadowFacts(setOf(idA), "none"))
        off.recordSuppressShadow("SKIPPED_BEFORE", 3)
        assertEquals(0, db.count("selfdev_memory_events", "kind = 'SHADOW_SUPPRESS'"))
    }

    @Test
    fun `the recent plans are the tasks of the newest three plans within 14 days, and the excluded plan is not one of them`() = TestDb().use { db ->
        val goalId = goal(db)
        val old = plan(db, goalId, days(20), listOf("docs/old.md"))
        fact(db, goalId, old, 1, "PLAN_PROPOSED", identityOf(listOf("docs/old.md")), days(20))
        val p1 = plan(db, goalId, t0.plus(Duration.ofHours(1)), listOf("docs/a.md"))
        val p2 = plan(db, goalId, t0.plus(Duration.ofHours(2)), listOf("docs/b.md"))
        val p3 = plan(db, goalId, t0.plus(Duration.ofHours(3)), listOf("docs/c.md"))
        val p4 = plan(db, goalId, t0.plus(Duration.ofHours(4)), listOf("docs/d.md"))
        for ((planId, path, at) in listOf(
            Triple(p1, "docs/a.md", t0.plus(Duration.ofHours(1))),
            Triple(p2, "docs/b.md", t0.plus(Duration.ofHours(2))),
            Triple(p3, "docs/c.md", t0.plus(Duration.ofHours(3))),
            Triple(p4, "docs/d.md", t0.plus(Duration.ofHours(4))),
        )) {
            fact(db, goalId, planId, 1, "PLAN_PROPOSED", identityOf(listOf(path)), at)
        }
        val idOld = identityOf(listOf("docs/old.md"))
        val all = setOf(idA, idB, idC, idD, idOld)
        // The newest three are p4, p3 and p2. p1 is the fourth newest, and the 14-day window does not reach the plan of 20 days ago.
        assertEquals(setOf(idB, idC, idD), repo(db).shadowFacts(all, "not-a-plan").recentPlanIdentities)
        assertFalse(idOld in repo(db).shadowFacts(all, "not-a-plan").recentPlanIdentities)
        // Excluding the newest plan (the one being stored) moves the window one plan back.
        assertEquals(setOf(idA, idB, idC), repo(db).shadowFacts(all, p4).recentPlanIdentities)
    }

    @Test
    fun `a skipped task counts for 90 days, and an older skip does not`() = TestDb().use { db ->
        val goalId = goal(db)
        fact(db, goalId, plan(db, goalId, days(10), listOf("docs/a.md")), 1, "OPERATOR_SKIPPED", idA, days(10))
        fact(db, goalId, plan(db, goalId, days(100), listOf("docs/b.md")), 1, "OPERATOR_SKIPPED", idB, days(100))
        val facts = repo(db).shadowFacts(setOf(idA, idB), "none")
        assertEquals(setOf(idA), facts.skippedIdentities)
        assertTrue(facts.recentPlanIdentities.isEmpty())
    }

    @Test
    fun `a repeated failure is two outcome facts in a row with a failure code, within 14 days`() = TestDb().use { db ->
        val goalId = goal(db)
        // F: AUTO_SCOPE_EXCEEDED, then a PLAN_PROPOSED (not an outcome, so it does not break the run), then AUTO_SCOPE_EXCEEDED.
        val pf = plan(db, goalId, days(3), listOf("docs/f.md"))
        fact(db, goalId, pf, 1, "AUTO_SCOPE_EXCEEDED", idA, days(3))
        fact(db, goalId, pf, 1, "PLAN_PROPOSED", idA, days(2))
        fact(db, goalId, pf, 1, "AUTO_SCOPE_EXCEEDED", idA, days(1))
        // B: the last two outcomes are a failure and then an approval, so no run.
        val pb = plan(db, goalId, days(3), listOf("docs/b.md"))
        fact(db, goalId, pb, 1, "AUTO_SCOPE_EXCEEDED", idB, days(3))
        fact(db, goalId, pb, 1, "OPERATOR_APPROVED", idB, days(2))
        fact(db, goalId, pb, 1, "AUTO_SCOPE_EXCEEDED", idB, days(1))
        // C: a single failure.
        fact(db, goalId, plan(db, goalId, days(1), listOf("docs/c.md")), 1, "AUTO_SCOPE_EXCEEDED", idC, days(1))
        // D: two failures, but 20 days ago, outside the window.
        val pd = plan(db, goalId, days(20), listOf("docs/d.md"))
        fact(db, goalId, pd, 1, "AUTO_SCOPE_EXCEEDED", idD, days(20))
        fact(db, goalId, pd, 1, "AUTO_SCOPE_EXCEEDED", idD, days(19))

        val facts = repo(db).shadowFacts(setOf(idA, idB, idC, idD), "none")
        assertEquals(setOf(idA), facts.repeatedFailureIdentities)
    }

    @Test
    fun `the shadow reads identities and fixed codes only, and writes one audit row per rule with its count and no text`() = TestDb().use { db ->
        val goalId = goal(db)
        fact(db, goalId, plan(db, goalId, t0, listOf("docs/a.md")), 1, "OPERATOR_SKIPPED", idA, t0)
        val facts = repo(db).shadowFacts(setOf(idA, idB), "none")
        assertTrue(identitiesOf(facts).all { HEX64.matches(it) })

        val writer = repo(db)
        writer.recordSuppressShadow("SKIPPED_BEFORE", 2)
        writer.recordSuppressShadow("DUPLICATE_RECENT_PLAN", 0)
        val events = transaction(db.database) {
            execQuery("SELECT kind, code, scope, detail, count_value, record_id FROM selfdev_memory_events WHERE kind = 'SHADOW_SUPPRESS'") { rs ->
                (1..6).map { rs.getString(it) }
            }
        }
        assertEquals(listOf(listOf("SHADOW_SUPPRESS", "SKIPPED_BEFORE", null, null, "2", null)), events)
        assertEquals(0, db.count("selfdev_memory_records", "kind = 'TASK_FACT' AND code = 'SHADOW_SUPPRESS'"))
        assertFalse(events.flatten().any { it?.contains("docs/") == true })
    }

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}
