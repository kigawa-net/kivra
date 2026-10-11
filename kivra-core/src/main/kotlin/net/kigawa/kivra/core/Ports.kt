package net.kigawa.kivra.core

import java.time.Instant

/*
 * Port interfaces (design #1 section 4). Definitions only: Adapters (exkes, fusha-task,
 * recl, ai-scheduler) are implemented in later milestones. Types are intentionally minimal
 * and expected to grow with those milestones.
 */

/** Deterministic idempotency key; the same logical operation must always produce the same key (design #11 section 4.3). */
@JvmInline
value class IdempotencyKey(val value: String)

// ---- ExecutionPort ----
// Lives in kivra-exkes (net.kigawa.kivra.exkes.ExecutionPort, issue #29): its results carry UNKNOWN / Unsupported
// and exkes cannot honour IdempotencyKeys, so the earlier placeholder here was removed in #30.

// ---- TaskProvider (fusha-task) ----
@JvmInline value class TaskId(val value: String)

data class TaskFilter(val assignee: String? = null, val labels: Set<String> = emptySet())
data class TaskSnapshot(val id: TaskId, val title: String, val description: String?, val updatedAt: Instant)
data class TaskPatch(val fields: Map<String, String>)

interface TaskProvider {
    suspend fun listRunnable(filter: TaskFilter): List<TaskSnapshot>
    suspend fun get(id: TaskId): TaskSnapshot
    suspend fun update(id: TaskId, patch: TaskPatch, expectedUpdatedAt: Instant?)
    suspend fun comment(id: TaskId, body: String)
}

// ---- MemoryPort (recl) ----
@JvmInline value class MemoryId(val value: String)

data class MemoryEntry(val body: String, val provenance: String, val sensitivity: String = "normal")
data class MemoryQuery(val text: String, val scope: String, val limit: Int = 10)
data class MemoryHit(val id: MemoryId, val entry: MemoryEntry, val score: Double)

interface MemoryPort {
    suspend fun write(entry: MemoryEntry, key: IdempotencyKey): MemoryId
    suspend fun search(q: MemoryQuery): List<MemoryHit>
    suspend fun get(id: MemoryId): MemoryEntry
    suspend fun supersede(id: MemoryId, by: MemoryEntry)
    suspend fun delete(id: MemoryId)
}

// ---- SchedulePort (ai-scheduler) ----
@JvmInline value class JobRef(val id: String)
@JvmInline value class ScheduleExecutionRef(val id: String)

data class JobSpec(val name: String, val cron: String?, val runAt: Instant?)
data class ScheduleExecutionStatus(val ref: ScheduleExecutionRef, val status: String)

interface SchedulePort {
    suspend fun upsertJob(spec: JobSpec, key: IdempotencyKey): JobRef
    suspend fun pauseJob(ref: JobRef)
    suspend fun triggerNow(ref: JobRef, key: IdempotencyKey): ScheduleExecutionRef
    suspend fun getExecution(ref: ScheduleExecutionRef): ScheduleExecutionStatus
    suspend fun cancel(ref: ScheduleExecutionRef)
}
