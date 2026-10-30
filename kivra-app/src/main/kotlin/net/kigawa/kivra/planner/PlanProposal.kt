package net.kigawa.kivra.planner

/**
 * Structured output of a Planner run (design #3 section 5.4).
 *
 * The Planner decomposes a parent task into child tasks with dependencies,
 * estimates, and acceptance criteria.
 */
data class PlanProposal(
    val childTasks: List<PlannedChildTask>,
    val summary: String,
    val estimatedEffort: Int? = null,
) {
    init {
        require(childTasks.isNotEmpty()) { "plan must have at least one child task" }
        require(childTasks.size <= 10) { "plan cannot have more than 10 child tasks" }
        require(childTasks.none { it.title.isBlank() }) { "child task title must not be blank" }
        require(childTasks.none { it.description.isBlank() }) { "child task description must not be blank" }
    }
}

/**
 * One child task proposed by the Planner (design #3 section 5.4).
 */
data class PlannedChildTask(
    val title: String,
    val description: String,
    val dependencies: List<Int> = emptyList(),
    val estimatedHours: Int? = null,
    val acceptanceCriteria: List<String> = emptyList(),
)
