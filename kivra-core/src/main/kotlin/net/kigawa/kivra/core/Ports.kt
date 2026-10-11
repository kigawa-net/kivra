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

// ---- ExecutionPort (exkes) ----
@JvmInline value class WorkspaceRef(val id: String)
@JvmInline value class RuntimeRef(val id: String)
@JvmInline value class ExecutionRef(val id: String)
@JvmInline value class LogCursor(val value: String)

data class WorkspaceSpec(val name: String, val labels: Map<String, String> = emptyMap())
data class RuntimeSpec(val labels: Map<String, String> = emptyMap())
data class ExecutionSpec(val type: String, val payloadJson: String)
data class WorkspaceStatus(val ref: WorkspaceRef, val status: String)
data class ExecutionStatus(val ref: ExecutionRef, val status: String)
data class LogPage(val lines: List<String>, val next: LogCursor?)
data class ResourceRef(val kind: String, val id: String)

interface ExecutionPort {
    suspend fun createWorkspace(spec: WorkspaceSpec, key: IdempotencyKey): WorkspaceRef
    suspend fun getWorkspace(ref: WorkspaceRef): WorkspaceStatus
    suspend fun createRuntime(ws: WorkspaceRef, spec: RuntimeSpec, key: IdempotencyKey): RuntimeRef
    suspend fun startExecution(rt: RuntimeRef, spec: ExecutionSpec, key: IdempotencyKey): ExecutionRef
    suspend fun getExecution(ref: ExecutionRef): ExecutionStatus
    suspend fun cancelExecution(ref: ExecutionRef)
    suspend fun readLogs(ref: ExecutionRef, cursor: LogCursor?): LogPage
    suspend fun deleteWorkspace(ref: WorkspaceRef)

    /** For reconciliation after an unknown result. */
    suspend fun findByLabel(labels: Map<String, String>): List<ResourceRef>
}

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
