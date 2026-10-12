package net.kigawa.kivra.fusha.poller

import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskProviderException
import net.kigawa.kivra.core.TaskSnapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskPollerTest {
    private val clock = MutableClock(Instant.parse("2026-10-12T00:00:00Z"))

    private fun poller(p: ScriptedProvider, s: RecordingSink, c: TaskPollerConfig = config()) =
        TaskPoller(p, s, c, clock)

    @Test
    fun `healthy health state expires when background polling stops`() = runTest {
        val provider = ScriptedProvider().apply { put(task(1)) }
        val poller = poller(provider, RecordingSink())
        assertFalse(poller.admissionAllowed())
        poller.pollOnce()
        assertTrue(poller.admissionAllowed())

        // Two 30-second intervals plus 5-second jitter have elapsed without an observation.
        clock.now = clock.now.plusSeconds(66)
        assertFalse(poller.admissionAllowed(), "stale healthy snapshot must not authorize admission")

        poller.pollOnce()
        assertTrue(poller.admissionAllowed(), "fresh confirmed poll restores admission")
    }

    @Test
    fun `first poll discovers scoped tasks and an unchanged poll emits nothing`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)); put(task(2)) }
        val s = RecordingSink()
        val poller = poller(p, s)

        val first = poller.pollOnce()
        assertIs<PollResult.Published>(first)
        assertEquals(listOf(TaskId("fusha:dev:1"), TaskId("fusha:dev:2")), s.all.map { it.taskId })
        assertTrue(s.all.all { it is TaskDiscovered })

        val second = poller.pollOnce() as PollResult.Published
        assertTrue(second.events.isEmpty())
        assertEquals(2, s.all.size)
    }

    @Test
    fun `updatedAt advance is detected as a change with the previous revision`() = runTest {
        val p = ScriptedProvider().apply { put(task(1, fields = mapOf("status" to "\"todo\""))) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        p.put(task(1, updatedAt = "2026-10-12T00:01:00.000Z", fields = mapOf("status" to "\"done\"")))
        poller.pollOnce()

        val changed = s.all.last()
        assertIs<TaskChanged>(changed)
        assertEquals(ChangeReason.UPDATED_AT_ADVANCED, changed.reason)
        assertEquals(Instant.parse(T0), changed.previousRevision)
    }

    @Test
    fun `same-millisecond update is detected by content hash`() = runTest {
        val p = ScriptedProvider().apply { put(task(1, fields = mapOf("status" to "\"todo\""))) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        // Same updatedAt, different status: an updatedAt-only comparison would miss this.
        p.put(task(1, fields = mapOf("status" to "\"done\"")))
        poller.pollOnce()

        assertEquals(ChangeReason.SAME_REVISION_CONTENT_CHANGED, s.all.last().reasonOrNull())
        assertEquals(2, s.all.size)
    }

    @Test
    fun `updatedAt moving backwards is reported as a change, not ignored`() = runTest {
        val p = ScriptedProvider().apply { put(task(1, updatedAt = "2026-10-12T00:05:00.000Z")) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        p.put(task(1, updatedAt = "2026-10-12T00:01:00.000Z", title = "restored"))
        poller.pollOnce()

        assertEquals(ChangeReason.UPDATED_AT_REGRESSED, s.all.last().reasonOrNull())
    }

    @Test
    fun `assignee moved to someone else is LEFT_SCOPE, not a deletion, and later edits are tracked`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        p.put(task(1, assignee = "someone-else", updatedAt = "2026-10-12T00:02:00.000Z"))
        poller.pollOnce()
        assertEquals(ChangeReason.LEFT_SCOPE, s.all.last().reasonOrNull())

        // Nothing more while it stays out of scope and unchanged.
        val quiet = poller.pollOnce() as PollResult.Published
        assertTrue(quiet.events.isEmpty())

        p.put(task(1, assignee = "someone-else", updatedAt = "2026-10-12T00:03:00.000Z", title = "edited outside"))
        poller.pollOnce()
        assertEquals(ChangeReason.OUT_OF_SCOPE_CHANGED, s.all.last().reasonOrNull())
    }

    @Test
    fun `absent task with null GET is TaskPossiblyGone and is not reported as deleted`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        p.tasks.clear() // hidden or deleted: fusha-task answers the same way for both
        poller.pollOnce()
        val gone = s.all.last()
        assertIs<TaskPossiblyGone>(gone)
        assertEquals(TaskId("fusha:dev:1"), gone.taskId)

        // Not re-emitted on every poll while still absent.
        val again = poller.pollOnce() as PollResult.Published
        assertTrue(again.events.isEmpty())

        // Visible again: reported as REAPPEARED, not as a fresh discovery.
        p.put(task(1, updatedAt = "2026-10-12T00:04:00.000Z"))
        poller.pollOnce()
        assertEquals(ChangeReason.REAPPEARED, s.all.last().reasonOrNull())
    }

    @Test
    fun `task that still exists out of scope is LEFT_SCOPE even when the listing omits it`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        p.listOverride = { emptyList() } // listing no longer contains it, but GET still finds it
        p.getOverride = { id -> if (id == TaskId("fusha:dev:1")) task(1, assignee = "other") else null }
        poller.pollOnce()

        assertEquals(ChangeReason.LEFT_SCOPE, s.all.last().reasonOrNull())
        assertTrue(s.all.none { it is TaskPossiblyGone })
    }

    @Test
    fun `duplicate ids in the listing reject the whole poll and emit nothing`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()
        val before = s.all.size

        p.listOverride = { listOf(task(2), task(2, title = "dup")) }
        val result = poller.pollOnce()

        assertIs<PollResult.Failed>(result)
        assertEquals(PollFailureCause.VALIDATION, result.cause)
        assertEquals(before, s.all.size)
        assertIs<PollerHealth.Degraded>(poller.health.value)
    }

    @Test
    fun `invalid task ids are rejected (wrong environment, zero, leading zero, bad reference)`() = runTest {
        val cases = listOf(
            task(1, environment = "prod"),
            TaskSnapshot(TaskId("fusha:dev:0"), "zero", null, SCOPE_ASSIGNEE, null, emptyMap(), emptyList(), emptyList(), Instant.parse(T0), Instant.EPOCH),
            TaskSnapshot(TaskId("fusha:dev:01"), "leading zero", null, SCOPE_ASSIGNEE, null, emptyMap(), emptyList(), emptyList(), Instant.parse(T0), Instant.EPOCH),
            task(1, nextTaskIds = listOf(TaskId("fusha:prod:9"))),
            TaskSnapshot(TaskId("1"), "bare", null, SCOPE_ASSIGNEE, null, emptyMap(), emptyList(), emptyList(), Instant.parse(T0), Instant.EPOCH),
        )
        for (bad in cases) {
            val p = ScriptedProvider()
            p.listOverride = { listOf(bad) }
            val s = RecordingSink()
            val result = poller(p, s).pollOnce()
            assertIs<PollResult.Failed>(result, "expected rejection for ${bad.id.value}")
            assertEquals(PollFailureCause.VALIDATION, result.cause)
            assertTrue(s.all.isEmpty())
        }
    }

    @Test
    fun `GET returning a different task id fails closed`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        p.listOverride = { emptyList() }
        p.getOverride = { task(2, assignee = "other") } // wrong resource for id 1
        val result = poller.pollOnce()

        assertIs<PollResult.Failed>(result)
        assertEquals(PollFailureCause.VALIDATION, result.cause)
    }

    @Test
    fun `updatedAt is compared at millisecond precision`() = runTest {
        val p = ScriptedProvider().apply { put(task(1, updatedAt = "2026-10-12T00:00:00.123Z")) }
        val s = RecordingSink()
        val poller = poller(p, s)
        poller.pollOnce()

        // Same millisecond with sub-millisecond digits from another read path: not a change.
        p.put(task(1, updatedAt = "2026-10-12T00:00:00.123456Z"))
        val result = poller.pollOnce() as PollResult.Published
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `provider read failure degrades, emits nothing, and recovery resumes discovery`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        assertFalse(poller.admissionAllowed())
        assertIs<PollerHealth.Starting>(poller.health.value)

        p.listError = TaskProviderException(TaskErrorKind.UNAUTHORIZED, "token expired", 401)
        val failed = poller.pollOnce()
        assertIs<PollResult.Failed>(failed)
        assertEquals(PollFailureCause.PROVIDER, failed.cause)
        assertEquals(TaskErrorKind.UNAUTHORIZED, failed.providerKind)
        assertTrue(s.all.isEmpty())
        assertFalse(poller.admissionAllowed())
        assertIs<PollerHealth.Degraded>(poller.health.value)

        p.listError = null
        poller.pollOnce()
        assertTrue(poller.admissionAllowed())
        assertEquals(listOf(TaskId("fusha:dev:1")), s.all.map { it.taskId })
        assertTrue(s.all.all { it is TaskDiscovered })
    }

    @Test
    fun `circuit breaker stops provider calls after threshold and half-opens after cooldown`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s, config(threshold = 2, cooldown = Duration.ofSeconds(60)))
        p.listError = TaskProviderException(TaskErrorKind.TRANSIENT, "503", 503)

        poller.pollOnce()
        poller.pollOnce()
        val degraded = assertIs<PollerHealth.Degraded>(poller.health.value)
        assertNotNull(degraded.breakerOpenUntil)
        val callsWhenOpened = p.listCalls

        assertEquals(PollResult.SkippedBreakerOpen, poller.pollOnce())
        assertEquals(callsWhenOpened, p.listCalls, "no provider call while the circuit is open")

        clock.now = clock.now.plusSeconds(61)
        p.listError = null
        val trial = poller.pollOnce()
        assertIs<PollResult.Published>(trial)
        assertEquals(callsWhenOpened + 1, p.listCalls)
        assertTrue(poller.admissionAllowed())
        val healthy = assertIs<PollerHealth.Healthy>(poller.health.value)
        assertEquals(clock.now, healthy.lastSuccessAt)
    }

    @Test
    fun `sink failure keeps state so the same events are re-emitted, with identical dedup keys`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s)
        s.failNext = true

        val failed = poller.pollOnce()
        assertIs<PollResult.Failed>(failed)
        assertEquals(PollFailureCause.SINK, failed.cause)
        assertTrue(s.all.isEmpty())

        poller.pollOnce()
        val delivered = s.all
        assertEquals(1, delivered.size)
        assertIs<TaskDiscovered>(delivered.single())

        // A fresh poller (restart) reports the same observation with the same key, so consumers can dedupe.
        val restarted = RecordingSink()
        poller(p, restarted).pollOnce()
        assertEquals(delivered.single().dedupKey, restarted.all.single().dedupKey)
    }

    @Test
    fun `full rescan reports a listing that disagrees with GET`() = runTest {
        val p = ScriptedProvider().apply { put(task(1, title = "old")) }
        val s = RecordingSink()
        val poller = poller(p, s, config(fullRescanEveryPolls = 1))
        poller.pollOnce()

        // The listing keeps returning a stale snapshot while GET has the newer one.
        p.put(task(1, updatedAt = "2026-10-12T00:09:00.000Z", title = "new"))
        p.listOverride = { listOf(task(1, title = "old")) }
        poller.pollOnce()

        val mismatch = s.all.last()
        assertIs<TaskChanged>(mismatch)
        assertEquals(ChangeReason.RESCAN_MISMATCH, mismatch.reason)
        assertEquals("new", mismatch.task.title)
    }

    @Test
    fun `full rescan with null GET for a listed task is TaskPossiblyGone`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = poller(p, s, config(fullRescanEveryPolls = 1))
        poller.pollOnce()

        p.getOverride = { null }
        poller.pollOnce()
        assertIs<TaskPossiblyGone>(s.all.last())
    }

    @Test
    fun `scope must be restricted and interval must be positive`() {
        val ex1 = runCatching { TaskPollerConfig("dev", net.kigawa.kivra.core.TaskFilter()) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex1)
        val ex2 = runCatching {
            TaskPollerConfig("dev", net.kigawa.kivra.core.TaskFilter(viewId = 10), interval = Duration.ZERO)
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex2)
        // A view alone is a valid scope.
        TaskPollerConfig("dev", net.kigawa.kivra.core.TaskFilter(viewId = 10))
    }
}
