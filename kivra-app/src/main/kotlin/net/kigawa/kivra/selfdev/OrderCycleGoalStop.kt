package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.db.SelfDevGoalRepository
import net.kigawa.kivra.db.SelfDevOperatorRepository
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.Transition

/**
 * Stops the goal of the cycle in progress of an order of a stopped part, through the existing goal stop (ADR 0015 section 4.5). Shared by the
 * approval of a stop (the HTTP route, a person's decision) and the automatic child stop (the order watch), so both stop the goal the same way.
 * The order is already STOPPED when this runs, and the dispatcher starts no Run for it, so a failure is left for the operator.
 *
 * Returns null when there is no goal to stop (no cycle in progress, or the goal is already DONE or STOPPED), true when the goal was stopped,
 * and false when the stop was refused.
 */
internal fun stopCycleGoalOf(order: SelfDevOrder, goals: SelfDevGoalRepository, operations: SelfDevOperatorRepository): Boolean? {
    val goalId = order.currentGoalId ?: return null
    val goal = goals.get(goalId) ?: return null
    if (goal.state == GoalState.DONE || goal.state == GoalState.STOPPED) return null
    return operations.stopGoal(goalId, goal.version) is Transition.Moved
}
