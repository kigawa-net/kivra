package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.TaskTransition

/**
 * Stops the cycle in progress of [order] after its stop was approved (ADR 0015 section 4.5, V48). The cycle's root Task is the one in
 * `current_goal_id`; it moves to STOPPED through the operator's task stop, which also skips its planned tasks that have not started.
 * A Task that already ended is left alone.
 *
 * Returns null when the order has no cycle in progress, true when the Task was stopped, and false when the stop was refused (the Task
 * moved meanwhile, or it is in a state that cannot stop). The order itself is not changed here: the approval has already stopped it.
 */
internal fun stopCycleGoalOf(order: SelfDevOrder, operations: SelfDevOperatorRepository): Boolean? {
    val taskId = order.currentGoalId ?: return null
    val task = operations.task(taskId) ?: return false
    if (task.state in ENDED_TASK_STATES) return null
    return operations.stopTask(taskId, task.version) is TaskTransition.Moved
}

/** The states a Task cannot leave by a stop: nothing is done to them. */
private val ENDED_TASK_STATES: Set<TaskState> = setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.SKIPPED, TaskState.DONE, TaskState.STOPPED)
