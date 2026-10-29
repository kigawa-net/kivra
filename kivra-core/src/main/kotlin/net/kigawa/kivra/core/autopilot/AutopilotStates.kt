package net.kigawa.kivra.core.autopilot

import net.kigawa.kivra.core.StateMachine

/*
 * Self-development autopilot state machine (V40 flat task model). Pure Kotlin, no I/O. The repositories call the check
 * functions before they write, and the conditional UPDATE is the other half of the guard (CAS on version and state).
 *
 * Every allowed edge is listed once in a table below. A transition is allowed only when its edge exists, its reason code is one
 * the edge allows (null means the edge takes no reason), and the context guard holds. Everything else is refused.
 * COMPLETED, SKIPPED, DONE and STOPPED have no outgoing edge (terminal states).
 *
 * The unified TaskState combines the former GoalState and TaskState into a single lifecycle:
 *   TASK_SUBMITTED -> PLANNING -> PENDING_APPROVAL -> QUEUED -> SUBMITTING -> SUBMITTED -> COMPLETED/FAILED/CANCELLED
 *   Control: BLOCKED (dispatch guard), PAUSED (operator pause), DONE (all done), STOPPED (operator stop)
 */

/**
 * Unified task states (`selfdev_tasks.state`). PAUSED returns to the state it was paused from (see [TaskContext.resumeState]).
 * BLOCKED returns only through the dispatch guards (see [TaskContext.blockedReason]).
 */
enum class TaskState : StateMachine<TaskState> {
    TASK_SUBMITTED, PLANNING, PENDING_APPROVAL, QUEUED, SUBMITTING, SUBMITTED,
    COMPLETED, FAILED, CANCELLED, SKIPPED, BLOCKED, PAUSED, DONE, STOPPED;

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
    OPERATOR_APPROVED, AUTO_APPROVED, OPERATOR_SKIPPED, OPERATOR_REQUEUED, TASK_STOPPED,
    ACTIVE_RUN_LIMIT, PR_MERGED, RUN_FAILED, POLICY_REJECTED, PR_ABANDONED, RUN_RELEASED,
    /** Every task ended (COMPLETED, FAILED or SKIPPED) and not all of them COMPLETED: the task stops by the dispatcher (ADR 0011 section 2.8b). */
    TASKS_INCOMPLETE,
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

/** Facts a Task transition needs from the row. Defaults are the values a fresh row has. */
data class TaskContext(
    /** Plan revisions created so far (`selfdev_tasks.revision`). Replanning needs fewer than [MAX_REVISIONS]. */
    val revision: Int = 0,
    /** The `state_reason` of a BLOCKED task. Resuming from BLOCKED needs a dispatch-guard reason. */
    val blockedReason: AutopilotReason? = null,
    /** The state a PAUSED task returns to. A PAUSED task may only move to this state. */
    val resumeState: TaskState? = null,
)

/** A Task has at most this many Plan revisions (ADR 0011 section 2.1). */
const val MAX_REVISIONS = 3

/** The dispatch guards of ADR 0011 section 5.2 that block a Task (BLOCKED). The pause and the circuit breaker are not in it. */
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
 * The planning-stage blocks (ADR 0011 section 2.8, U-16). Only an operator moves a task out of them, back to TASK_SUBMITTED, so a
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

/**
 * Unified task edges — combines the former GoalState and TaskState transitions into one lifecycle.
 * Flow: TASK_SUBMITTED -> PLANNING -> PENDING_APPROVAL -> QUEUED -> SUBMITTING -> SUBMITTED -> COMPLETED/FAILED/CANCELLED
 * Control: BLOCKED (dispatch guard), PAUSED (operator pause), DONE (all done), STOPPED (operator stop)
 */
private val TASK_EDGES: List<Edge<TaskState>> = buildList {
    fun edge(from: TaskState?, to: TaskState, reasons: Set<AutopilotReason?>) = add(Edge(from, to, reasons))

    // --- Submission & Planning (former GoalState flow) ---
    edge(null, TaskState.TASK_SUBMITTED, NO_REASON)
    edge(TaskState.TASK_SUBMITTED, TaskState.PLANNING, NO_REASON)
    edge(TaskState.PLANNING, TaskState.PENDING_APPROVAL, NO_REASON)  // PLANNING -> PENDING_APPROVAL (plan committed)
    edge(TaskState.PLANNING, TaskState.BLOCKED, PLAN_BLOCK_REASONS)
    edge(TaskState.PLANNING, TaskState.TASK_SUBMITTED, setOf(AutopilotReason.CLAIM_EXPIRED))
    edge(TaskState.BLOCKED, TaskState.TASK_SUBMITTED, setOf(AutopilotReason.REPLAN_REQUESTED))

    // --- Approval & Execution (former TaskState flow) ---
    edge(
        TaskState.PENDING_APPROVAL, TaskState.QUEUED,
        setOf(AutopilotReason.OPERATOR_APPROVED, AutopilotReason.AUTO_APPROVED),
    )
    edge(TaskState.PENDING_APPROVAL, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.TASK_STOPPED))
    edge(TaskState.QUEUED, TaskState.SUBMITTING, NO_REASON)
    edge(TaskState.QUEUED, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.TASK_STOPPED))
    edge(TaskState.SUBMITTING, TaskState.SUBMITTED, NO_REASON)
    edge(TaskState.SUBMITTING, TaskState.QUEUED, setOf(AutopilotReason.ACTIVE_RUN_LIMIT))
    edge(TaskState.SUBMITTED, TaskState.COMPLETED, setOf(AutopilotReason.PR_MERGED))
    edge(
        TaskState.SUBMITTED, TaskState.FAILED,
        setOf(AutopilotReason.RUN_FAILED, AutopilotReason.POLICY_REJECTED, AutopilotReason.PR_ABANDONED),
    )
    edge(TaskState.SUBMITTED, TaskState.CANCELLED, setOf(AutopilotReason.RUN_RELEASED))
    edge(TaskState.SUBMITTED, TaskState.PENDING_APPROVAL, setOf(AutopilotReason.AUTO_SCOPE_EXCEEDED))
    edge(TaskState.FAILED, TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_REQUEUED))
    edge(TaskState.FAILED, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED))
    edge(TaskState.CANCELLED, TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_REQUEUED))
    edge(TaskState.CANCELLED, TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED))

    // --- Dispatch guards (BLOCKED from execution states) ---
    val dispatchable = listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED)
    for (from in dispatchable) edge(from, TaskState.BLOCKED, DISPATCH_BLOCK_REASONS)
    val resume: Set<AutopilotReason?> = setOf(AutopilotReason.GUARD_CLEARED, AutopilotReason.OPERATOR_RESUMED)
    for (to in dispatchable) edge(TaskState.BLOCKED, to, resume)

    // --- Completion ---
    edge(TaskState.COMPLETED, TaskState.DONE, setOf(AutopilotReason.ALL_TASKS_COMPLETED))

    // --- Pause/Resume (execution states only) ---
    val pausable = listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED, TaskState.BLOCKED)
    for (from in pausable) edge(from, TaskState.PAUSED, setOf(AutopilotReason.OPERATOR_PAUSED))
    for (to in pausable) edge(TaskState.PAUSED, to, setOf(AutopilotReason.OPERATOR_RESUMED))

    // --- Stop (from any active state) ---
    val active = listOf(
        TaskState.TASK_SUBMITTED, TaskState.PLANNING, TaskState.PENDING_APPROVAL,
        TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED,
        TaskState.BLOCKED, TaskState.PAUSED,
    )
    val dispatchEnds = setOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED, TaskState.BLOCKED)
    for (from in active) {
        val reasons = if (from in dispatchEnds) {
            setOf(AutopilotReason.OPERATOR_STOPPED, AutopilotReason.TASKS_INCOMPLETE)
        } else {
            setOf(AutopilotReason.OPERATOR_STOPPED)
        }
        edge(from, TaskState.STOPPED, reasons)
    }
}

private fun <S> checkEdge(edges: List<Edge<S>>, from: S?, to: S, reason: AutopilotReason?): TransitionDecision {
    val edge = edges.firstOrNull { it.from == from && it.to == to }
        ?: return TransitionDecision.Refused(TransitionRefusal.NO_EDGE)
    if (reason !in edge.reasons) return TransitionDecision.Refused(TransitionRefusal.REASON_NOT_ALLOWED)
    return TransitionDecision.Allowed
}

/** Checks a Task transition. [from] is null for the creation of the Task. */
fun checkTaskTransition(from: TaskState?, to: TaskState, reason: AutopilotReason?, context: TaskContext): TransitionDecision {
    val edge = checkEdge(TASK_EDGES, from, to, reason)
    if (edge != TransitionDecision.Allowed) return edge
    val guardHolds = when {
        // Replanning: PLANNING -> TASK_SUBMITTED needs fewer than MAX_REVISIONS
        from == TaskState.PLANNING && to == TaskState.TASK_SUBMITTED && reason == AutopilotReason.CLAIM_EXPIRED -> true
        from == TaskState.BLOCKED && to == TaskState.TASK_SUBMITTED -> context.blockedReason in PLAN_BLOCK_REASONS
        from == TaskState.PAUSED -> context.resumeState == to
        from == TaskState.BLOCKED && to != TaskState.PAUSED && to != TaskState.STOPPED && to != TaskState.TASK_SUBMITTED -> {
            val blocked = context.blockedReason
            blocked in DISPATCH_BLOCK_REASONS && (reason != AutopilotReason.GUARD_CLEARED || blocked in AUTO_CLEARABLE_REASONS)
        }
        else -> true
    }
    return if (guardHolds) TransitionDecision.Allowed else TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED)
}
