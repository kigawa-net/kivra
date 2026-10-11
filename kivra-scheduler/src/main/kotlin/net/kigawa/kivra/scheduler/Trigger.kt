package net.kigawa.kivra.scheduler

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import net.kigawa.kivra.core.JobRef

/**
 * Start contract for periodic / deadline wake-ups. The Orchestrator (#36) implements [ScheduleTriggerSink]; this
 * module does not wire to it. ai-scheduler delivers at-least-once (callback retry or poll re-observation), so the
 * handler dedupes on `(jobId, scheduledAt)`.
 */
enum class ScheduleTriggerKind { TICK, DEADLINE_RESUME, TIMED_TASK }

data class ScheduleTrigger(val job: JobRef, val scheduledAt: Instant, val kind: ScheduleTriggerKind = ScheduleTriggerKind.TICK) {
    /** Idempotency key `(jobId, scheduledAt)`. */
    val dedupKey: String get() = "${job.id}@$scheduledAt"
}

/** Receiver of a de-duplicated wake-up. Must itself be idempotent (a crash after [handle] re-delivers). */
fun interface ScheduleTriggerSink {
    suspend fun onTrigger(trigger: ScheduleTrigger)
}

/** Atomic claim store for `(jobId, scheduledAt)`. A durable implementation (DB unique key) is added with the Orchestrator wiring. */
interface TriggerDedupStore {
    /** True only for the first caller of [key]. */
    fun claim(key: String): Boolean
    fun release(key: String)
}

class InMemoryTriggerDedupStore : TriggerDedupStore {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    override fun claim(key: String) = seen.add(key)
    override fun release(key: String) { seen.remove(key) }
}

enum class TriggerOutcome { ACCEPTED, DUPLICATE }

/** The body of the Kivra "start endpoint" (HTTP exposure is wired later). */
class ScheduleTriggerHandler(private val dedup: TriggerDedupStore, private val sink: ScheduleTriggerSink) {
    suspend fun handle(trigger: ScheduleTrigger): TriggerOutcome {
        if (!dedup.claim(trigger.dedupKey)) return TriggerOutcome.DUPLICATE
        try {
            sink.onTrigger(trigger)
        } catch (e: Throwable) {
            // Not started: free the claim so the next delivery (at-least-once) can retry.
            dedup.release(trigger.dedupKey)
            throw e
        }
        return TriggerOutcome.ACCEPTED
    }
}

/**
 * Polling fallback (design #1 section 4.4) for when ai-scheduler cannot call back: observes each job's `lastRunAt`
 * and feeds it to the handler. Re-observing the same value is harmless (deduped). Failure of one job (including
 * ai-scheduler being down) is reported in the result and never throws, so the caller's own loop continues.
 */
class SchedulePoller(private val client: ScheduleClient, private val handler: ScheduleTriggerHandler) {
    data class Result(val accepted: Int, val duplicates: Int, val failures: Map<JobRef, String>)

    suspend fun poll(jobs: Collection<JobRef>, kind: ScheduleTriggerKind = ScheduleTriggerKind.TICK): Result {
        var accepted = 0
        var duplicates = 0
        val failures = linkedMapOf<JobRef, String>()
        for (ref in jobs) {
            try {
                val at = client.getJob(ref).lastRunAt ?: continue
                when (handler.handle(ScheduleTrigger(ref, at, kind))) {
                    TriggerOutcome.ACCEPTED -> accepted++
                    TriggerOutcome.DUPLICATE -> duplicates++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures[ref] = e::class.simpleName ?: "error"
            }
        }
        return Result(accepted, duplicates, failures)
    }
}
