package net.kigawa.kivra.scheduler

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleWriterLease

/** ai-scheduler stopped / unreachable: the Kivra side must keep working and must not start or re-send anything blindly. */
class ScheduleOutageTest {
    private val cfg = AiSchedulerConfig(baseUrl = "http://ai-scheduler.internal:8080", allowInsecureHttp = true, devAllowInMemoryDedup = true)
    private val writer = ScheduleWriterLease("outage", 1)
    private val now = Instant.parse("2026-10-14T00:00:00Z")

    @Test fun `ai-scheduler down - poller reports failures without throwing, and recovers without a duplicate start`() = runTest {
        val server = FakeAiSchedulerServer(now.toString())
        val adapter = AiSchedulerAdapter(aiSchedulerHttpClient(server.engine(), cfg), cfg, backoff = {}, dedup = InMemoryTriggerDedupStore())
        val job = adapter.upsertJob(JobSpec(JobNaming.build("test", "tick"), "*/5 * * * *", null), IdempotencyKey("k"), writer)
        adapter.triggerNow(job, IdempotencyKey("t1")) // ai-scheduler up: the job now reports lastRunAt = now
        var starts = 0
        val handler = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = Clock.fixed(now, ZoneOffset.UTC)) { starts++ }
        val poller = SchedulePoller(adapter, handler)
        assertEquals(1, poller.poll(listOf(job)).accepted)

        server.down = true
        val r = poller.poll(listOf(job)) // must not throw: the caller's loop continues
        assertEquals(setOf(job), r.failures.keys)
        assertEquals(0, r.accepted)
        assertTrue(r.failures.values.none { it.contains("SECRET") || it.contains("refused") }, "failure detail must stay generic")

        // Kivra's own trigger path is unaffected by the outage
        assertEquals(TriggerOutcome.ACCEPTED, handler.handle(ScheduleTrigger(job, now.plusSeconds(60))))

        server.down = false
        assertEquals(1, poller.poll(listOf(job)).duplicates) // same lastRunAt re-observed: not started again
        assertEquals(2, starts)
    }

    @Test fun `ai-scheduler down during triggerNow - outcome is UNKNOWN and the POST is not resent`() = runTest {
        val server = FakeAiSchedulerServer(now.toString())
        val adapter = AiSchedulerAdapter(aiSchedulerHttpClient(server.engine(), cfg), cfg, backoff = {}, dedup = InMemoryTriggerDedupStore())
        val job = adapter.upsertJob(JobSpec(JobNaming.build("test", "tick"), "*/5 * * * *", null), IdempotencyKey("k"), writer)
        server.down = true
        assertFailsWith<ScheduleUnknownException> { adapter.triggerNow(job, IdempotencyKey("t2")) }
        server.down = false
        // the same key is held until reconciled, even though ai-scheduler is back
        assertFailsWith<ScheduleUnknownException> { adapter.triggerNow(job, IdempotencyKey("t2")) }
        assertEquals(0, server.executionPosts)
    }
}
