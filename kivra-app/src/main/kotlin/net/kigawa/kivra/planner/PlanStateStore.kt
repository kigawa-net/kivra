package net.kigawa.kivra.planner

import java.util.UUID
import net.kigawa.kivra.reviewer.ReviewState

/**
 * Stores the latest pipeline state per task or run. Missing keys return null.
 * Saving replaces the snapshot; saving the same key and state again is idempotent.
 */
interface PlanStateStore {
    fun getDecompositionState(taskId: String): DecompositionState?
    fun saveDecompositionState(taskId: String, state: DecompositionState)
    fun getReviewState(runId: UUID): ReviewState?
    fun saveReviewState(runId: UUID, state: ReviewState)
}
