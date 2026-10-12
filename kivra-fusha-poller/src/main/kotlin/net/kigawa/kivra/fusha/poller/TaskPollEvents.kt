package net.kigawa.kivra.fusha.poller

import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskSnapshot
import java.time.Instant

/**
 * Internal events produced by [TaskPoller] (design #2 section 7.2). These are observations only.
 *
 * None of them confirms a deletion or a cancellation. In particular there is no "DISAPPEARED": a task that is absent
 * from the listing and returns null from GET is reported as [TaskPossiblyGone] (fusha-task answers 404 both for a
 * missing and for an invisible task). Consumers must be idempotent on [dedupKey] (at-least-once delivery).
 */
sealed interface TaskPollEvent {
    val taskId: TaskId

    /** `taskId|updatedAt|contentHash` (or `taskId|gone|lastRevision`): identical for re-delivery of the same observation. */
    val dedupKey: String
}

/** A task that became visible to the poll scope for the first time (since this poller started). */
data class TaskDiscovered(val task: TaskSnapshot, override val dedupKey: String) : TaskPollEvent {
    override val taskId: TaskId get() = task.id
}

/** A tracked task changed; [reason] says what was detected. [previousRevision] is the last observed `updatedAt`. */
data class TaskChanged(
    val task: TaskSnapshot,
    val reason: ChangeReason,
    val previousRevision: Instant?,
    override val dedupKey: String,
) : TaskPollEvent {
    override val taskId: TaskId get() = task.id
}

/**
 * The task was tracked but is no longer in the listing and GET returned null. Not a confirmed deletion: it may be
 * deleted, or out of the credential's visibility. Only reconciliation or human confirmation may act on it.
 */
data class TaskPossiblyGone(
    override val taskId: TaskId,
    val lastKnownRevision: Instant?,
    override val dedupKey: String,
) : TaskPollEvent

enum class ChangeReason {
    /** `updatedAt` moved forward. */
    UPDATED_AT_ADVANCED,
    /** `updatedAt` moved backwards (rollback, restore, or an inconsistent source). Treated as a change, never ignored. */
    UPDATED_AT_REGRESSED,
    /** Same `updatedAt` but different content hash (same-millisecond update). */
    SAME_REVISION_CONTENT_CHANGED,
    /** Still exists, but no longer matches the poll scope (for example the assignee was changed to someone else). */
    LEFT_SCOPE,
    /** Matches the poll scope again after having left it. */
    REENTERED_SCOPE,
    /** Content changed while outside the poll scope. */
    OUT_OF_SCOPE_CHANGED,
    /** Was [TaskPossiblyGone] and is visible again in the listing. */
    REAPPEARED,
    /** Was [TaskPossiblyGone] and GET finds it again while outside the listing. */
    REAPPEARED_OUT_OF_SCOPE,
    /** Full rescan: GET returned a different snapshot than the listing did. */
    RESCAN_MISMATCH,
}

/**
 * Destination for a poll's events. [publish] must either persist the whole batch or throw. When it throws, the poller
 * keeps its previous state and re-emits the same events on the next poll. Outbox wiring is not part of this module (it needs the TaskBinding decision in #26).
 */
fun interface TaskEventSink {
    suspend fun publish(events: List<TaskPollEvent>)
}
