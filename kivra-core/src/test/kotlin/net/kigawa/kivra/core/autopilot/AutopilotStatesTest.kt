package net.kigawa.kivra.core.autopilot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Exhaustive checks of the autopilot state machines (ADR 0011 section 2.5). The expected edges below are written out by hand from
 * the ADR, separately from the code's table. A change to the code's table that is not also a change here fails the matrix tests.
 */
class AutopilotStatesTest {

    private val dispatchBlocks = setOf(
        AutopilotReason.PENDING_PR_LIMIT, AutopilotReason.DAILY_RUN_CAP, AutopilotReason.BUDGET_HOLD,
        AutopilotReason.CIRCUIT_OPEN, AutopilotReason.AUTO_SCOPE_EXCEEDED,
    )
    private val none: Set<AutopilotReason?> = setOf(null)

    /** Expected Goal edges: (from, to) -> the reasons allowed (null = no reason). A missing pair is not an edge. */
    private val expectedGoal: Map<Pair<GoalState?, GoalState>, Set<AutopilotReason?>> = buildMap {
        put(null to GoalState.GOAL_SUBMITTED, none)
        put(GoalState.GOAL_SUBMITTED to GoalState.PLANNING, none)
        put(GoalState.PLANNING to GoalState.PLAN_PROPOSED, none)
        put(GoalState.PLANNING to GoalState.BLOCKED, setOf(AutopilotReason.PLAN_INVALID, AutopilotReason.PLAN_BUDGET_HOLD, AutopilotReason.PLAN_CALL_FAILED))
        put(GoalState.PLANNING to GoalState.GOAL_SUBMITTED, setOf(AutopilotReason.CLAIM_EXPIRED))
        put(GoalState.PLAN_PROPOSED to GoalState.TASKS_QUEUED, none)
        put(GoalState.PLAN_PROPOSED to GoalState.GOAL_SUBMITTED, setOf(AutopilotReason.REPLAN_REQUESTED))
        put(GoalState.BLOCKED to GoalState.GOAL_SUBMITTED, setOf(AutopilotReason.REPLAN_REQUESTED))
        put(GoalState.TASKS_QUEUED to GoalState.RUNNING, none)
        put(GoalState.RUNNING to GoalState.BLOCKED, dispatchBlocks)
        put(GoalState.TASKS_QUEUED to GoalState.BLOCKED, dispatchBlocks)
        put(GoalState.BLOCKED to GoalState.RUNNING, setOf(AutopilotReason.GUARD_CLEARED, AutopilotReason.OPERATOR_RESUMED))
        put(GoalState.BLOCKED to GoalState.TASKS_QUEUED, setOf(AutopilotReason.GUARD_CLEARED, AutopilotReason.OPERATOR_RESUMED))
        put(GoalState.RUNNING to GoalState.DONE, setOf(AutopilotReason.ALL_TASKS_COMPLETED))
        for (from in listOf(GoalState.TASKS_QUEUED, GoalState.RUNNING, GoalState.BLOCKED)) {
            put(from to GoalState.PAUSED, setOf(AutopilotReason.OPERATOR_PAUSED))
        }
        for (to in listOf(GoalState.TASKS_QUEUED, GoalState.RUNNING, GoalState.BLOCKED)) {
            put(GoalState.PAUSED to to, setOf(AutopilotReason.OPERATOR_RESUMED))
        }
        for (from in listOf(
            GoalState.GOAL_SUBMITTED, GoalState.PLANNING, GoalState.PLAN_PROPOSED, GoalState.TASKS_QUEUED,
            GoalState.RUNNING, GoalState.BLOCKED, GoalState.PAUSED,
        )) {
            put(from to GoalState.STOPPED, setOf(AutopilotReason.OPERATOR_STOPPED))
        }
        // The dispatcher's terminal step: every task ended, not all COMPLETED (ADR 0011 section 2.8b).
        for (from in listOf(GoalState.TASKS_QUEUED, GoalState.RUNNING, GoalState.BLOCKED)) {
            put(from to GoalState.STOPPED, setOf(AutopilotReason.OPERATOR_STOPPED, AutopilotReason.TASKS_INCOMPLETE))
        }
    }

    private val expectedPlan: Map<Pair<PlanState?, PlanState>, Set<AutopilotReason?>> = mapOf(
        (null to PlanState.PROPOSED) to none,
        (null to PlanState.REJECTED) to setOf(AutopilotReason.PLAN_INVALID),
        (PlanState.PROPOSED to PlanState.SUPERSEDED) to setOf(AutopilotReason.REPLAN_REQUESTED),
    )

    private val expectedTask: Map<Pair<TaskState, TaskState>, Set<AutopilotReason?>> = mapOf(
        (TaskState.PENDING_APPROVAL to TaskState.QUEUED) to
            setOf(AutopilotReason.OPERATOR_APPROVED, AutopilotReason.AUTO_APPROVED),
        (TaskState.PENDING_APPROVAL to TaskState.SKIPPED) to setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.GOAL_STOPPED),
        (TaskState.QUEUED to TaskState.SUBMITTING) to none,
        (TaskState.QUEUED to TaskState.SKIPPED) to setOf(AutopilotReason.OPERATOR_SKIPPED, AutopilotReason.GOAL_STOPPED),
        (TaskState.SUBMITTING to TaskState.SUBMITTED) to none,
        (TaskState.SUBMITTING to TaskState.QUEUED) to setOf(AutopilotReason.ACTIVE_RUN_LIMIT),
        (TaskState.SUBMITTED to TaskState.COMPLETED) to setOf(AutopilotReason.PR_MERGED),
        (TaskState.SUBMITTED to TaskState.FAILED) to
            setOf(AutopilotReason.RUN_FAILED, AutopilotReason.POLICY_REJECTED, AutopilotReason.PR_ABANDONED),
        (TaskState.SUBMITTED to TaskState.CANCELLED) to setOf(AutopilotReason.RUN_RELEASED),
        (TaskState.SUBMITTED to TaskState.PENDING_APPROVAL) to setOf(AutopilotReason.AUTO_SCOPE_EXCEEDED),
        (TaskState.FAILED to TaskState.QUEUED) to setOf(AutopilotReason.OPERATOR_REQUEUED),
        (TaskState.FAILED to TaskState.SKIPPED) to setOf(AutopilotReason.OPERATOR_SKIPPED),
        (TaskState.CANCELLED to TaskState.QUEUED) to setOf(AutopilotReason.OPERATOR_REQUEUED),
        (TaskState.CANCELLED to TaskState.SKIPPED) to setOf(AutopilotReason.OPERATOR_SKIPPED),
    )

    /** A context in which every guard holds for [to] (a permissive setup, so the matrix checks the table alone). */
    private fun permissive(to: GoalState) = GoalContext(
        revision = 0,
        // A planning block is the only block that can return to GOAL_SUBMITTED; a dispatch block is the one that clears to dispatch.
        blockedReason = if (to == GoalState.GOAL_SUBMITTED) AutopilotReason.PLAN_INVALID else AutopilotReason.PENDING_PR_LIMIT,
        resumeState = to,
    )

    @Test
    fun `every goal pair and every reason code is decided exactly as the ADR table says`() {
        val reasons: List<AutopilotReason?> = listOf(null) + AutopilotReason.entries
        var checked = 0
        for (from in listOf<GoalState?>(null) + GoalState.entries) {
            for (to in GoalState.entries) {
                val allowedReasons = expectedGoal[from to to]
                for (reason in reasons) {
                    val decision = checkGoalTransition(from, to, reason, permissive(to))
                    val expected = if (allowedReasons != null && reason in allowedReasons) {
                        TransitionDecision.Allowed
                    } else if (allowedReasons == null) {
                        TransitionDecision.Refused(TransitionRefusal.NO_EDGE)
                    } else {
                        TransitionDecision.Refused(TransitionRefusal.REASON_NOT_ALLOWED)
                    }
                    assertEquals(expected, decision, "goal $from -> $to reason=$reason")
                    checked++
                }
            }
        }
        assertEquals((GoalState.entries.size + 1) * GoalState.entries.size * (AutopilotReason.entries.size + 1), checked)
    }

    @Test
    fun `every plan pair and every reason code is decided exactly as the ADR table says`() {
        val reasons: List<AutopilotReason?> = listOf(null) + AutopilotReason.entries
        for (from in listOf<PlanState?>(null) + PlanState.entries) {
            for (to in PlanState.entries) {
                val allowedReasons = expectedPlan[from to to]
                for (reason in reasons) {
                    val decision = checkPlanTransition(from, to, reason)
                    val expected = when {
                        allowedReasons == null -> TransitionDecision.Refused(TransitionRefusal.NO_EDGE)
                        reason in allowedReasons -> TransitionDecision.Allowed
                        else -> TransitionDecision.Refused(TransitionRefusal.REASON_NOT_ALLOWED)
                    }
                    assertEquals(expected, decision, "plan $from -> $to reason=$reason")
                }
            }
        }
    }

    @Test
    fun `every task pair and every reason code is decided exactly as the ADR table says`() {
        val reasons: List<AutopilotReason?> = listOf(null) + AutopilotReason.entries
        for (from in TaskState.entries) {
            for (to in TaskState.entries) {
                val allowedReasons = expectedTask[from to to]
                for (reason in reasons) {
                    val decision = checkTaskTransition(from, to, reason)
                    val expected = when {
                        allowedReasons == null -> TransitionDecision.Refused(TransitionRefusal.NO_EDGE)
                        reason in allowedReasons -> TransitionDecision.Allowed
                        else -> TransitionDecision.Refused(TransitionRefusal.REASON_NOT_ALLOWED)
                    }
                    assertEquals(expected, decision, "task $from -> $to reason=$reason")
                }
            }
        }
    }

    @Test
    fun `the next states of every state match the expected edges, and terminal states have none`() {
        for (state in GoalState.entries) {
            val expectedNext = expectedGoal.keys.filter { it.first == state }.map { it.second }.toSet()
            assertEquals(expectedNext, state.next, "goal next of $state")
        }
        assertTrue(GoalState.DONE.next.isEmpty() && GoalState.DONE.isTerminal)
        assertTrue(GoalState.STOPPED.next.isEmpty() && GoalState.STOPPED.isTerminal)
        for (state in PlanState.entries) {
            val expectedNext = expectedPlan.keys.filter { it.first == state }.map { it.second }.toSet()
            assertEquals(expectedNext, state.next, "plan next of $state")
        }
        for (state in TaskState.entries) {
            val expectedNext = expectedTask.keys.filter { it.first == state }.map { it.second }.toSet()
            assertEquals(expectedNext, state.next, "task next of $state")
        }
        assertTrue(TaskState.COMPLETED.isTerminal && TaskState.SKIPPED.isTerminal)
        assertTrue(PlanState.SUPERSEDED.isTerminal && PlanState.REJECTED.isTerminal)
    }

    @Test
    fun `a revision at the cap refuses a replan, and one below it allows the replan`() {
        assertEquals(
            TransitionDecision.Allowed,
            checkGoalTransition(GoalState.PLAN_PROPOSED, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED, GoalContext(revision = 2)),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            checkGoalTransition(GoalState.PLAN_PROPOSED, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED, GoalContext(revision = MAX_REVISIONS)),
        )
    }

    @Test
    fun `a paused goal returns only to the state it was paused from`() {
        assertEquals(
            TransitionDecision.Allowed,
            checkGoalTransition(GoalState.PAUSED, GoalState.RUNNING, AutopilotReason.OPERATOR_RESUMED, GoalContext(resumeState = GoalState.RUNNING)),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            checkGoalTransition(GoalState.PAUSED, GoalState.TASKS_QUEUED, AutopilotReason.OPERATOR_RESUMED, GoalContext(resumeState = GoalState.RUNNING)),
        )
    }

    @Test
    fun `a blocked goal resumes only through a dispatch guard, and only an automatic block clears on its own`() {
        fun blocked(reason: AutopilotReason?, to: GoalState, why: AutopilotReason?) =
            checkGoalTransition(GoalState.BLOCKED, to, why, GoalContext(blockedReason = reason))
        // Automatic blocks clear by GUARD_CLEARED or by the operator.
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.DAILY_RUN_CAP, GoalState.RUNNING, AutopilotReason.GUARD_CLEARED))
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.BUDGET_HOLD, GoalState.TASKS_QUEUED, AutopilotReason.GUARD_CLEARED))
        // Manual blocks need the operator.
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.CIRCUIT_OPEN, GoalState.RUNNING, AutopilotReason.GUARD_CLEARED),
        )
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.CIRCUIT_OPEN, GoalState.RUNNING, AutopilotReason.OPERATOR_RESUMED))
        assertEquals(
            TransitionDecision.Allowed,
            blocked(AutopilotReason.AUTO_SCOPE_EXCEEDED, GoalState.TASKS_QUEUED, AutopilotReason.OPERATOR_RESUMED),
        )
        // A planning-stage block has no edge back to dispatch.
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.PLAN_INVALID, GoalState.RUNNING, AutopilotReason.OPERATOR_RESUMED),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(null, GoalState.TASKS_QUEUED, AutopilotReason.OPERATOR_RESUMED),
        )
        // U-16: a planning-stage block returns to GOAL_SUBMITTED by the operator's replan, and only from a planning reason.
        assertEquals(
            TransitionDecision.Allowed,
            blocked(AutopilotReason.PLAN_INVALID, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        assertEquals(
            TransitionDecision.Allowed,
            blocked(AutopilotReason.PLAN_CALL_FAILED, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.DAILY_RUN_CAP, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        assertEquals(
            TransitionDecision.Refused(TransitionRefusal.GUARD_FAILED),
            blocked(AutopilotReason.CIRCUIT_OPEN, GoalState.GOAL_SUBMITTED, AutopilotReason.REPLAN_REQUESTED),
        )
        // Pausing a blocked goal and stopping it are not guarded by the block reason.
        assertEquals(TransitionDecision.Allowed, blocked(null, GoalState.PAUSED, AutopilotReason.OPERATOR_PAUSED))
        assertEquals(TransitionDecision.Allowed, blocked(AutopilotReason.PLAN_INVALID, GoalState.STOPPED, AutopilotReason.OPERATOR_STOPPED))
    }

    @Test
    fun `the dispatch block reasons and the automatic ones are the ADR sets`() {
        assertEquals(dispatchBlocks, DISPATCH_BLOCK_REASONS)
        assertEquals(setOf(AutopilotReason.PENDING_PR_LIMIT, AutopilotReason.DAILY_RUN_CAP, AutopilotReason.BUDGET_HOLD), AUTO_CLEARABLE_REASONS)
        assertEquals(setOf(AutopilotReason.PLAN_INVALID, AutopilotReason.PLAN_BUDGET_HOLD, AutopilotReason.PLAN_CALL_FAILED), PLAN_BLOCK_REASONS)
        assertEquals(3, MAX_REVISIONS)
    }
}
