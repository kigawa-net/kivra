package net.kigawa.kivra.fusha.poller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kigawa.kivra.core.TaskErrorKind
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskProviderException
import net.kigawa.kivra.core.TaskSnapshot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Health exposed to Admission (#26). Only [Healthy] permits new admissions (fail closed). */
sealed interface PollerHealth {
    /** No poll has succeeded yet. */
    data object Starting : PollerHealth

    data class Healthy(val lastSuccessAt: Instant) : PollerHealth

    data class Degraded(
        val cause: PollFailureCause,
        val providerKind: TaskErrorKind?,
        val message: String,
        val consecutiveFailures: Int,
        val since: Instant,
        /** Non-null while the circuit breaker is open; no provider calls are made before this instant. */
        val breakerOpenUntil: Instant?,
    ) : PollerHealth
}

enum class PollFailureCause {
    /** The TaskProvider call failed (auth, forbidden, transient, protocol). */
    PROVIDER,
    /** A returned task failed validation; the whole poll was rejected. */
    VALIDATION,
    /** The event sink failed; state was not advanced, so the same events are re-emitted later. */
    SINK,
}

sealed interface PollResult {
    data class Published(val events: List<TaskPollEvent>) : PollResult
    data class Failed(val cause: PollFailureCause, val providerKind: TaskErrorKind?, val message: String) : PollResult
    /** Circuit breaker is open; no provider call was made. */
    data object SkippedBreakerOpen : PollResult
}

/**
 * Read-only poller for fusha-task (issue #28, design #2 section 7). Each [pollOnce]:
 *  1. lists the scoped tasks (view / assignee) and validates them,
 *  2. GETs every tracked task that is no longer listed, to tell "left the scope" from "possibly gone",
 *  3. on every N-th attempt, GETs every listed task (full rescan) and compares it with the listing,
 *  4. emits events only when the whole poll succeeded, then commits the new state.
 *
 * Change detection never relies on `updatedAt` alone: a change is reported when `updatedAt` differs (forward or
 * backward) or when the content hash differs at the same `updatedAt`.
 *
 * Not thread-safe: call [pollOnce] from one coroutine (see [run]). State is in memory only. After a restart every
 * visible task is reported as [TaskDiscovered] again, which is why consumers must dedupe on [TaskPollEvent.dedupKey].
 */
class TaskPoller(
    private val provider: TaskProvider,
    private val sink: TaskEventSink,
    private val config: TaskPollerConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val guard = TaskSnapshotGuard(config.environment)

    private class Tracked(val snapshot: TaskSnapshot, val tracking: Tracking, val hash: String)

    private enum class Tracking { IN_SCOPE, OUT_OF_SCOPE, POSSIBLY_GONE }

    private class Plan(val events: List<TaskPollEvent>, val next: Map<TaskId, Tracked>)

    private val known = LinkedHashMap<TaskId, Tracked>()
    private var attempts = 0L
    private var consecutiveFailures = 0
    private var firstFailureAt: Instant? = null
    private var breakerOpenUntil: Instant? = null

    private val _health = MutableStateFlow<PollerHealth>(PollerHealth.Starting)
    val health: StateFlow<PollerHealth> = _health.asStateFlow()

    /**
     * Admission requires a RECENT successful poll, not merely the last Healthy state.
     * If the background loop is stopped or stalled, no new tasks may be admitted forever
     * based on an old snapshot. Two scheduled intervals plus jitter is the bounded age.
     */
    fun admissionAllowed(): Boolean {
        val healthy = _health.value as? PollerHealth.Healthy ?: return false
        val age = Duration.between(healthy.lastSuccessAt, clock.instant())
        val maxAge = config.interval.multipliedBy(2).plus(config.jitter)
        return !age.isNegative && age <= maxAge
    }

    suspend fun pollOnce(): PollResult {
        val now = clock.instant()
        breakerOpenUntil?.let { until ->
            if (now.isBefore(until)) return PollResult.SkippedBreakerOpen
        }
        attempts++
        val rescan = attempts % config.fullRescanEveryPolls.toLong() == 0L

        val plan = try {
            computePlan(rescan)
        } catch (e: TaskProviderException) {
            return failure(now, PollFailureCause.PROVIDER, e.kind, "task provider call failed (${e.kind})")
        } catch (e: TaskValidationException) {
            return failure(now, PollFailureCause.VALIDATION, null, e.message ?: "task validation failed")
        } catch (e: CancellationException) {
            // Only this coroutine's own cancellation may stop the loop. A CancellationException from an inner
            // timeout is a provider failure; otherwise run() would die and admission would silently go stale.
            currentCoroutineContext().ensureActive()
            return failure(now, PollFailureCause.PROVIDER, null, "task provider call was cancelled (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            // Any other provider error (e.g. a token refresh failure) is a degraded poll. Only the class name is
            // reported: the message may carry credentials or response detail.
            return failure(now, PollFailureCause.PROVIDER, null, "task provider call failed (${e.javaClass.simpleName})")
        }

        try {
            if (plan.events.isNotEmpty()) sink.publish(plan.events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(now, PollFailureCause.SINK, null, "event sink failed (${e.javaClass.simpleName})")
        }

        known.clear()
        known.putAll(plan.next)
        succeed(now)
        return PollResult.Published(plan.events)
    }

    /** Runs [pollOnce] forever with `interval + random(0..jitter)` between attempts. Stops on coroutine cancellation. */
    suspend fun run() {
        while (true) {
            pollOnce()
            val extra = if (config.jitter.isZero) 0L else Random.nextLong(0L, config.jitter.toMillis() + 1)
            delay(config.interval.toMillis() + extra)
        }
    }

    private suspend fun computePlan(rescan: Boolean): Plan {
        val listed = guard.validateList(provider.list(config.filter))
        val events = mutableListOf<TaskPollEvent>()
        val next = LinkedHashMap<TaskId, Tracked>()

        // 1. Listed tasks: discovery and change detection.
        for ((id, snapshot) in listed) {
            val hash = TaskContentHash.of(snapshot)
            next[id] = Tracked(snapshot, Tracking.IN_SCOPE, hash)
            inScopeEvent(known[id], snapshot, hash)?.let { events += it }
        }

        // 2. Tracked tasks that are no longer listed: check with GET; never confirm a deletion from null.
        for ((id, prev) in known) {
            if (id in listed) continue
            val got = provider.get(id)?.let { guard.validateGet(id, it) }
            if (got == null) {
                if (prev.tracking != Tracking.POSSIBLY_GONE) {
                    events += TaskPossiblyGone(id, prev.snapshot.updatedAt, "${id.value}|gone|${prev.snapshot.updatedAt.toRevisionText()}")
                }
                next[id] = Tracked(prev.snapshot, Tracking.POSSIBLY_GONE, prev.hash)
                continue
            }
            val hash = TaskContentHash.of(got)
            when (prev.tracking) {
                Tracking.IN_SCOPE -> events += changed(got, hash, ChangeReason.LEFT_SCOPE, prev)
                Tracking.POSSIBLY_GONE -> events += changed(got, hash, ChangeReason.REAPPEARED_OUT_OF_SCOPE, prev)
                Tracking.OUT_OF_SCOPE -> if (prev.hash != hash) events += changed(got, hash, ChangeReason.OUT_OF_SCOPE_CHANGED, prev)
            }
            next[id] = Tracked(got, Tracking.OUT_OF_SCOPE, hash)
        }

        // 3. Full rescan: the listing must agree with GET for every listed task.
        if (rescan) {
            for ((id, listedSnapshot) in listed) {
                val got = provider.get(id)?.let { guard.validateGet(id, it) }
                if (got == null) {
                    // The listing shows it but GET does not: treat as possibly gone, not as a deletion.
                    val prev = known[id]
                    if (prev?.tracking != Tracking.POSSIBLY_GONE) {
                        events += TaskPossiblyGone(id, listedSnapshot.updatedAt, "${id.value}|gone|${listedSnapshot.updatedAt.toRevisionText()}")
                    }
                    next[id] = Tracked(listedSnapshot, Tracking.POSSIBLY_GONE, next.getValue(id).hash)
                    continue
                }
                val hash = TaskContentHash.of(got)
                if (hash != next.getValue(id).hash) {
                    events += changed(got, hash, ChangeReason.RESCAN_MISMATCH, known[id] ?: Tracked(listedSnapshot, Tracking.IN_SCOPE, next.getValue(id).hash))
                    next[id] = Tracked(got, Tracking.IN_SCOPE, hash)
                }
            }
        }
        return Plan(events, next)
    }

    private fun inScopeEvent(prev: Tracked?, current: TaskSnapshot, hash: String): TaskPollEvent? {
        if (prev == null) return TaskDiscovered(current, dedupKey(current, hash))
        return when {
            prev.tracking == Tracking.OUT_OF_SCOPE -> changed(current, hash, ChangeReason.REENTERED_SCOPE, prev)
            prev.tracking == Tracking.POSSIBLY_GONE -> changed(current, hash, ChangeReason.REAPPEARED, prev)
            prev.snapshot.updatedAt.isAfter(current.updatedAt) -> changed(current, hash, ChangeReason.UPDATED_AT_REGRESSED, prev)
            prev.snapshot.updatedAt != current.updatedAt -> changed(current, hash, ChangeReason.UPDATED_AT_ADVANCED, prev)
            prev.hash != hash -> changed(current, hash, ChangeReason.SAME_REVISION_CONTENT_CHANGED, prev)
            else -> null
        }
    }

    private fun changed(task: TaskSnapshot, hash: String, reason: ChangeReason, prev: Tracked) =
        TaskChanged(task, reason, prev.snapshot.updatedAt, dedupKey(task, hash))

    private fun dedupKey(task: TaskSnapshot, hash: String): String =
        "${task.id.value}|${task.updatedAt.toRevisionText()}|$hash"

    private fun succeed(now: Instant) {
        consecutiveFailures = 0
        firstFailureAt = null
        breakerOpenUntil = null
        _health.value = PollerHealth.Healthy(now)
    }

    private fun failure(now: Instant, cause: PollFailureCause, kind: TaskErrorKind?, message: String): PollResult.Failed {
        consecutiveFailures++
        val since = firstFailureAt ?: now.also { firstFailureAt = it }
        val open = consecutiveFailures >= config.breakerFailureThreshold
        if (open) breakerOpenUntil = now.plus(config.breakerCooldown)
        _health.value = PollerHealth.Degraded(cause, kind, message, consecutiveFailures, since, if (open) breakerOpenUntil else null)
        return PollResult.Failed(cause, kind, message)
    }
}
