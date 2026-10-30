package net.kigawa.kivra.planner

/**
 * A child task in a plan proposal (design #3 section 5.4).
 */
data class PlannedChildTask(
    val title: String,
    val description: String,
    val dependencies: List<Int> = emptyList(),
)

/**
 * A plan proposal from the Planner agent (design #3 section 5.4).
 */
data class PlanProposal(
    val childTasks: List<PlannedChildTask>,
)
