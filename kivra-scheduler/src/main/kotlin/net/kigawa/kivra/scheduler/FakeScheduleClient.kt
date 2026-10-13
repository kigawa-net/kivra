package net.kigawa.kivra.scheduler

import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.JobRef
import net.kigawa.kivra.core.JobSpec
import net.kigawa.kivra.core.ScheduleExecutionRef
import net.kigawa.kivra.core.ScheduleExecutionStatus
import net.kigawa.kivra.core.ScheduleWriterLease

/**
 * In-memory SchedulePort (+ [ScheduleClient]) for Orchestrator tests: no network. Jobs are keyed by their
 * deterministic name; `triggerNow` is idempotent per key. [failNext] injects an UNKNOWN outcome to test callers'
 * fail-closed handling; [down] simulates ai-scheduler being stopped (every call throws [ScheduleNetworkException]).
 */
class FakeScheduleClient(private val clock: Clock = Clock.systemUTC()) : ScheduleClient {
    private val lock = Mutex()
    private val jobs = linkedMapOf<String, ScheduleJob>()
    private val executions = linkedMapOf<String, ScheduleExecution>()
    private val triggerKeys = hashMapOf<String, ScheduleExecutionRef>()

    @Volatile var down = false
    private var unknownOps = 0

    /** The next [count] write calls throw [ScheduleUnknownException] (the effect is still applied, like a lost response). */
    fun failNext(count: Int = 1) { unknownOps = count }

    private suspend fun <T> guarded(op: String, write: Boolean = false, block: () -> T): T = lock.withLock {
        if (down) throw ScheduleNetworkException("fake ai-scheduler is down")
        val result = block()
        if (write && unknownOps > 0) {
            unknownOps--
            throw ScheduleUnknownException(op, "injected")
        }
        result
    }

    override suspend fun upsertJob(spec: JobSpec, key: IdempotencyKey, writer: ScheduleWriterLease): JobRef = guarded("upsertJob", write = true) {
        require(JobNaming.isValid(spec.name)) { "job name must follow kivra:fusha:{env}:{id}" }
        require(spec.cron == null || spec.runAt == null) { "cron and runAt are mutually exclusive" }
        val existing = jobs.values.firstOrNull { it.name == spec.name }
        if (existing != null) {
            if (existing.cron != spec.cron || existing.runAt != spec.runAt) {
                throw ScheduleConflictException("trigger change is not supported for existing job")
            }
            existing.ref
        } else {
            val ref = JobRef(UUID.randomUUID().toString())
            jobs[ref.id] = ScheduleJob(ref, spec.name, true, spec.cron, spec.runAt, null, spec.runAt)
            ref
        }
    }

    override suspend fun getJob(ref: JobRef): ScheduleJob = guarded("getJob") {
        jobs[ref.id] ?: throw ScheduleNotFoundException("job not found")
    }

    private fun setEnabled(ref: JobRef, enabled: Boolean) {
        val j = jobs[ref.id] ?: throw ScheduleNotFoundException("job not found")
        jobs[ref.id] = j.copy(enabled = enabled)
    }

    override suspend fun pauseJob(ref: JobRef) = guarded("pauseJob", write = true) { setEnabled(ref, false) }
    override suspend fun resumeJob(ref: JobRef) = guarded("resumeJob", write = true) { setEnabled(ref, true) }
    override suspend fun deleteJob(ref: JobRef) = guarded("deleteJob", write = true) { setEnabled(ref, false) }

    override suspend fun triggerNow(ref: JobRef, key: IdempotencyKey): ScheduleExecutionRef =
        guarded("triggerNow", write = true) {
            if (jobs[ref.id] == null) throw ScheduleNotFoundException("job not found")
            triggerKeys.getOrPut("${ref.id}|${key.value}") {
                val now = clock.instant()
                val e = ScheduleExecutionRef(UUID.randomUUID().toString())
                executions[e.id] = ScheduleExecution(e, ref, ScheduleExecutionState.SCHEDULED, "SCHEDULED", now, null, null)
                jobs[ref.id] = jobs.getValue(ref.id).copy(lastRunAt = now)
                e
            }
        }

    override suspend fun getExecution(ref: ScheduleExecutionRef): ScheduleExecutionStatus =
        getExecutionDetail(ref).let { ScheduleExecutionStatus(it.ref, it.rawStatus) }

    override suspend fun getExecutionDetail(ref: ScheduleExecutionRef): ScheduleExecution = guarded("getExecution") {
        executions[ref.id] ?: throw ScheduleNotFoundException("execution not found")
    }

    override suspend fun listExecutions(job: JobRef, limit: Int): List<ScheduleExecution> = guarded("listExecutions") {
        executions.values.filter { it.job == job }.reversed().take(limit)
    }

    override suspend fun cancel(ref: ScheduleExecutionRef) = guarded("cancel", write = true) {
        val e = executions[ref.id] ?: throw ScheduleNotFoundException("execution not found")
        if (e.state.isTerminal) throw ScheduleConflictException("execution already terminal")
        executions[ref.id] = e.copy(state = ScheduleExecutionState.CANCELLED, rawStatus = "CANCELLED", completedAt = clock.instant())
    }

    override suspend fun retry(ref: ScheduleExecutionRef): ScheduleExecution = guarded("retry", write = true) {
        val e = executions[ref.id] ?: throw ScheduleNotFoundException("execution not found")
        if (e.state !in setOf(ScheduleExecutionState.FAILED, ScheduleExecutionState.TIMED_OUT, ScheduleExecutionState.CANCELLED)) {
            throw ScheduleConflictException("execution cannot be retried")
        }
        e.copy(state = ScheduleExecutionState.SCHEDULED, rawStatus = "SCHEDULED", completedAt = null).also { executions[ref.id] = it }
    }

    /** Test helper: move an execution to [state] (e.g. SUCCEEDED). */
    suspend fun completeExecution(ref: ScheduleExecutionRef, state: ScheduleExecutionState, at: Instant = clock.instant()) = lock.withLock {
        val e = executions.getValue(ref.id)
        executions[ref.id] = e.copy(state = state, rawStatus = state.name, completedAt = if (state.isTerminal) at else null)
    }

    suspend fun jobCount(): Int = lock.withLock { jobs.size }
}
