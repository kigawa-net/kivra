package net.kigawa.kivra.scheduler

import java.time.Instant
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobRef
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleExecutionRef
import net.kigawa.kivra.core.SchedulePort

/**
 * ai-scheduler is only the "wake-up" role (design #1 section 4.4, #3 section 11).
 *
 * **The Orchestrator must keep working while ai-scheduler is down.** Nothing in Kivra's main path may require this
 * port: the Orchestrator owns its own loop/timer and the Run source of truth is the Kivra DB. ai-scheduler only adds
 * extra wake-ups (periodic tick, approval-deadline resume, time-specified tasks). Every method here can fail with a
 * [ScheduleException]; callers must treat that as "no extra wake-up", never as a Run failure. An ai-scheduler
 * Execution status is never copied into a Run state; it is only evidence that a wake-up happened.
 */
interface ScheduleClient : SchedulePort {
    suspend fun getJob(ref: JobRef): ScheduleJob

    /** Resume a job paused by [pauseJob]. */
    suspend fun resumeJob(ref: JobRef)

    /**
     * Retire a job. ai-scheduler (Phase 1) has no DELETE endpoint, so the adapter disables the job (logical delete);
     * the name stays reserved and a later [upsertJob] with the same name finds it again.
     */
    suspend fun deleteJob(ref: JobRef)

    suspend fun getExecutionDetail(ref: ScheduleExecutionRef): ScheduleExecution

    /** Executions of a job, newest first. The real ai-scheduler has no such endpoint yet: see docs/ai-scheduler-requests.md. */
    suspend fun listExecutions(job: JobRef, limit: Int = 50): List<ScheduleExecution>

    suspend fun retry(ref: ScheduleExecutionRef): ScheduleExecution
}

/** Job as seen by Kivra. The prompt/model that ai-scheduler requires are never exposed (and never printed). */
data class ScheduleJob(
    val ref: JobRef,
    val name: String,
    val enabled: Boolean,
    val cron: String?,
    val runAt: Instant?,
    val lastRunAt: Instant?,
    val nextRunAt: Instant?,
)

/** ai-scheduler Execution statuses (design). Unknown wire values map to [UNRECOGNIZED] so a new status cannot be misread. */
enum class ScheduleExecutionState {
    SCHEDULED, QUEUED, WAITING_BATCH, SUBMITTED, RUNNING, WAITING, WAITING_APPROVAL,
    SUCCEEDED, FAILED, TIMED_OUT, EXPIRED, CANCELLED, UNRECOGNIZED;

    val isTerminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, TIMED_OUT, EXPIRED, CANCELLED)

    companion object {
        fun fromWire(value: String?): ScheduleExecutionState =
            entries.firstOrNull { it != UNRECOGNIZED && it.name == value } ?: UNRECOGNIZED
    }
}

/** Execution evidence only. `output` / `error` bodies are intentionally not carried (they may contain secrets). */
data class ScheduleExecution(
    val ref: ScheduleExecutionRef,
    val job: JobRef,
    val state: ScheduleExecutionState,
    val rawStatus: String,
    val scheduledAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

/**
 * Failures. Messages never contain response bodies, tokens or prompts. [ScheduleUnknownException] is the fail-closed
 * "may or may not have happened" result of a non-idempotent write: it must not be re-sent on a guess.
 */
sealed class ScheduleException(message: String) : RuntimeException(message)

class ScheduleNotFoundException(message: String) : ScheduleException(message)
class ScheduleConflictException(message: String) : ScheduleException(message)
class ScheduleAuthException(message: String) : ScheduleException(message)
class ScheduleRejectedException(val status: Int, message: String) : ScheduleException(message)
class ScheduleUnsupportedException(message: String) : ScheduleException(message)

/** Network failure / 5xx on a read that exhausted its retries (safe to retry later). */
class ScheduleNetworkException(message: String) : ScheduleException(message)

/** The response was about a different object than the one requested (design review lesson from PR #47). */
class ScheduleIdentityMismatchException(message: String) : ScheduleException(message)

/** A write whose outcome is unknown (timeout / transport error / 5xx). Reconcile by deterministic name/key. */
class ScheduleUnknownException(val operation: String, detail: String) :
    ScheduleException("outcome unknown for $operation: $detail")
