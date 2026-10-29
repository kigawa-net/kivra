package net.kigawa.kivra.core.autopilot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Exhaustive checks of the unified autopilot state machine (V40 flat task model). The expected edges below are written out by hand,
 * separately from the code's table. A change to the code's table that is not also a change here fails the matrix tests.
 */
class AutopilotStatesTest {

    private val dispatchBlocks = setOf(
        AutopilotReason.PENDING_PR_LIMIT, AutopilotReason.DAILY_RUN_CAP, AutopilotReason.BUDGET_HOLD,
        AutopilotReason.CIRCUIT_OPEN, AutopilotReason.AUTO_SCOPE_EXCEEDED,
    )
    private val none: Set<AutopilotReason?> = setOf(null)

    /** Expected unified task edges: (from, to) -> the reasons allowed (null = no reason). A missing pair is not an edge. */
    private val expectedTask: Map<Pair<TaskState?, TaskState>, Set<AutopilotReason?>> = buildMap {
        // --- Submission & Planning ---
        put(null to TaskState.TASK_SUBMITTED, none)
        put(TaskState.TASK_SUBMITTED to TaskState.PLANNING, none)
        put(TaskState.PLANNING to TaskState.PENDING_APPROVAL, none)
        put(TaskState.PLANNING to TaskState.BLOCKED, setOf(AutopilotReason.PLAN_INVALID, AutopilotReason.PLAN_BUDGET_HOLD, AutopilotReason.PLAN_CALL_FAILED))
        put(TaskState.PLANNING to TaskState.TASK_SUBMITTED, setOf(AutopilotReason.CLAIM_EXPIRED))
        put(TaskState.BLOCKED to TaskState.TASK_SUBMITTED, setOf(AutopilotReason.REPLAN_REQUESTED))

        // --- Approval & Execution ---
        put(TaskState.PENDING_APPROVAL to TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_APPROVED, AutopilotReason.AUTO_APPROVED))
        put(TaskState.PENDING_APPROVAL to TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.TASK_STOPPED))
        put(TaskState.QUEUED to TaskState.SUBMITTING, none)
        put(TaskState.QUEUED to TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.TASK_STOPPED))
        put(TaskState.SUBMITTING to TaskState.SUBMITTED, none)
        put(TaskState.SUBMITTING to TaskState.QUEUED, setOf(AutopilotReason.ACTIVE_RUN_LIMIT))
        put(TaskState.SUBMITTED to TaskState.COMPLETED, setOf(AutopilotReason.PR_MERGED))
        put(TaskState.SUBMITTED to TaskState.FAILED, setOf(AutopilotReason.RUN_FAILED, AutopilotReason.POLICY_REJECTED, AutopilotReason.PR_ABANDONED))
        put(TaskState.SUBMITTED to TaskState.CANCELLED, setOf(AutopilotReason.RUN_RELEASED))
        put(TaskState.SUBMITTED to TaskState.PENDING_APPROVAL, setOf(AutopilotReason.AUTO_SCOPE_EXCEEDED))
        put(TaskState.FAILED to TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_REQUEUED))
        put(TaskState.FAILED to TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED))
        put(TaskState.CANCELLED to TaskState.QUEUED, setOf(AutopilotReason.OPERATOR_REQUEUED))
        put(TaskState.CANCELLED to TaskState.SKIPPED, setOf(AutopilotReason.OPERATOR_SKIPPED))

        // --- Dispatch guards ---
        for (from in listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED)) {
            put(from to TaskState.BLOCKED, dispatchBlocks)
        }
        val resume: Set<AutopilotReason?> = setOf(AutopilotReason.GUARD_CLEARED, AutopilotReason.OPERATOR_RESUMED)
        for (to in listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED)) {
            put(TaskState.BLOCKED to to, resume)
        }

        // --- Completion ---
        put(TaskState.COMPLETED to TaskState.DONE, setOf(AutopilotReason.ALL_TASKS_COMPLETED))

        // --- Pause/Resume ---
        for (from in listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED, TaskState.BLOCKED)) {
            put(from to TaskState.PAUSED, setOf(AutopilotReason.OPERATOR_PAUSED))
        }
        for (to in listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED, TaskState.BLOCKED)) {
            put(TaskState.PAUSED to to, setOf(AutopilotReason.OPERATOR_RESUMED))
        }

        // --- Stop ---
        for (from in listOf(
            TaskState.TASK_SUBMITTED, TaskState.PLANNING, TaskState.PENDING_APPROVAL,
            TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED,
            TaskState.BLOCKED, TaskState.PAUSED,
        )) {
            put(from to TaskState.STOPPED, setOf(AutopilotReason.OPERATOR_STOPPED))
        }
        // Dispatcher's terminal step: every task ended, not all COMPLETED.
        for (from in listOf(TaskState.QUEUED, TaskState.SUBMITTING, TaskState.SUBMITTED, TaskState.BLOCKED)) {
            put(from to TaskState.STOPPED, setOf(AutopilotReason.OPERATOR_STOPPED, AutopilotReason.TASKS_INCOMPLETE))
        }
    }

    /** A context in which every guard holds for [to] (a permissive setup, so the matrix checks the table alone). */
    private fun permissive(to: TaskState) = TaskContext(
        revision = 0,
        blockedReason = if (to == TaskState.TASK_SUBMITTED) AutopilotReason.PLAN_INVALID else AutopilotReason.PENDING_PR_LIMIT,
        resumeState = to,
    )

    @Test
    fun `every task pair and every reason code is decided exactly as the table says`() {
        val reasons: List<AutopilotReason?> = listOf(null) + AutopilotReason.entries
        var checked = 0
        for (from in listOf<TaskState?>(null) + TaskState.entries) {
            for (to in TaskState.entries) {
                val allowedReasons = expectedTask[from to to]
                for (reason in reasons) {
                    val decision = checkTaskTransition(from, to, reason, permissive(to))
                    val expected = if (allowedReasons != null && reason in allowedReasons) {
                        TransitionDecision.Allowed
                    } else if (allowedReasons == null) {
                        TransitionDecision.Refused(TransitionRefusal.NO_EDGE)
                    } else {
                        TransitionDecision.Refused(TransitionRefusal.REASON_NOT_ALLOWED)
                    }
                    assertEquals(expected, decision, "task $from -> $to reason=$reason")
                    checked++
                }
            }
        }
        assertEquals((TaskState.entries.size + 1) * TaskState.entries.size * (AutopilotReason.entries.size + 1), checked)
    }

    @Test
    fun `the next states of every state match the expected edges, and terminal states have none`() {
        for (state in TaskState.entries) {
            val expectedNext = expectedTask.keys.filter { it.first == state }.map { it.second }.toSet()
            assertEquals(expectedNext, state.next, "task next of $state")
        }
        // Terminal states: SKIPPED, DONE, STOPPED have no outgoing edges.
        // COMPLETED transitions to DONE, so it is not terminal in the unified model.
        assertTrue(TaskState.SKIPPED.next.isEmpty() && TaskState.SKIPPED.isTerminal)
        assertTrue(TaskState.DONE.next.isEmpty() && TaskState.DONE.isTerminal)
        assertTrue(TaskState.STOPPED.next.isEmpty() && TaskState.STOPPED.isTerminal)
    }

    @Test
    fun `a paused task returns only to the state it was paused from`() {
        assertEquals(
            TransitionDecision.Allowed,
            checkTaskTransition(TaskState.PAUSED, TaskState.SUBMITTED, AutopilotReason.OPERATOR_RESUMED, TaskContext(resumeState = TaskState.SUBMITTED)),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            checkTaskTransition(TaskState.PAUSED, TaskState.QUEUED, AutopilotReason.OPERATOR_RESUMED, TaskContext(resumeState = TaskState.SUBMITTED)),
        )
    }

    @Test
    fun `a blocked task resumes only through a dispatch guard, and only an automatic block clears on its own`() {
        fun blocked(reason: AutopilotReason?, to: TaskState, why: AutopilotReason?) =
            checkTaskTransition(TaskState.BLOCKED, to, why, TaskContext(blockedReason = reason))
        // Automatic blocks clear by GUARD_CLEARED or by the operator.
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.DAILY_RUN_CAP, TaskState.SUBMITTED, AutopilotReason.GUARD_CLEARED))
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.BUDGET_HOLD, TaskState.QUEUED, AutopilotReason.GUARD_CLEARED))
        // Manual blocks need the operator.
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.CIRCUIT_OPEN, TaskState.SUBMITTED, AutopilotReason.GUARD_CLEARED),
        )
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.CIRCUIT_OPEN, TaskState.SUBMITTED, AutopilotReason.OPERATOR_RESUMED))
        assertEquals(
            TransitionDecision.Allowed,
            blocked(AutopilotReason.AUTO_SCOPE_EXCEEDED, TaskState.QUEUED, AutopilotReason.OPERATOR_RESUMED),
        )
        // A planning-stage block has no edge back to dispatch.
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.PLAN_INVALID, TaskState.SUBMITTED, AutopilotReason.OPERATOR_RESUMED),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(null, TaskState.QUEUED, AutopilotReason.OPERATOR_RESUMED),
        )
        // U-16: a planning-stage block returns to TASK_SUBMITTED by the operator's replan, and only from a planning reason.
        assertEquals(
            TransitionDecision.Allowed,
            blocked(AutopilotReason.PLAN_INVALID, TaskState.TASK_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        assertEquals(
            TransitionDecision.Allowed,
            blocked(AutopilotReason.PLAN_CALL_FAILED, TaskState.TASK_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.DAILY_RUN_CAP, TaskState.TASK_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.CIRCUIT_OPEN, TaskState.TASK_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        // Pausing a blocked task and stopping it are not guarded by the block reason.
        assertEquals(TransitionDecision.Allowed, blocked(null, TaskState.PAUSED, AutopilotReason.OPERATOR_PAUSED))
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.PLAN_INVALID, TaskState.STOPPED, AutopilotReason.OPERATOR_STOPPED))
    }

    @Test
    fun `the dispatch block reasons and the automatic ones are the ADR sets`() {
        assertEquals(dispatchBlocks, DISPATCH_BLOCK_REASONS)
        assertEquals(setOf(AutopilotReason.PENDING_PR_LIMIT, AutopilotReason.DAILY_RUN_CAP, AutopilotReason.BUDGET_HOLD), AUTO_CLEARABLE_REASONS)
        assertEquals(setOf(AutopilotReason.PLAN_INVALID, AutopilotReason.PLAN_BUDGET_HOLD, AutopilotReason.PLAN_CALL_FAILED), PLAN_BLOCK_REASONS)
        assertEquals(3, MAX_REVISIONS)
    }
}
