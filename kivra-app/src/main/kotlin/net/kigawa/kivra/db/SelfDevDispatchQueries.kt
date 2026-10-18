package net.kigawa.kivra.db

import net.kigawa.kivra.core.autopilot.GoalState
import net.kigawa.kivra.core.autopilot.TaskState
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** A task found by state, with the goal that owns its plan. Read-only; the state is read again before any change. */
internal data class TaskRef(val goalId: String, val planId: String, val ordinal: Int, val state: TaskState)

/**
 * Read-only lookups for the autopilot dispatcher (ADR 0011 section 5). They return ids and states only, and the caller reads the full
 * row through the repository that owns it, so the repository's checks still apply to every change.
 */
internal class SelfDevDispatchQueries(private val database: Database) {

    /** Goal ids in any of [states], in creation order (goal_id is a ULID). */
    fun goalIdsInStates(states: Set<GoalState>): List<String> = transaction(database) {
        SelfDevGoalsTable.selectAll()
            .where { SelfDevGoalsTable.state inList states.map { it.name } }
            .orderBy(SelfDevGoalsTable.goalId to SortOrder.ASC)
            .map { it[SelfDevGoalsTable.goalId] }
    }

    /** Tasks in any of [states] across every plan, ordered by plan and ordinal. */
    fun taskRefsInStates(states: Set<TaskState>): List<TaskRef> = transaction(database) {
        val tasks = SelfDevPlanTasksTable.selectAll()
            .where { SelfDevPlanTasksTable.state inList states.map { it.name } }
            .orderBy(SelfDevPlanTasksTable.planId to SortOrder.ASC, SelfDevPlanTasksTable.ordinal to SortOrder.ASC)
            .map { Triple(it[SelfDevPlanTasksTable.planId], it[SelfDevPlanTasksTable.ordinal], TaskState.valueOf(it[SelfDevPlanTasksTable.state])) }
        if (tasks.isEmpty()) return@transaction emptyList()
        val goalOfPlan = SelfDevPlansTable.selectAll()
            .where { SelfDevPlansTable.planId inList tasks.map { it.first }.distinct() }
            .associate { it[SelfDevPlansTable.planId] to it[SelfDevPlansTable.goalId] }
        tasks.mapNotNull { (planId, ordinal, state) ->
            goalOfPlan[planId]?.let { TaskRef(it, planId, ordinal, state) }
        }
    }
}
