package net.kigawa.kivra.scheduler

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import net.kigawa.kivra.core.JobRef

/**
 * Start contract for periodic / deadline wake-ups. The Orchestrator (#36) implements [ScheduleTriggerSink]; this
 * module does not wire to it. ai-scheduler delivers at-least-once (callback retry or poll re-observation), so the
 * handler dedupes on `(jobId, scheduledAt)` through a [TriggerDedupStore].
 */
enum class ScheduleTriggerKind { TICK, DEADLINE_RESUME, TIMED_TASK }

data class ScheduleTrigger(val job: JobRef, val scheduledAt: Instant, val kind: ScheduleTriggerKind = ScheduleTriggerKind.TICK) {
    /** Idempotency key `(jobId, scheduledAt)`. */
    val dedupKey: String get() = "${job.id}@$scheduledAt"
}

/**
 * Receiver of a de-duplicated wake-up. Must itself be idempotent (a crash after [onTrigger] re-delivers the trigger's
 * claim in its persisted state, but a crash before the claim is completed can still lead to a second call).
 *
 * Failure semantics (the handler decides what happens to the claim from the exception type):
 * - Throw [TriggerNotStartedException] only when it is certain that no Run / Outbox / external side effect was
 *   committed. The claim is then released and the next delivery may start again.
 * - Any other throwable (including cancellation) means the side effect may have happened. The claim is kept as
 *   UNKNOWN and later deliveries are held (not re-started) until someone reconciles it.
 */
fun interface ScheduleTriggerSink {
    suspend fun onTrigger(trigger: ScheduleTrigger)
}

/** Thrown by a [ScheduleTriggerSink] to state that nothing was started, so the claim may be released. */
class TriggerNotStartedException(message: String) : Exception(message)

/**
 * Atomic, durable claim store for at-most-once starts, keyed by `(jobId, scheduledAt)` for triggers and by
 * `(jobId, idempotencyKey)` for `triggerNow`.
 *
 * CONTRACT (a production implementation must satisfy all of these):
 * - [tryClaim] is a single conditional write (e.g. INSERT with a unique key, or CAS). Check-then-insert is forbidden.
 * - Durable and shared: a claim survives process restart and is visible to every Worker. After a restart, a key that
 *   is DONE or UNKNOWN must never be started again by a redelivery, so a restart cannot cause a duplicate start.
 * - Startup (or lease takeover) converts every leftover IN_FLIGHT record to UNKNOWN, because its owner may have crashed
 *   after the side effect.
 * - Records are not evicted by count or size while redelivery is still possible. Eviction would re-open duplicates.
 *
 * [InMemoryTriggerDedupStore] is for development and tests only. It is lost on restart and is not a production store.
 */
interface TriggerDedupStore {
    /** Returns null when the caller now owns a new IN_FLIGHT claim for [key]; otherwise the existing, unchanged claim. */
    fun tryClaim(key: String): Claim?

    /** IN_FLIGHT -> DONE. [result] is an opaque, non-secret note (e.g. the execution id). */
    fun complete(key: String, result: String)

    /** IN_FLIGHT -> UNKNOWN: the side effect may have happened. Only reconciliation may move it on. */
    fun markUnknown(key: String)

    /** IN_FLIGHT -> removed. Only for failures known to have happened before any side effect. */
    fun release(key: String)
}

enum class ClaimState { IN_FLIGHT, DONE, UNKNOWN }

data class Claim(val state: ClaimState, val result: String? = null)

/**
 * DEV / TEST ONLY. Not durable, not shared between processes, and it keeps every key (no eviction).
 * Refused at startup by [requireDurableTriggerDedup] unless the caller explicitly allows it for dev/tests.
 */
class InMemoryTriggerDedupStore : TriggerDedupStore {
    private val claims = ConcurrentHashMap<String, Claim>()

    override fun tryClaim(key: String): Claim? = claims.putIfAbsent(key, Claim(ClaimState.IN_FLIGHT))

    override fun complete(key: String, result: String) {
        claims.computeIfPresent(key) { _, c -> if (c.state == ClaimState.IN_FLIGHT) Claim(ClaimState.DONE, result) else c }
    }

    override fun markUnknown(key: String) {
        claims.computeIfPresent(key) { _, c -> if (c.state == ClaimState.IN_FLIGHT) Claim(ClaimState.UNKNOWN) else c }
    }

    override fun release(key: String) {
        claims.computeIfPresent(key) { _, c -> if (c.state == ClaimState.IN_FLIGHT) null else c }
    }
}

/**
 * Fail-fast guard run at construction (i.e. at startup). A process must not start with [InMemoryTriggerDedupStore]
 * unless [allowInMemoryForDev] is set explicitly: its claims vanish on restart and would re-open duplicate starts.
 * **未対応:** a persistent [TriggerDedupStore] backed by the M0 `external_operations` unique key is not implemented yet.
 */
fun requireDurableTriggerDedup(store: TriggerDedupStore, allowInMemoryForDev: Boolean) {
    check(store !is InMemoryTriggerDedupStore || allowInMemoryForDev) {
        "a durable TriggerDedupStore is required; InMemoryTriggerDedupStore is dev/test only (persistent store: 未対応)"
    }
}

enum class TriggerOutcome {
    /** Started by this call. */
    ACCEPTED,

    /** Already started, or another delivery is starting it right now. */
    DUPLICATE,

    /** An earlier start may have happened and is UNKNOWN. Not started again; needs reconciliation. */
    HELD_UNKNOWN,
}

/**
 * The body of the Kivra "start endpoint" (HTTP exposure is wired later). Fails at construction when [dedup] is an
 * in-memory store and [allowInMemoryForDev] is not set (see [requireDurableTriggerDedup]).
 */
class ScheduleTriggerHandler(
    private val dedup: TriggerDedupStore,
    allowInMemoryForDev: Boolean = false,
    private val sink: ScheduleTriggerSink,
) {
    init {
        requireDurableTriggerDedup(dedup, allowInMemoryForDev)
    }

    suspend fun handle(trigger: ScheduleTrigger): TriggerOutcome {
        val key = trigger.dedupKey
        dedup.tryClaim(key)?.let { existing ->
            return if (existing.state == ClaimState.UNKNOWN) TriggerOutcome.HELD_UNKNOWN else TriggerOutcome.DUPLICATE
        }
        try {
            sink.onTrigger(trigger)
        } catch (e: TriggerNotStartedException) {
            dedup.release(key) // provably nothing started: a later delivery may retry
            throw e
        } catch (e: Throwable) {
            // Side effect may have been committed (e.g. exception after the DB commit, or cancellation). Never release.
            dedup.markUnknown(key)
            throw e
        }
        dedup.complete(key, "ACCEPTED")
        return TriggerOutcome.ACCEPTED
    }
}

/**
 * Polling fallback (design #1 section 4.4) for when ai-scheduler cannot call back: observes each job's `lastRunAt`
 * and feeds it to the handler. Re-observing the same value is harmless (deduped). Failure of one job (including
 * ai-scheduler being down) is reported in the result and never throws, so the caller's own loop continues.
 */
class SchedulePoller(private val client: ScheduleClient, private val handler: ScheduleTriggerHandler) {
    data class Result(
        val accepted: Int,
        val duplicates: Int,
        val failures: Map<JobRef, String>,
        val heldUnknown: Int = 0,
    )

    suspend fun poll(jobs: Collection<JobRef>, kind: ScheduleTriggerKind = ScheduleTriggerKind.TICK): Result {
        var accepted = 0
        var duplicates = 0
        var held = 0
        val failures = linkedMapOf<JobRef, String>()
        for (ref in jobs) {
            try {
                val at = client.getJob(ref).lastRunAt ?: continue
                when (handler.handle(ScheduleTrigger(ref, at, kind))) {
                    TriggerOutcome.ACCEPTED -> accepted++
                    TriggerOutcome.DUPLICATE -> duplicates++
                    TriggerOutcome.HELD_UNKNOWN -> held++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[ref] = e::class.simpleName ?: "error"
            }
        }
        return Result(accepted, duplicates, failures, held)
    }
}
