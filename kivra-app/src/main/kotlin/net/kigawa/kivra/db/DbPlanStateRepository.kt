package net.kigawa.kivra.db

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.planner.DecompositionState
import net.kigawa.kivra.planner.PlanProposal
import net.kigawa.kivra.planner.PlanStateStore
import net.kigawa.kivra.planner.PlannedChildTask
import net.kigawa.kivra.reviewer.ReviewState
import net.kigawa.kivra.reviewer.ReviewerVerdict
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Durable snapshots. Each save is an atomic upsert, including concurrent first writes. */
class DbPlanStateRepository(private val database: Database) : PlanStateStore {
    override fun getDecompositionState(taskId: String): DecompositionState? = transaction(database) {
        PlanDecompositionStatesTable.selectAll().where { PlanDecompositionStatesTable.taskId eq taskId }
            .firstOrNull()?.let { row ->
                val snapshot = Json.decodeFromString<DecompositionSnapshot>(row[PlanDecompositionStatesTable.stateJson])
                DecompositionState(
                    decompositionDepth = snapshot.decompositionDepth,
                    childTaskIds = snapshot.childTaskIds,
                    childRunStates = snapshot.childRunStates.mapValues { DevelopmentRunState.valueOf(it.value) },
                    planProposal = snapshot.childTasks?.let { tasks ->
                        PlanProposal(tasks.map { PlannedChildTask(it.title, it.description, it.dependencies) })
                    },
                    planApproved = snapshot.planApproved,
                )
            }
    }

    override fun saveDecompositionState(taskId: String, state: DecompositionState) {
        val payload = Json.encodeToString(DecompositionSnapshot(
            decompositionDepth = state.decompositionDepth,
            childTaskIds = state.childTaskIds,
            childRunStates = state.childRunStates.mapValues { it.value.name },
            childTasks = state.planProposal?.childTasks?.map { ChildSnapshot(it.title, it.description, it.dependencies) },
            planApproved = state.planApproved,
        ))
        transaction(database) {
            execUpdate(
                "INSERT INTO plan_decomposition_states (task_id, state_json) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE state_json = VALUES(state_json)",
                taskId, payload,
            )
        }
    }

    override fun getReviewState(runId: UUID): ReviewState? = transaction(database) {
        PlanReviewStatesTable.selectAll().where { PlanReviewStatesTable.runId eq runId.toString() }
            .firstOrNull()?.let { row ->
                val snapshot = Json.decodeFromString<ReviewSnapshot>(row[PlanReviewStatesTable.stateJson])
                ReviewState(snapshot.reviewRound, snapshot.previousFindings, snapshot.lastVerdict?.let(ReviewerVerdict::valueOf))
            }
    }

    override fun saveReviewState(runId: UUID, state: ReviewState) {
        val payload = Json.encodeToString(ReviewSnapshot(state.reviewRound, state.previousFindings, state.lastVerdict?.name))
        transaction(database) {
            execUpdate(
                "INSERT INTO plan_review_states (run_id, state_json) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE state_json = VALUES(state_json)",
                runId.toString(), payload,
            )
        }
    }
}

@Serializable
private data class DecompositionSnapshot(
    val decompositionDepth: Int,
    val childTaskIds: Map<Int, String>,
    val childRunStates: Map<String, String>,
    val childTasks: List<ChildSnapshot>?,
    val planApproved: Boolean,
)

@Serializable
private data class ChildSnapshot(val title: String, val description: String, val dependencies: List<Int>)

@Serializable
private data class ReviewSnapshot(val reviewRound: Int, val previousFindings: Set<String>, val lastVerdict: String?)
