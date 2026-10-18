package net.kigawa.kivra.core.autopilot

import net.kigawa.kivra.core.StateMachine

/*
 * Self-development autopilot state machines (ADR 0011 section 2.5). Pure Kotlin, no I/O. The repositories call the check
 * functions before they write, and the conditional UPDATE is the other half of the guard (CAS on version and state).
 *
 * Every allowed edge is listed once in a table below. A transition is allowed only when its edge exists, its reason code is one
 * the edge allows (null means the edge takes no reason), and the context guard holds. Everything else is refused.
 * DONE and STOPPED (Goal), SUPERSEDED and REJECTED (Plan), COMPLETED and SKIPPED (task) have no outgoing edge.
 */

/**
 * Goal states (`selfdev_goals.state`). PAUSED returns to the state it was paused from (see [GoalContext.resumeState]).
 * BLOCKED returns only through the dispatch guards (see [GoalContext.blockedReason]).
 */
enum class GoalState : StateMachine<GoalState> {
    GOAL_SUBMITTED, PLANNING, PLAN_PROPOSED, TASKS_QUEUED, RUNNING, BLOCKED, PAUSED, DONE, STOPPED;

    override val next: Set<GoalState> get() = GOAL_EDGES.filter { it.from == this }.map { it.to }.toSet()
}

/** Plan states (`selfdev_plans.state`). */
enum class PlanState : StateMachine<PlanState> {
    PROPOSED, SUPERSEDED, REJECTED;

    override val next: Set<PlanState> get() = PLAN_EDGES.filter { it.from == this }.map { it.to }.toSet()
}

/**
 * Task states (`selfdev_plan_tasks.state`). A task is a projection of its Run (ADR 0001): SUBMITTED is left only with the
 * Run's outcome, and the outcome is read from the Run, never written back to it.
 */
enum class TaskState : StateMachine<TaskState> {
    PENDING_APPROVAL, QUEUED, SUBMITTING, SUBMITTED, COMPLETED, FAILED, CANCELLED, SKIPPED;

    override val next: Set<TaskState> get() = TASK_EDGES.filter { it.from == this }.map { it.to }.toSet()
}

/**
 * Fixed reason codes (`state_reason`, ADR 0011 sections 2.5 and 5.2). Never free text. [PLAN_BUDGET_HOLD] is the planning-stage
 * form of the budget hold: a dispatch-stage hold is [BUDGET_HOLD]. The split keeps a planning block from ever resuming into
 * dispatch (see the note in the ADR).
 */
enum class AutopilotReason {
    PLAN_INVALID, PLAN_BUDGET_HOLD, PLAN_CALL_FAILED, CLAIM_EXPIRED, REPLAN_REQUESTED,
    PENDING_PR_LIMIT, DAILY_RUN_CAP, BUDGET_HOLD, CIRCUIT_OPEN, AUTO_SCOPE_EXCEEDED,
    GUARD_CLEARED, OPERATOR_PAUSED, OPERATOR_RESUMED, OPERATOR_STOPPED, ALL_TASKS_COMPLETED,
    OPERATOR_APPROVED, OPERATOR_SKIPPED, OPERATOR_REQUEUED, GOAL_STOPPED,
    ACTIVE_RUN_LIMIT, PR_MERGED, RUN_FAILED, POLICY_REJECTED, PR_ABANDONED, RUN_RELEASED,
}

/** Why a transition is refused. Fixed codes. */
enum class TransitionRefusal {
    /** No edge from the state to the target. */
    NO_EDGE,
    /** The edge exists, but this reason code is not one it allows. */
    REASON_NOT_ALLOWED,
    /** The edge and reason are allowed, but the context guard does not hold. */
    GUARD_FAILED,
}

sealed interface TransitionDecision {
    data object Allowed : TransitionDecision
    data class Refused(val reason: TransitionRefusal) : TransitionDecision
}

/** Facts a Goal transition needs from the row. Defaults are the values a fresh row has. */
data class GoalContext(
    /** Plan revisions created so far (`selfdev_goals.revision`). Replanning needs fewer than [MAX_REVISIONS]. */
    val revision: Int = 0,
    /** The `state_reason` of a BLOCKED goal. Resuming from BLOCKED needs a dispatch-guard reason. */
    val blockedReason: AutopilotReason? = null,
    /** The state a PAUSED goal returns to. A PAUSED goal may only move to this state. */
    val resumeState: GoalState? = null,
)

/** A Goal has at most this many Plan revisions (ADR 0011 section 2.1). */
const val MAX_REVISIONS = 3

/** The dispatch guards of ADR 0011 section 5.2 that block a Goal (BLOCKED). The pause and the circuit breaker are not in it. */
val DISPATCH_BLOCK_REASONS: Set<AutopilotReason> = setOf(
    AutopilotReason.PENDING_PR_LIMIT,
    AutopilotReason.DAILY_RUN_CAP,
    AutopilotReason.BUDGET_HOLD,
    AutopilotReason.CIRCUIT_OPEN,
    AutopilotReason.AUTO_SCOPE_EXCEEDED,
)

/** The dispatch blocks that clear on their own (GUARD_CLEARED). The others need an operator (OPERATOR_RESUMED). */
val AUTO_CLEARABLE_REASONS: Set<AutopilotReason> = setOf(
    AutopilotReason.PENDING_PR_LIMIT,
    AutopilotReason.DAILY_RUN_CAP,
    AutopilotReason.BUDGET_HOLD,
)

/**
 * The planning-stage blocks (ADR 0011 section 2.8, U-16). Only an operator moves a goal out of them, back to GOAL_SUBMITTED, so a
 * block that the Planner made is never cleared by a dispatch guard.
 */
val PLAN_BLOCK_REASONS: Set<AutopilotReason> = setOf(
    AutopilotReason.PLAN_INVALID,
    AutopilotReason.PLAN_BUDGET_HOLD,
    AutopilotReason.PLAN_CALL_FAILED,
)

/** One allowed edge. [from] is null for the creation edge. [reasons] holds null when the edge takes no reason. */
private class Edge<S>(val from: S?, val to: S, val reasons: Set<AutopilotReason?>)

private val NO_REASON: Set<AutopilotReason?> = setOf(null)

private val GOAL_EDGES: List<Edge<GoalState>> = buildList {
    fun edge(from: GoalState?, to: GoalState, reasons: Set<AutopilotReason?>) = add(Edge(from, to, reasons))
    edge(null, GoalState.GOAL_SUBMITTED, NO_REASON)
    edge(GoalState.GOAL_SUBMITTED, GoalState.PLANNING, NO_REASON)
    edge(GoalState.PLANNING, GoalState.PLAN_PROPOSED, NO_REASON)
    edge(GoalState.PLANNING, GoalState.BLOCKED, PLAN_BLOCK_REASONS)
    edge(GoalState.PLANNING, GoalState.GOAL_SUBMITTED, setOf(AutopilotReason.CLAIM_EXPIRED))
    edge(GoalState.PLAN_PROPOSED, GoalState.TASKS_QUEUED, NO_REASON)
    edge(GoalState.PLAN_PROPOSED, GoalState.GOAL_SUBMITTED, setOf(AutopilotReason.REPLAN_REQUESTED))
    edge(GoalState.BLOCKED, GoalState.GOAL_SUBMITTED, setOf(AutopilotReason.REPLAN_REQUESTED))
    edge(GoalState.TASKS_QUEUED, GoalState.RUNNING, NO_REASON)
    edge(GoalState.RUNNING, GoalState.BLOCKED, DISPATCH_BLOCK_REASONS)
    edge(GoalState.TASKS_QUEUED, GoalState.BLOCKED, DISPATCH_BLOCK_REASONS)
    val resume: Set<AutopilotReason?> = setOf(AutopilotReason.GUARD_CLEARED, AutopilotReason.OPERATOR_RESUMED)
    edge(GoalState.BLOCKED, GoalState.RUNNING, resume)
    edge(GoalState.BLOCKED, GoalState.TASKS_QUEUED, resume)
    edge(GoalState.RUNNING, GoalState.DONE, setOf(AutopilotReason.ALL_TASKS_COMPLETED))
    val pausable = listOf(GoalState.TASKS_QUEUED, GoalState.RUNNING, GoalState.BLOCKED)
    for (from in pausable) edge(from, GoalState.PAUSED, setOf(AutopilotReason.OPERATOR_PAUSED))
    for (to in pausable) edge(GoalState.PAUSED, to, setOf(AutopilotReason.OPERATOR_RESUMED))
    val active = listOf(
        GoalState.GOAL_SUBMITTED, GoalState.PLANNING, GoalState.PLAN_PROPOSED, GoalState.TASKS_QUEUED,
        GoalState.RUNNING, GoalState.BLOCKED, GoalState.PAUSED,
    )
    for (from in active) edge(from, GoalState.STOPPED, setOf(AutopilotReason.OPERATOR_STOPPED))
}

private val PLAN_EDGES: List<Edge<PlanState>> = listOf(
    Edge(null, PlanState.PROPOSED, NO_REASON),
    Edge(null, PlanState.REJECTED, setOf(AutopilotReason.PLAN_INVALID)),
    Edge(PlanState.PROPOSED, PlanState.SUPERSEDED, setOf(AutopilotReason.REPLAN_REQUESTED)),
)

private val TASK_EDGES: List<Edge<TaskState>> = listOf(
    Edge(TaskState.PENDING_APPROVAL, TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_APPROVED)),
    Edge(TaskState.PENDING_APPROVAL, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.GOAL_STOPPED)),
    Edge(TaskState.QUEUED, TaskState.SUBMITTING, NO_REASON),
    Edge(TaskState.QUEUED, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.GOAL_STOPPED)),
    Edge(TaskState.SUBMITTING, TaskState.SUBMITTED, NO_REASON),
    Edge(TaskState.SUBMITTING, TaskState.QUEUED, setOf(AutopilotReason.ACTIVE_RUN_LIMIT)),
    Edge(TaskState.SUBMITTED, TaskState.COMPLETED, setOf(AutopilotReason.PR_MERGED)),
    Edge(
        TaskState.SUBMITTED, TaskState.FAILED,
        setOf(AutopilotReason.RUN_FAILED, AutopilotReason.POLICY_REJECTED, AutopilotReason.PR_ABANDONED),
    ),
    Edge(TaskState.SUBMITTED, TaskState.CANCELLED, setOf(AutopilotReason.RUN_RELEASED)),
    Edge(TaskState.FAILED, TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_REQUEUED)),
    Edge(TaskState.FAILED, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED)),
    Edge(TaskState.CANCELLED, TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_REQUEUED)),
    Edge(TaskState.CANCELLED, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED)),
)

private fun <S> checkEdge(edges: List<Edge<S>>, from: S?, to: S, reason: AutopilotReason?): TransitionDecision {
    val edge = edges.firstOrNull { it.from == from && it.to == to }
        ?: return TransitionDecision.Refused(TransitionRefusal.NO_EDGE)
    if (reason !in edge.reasons) return TransitionDecision.Refused(TransitionRefusal.REASON_NOT_ALLOWED)
    return TransitionDecision.Allowed
}

/** Checks a Goal transition. [from] is null for the creation of the Goal. */
fun checkGoalTransition(from: GoalState?, to: GoalState, reason: AutopilotReason?, context: GoalContext): TransitionDecision {
    val edge = checkEdge(GOAL_EDGES, from, to, reason)
    if (edge != TransitionDecision.Allowed) return edge
    val guardHolds = when {
        from == GoalState.PLAN_PROPOSED && to == GoalState.GOAL_SUBMITTED -> context.revision < MAX_REVISIONS
        from == GoalState.PAUSED -> context.resumeState == to
        from == GoalState.BLOCKED && to == GoalState.GOAL_SUBMITTED -> context.blockedReason in PLAN_BLOCK_REASONS
        from == GoalState.BLOCKED && to != GoalState.PAUSED && to != GoalState.STOPPED -> {
            val blocked = context.blockedReason
            blocked in DISPATCH_BLOCK_REASONS && (reason != AutopilotReason.GUARD_CLEARED || blocked in AUTO_CLEARABLE_REASONS)
        }
        else -> true
    }
    return if (guardHolds) TransitionDecision.Allowed else TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED)
}

/** Checks a Plan transition. [from] is null for the creation of the Plan. */
fun checkPlanTransition(from: PlanState?, to: PlanState, reason: AutopilotReason?): TransitionDecision =
    checkEdge(PLAN_EDGES, from, to, reason)

/** Checks a task transition. The Run that a task points to is checked by the repository, not here. */
fun checkTaskTransition(from: TaskState, to: TaskState, reason: AutopilotReason?): TransitionDecision =
    checkEdge(TASK_EDGES, from, to, reason)
