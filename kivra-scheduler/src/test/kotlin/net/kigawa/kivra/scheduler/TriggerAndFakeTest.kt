package net.kigawa.kivra.scheduler

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobRef
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleWriterLease

private val writer = ScheduleWriterLease("test-writer", 1)

class TriggerAndFakeTest {
    private val ref = JobRef("j1")
    private val at = Instant.parse("2026-10-12T00:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC) // scheduledAt is inside the window; no real clock

    @Test fun `same (jobId, scheduledAt) starts only once, even concurrently`() = runTest {
        var starts = 0
        val h = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) { starts++ }
        val results = (1..10).map { async { h.handle(ScheduleTrigger(ref, at)) } }.awaitAll()
        assertEquals(1, results.count { it == TriggerOutcome.ACCEPTED })
        assertEquals(1, starts)
        assertEquals(TriggerOutcome.ACCEPTED, h.handle(ScheduleTrigger(ref, at.plusSeconds(60))))
        assertEquals(TriggerOutcome.ACCEPTED, h.handle(ScheduleTrigger(JobRef("j2"), at)))
    }

    @Test fun `a start that is provably not started releases the claim so redelivery can retry`() = runTest {
        var fail = true
        var starts = 0
        val h = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) {
            if (fail) throw TriggerNotStartedException("nothing committed") else starts++
        }
        assertFailsWith<TriggerNotStartedException> { h.handle(ScheduleTrigger(ref, at)) }
        fail = false
        assertEquals(TriggerOutcome.ACCEPTED, h.handle(ScheduleTrigger(ref, at)))
        assertEquals(TriggerOutcome.DUPLICATE, h.handle(ScheduleTrigger(ref, at)))
        assertEquals(1, starts)
    }

    @Test fun `an exception after a possible side effect keeps the claim UNKNOWN and holds redelivery`() = runTest {
        var calls = 0
        val h = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) { calls++; error("commit response lost") }
        assertFailsWith<IllegalStateException> { h.handle(ScheduleTrigger(ref, at)) }
        assertEquals(TriggerOutcome.HELD_UNKNOWN, h.handle(ScheduleTrigger(ref, at)))
        assertEquals(TriggerOutcome.HELD_UNKNOWN, h.handle(ScheduleTrigger(ref, at)))
        assertEquals(1, calls, "a held UNKNOWN trigger must not start the sink again")
    }

    @Test fun `cancellation after a possible side effect is treated like any other unknown outcome`() = runTest {
        var calls = 0
        val h = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) { calls++; throw kotlinx.coroutines.CancellationException("worker moved") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { h.handle(ScheduleTrigger(ref, at)) }
        assertEquals(TriggerOutcome.HELD_UNKNOWN, h.handle(ScheduleTrigger(ref, at)))
        assertEquals(1, calls)
    }

    @Test fun `a restart that shares the durable store does not start a completed trigger again`() = runTest {
        val store = InMemoryTriggerDedupStore() // stands in for the durable table: the handler is recreated, the store is not
        var calls = 0
        assertEquals(TriggerOutcome.ACCEPTED, ScheduleTriggerHandler(store, true, clock = clock) { calls++ }.handle(ScheduleTrigger(ref, at)))
        assertEquals(TriggerOutcome.DUPLICATE, ScheduleTriggerHandler(store, true, clock = clock) { calls++ }.handle(ScheduleTrigger(ref, at)))
        assertEquals(1, calls)
    }

    @Test fun `concurrent claims for one key yield exactly one winner`() = runTest {
        val store = InMemoryTriggerDedupStore()
        val winners = (1..50).map { async { store.tryClaim("k") } }.awaitAll().count { it == null }
        assertEquals(1, winners)
    }

    @Test fun `terminal claims cannot be released or downgraded by stray calls`() {
        val store = InMemoryTriggerDedupStore()
        assertEquals(null, store.tryClaim("k"))
        store.complete("k", "e1")
        store.release("k")
        store.markUnknown("k")
        assertEquals(Claim(ClaimState.DONE, "e1"), store.tryClaim("k"))
    }

    @Test fun `poller turns lastRunAt into deduped triggers and survives ai-scheduler being down`() = runTest {
        val fake = FakeScheduleClient(clock)
        val job = fake.upsertJob(JobSpec("kivra:fusha:test:tick", "* * * * *", null), IdempotencyKey("k"), writer)
        fake.triggerNow(job, IdempotencyKey("t1"))
        var starts = 0
        val poller = SchedulePoller(fake, ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) { starts++ })
        assertEquals(1, poller.poll(listOf(job)).accepted)
        assertEquals(1, poller.poll(listOf(job)).duplicates)
        fake.down = true
        val r = poller.poll(listOf(job)) // must not throw: the Orchestrator keeps running without ai-scheduler
        assertEquals(setOf(job), r.failures.keys)
        assertEquals(1, starts)
    }

    @Test fun `poller reports held UNKNOWN triggers separately`() = runTest {
        val fake = FakeScheduleClient(clock)
        val job = fake.upsertJob(JobSpec("kivra:fusha:test:tick", "* * * * *", null), IdempotencyKey("k"), writer)
        fake.triggerNow(job, IdempotencyKey("t1"))
        val poller = SchedulePoller(fake, ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) { error("lost") })
        assertEquals(setOf(job), poller.poll(listOf(job)).failures.keys) // first sink call fails, claim is UNKNOWN
        val second = poller.poll(listOf(job))
        assertEquals(1, second.heldUnknown)
        assertEquals(0, second.accepted)
    }

    @Test fun `fake upsert is idempotent by name and triggerNow by key`() = runTest {
        val fake = FakeScheduleClient(clock)
        val s = JobSpec("kivra:fusha:test:tick", "* * * * *", null)
        assertEquals(fake.upsertJob(s, IdempotencyKey("a"), writer), fake.upsertJob(s, IdempotencyKey("b"), writer))
        assertEquals(1, fake.jobCount())
        val j = fake.upsertJob(s, IdempotencyKey("a"), writer)
        assertEquals(fake.triggerNow(j, IdempotencyKey("k")), fake.triggerNow(j, IdempotencyKey("k")))
        assertEquals(1, fake.listExecutions(j).size)
    }

    @Test fun `fake lifecycle pause cancel retry and unknown injection`() = runTest {
        val fake = FakeScheduleClient(clock)
        val j = fake.upsertJob(JobSpec("kivra:fusha:test:t2", null, null), IdempotencyKey("a"), writer)
        fake.pauseJob(j)
        assertEquals(false, fake.getJob(j).enabled)
        fake.resumeJob(j)
        val e = fake.triggerNow(j, IdempotencyKey("k"))
        fake.cancel(e)
        assertFailsWith<ScheduleConflictException> { fake.cancel(e) }
        assertEquals(ScheduleExecutionState.SCHEDULED, fake.retry(e).state)
        fake.failNext()
        assertFailsWith<ScheduleUnknownException> { fake.triggerNow(j, IdempotencyKey("k2")) }
        assertEquals(2, fake.listExecutions(j).size) // effect applied despite UNKNOWN: callers must reconcile
        fake.down = true
        assertFailsWith<ScheduleNetworkException> { fake.getJob(j) }
    }

    @Test fun `concurrent deliveries with a suspending sink start exactly once`() = runTest {
        var starts = 0
        val h = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = clock) {
            delay(10) // the sink suspends while the claim is IN_FLIGHT: other deliveries must not start it
            starts++
        }
        for (round in 1..3) {
            val t = ScheduleTrigger(JobRef("r$round"), at)
            val results = (1..10).map { async { h.handle(t) } }.awaitAll()
            assertEquals(1, results.count { it == TriggerOutcome.ACCEPTED }, "round $round")
            assertEquals(9, results.count { it == TriggerOutcome.DUPLICATE }, "round $round")
        }
        assertEquals(3, starts)
    }

    @Test fun `scheduledAt far in the future or long ago is rejected before any claim or start`() = runTest {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        var starts = 0
        val h = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = Clock.fixed(now, ZoneOffset.UTC)) { starts++ }
        assertEquals(TriggerOutcome.REJECTED_SCHEDULE_TIME, h.handle(ScheduleTrigger(ref, now.plusSeconds(3600))))
        assertEquals(TriggerOutcome.REJECTED_SCHEDULE_TIME, h.handle(ScheduleTrigger(ref, now.minus(Duration.ofDays(2)))))
        assertEquals(0, starts)
        // inside the window (5 min ahead / 24 h back) is accepted; the rejected values above left no claim behind
        assertEquals(TriggerOutcome.ACCEPTED, h.handle(ScheduleTrigger(ref, now.plusSeconds(120))))
        assertEquals(TriggerOutcome.ACCEPTED, h.handle(ScheduleTrigger(ref, now.minusSeconds(3600))))
        assertEquals(2, starts)
    }

    @Test fun `poller counts out-of-window triggers as rejected and does not start them`() = runTest {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        val fake = FakeScheduleClient(Clock.fixed(now, ZoneOffset.UTC))
        val job = fake.upsertJob(JobSpec("kivra:fusha:test:tick", "* * * * *", null), IdempotencyKey("k"), writer)
        fake.triggerNow(job, IdempotencyKey("t1")) // lastRunAt = now (fixed clock): inside the window
        var starts = 0
        val handler = ScheduleTriggerHandler(InMemoryTriggerDedupStore(), true, clock = Clock.fixed(now.plus(Duration.ofDays(3)), ZoneOffset.UTC)) { starts++ }
        val r = SchedulePoller(fake, handler).poll(listOf(job))
        assertEquals(1, r.rejected)
        assertEquals(0, starts)
    }
}
