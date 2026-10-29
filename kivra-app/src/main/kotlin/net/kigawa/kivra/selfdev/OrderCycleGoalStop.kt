package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.autopilot.TaskState
import net.kigawa.kivra.db.SelfDevTaskRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.TaskTransition

/**
 * Stops the task of the cycle in progress of an order of a stopped part, through the existing task stop (V40 flat task model).
 * Returns null when there is no task to stop (no cycle in progress, or the task is already DONE or STOPPED),
 * true when the task was stopped, and false when the stop was refused.
 */
internal fun stopCycleGoalOf(order: SelfDevOrder, tasks: SelfDevTaskRepository, operations: SelfDevOperatorRepository): Boolean? {
    val taskId = order.currentTaskId ?: return null
    val task = tasks.get(taskId) ?: return null
    if (task.state == TaskState.DONE || task.state == TaskState.STOPPED) return null
    return operations.stopTask(taskId, task.version) is TaskTransition.Moved
}
