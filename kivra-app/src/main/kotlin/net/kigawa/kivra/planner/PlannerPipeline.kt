package net.kigawa.kivra.planner

import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.agent.AgentProfile
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.fusha.FushaTaskAdapter
import net.kigawa.kivra.reviewer.ReviewerPipeline
import net.kigawa.kivra.reviewer.ReviewerResult
import net.kigawa.kivra.reviewer.ReviewerVerdict
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Planner pipeline configuration (design #3 section 5.4).
 */
data class PlannerPipelineConfig(
    val maxDecompositionDepth: Int = 2,
    val maxChildTasks: Int = 10,
    val plannerProfile: AgentProfile? = null,
) {
    init {
        require(maxDecompositionDepth >= 1) { "maxDecompositionDepth must be >= 1" }
        require(maxChildTasks in 1..10) { "maxChildTasks must be between 1 and 10" }
    }
}

/**
 * Tracks the decomposition state for a single parent task (design #3 section 5.4).
 */
data class DecompositionState(
    val decompositionDepth: Int = 0,
    val childTaskIds: Map<Int, String> = emptyMap(),
    val childRunStates: Map<String, DevelopmentRunState> = emptyMap(),
    val planProposal: PlanProposal? = null,
    val planApproved: Boolean = false,
)

/**
 * Manages the Planner → Reviewer → Child Task creation pipeline (design #3 section 5.4, C3).
 */
class PlannerPipeline(
    private val runRepo: DevelopmentRunRepository,
    private val fushaAdapter: FushaTaskAdapter?,
    private val reviewerPipeline: ReviewerPipeline?,
    private val config: PlannerPipelineConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(PlannerPipeline::class.java)
    private val decompositionStates = mutableMapOf<String, DecompositionState>()

    /**
     * Check if a task should enter the planning phase.
     * Returns true when the task has no existing plan proposal.
     */
    suspend fun shouldEnterPlanning(taskId: String): Boolean {
        val state = decompositionStates[taskId]
        return state == null || state.planProposal == null
    }

    /**
     * Get the current decomposition state for a task.
     */
    fun getDecompositionState(taskId: String): DecompositionState {
        return decompositionStates.getOrPut(taskId) { DecompositionState() }
    }

    /**
     * Process a plan proposal from the Planner agent.
     * Validates depth limits, child task count, and dependency cycles.
     *
     * Returns true if the proposal is accepted, false if rejected.
     */
    fun processPlanProposal(taskId: String, proposal: PlanProposal): Boolean {
        val currentState = decompositionStates[taskId] ?: DecompositionState()
        if (currentState.decompositionDepth >= config.maxDecompositionDepth) {
            logger.warn("planner pipeline: taskId={}, decomposition depth limit reached ({})",
                taskId, config.maxDecompositionDepth)
            return false
        }
        if (proposal.childTasks.size > config.maxChildTasks) {
            logger.warn("planner pipeline: taskId={}, too many child tasks ({}/{})",
                taskId, proposal.childTasks.size, config.maxChildTasks)
            return false
        }
        // Validate dependency indices
        val indices = proposal.childTasks.indices.toSet()
        for ((idx, child) in proposal.childTasks.withIndex()) {
            for (dep in child.dependencies) {
                if (dep !in indices || dep == idx) {
                    logger.warn("planner pipeline: taskId={}, invalid dependency {} in child {}",
                        taskId, dep, idx)
                    return false
                }
            }
        }
        // Check for circular dependencies
        if (hasCircularDependencies(proposal.childTasks)) {
            logger.warn("planner pipeline: taskId={}, circular dependencies detected", taskId)
            return false
        }
        decompositionStates[taskId] = currentState.copy(
            planProposal = proposal,
            decompositionDepth = currentState.decompositionDepth + 1
        )
        logger.info("planner pipeline: taskId={}, accepted plan with {} child tasks",
            taskId, proposal.childTasks.size)
        return true
    }

    /**
     * Process the review result for a plan proposal.
     * If approved, the plan is ready for child task creation.
     * If rejected, the plan is escalated.
     */
    suspend fun processPlanReview(taskId: String, result: ReviewerResult): PlannerPipelineOutcome {
        val state = decompositionStates[taskId]
        if (state == null || state.planProposal == null) {
            return PlannerPipelineOutcome.ERROR_NO_PLAN
        }
        return when (result.verdict) {
            ReviewerVerdict.APPROVE -> {
                decompositionStates[taskId] = state.copy(planApproved = true)
                logger.info("planner pipeline: taskId={}, plan approved", taskId)
                PlannerPipelineOutcome.PLAN_APPROVED
            }
            ReviewerVerdict.REQUEST_CHANGES -> {
                logger.info("planner pipeline: taskId={}, plan review requested changes", taskId)
                PlannerPipelineOutcome.REQUEST_CHANGES
            }
            ReviewerVerdict.REJECT -> {
                logger.warn("planner pipeline: taskId={}, plan rejected, escalating", taskId)
                PlannerPipelineOutcome.ESCALATED_REJECTED
            }
        }
    }

    /**
     * Check if all child tasks of a parent task are complete.
     */
    suspend fun areAllChildrenComplete(taskId: String): Boolean {
        val state = decompositionStates[taskId] ?: return false
        val childIds = state.childTaskIds.values
        if (childIds.isEmpty()) return false
        return childIds.all { isChildTaskComplete(it) }
    }

    private suspend fun isChildTaskComplete(childTaskId: String): Boolean {
        val entry = decompositionStates.values
            .flatMap { it.childRunStates.entries }
            .find { it.key == childTaskId }
        return entry?.value?.isTerminal ?: false
    }

    /**
     * Add a child task to the decomposition state.
     */
    fun addChildTask(taskId: String, childIndex: Int, childTaskId: String) {
        val state = decompositionStates[taskId] ?: return
        decompositionStates[taskId] = state.copy(
            childTaskIds = state.childTaskIds + (childIndex to childTaskId)
        )
    }

    /**
     * Update the run state of a child task.
     */
    fun updateChildRunState(taskId: String, childTaskId: String, state: DevelopmentRunState) {
        val decState = decompositionStates[taskId] ?: return
        decompositionStates[taskId] = decState.copy(
            childRunStates = decState.childRunStates + (childTaskId to state)
        )
    }

    /**
     * Get the approved plan for a task. Returns null if not approved.
     */
    fun getApprovedPlan(taskId: String): PlanProposal? {
        val state = decompositionStates[taskId] ?: return null
        return if (state.planApproved) state.planProposal else null
    }

    /**
     * Detect circular dependencies in child task dependency graph.
     */
    private fun hasCircularDependencies(childTasks: List<PlannedChildTask>): Boolean {
        val visited = mutableSetOf<Int>()
        val recursionStack = mutableSetOf<Int>()
        fun hasCycle(node: Int): Boolean {
            visited.add(node)
            recursionStack.add(node)
            for (dep in childTasks.getOrNull(node)?.dependencies ?: emptyList()) {
                if (dep !in visited) {
                    if (hasCycle(dep)) return true
                } else if (dep in recursionStack) return true
            }
            recursionStack.remove(node)
            return false
        }
        return childTasks.indices.any { it !in visited && hasCycle(it) }
    }
}

/**
 * Outcomes of processing a plan review.
 */
enum class PlannerPipelineOutcome {
    PLAN_APPROVED,
    REQUEST_CHANGES,
    ESCALATED_REJECTED,
    ERROR_NO_PLAN,
}
