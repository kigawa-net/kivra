package net.kigawa.kivra.fusha.poller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskProviderException
import net.kigawa.kivra.core.TaskSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Degradation under poll failures (issue #28 completion conditions): a failing poll must never stop the poller,
 * must not emit events, must not authorize admission, and must resume on the next successful poll.
 */
class TaskPollerResilienceTest {
    /** Wraps [ScriptedProvider] so a test can throw any Throwable from `list`, not only TaskProviderException. */
    private class FaultyProvider(private val inner: ScriptedProvider) : TaskProvider by inner {
        var listThrowable: Throwable? = null
        var listCalls = 0

        override suspend fun list(filter: TaskFilter): List<TaskSnapshot> {
            listCalls++
            listThrowable?.let { throw it }
            return inner.list(filter)
        }
    }

    private val secret = "sk-test-secret-do-not-log"

    @Test
    fun `an unexpected provider exception degrades the poll instead of escaping pollOnce`() = runTest {
        val inner = ScriptedProvider().apply { put(task(1)) }
        val provider = FaultyProvider(inner)
        val s = RecordingSink()
        val clock = MutableClock(Instant.parse("2026-10-12T00:00:00Z"))
        val poller = TaskPoller(provider, s, config(), clock)

        provider.listThrowable = IllegalStateException("token refresh failed: $secret")
        val result = poller.pollOnce()

        assertIs<PollResult.Failed>(result)
        assertEquals(PollFailureCause.PROVIDER, result.cause)
        assertTrue(s.all.isEmpty())
        assertFalse(poller.admissionAllowed())
        val degraded = assertIs<PollerHealth.Degraded>(poller.health.value)
        assertFalse(degraded.message.contains(secret), "the provider exception message must not reach health output")

        provider.listThrowable = null
        poller.pollOnce()
        assertTrue(poller.admissionAllowed())
        assertEquals(listOf(TaskId("fusha:dev:1")), s.all.map { it.taskId })
    }

    @Test
    fun `a cancellation that is not ours (for example an inner timeout) is a provider failure`() = runTest {
        val provider = FaultyProvider(ScriptedProvider().apply { put(task(1)) })
        val s = RecordingSink()
        val poller = TaskPoller(provider, s, config(), MutableClock(Instant.parse("2026-10-12T00:00:00Z")))

        provider.listThrowable = CancellationException("request timed out")
        val result = poller.pollOnce()

        assertIs<PollResult.Failed>(result)
        assertEquals(PollFailureCause.PROVIDER, result.cause)
        assertTrue(s.all.isEmpty())
        assertIs<PollerHealth.Degraded>(poller.health.value)
    }

    @Test
    fun `a validation failure degrades, and the next valid poll restores admission and discovery`() = runTest {
        val p = ScriptedProvider().apply { put(task(1)) }
        val s = RecordingSink()
        val poller = TaskPoller(p, s, config(), MutableClock(Instant.parse("2026-10-12T00:00:00Z")))

        p.listOverride = { listOf(task(1), task(1, title = "dup")) }
        assertIs<PollResult.Failed>(poller.pollOnce())
        assertFalse(poller.admissionAllowed())
        assertTrue(s.all.isEmpty())

        p.listOverride = null
        poller.pollOnce()
        assertTrue(poller.admissionAllowed())
        assertIs<TaskDiscovered>(s.all.single())
    }

    @Test
    fun `the background loop keeps polling through failures, one call per interval, and resumes on recovery`() = runTest {
        val inner = ScriptedProvider().apply { put(task(1)) }
        val provider = FaultyProvider(inner)
        val s = RecordingSink()
        val clock = MutableClock(Instant.parse("2026-10-12T00:00:00Z"))
        // Threshold is high so the circuit breaker does not stop the calls this test counts.
        val poller = TaskPoller(provider, s, config(threshold = 100), clock)

        provider.listThrowable = TaskProviderException(TaskErrorKind.TRANSIENT, "503", 503)
        val job = backgroundScope.launch { poller.run() }
        runCurrent() // poll #1 at t=0 fails
        repeat(2) {
            advanceTimeBy(30_000)
            clock.now = clock.now.plusSeconds(30)
            runCurrent()
        }
        assertTrue(job.isActive, "a failing poll must not stop the loop")
        assertEquals(3, provider.listCalls, "exactly one provider call per interval, no spinning")
        assertEquals(3, assertIs<PollerHealth.Degraded>(poller.health.value).consecutiveFailures)
        assertTrue(s.all.isEmpty())

        provider.listThrowable = null
        advanceTimeBy(30_000)
        clock.now = clock.now.plusSeconds(30)
        runCurrent()
        assertTrue(job.isActive)
        assertIs<PollerHealth.Healthy>(poller.health.value)
        assertEquals(listOf(TaskId("fusha:dev:1")), s.all.map { it.taskId })
        assertTrue(s.all.all { it is TaskDiscovered })
    }

    @Test
    fun `a task that returns to an earlier status with a newer updatedAt is a new change with a new dedup key`() = runTest {
        val p = ScriptedProvider().apply { put(task(1, fields = mapOf("status" to "\"todo\""))) }
        val s = RecordingSink()
        val poller = TaskPoller(p, s, config(), MutableClock(Instant.parse("2026-10-12T00:00:00Z")))
        poller.pollOnce()

        p.put(task(1, updatedAt = "2026-10-12T00:01:00.000Z", fields = mapOf("status" to "\"done\"")))
        poller.pollOnce()
        p.put(task(1, updatedAt = "2026-10-12T00:02:00.000Z", fields = mapOf("status" to "\"todo\"")))
        poller.pollOnce()

        val changes = s.all.drop(1)
        assertEquals(2, changes.size, "the reversal is reported, not suppressed as a duplicate of the first state")
        assertTrue(changes.all { it is TaskChanged && it.reason == ChangeReason.UPDATED_AT_ADVANCED })
        assertEquals(s.all.map { it.dedupKey }.distinct().size, s.all.size)
    }

    @Test
    fun `failure messages carry only the error kind, never the provider detail`() = runTest {
        val p = ScriptedProvider()
        val s = RecordingSink()
        val poller = TaskPoller(p, s, config(), MutableClock(Instant.parse("2026-10-12T00:00:00Z")))

        p.listError = TaskProviderException(TaskErrorKind.UNAUTHORIZED, "Authorization: Bearer $secret", 401)
        poller.pollOnce()
        val degraded = assertIs<PollerHealth.Degraded>(poller.health.value)
        assertEquals(TaskErrorKind.UNAUTHORIZED, degraded.providerKind)
        assertFalse(degraded.message.contains(secret))
        assertFalse(degraded.toString().contains(secret))
    }
}
