package net.kigawa.kivra.db

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import net.kigawa.kivra.selfdev.memory.MemoryCandidate
import net.kigawa.kivra.selfdev.memory.MemoryChannel
import net.kigawa.kivra.selfdev.memory.MemoryRefusal
import net.kigawa.kivra.selfdev.memory.SelfDevMemoryValidator

/**
 * ADR 0016 section 4.4 (V12) under concurrent writes: the quotas hold exactly, not only one write at a time. Runs on H2 by default
 * and on MariaDB with `KIVRA_TEST_DB=mariadb`. Each racer starts at the same instant (a barrier), so the saves overlap.
 */
class SelfDevMemoryConcurrencyTest {

    private val rounds = 5

    private fun note(body: String, goalId: String? = null) = MemoryCandidate(
        kind = "OPERATOR_NOTE", channel = MemoryChannel.HTTP_OPERATOR, operatorAuthenticated = true, operatorId = "operator-1",
        goalId = goalId, body = body,
    )

    private fun autoRationale(body: String) = MemoryCandidate(kind = "PLAN_RATIONALE", channel = MemoryChannel.PLANNER_AUTO, body = body)

    private fun goal(db: TestDb, key: String): String {
        val result = SelfDevGoalRepository(db.database).submit("operator-1", key, "題名", "本文", "kigawa-net/kivra")
        return (result as SubmitResult.Created).goal.goalId
    }

    /** Runs [count] tasks at once, each on its own thread, and returns their results in task order. */
    private fun <T> together(count: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val start = CyclicBarrier(count)
            val futures = (0 until count).map { i ->
                pool.submit<T> {
                    start.await(30, TimeUnit.SECONDS)
                    task(i)
                }
            }
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent notes on one goal stop exactly at the per-goal limit`() = TestDb().use { db ->
        val repo = SelfDevMemoryRepository(db.database, enabled = true)
        val limit = SelfDevMemoryValidator.MAX_TEXTS_PER_GOAL
        repeat(rounds) { round ->
            val goalId = goal(db, "k-race-goal-$round")
            val results = together(limit + 5) { i -> repo.save(note("並行 $round-$i", goalId)) }
            assertEquals(limit, results.count { it is MemorySaveResult.Written }, "round $round: written")
            assertEquals(5, results.count { it == MemorySaveResult.Refused(MemoryRefusal.QUOTA_EXCEEDED) }, "round $round: refused")
            assertEquals(limit, db.count("selfdev_memory_records", "goal_id = '$goalId' AND status = 'ACTIVE'"), "round $round: stored")
        }
    }

    @Test
    fun `concurrent automatic notes stop exactly at the daily limit across all goals`() = TestDb().use { db ->
        // The daily automatic cap counts every automatic text of the day, not one goal's, so it is the global lock that holds it.
        val repo = SelfDevMemoryRepository(db.database, enabled = true)
        val limit = SelfDevMemoryValidator.MAX_AUTO_TEXTS_PER_DAY
        val results = together(limit + 10) { i -> repo.save(autoRationale("自動の理由 $i")) }
        assertEquals(limit, results.count { it is MemorySaveResult.Written })
        assertEquals(10, results.count { it == MemorySaveResult.Refused(MemoryRefusal.QUOTA_EXCEEDED) })
        assertEquals(limit, db.count("selfdev_memory_records", "kind = 'PLAN_RATIONALE' AND status = 'ACTIVE'"))
    }

    @Test
    fun `concurrent saves of one text write one record, and the others return its id`() = TestDb().use { db ->
        val repo = SelfDevMemoryRepository(db.database, enabled = true)
        val results = together(10) { repo.save(note("同じ文章")) }
        val written = results.filterIsInstance<MemorySaveResult.Written>()
        assertEquals(1, written.size)
        assertEquals(9, results.count { it is MemorySaveResult.Duplicate })
        results.filterIsInstance<MemorySaveResult.Duplicate>().forEach { assertEquals(written.single().recordId, it.recordId) }
        assertEquals(1, db.count("selfdev_memory_records"))
    }

    @Test
    fun `a resend of a stored text at the limit returns its id and adds no record`() = TestDb().use { db ->
        val repo = SelfDevMemoryRepository(db.database, enabled = true)
        val goalId = goal(db, "k-resend-at-limit")
        val limit = SelfDevMemoryValidator.MAX_TEXTS_PER_GOAL
        val first = (1..limit).map { assertIs<MemorySaveResult.Written>(repo.save(note("メモ $it", goalId))) }
        // The goal is full: a new text is refused, but a resend of a stored one is the duplicate (V11 before V12).
        assertEquals(MemorySaveResult.Refused(MemoryRefusal.QUOTA_EXCEEDED), repo.save(note("新しい文", goalId)))
        assertEquals(MemorySaveResult.Duplicate(first.last().recordId), repo.save(note("メモ $limit", goalId)))
        assertEquals(limit, db.count("selfdev_memory_records", "goal_id = '$goalId' AND status = 'ACTIVE'"))
    }
}
