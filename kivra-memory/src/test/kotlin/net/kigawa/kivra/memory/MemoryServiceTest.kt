package net.kigawa.kivra.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.IdempotencyKey
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryServiceTest {
    private val clock = MutableClock(T.at)
    private val ctx = T.ctx(T.project)
    private val fake = FakeMemoryPort(clock)
    private val events = mutableListOf<MemoryAuditEvent>()
    private fun service(
        provider: MemoryProvider = fake, queue: WriteQueue = InMemoryWriteQueue(), threshold: Int = 3,
    ) = MemoryService(provider, queue, { events += it }, clock, breakerThreshold = threshold, breakerCooldown = Duration.ofSeconds(30))

    private val down get() = MemoryException(MemoryErrorKind.UNAVAILABLE, "down")
    private val q = MemoryQuery("jdk", listOf(T.project))

    @Test
    fun `healthy provider passes through`() = runTest {
        val s = service()
        val saved = assertIs<RememberOutcome.Saved>(s.remember(T.entry(), ctx))
        val r = s.recall(q, ctx)
        assertFalse(r.degraded)
        assertEquals(listOf(saved.id), r.entries.map { it.id })
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `reads degrade to empty with a memory_unavailable warning when recl is down`() = runTest {
        fake.failure = down
        val r = service().recall(q, ctx)
        assertTrue(r.degraded)
        assertTrue(r.entries.isEmpty())
        assertEquals(setOf(ContextWarning.MEMORY_UNAVAILABLE), r.warnings)
        assertTrue(service().pinned(listOf(T.project), ctx).degraded)
        assertEquals(MemoryAuditType.READ_DEGRADED, events.first().type)
    }

    @Test
    fun `even unexpected adapter bugs and permission errors degrade instead of stopping the core`() = runTest {
        fake.failure = MemoryException(MemoryErrorKind.FORBIDDEN, "denied")
        assertTrue(service().recall(q, ctx).degraded)
        val buggy = object : MemoryProvider by fake {
            override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> = error("boom")
        }
        assertTrue(service(buggy).recall(q, ctx).degraded)
    }

    /** A provider whose writes fail with an exception the adapter did not anticipate; it does not claim idempotency. */
    private fun unexpectedWriteFailure(e: Throwable) = object : MemoryProvider by fake {
        override val capabilities: Set<MemoryCapability> get() = emptySet()
        override suspend fun save(e2: NewMemoryEntry, key: IdempotencyKey, ctx: AccessContext): MemoryId = throw e
    }

    @Test
    fun `unexpected exceptions on writes are an ambiguous outcome and never crash the core`() = runTest {
        val outcome = service(unexpectedWriteFailure(IllegalStateException("boom"))).remember(T.entry(), ctx)
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.OUTCOME_UNKNOWN), outcome)
        val last = events.last()
        assertEquals(MemoryAuditType.WRITE_DROPPED_AMBIGUOUS, last.type)
        // audit carries ids and kinds only, never content
        assertFalse(last.detail.values.any { it.contains("Build kivra") || it.contains("boom") })
    }

    @Test
    fun `unexpected exceptions during flush drop the write as ambiguous without throwing`() = runTest {
        val queue = InMemoryWriteQueue()
        fake.failure = down
        service(fake, queue).remember(T.entry(), ctx)
        fake.failure = null
        val report = service(unexpectedWriteFailure(IllegalStateException("boom")), queue).flush()
        assertEquals(1, report.dropped)
        assertEquals(0, queue.size())
    }

    @Test
    fun `cancellation is never swallowed by the degradation layer`() = runTest {
        assertFailsWith<CancellationException> {
            service(unexpectedWriteFailure(CancellationException("cancelled"))).remember(T.entry(), ctx)
        }
    }

    @Test
    fun `slow reads time out into degraded mode`() = runTest {
        val never = CompletableDeferred<Unit>()
        val slow = object : MemoryProvider by fake {
            override suspend fun search(q: MemoryQuery, ctx: AccessContext): List<MemoryHit> { never.await(); return emptyList() }
        }
        assertTrue(service(slow).recall(q, ctx).degraded)
    }

    @Test
    fun `circuit opens after repeated failures and skips the provider`() = runTest {
        fake.failure = down
        val s = service(threshold = 2)
        s.recall(q, ctx); s.recall(q, ctx)
        val before = fake.calls["search"]
        assertTrue(s.recall(q, ctx).degraded)
        assertEquals(before, fake.calls["search"]) // no call while open
        fake.failure = null
        clock.advance(Duration.ofSeconds(31))
        assertFalse(s.recall(q, ctx).degraded)
    }

    @Test
    fun `failed writes are queued and re-sent with the same key`() = runTest {
        fake.failure = down
        val queue = InMemoryWriteQueue()
        val s = service(queue = queue, threshold = 100)
        assertEquals(RememberOutcome.Queued, s.remember(T.entry(), ctx))
        assertEquals(RememberOutcome.Queued, s.remember(T.entry(), ctx)) // same key: no duplicate queue entry
        assertEquals(1, queue.size())
        assertEquals(FlushReport(0, 1, 0), s.flush())
        fake.failure = null
        assertEquals(FlushReport(1, 0, 0), s.flush())
        assertEquals(1, fake.size())
        // a duplicate delivery is absorbed by the idempotent key
        assertIs<RememberOutcome.Saved>(s.remember(T.entry(), ctx))
        assertEquals(1, fake.size())
    }

    @Test
    fun `permanent refusals are reported not queued, and the core is not stopped`() = runTest {
        val s = service()
        val outside = T.entry(scope = ScopeRef.project("t9", "x"))
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.FORBIDDEN), s.remember(outside, ctx))
        assertEquals(MemoryAuditType.WRITE_DROPPED_PERMANENT, events.last().type)
    }

    @Test
    fun `queue is bounded and overflow is dropped with an audit event`() = runTest {
        fake.failure = down
        val s = service(queue = InMemoryWriteQueue(maxSize = 1), threshold = 100)
        assertEquals(RememberOutcome.Queued, s.remember(T.entry(title = "a"), ctx))
        assertEquals(RememberOutcome.Dropped, s.remember(T.entry(title = "b", summary = "other"), ctx))
        assertEquals(MemoryAuditType.WRITE_DROPPED_OVERFLOW, events.last().type)
    }

    @Test
    fun `queued writes expire`() = runTest {
        fake.failure = down
        val queue = InMemoryWriteQueue()
        val s = service(queue = queue, threshold = 100)
        s.remember(T.entry(), ctx)
        clock.advance(Duration.ofHours(25))
        assertEquals(FlushReport(0, 0, 1), s.flush())
        assertEquals(MemoryAuditType.WRITE_DROPPED_EXPIRED, events.last().type)
    }

    @Test
    fun `ambiguous outcome without idempotent save is not re-sent`() = runTest {
        val noIdem = object : MemoryProvider by fake {
            override val capabilities: Set<MemoryCapability> = emptySet()
            override suspend fun save(e: NewMemoryEntry, key: net.kigawa.kivra.core.IdempotencyKey, ctx: AccessContext): MemoryId =
                throw MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "timeout after send")
        }
        val queue = InMemoryWriteQueue()
        val s = service(noIdem, queue)
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.OUTCOME_UNKNOWN), s.remember(T.entry(), ctx))
        assertEquals(0, queue.size())
        assertEquals(MemoryAuditType.WRITE_DROPPED_AMBIGUOUS, events.last().type)
    }

    @Test
    fun `ambiguous outcome with idempotent save is queued and re-sent`() = runTest {
        val flaky = object : MemoryProvider by fake {
            var first = true
            override suspend fun save(e: NewMemoryEntry, key: net.kigawa.kivra.core.IdempotencyKey, ctx: AccessContext): MemoryId {
                if (first) { first = false; throw MemoryException(MemoryErrorKind.OUTCOME_UNKNOWN, "timeout after send") }
                return fake.save(e, key, ctx)
            }
        }
        val s = service(flaky, threshold = 100)
        assertEquals(RememberOutcome.Queued, s.remember(T.entry(), ctx))
        assertEquals(1, s.flush().flushed)
    }

    @Test
    fun `secret guard rejects by default and can mask`() = runTest {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val guarded = FilteringMemoryProvider(fake, audit = { events += it })
        val ex = kotlin.test.assertFailsWith<MemoryException> { guarded.save(T.entry(summary = "deploy using $token"), T.key("k"), ctx) }
        assertEquals(MemoryErrorKind.REJECTED, ex.kind)
        assertFalse(ex.message!!.contains(token))
        assertEquals(0, fake.size())
        val ev = events.single { it.type == MemoryAuditType.SECRET_DETECTED }
        assertFalse(ev.detail.values.any { it.contains(token) })

        val masking = FilteringMemoryProvider(fake, FilteringMemoryProvider.Mode.MASK)
        val id = masking.save(T.entry(summary = "deploy using $token", body = "TOKEN=$token"), T.key("k2"), ctx)
        val stored = fake.rawEntry(id)!!
        assertFalse(stored.summary.contains(token))
        assertFalse((stored.body ?: "").contains(token))
        assertTrue(stored.summary.contains("[REDACTED:VCS_TOKEN]"))
    }

    @Test
    fun `service through the guard reports a secret write as rejected and never queues it`() = runTest {
        fake.failure = null
        val queue = InMemoryWriteQueue()
        val s = service(FilteringMemoryProvider(fake), queue)
        val out = s.remember(T.entry(summary = "pw: ghp_abcdefghijklmnopqrstuvwxyz0123456789"), ctx)
        assertEquals(RememberOutcome.Rejected(MemoryErrorKind.REJECTED), out)
        assertEquals(0, queue.size())
    }
}
