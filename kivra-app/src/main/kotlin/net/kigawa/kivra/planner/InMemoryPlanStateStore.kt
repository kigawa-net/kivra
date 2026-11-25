package net.kigawa.kivra.planner

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kigawa.kivra.reviewer.ReviewState

/** Process-local store. Share an instance to share state between pipelines. */
class InMemoryPlanStateStore : PlanStateStore {
    private val decompositions = ConcurrentHashMap<String, DecompositionState>()
    private val reviews = ConcurrentHashMap<UUID, ReviewState>()

    override fun getDecompositionState(taskId: String): DecompositionState? = decompositions[taskId]

    override fun saveDecompositionState(taskId: String, state: DecompositionState) {
        decompositions[taskId] = state.copy(
            childTaskIds = state.childTaskIds.toMap(),
            childRunStates = state.childRunStates.toMap(),
            planProposal = state.planProposal?.let { proposal ->
                proposal.copy(childTasks = proposal.childTasks.map { it.copy(dependencies = it.dependencies.toList()) })
            },
        )
    }

    override fun getReviewState(runId: UUID): ReviewState? = reviews[runId]

    override fun saveReviewState(runId: UUID, state: ReviewState) {
        reviews[runId] = state.copy(previousFindings = state.previousFindings.toSet())
    }
}
