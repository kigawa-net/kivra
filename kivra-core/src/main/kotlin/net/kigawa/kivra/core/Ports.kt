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
// M1 (#27): read-only first. Write operations exist on the Port so callers can branch on
// capabilities, but the fusha-task adapter reports UNSUPPORTED until server-side CAS and
// authentication are confirmed (design #2 section 6.3, issue #27 boundary note).

/** Task reference. For fusha-task: `fusha:{env}:{id}` (design #2 section 5.1). */
@JvmInline value class TaskId(val value: String)

/** Operations a provider may support. Callers must check [TaskProvider.capabilities] before branching. */
enum class TaskCapability { GET, LIST, UPDATE, COMMENT, CREATE, DELETE }

/** Server-side filters. fusha-task only supports assignee and view (design #2 section 2.1). */
data class TaskFilter(val assigneeId: String? = null, val viewId: Long? = null)

/**
 * Point-in-time view of a task. [fields] holds each customField value as raw JSON text so core
 * stays free of a JSON library. [updatedAt] doubles as the revision (not a strict version).
 */
data class TaskSnapshot(
    val id: TaskId,
    val title: String,
    val description: String?,
    val assigneeId: String?,
    /** Raw due date text; the format is unconfirmed (design #2 section 5.1, needs confirmation). */
    val dueDate: String?,
    val fields: Map<String, String>,
    val nextTaskIds: List<TaskId>,
    val relatedTaskIds: List<TaskId>,
    val updatedAt: Instant,
    val fetchedAt: Instant,
)

data class TaskPatch(val fields: Map<String, String>)

/** Normalized change event (design #2 section 7.2). Produced by the Poller (separate issue). */
data class TaskEvent(val type: Type, val id: TaskId, val revision: Instant?, val dedupKey: String) {
    enum class Type { CREATED, UPDATED, DISAPPEARED }
}

/** Result of a write call. UNSUPPORTED lets callers branch instead of catching exceptions. */
sealed interface TaskWriteResult {
    data class Ok(val task: TaskSnapshot?) : TaskWriteResult
    data class Unsupported(val capability: TaskCapability, val reason: String) : TaskWriteResult
    data class Rejected(val reason: String, val httpStatus: Int?) : TaskWriteResult
}

enum class TaskErrorKind {
    /** 401: token missing/invalid/expired even after one refresh. */
    UNAUTHORIZED,
    /** 403: no permission. */
    FORBIDDEN,
    /** 400 and other non-retryable client errors. */
    REJECTED,
    /** Network error, timeout, 429, 5xx (after retries are exhausted). */
    TRANSIENT,
    /** Response could not be interpreted. */
    PROTOCOL,
}

class TaskProviderException(
    val kind: TaskErrorKind,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface TaskProvider {
    val capabilities: Set<TaskCapability>

    /**
     * Returns null when the task does not exist OR is not visible (NOT_FOUND_OR_NOT_VISIBLE; fusha-task hides the
     * difference). null is therefore NOT proof of deletion: callers must not auto Cancel/Delete or emit a confirmed
     * DISAPPEARED on it, only after reconciliation or human confirmation.
     * Implementations must fail closed (PROTOCOL) if the returned task id differs from the requested one.
     */
    suspend fun get(id: TaskId): TaskSnapshot?

    /** fusha-task has no paging: the full visible set is returned. */
    suspend fun list(filter: TaskFilter = TaskFilter()): List<TaskSnapshot>

    suspend fun update(id: TaskId, patch: TaskPatch, expectedUpdatedAt: Instant?): TaskWriteResult
    suspend fun comment(id: TaskId, body: String, key: IdempotencyKey): TaskWriteResult

    /**
     * Create a new task. Returns the created task snapshot on success.
     * [parentId] is optional and indicates the task that was decomposed into this one.
     */
    suspend fun create(
        title: String,
        description: String?,
        parentId: TaskId?,
        key: IdempotencyKey,
    ): TaskWriteResult
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

/**
 * Proof that the caller is the single schedule writer (the Orchestrator's leader lease, #36), required by
 * [SchedulePort.upsertJob]. Job creation is lookup-then-POST and ai-scheduler job names are not unique server-side, so
 * two concurrent writers can both create a job; the server-side check only detects that afterwards. Only the holder of
 * the writer lease may call upsertJob.
 *
 * NOT YET IMPLEMENTED (未対応): no leader-lease implementation produces this value yet. Callers must not fabricate it
 * outside tests.
 */
data class ScheduleWriterLease(val owner: String, val generation: Long) {
    init {
        require(owner.isNotBlank() && generation >= 0) { "invalid schedule writer lease" }
    }
}

interface SchedulePort {
    /**
     * Requires [writer] (single-writer guarantee, see [ScheduleWriterLease]). Duplicates created by a race are detected
     * after the POST and reported as a conflict; they are not removed automatically.
     */
    suspend fun upsertJob(spec: JobSpec, key: IdempotencyKey, writer: ScheduleWriterLease): JobRef
    suspend fun pauseJob(ref: JobRef)
    suspend fun triggerNow(ref: JobRef, key: IdempotencyKey): ScheduleExecutionRef
    suspend fun getExecution(ref: ScheduleExecutionRef): ScheduleExecutionStatus
    suspend fun cancel(ref: ScheduleExecutionRef)
}
