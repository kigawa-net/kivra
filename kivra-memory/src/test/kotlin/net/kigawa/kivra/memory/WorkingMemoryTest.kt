package net.kigawa.kivra.memory

import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

class WorkingMemoryTest {
    private val clock = MutableClock(T.at)
    private val wm = InMemoryWorkingMemory(clock)

    private fun handoff(id: String = "h1", ttl: Duration = Duration.ofHours(1), goal: String = "finish #37") = HandoffRecord(
        handoffId = id, fromExecutionId = "exec-1", toExecutionId = "exec-2", taskId = "task-1", createdAt = clock.instant(), ttl = ttl,
        goal = goal, doneSummary = "adapter done", memoryRefs = listOf(CitationId.of(MemoryId("m1"), 1)), artifactRefs = listOf(T.source()),
    )

    @Test
    fun `values are per execution`() = runTest {
        wm.put("e1", "goal", "ship M5")
        assertEquals("ship M5", wm.get("e1", "goal"))
        assertNull(wm.get("e2", "goal"))
    }

    @Test
    fun `finished executions expire after 24h and failed ones after 7d`() = runTest {
        wm.put("ok", "k", "v"); wm.put("ng", "k", "v")
        wm.finish("ok", failed = false); wm.finish("ng", failed = true)
        clock.advance(Duration.ofHours(23))
        assertEquals("v", wm.get("ok", "k"))
        clock.advance(Duration.ofHours(2))
        assertNull(wm.get("ok", "k"))
        assertEquals("v", wm.get("ng", "k"))
        clock.advance(Duration.ofDays(7))
        assertNull(wm.get("ng", "k"))
        assertEquals(2, wm.purgeExpired())
    }

    @Test
    fun `handoff carries references and expires`() = runTest {
        wm.putHandoff(handoff())
        val got = wm.getHandoff("h1", T.ctx(T.project, exec = "exec-2"))!!
        assertEquals(listOf(CitationId.of(MemoryId("m1"), 1)), got.memoryRefs)
        clock.advance(Duration.ofHours(2))
        assertNull(wm.getHandoff("h1", T.ctx(T.project, exec = "exec-2")))
    }

    @Test
    fun `handoff is refused to any execution other than its bound receiver`() = runTest {
        wm.putHandoff(handoff()) // to exec-2, from exec-1
        // another attempt in the same project (not the receiver) must not see goal/doneSummary/artifacts
        assertNull(wm.getHandoff("h1", T.ctx(T.project, exec = "exec-3")))
        // the sender is not the receiver either
        assertNull(wm.getHandoff("h1", T.ctx(T.project, exec = "exec-1")))
        assertNotNull(wm.getHandoff("h1", T.ctx(T.project, exec = "exec-2")))
    }

    @Test
    fun `resolveHandoff refuses a handoff object presented by another execution`() = runTest {
        val fake = FakeMemoryPort(clock)
        val id = fake.save(T.entry(), T.key("k"), T.ctx(T.project))
        val h = handoff().copy(memoryRefs = listOf(CitationId.of(id, 1)))
        // exec-3 holds the record object (e.g. leaked from logs or a shared store) but is not the receiver
        val other = fake.resolveHandoff(h, T.ctx(T.project, exec = "exec-3"), clock)
        assertEquals(0, other.entries.size)
        assertEquals(1, other.unauthorizedOrMissing)
    }

    private val otherTask get() = T.ctx(T.project, exec = "exec-2").copy(taskId = "task-2")

    @Test
    fun `getHandoff refuses another task for the same receiver execution`() = runTest {
        wm.putHandoff(handoff()) // task-1
        assertNull(wm.getHandoff("h1", otherTask))
        assertNotNull(wm.getHandoff("h1", T.ctx(T.project, exec = "exec-2")))
    }

    @Test
    fun `resolveHandoff refuses another task and an expired handoff`() = runTest {
        val fake = FakeMemoryPort(clock)
        val id = fake.save(T.entry(), T.key("k"), T.ctx(T.project))
        val h = handoff().copy(memoryRefs = listOf(CitationId.of(id, 1)))
        val wrongTask = fake.resolveHandoff(h, otherTask, clock)
        assertEquals(0, wrongTask.entries.size)
        assertEquals(1, wrongTask.unauthorizedOrMissing)
        assertEquals(1, fake.resolveHandoff(h, T.ctx(T.project, exec = "exec-2"), clock).entries.size)
        clock.advance(Duration.ofHours(2))
        val expired = fake.resolveHandoff(h, T.ctx(T.project, exec = "exec-2"), clock)
        assertEquals(0, expired.entries.size)
        assertEquals(1, expired.unauthorizedOrMissing)
    }

    @Test
    fun `handoff bundle is created only for the bound receiver, its task and before expiry`() = runTest {
        val fake = FakeMemoryPort(clock)
        val id = fake.save(T.entry(), T.key("k"), T.ctx(T.project))
        val h = handoff().copy(memoryRefs = listOf(CitationId.of(id, 1)))
        val receiver = T.ctx(T.project, exec = "exec-2")
        assertNull(fake.handoffBundle(h, receiver.copy(taskId = "task-2"), clock = clock)) // other task
        assertNull(fake.handoffBundle(h, T.ctx(T.project, exec = "exec-3"), clock = clock)) // other execution
        val bundle = assertNotNull(fake.handoffBundle(h, receiver, clock = clock))
        assertEquals(listOf(CitationId.of(id, 1)), bundle.sections.single().items.map { it.citationId })
        clock.advance(Duration.ofHours(2))
        assertNull(fake.handoffBundle(h, receiver, clock = clock)) // expired
    }

    @Test
    fun `secrets are rejected in values, handoffs and checkpoints`() = runTest {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals(MemoryErrorKind.REJECTED, assertFailsWith<MemoryException> { wm.put("e1", "k", "token=$token") }.kind)
        assertEquals(MemoryErrorKind.REJECTED, assertFailsWith<MemoryException> { wm.putHandoff(handoff(goal = "use $token")) }.kind)
        assertEquals(MemoryErrorKind.REJECTED, assertFailsWith<MemoryException> { wm.putHandoff(handoff().copy(checkpointRef = "-----BEGIN PRIVATE KEY-----")) }.kind)
        assertNull(wm.get("e1", "k"))
    }

    @Test
    fun `quotas are enforced`() = runTest {
        val small = InMemoryWorkingMemory(clock, maxValueBytes = 10, maxKeysPerExecution = 2, maxBytesPerExecution = 15)
        assertFailsWith<MemoryException> { small.put("e", "k", "x".repeat(11)) }
        small.put("e", "a", "12345678"); small.put("e", "b", "1234567")
        assertFailsWith<MemoryException> { small.put("e", "c", "1") } // key count
        assertFailsWith<MemoryException> { small.put("e", "a", "1234567890") } // bytes: 7 + 10 > 15
        small.put("e", "a", "1") // overwrite frees quota
    }

    @Test
    fun `handoff ttl is bounded`() {
        assertFailsWith<IllegalArgumentException> { handoff(ttl = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { handoff(ttl = Duration.ofDays(8)) }
    }

    @Test
    fun `receiver re-fetches handoff memory with its own access context`() = runTest {
        val fake = FakeMemoryPort(clock)
        val sender = T.ctx(T.project)
        val id = fake.save(T.entry(), T.key("k"), sender)
        val h = handoff().copy(memoryRefs = listOf(CitationId.of(id, 1), CitationId.of(MemoryId("gone"), 1)))
        val receiverAllowed = fake.resolveHandoff(h, T.ctx(T.project, exec = "exec-2"), clock)
        assertEquals(listOf(id), receiverAllowed.entries.map { it.id })
        assertEquals(1, receiverAllowed.unauthorizedOrMissing)
        // a receiver without access gets nothing: the sender's permissions are not inherited
        val receiverDenied = fake.resolveHandoff(h, T.ctx(T.otherTenant, exec = "exec-2"), clock)
        assertEquals(0, receiverDenied.entries.size)
        assertEquals(2, receiverDenied.unauthorizedOrMissing)
    }

    @Test
    fun `receiver drops a response for a different id`() = runTest {
        val wrong = object : MemoryProvider by FakeMemoryPort() {
            override suspend fun get(id: MemoryId, ctx: AccessContext): MemoryEntry? =
                FakeMemoryPort().also { it.save(T.entry(), T.key("k"), ctx) }.get(MemoryId("mem-1"), ctx)
        }
        val h = handoff().copy(memoryRefs = listOf(CitationId.of(MemoryId("not-mem-1"), 1)))
        assertEquals(1, wrong.resolveHandoff(h, T.ctx(T.project, exec = "exec-2"), clock).unauthorizedOrMissing)
    }
}
