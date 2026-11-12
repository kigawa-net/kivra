package net.kigawa.kivra.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatActionsTest {
    private fun order(
        id: String = "O1",
        state: String = "ACTIVE",
        version: Long = 3,
        goal: String? = null,
        started: Int = 0,
        max: Int = 2,
        expired: Boolean = false,
        stuck: Boolean = false,
    ) = ChatOrderFacts(id, state, version, goal, started, max, expired, stuck)

    @Test
    fun aRegistryCodeHasAFixedJapaneseLabel() {
        assertEquals("周を開始する", ChatActionCode.ORDER_START_CYCLE.label)
        assertEquals("進行中の周を放棄する", ChatActionCode.ORDER_ABANDON_CYCLE.label)
        assertEquals(5, ChatActionCode.entries.size)
    }

    @Test
    fun anActiveOrderWithoutACycleGetsAStartAtItsVersion() {
        val p = proposeChatActions(listOf(order(version = 7)))
        assertEquals(listOf(ChatActionProposal(ChatActionCode.ORDER_START_CYCLE, "O1", 7)), p)
    }

    @Test
    fun noStartWhenTheOrderIsPausedExpiredOrAtItsCycleLimit() {
        assertTrue(proposeChatActions(listOf(order(state = "PAUSED"))).isEmpty())
        assertTrue(proposeChatActions(listOf(order(state = "STOPPED"))).isEmpty())
        assertTrue(proposeChatActions(listOf(order(expired = true))).isEmpty())
        assertTrue(proposeChatActions(listOf(order(started = 2, max = 2))).isEmpty())
    }

    @Test
    fun aStartIsProposedEvenAfterFailuresBecauseTheCheckIsOnlyForTheAutomaticStart() {
        // The failure count is not a parameter of the rule: the operator's manual start stays allowed.
        assertEquals(1, proposeChatActions(listOf(order())).size)
    }

    @Test
    fun anAbandonIsProposedOnlyForAStuckCycleOfAnActiveOrder() {
        val stuck = proposeChatActions(listOf(order(goal = "T1", stuck = true)))
        assertEquals(listOf(ChatActionProposal(ChatActionCode.ORDER_ABANDON_CYCLE, "O1", 3)), stuck)
        assertTrue(proposeChatActions(listOf(order(goal = "T1", stuck = false))).isEmpty())
        assertTrue(proposeChatActions(listOf(order(state = "PAUSED", goal = "T1", stuck = true))).isEmpty())
    }

    @Test
    fun aCycleInProgressGetsNoStart() {
        val p = proposeChatActions(listOf(order(goal = "T1", stuck = true)))
        assertFalse(p.any { it.code == ChatActionCode.ORDER_START_CYCLE })
    }

    @Test
    fun aCycleIsStuckOnlyWhenEveryOpenChildIsQueuedBehindAFailedDependency() {
        val states = mapOf("F" to "FAILED", "S" to "STOPPED", "R" to "RUNNING")
        val stateOf: (String) -> String? = { states[it] }
        val blocked = listOf(ChatTaskFact("C1", "QUEUED", listOf("F")), ChatTaskFact("C2", "QUEUED", listOf("S")))
        assertTrue(isCycleStuck(rootEnded = false, children = blocked, stateOf = stateOf))
        // One open child that can still run: not stuck.
        val oneRunnable = blocked + ChatTaskFact("C3", "QUEUED", listOf("R"))
        assertFalse(isCycleStuck(rootEnded = false, children = oneRunnable, stateOf = stateOf))
        // A child that is RUNNING, BLOCKED or PLANNING is progress or a wait the rule does not judge: not stuck.
        assertFalse(isCycleStuck(false, listOf(ChatTaskFact("C1", "RUNNING", listOf("F"))), stateOf))
        assertFalse(isCycleStuck(false, listOf(ChatTaskFact("C1", "BLOCKED", listOf("F"))), stateOf))
        // A QUEUED child with no failed dependency, or an unknown one: not stuck.
        assertFalse(isCycleStuck(false, listOf(ChatTaskFact("C1", "QUEUED", listOf("R"))), stateOf))
        assertFalse(isCycleStuck(false, listOf(ChatTaskFact("C1", "QUEUED", listOf("X"))), stateOf))
    }

    @Test
    fun endedChildrenAndAnEndedRootAreNotStuck() {
        val stateOf: (String) -> String? = { "FAILED" }
        // No open child: nothing waits.
        assertFalse(isCycleStuck(false, listOf(ChatTaskFact("C1", "FAILED", listOf("F"))), stateOf))
        assertFalse(isCycleStuck(false, emptyList(), stateOf))
        // An ended root is the evaluator's to close.
        assertFalse(isCycleStuck(true, listOf(ChatTaskFact("C1", "QUEUED", listOf("F"))), stateOf))
    }

    private fun task(state: String = "BLOCKED", reason: String? = "PLAN_INVALID", version: Long = 4) =
        ChatCycleTaskFact("T1", version, state, reason)

    @Test
    fun aTaskRegistryCodeHasAFixedLabelAndTargetsTheTask() {
        assertEquals("計画に失敗した周を再計画する", ChatActionCode.TASK_REPLAN.label)
        assertEquals(ChatTargetKind.TASK, ChatActionCode.TASK_REPLAN.targetKind)
        assertEquals(ChatTargetKind.ORDER, ChatActionCode.ORDER_START_CYCLE.targetKind)
        assertEquals(ChatTargetKind.ORDER, ChatActionCode.ORDER_ABANDON_CYCLE.targetKind)
        assertEquals(5, ChatActionCode.entries.size)
    }

    @Test
    fun aBlockedCycleRootForAPlanningReasonIsProposedAtTheTaskVersion() {
        for (reason in listOf("PLAN_INVALID", "PLAN_BUDGET_HOLD", "PLAN_CALL_FAILED")) {
            val p = proposeChatActions(listOf(order(goal = "T1", version = 9).copy(cycleTask = task(reason = reason, version = 4))))
            // The order has a cycle in progress, so no start; the replan targets the Task at the Task's version, not the order's (9).
            assertEquals(listOf(ChatActionProposal(ChatActionCode.TASK_REPLAN, "T1", 4)), p, reason)
        }
    }

    @Test
    fun otherStatesAndReasonsGetNoReplan() {
        val cases = listOf(
            task(state = "PLANNING", reason = null),
            task(state = "TASK_SUBMITTED", reason = "REPLAN_REQUESTED"),
            task(state = "BLOCKED", reason = "CLAIM_EXPIRED"),
            task(state = "BLOCKED", reason = "PENDING_PR_LIMIT"),
            task(state = "BLOCKED", reason = null),
            task(state = "FAILED", reason = "PLAN_INVALID"),
        )
        for (t in cases) {
            val p = proposeChatActions(listOf(order(goal = "T1").copy(cycleTask = t)))
            assertFalse(p.any { it.code == ChatActionCode.TASK_REPLAN }, t.toString())
        }
    }

    @Test
    fun aReplanIsOnlyForAnActiveOrder() {
        val paused = order(state = "PAUSED", goal = "T1").copy(cycleTask = task())
        val stopped = order(state = "STOPPED", goal = "T1").copy(cycleTask = task())
        assertTrue(proposeChatActions(listOf(paused, stopped)).none { it.code == ChatActionCode.TASK_REPLAN })
        assertEquals(1, proposeChatActions(listOf(order(goal = "T1").copy(cycleTask = task()))).count { it.code == ChatActionCode.TASK_REPLAN })
    }

    @Test
    fun theReplanRuleAcceptsTheSamePlanningReasonsAsTheGoalPage() {
        assertEquals(setOf("PLAN_INVALID", "PLAN_BUDGET_HOLD", "PLAN_CALL_FAILED"), REPLANNABLE_BLOCK_REASONS)
    }

    private fun approval(id: String, state: String = "PENDING_APPROVAL", version: Long = 2, planRoot: Boolean = false) =
        ChatApprovalFact(id, version, state, planRoot)

    @Test
    fun aWaitingChildGetsBothApproveAndRejectAtItsVersion() {
        val p = proposeChatActions(listOf(order(goal = "T1", stuck = false).copy(approvals = listOf(approval("C1", version = 5)))))
        assertEquals(
            listOf(
                ChatActionProposal(ChatActionCode.TASK_APPROVE, "C1", 5),
                ChatActionProposal(ChatActionCode.TASK_REJECT, "C1", 5),
            ),
            p,
        )
    }

    @Test
    fun aPlanRootOrAnotherStateOfAChildGetsNoApprovalProposal() {
        val children = listOf(
            approval("PLAN", planRoot = true),
            approval("QUEUED", state = "QUEUED"),
            approval("RUNNING", state = "RUNNING"),
            approval("SKIPPED", state = "SKIPPED"),
            approval("FAILED", state = "FAILED"),
        )
        val p = proposeChatActions(listOf(order(goal = "T1").copy(approvals = children)))
        assertTrue(p.none { it.code == ChatActionCode.TASK_APPROVE || it.code == ChatActionCode.TASK_REJECT }, p.toString())
    }

    @Test
    fun noApprovalProposalWithoutACycleOrForANotActiveOrder() {
        val waiting = listOf(approval("C1"))
        // No cycle in progress: the children are not read, and a start is what the order gets.
        val noCycle = proposeChatActions(listOf(order().copy(approvals = waiting)))
        assertTrue(noCycle.none { it.code == ChatActionCode.TASK_APPROVE || it.code == ChatActionCode.TASK_REJECT })
        for (state in listOf("PAUSED", "STOPPED", "STOP_REQUESTED")) {
            val p = proposeChatActions(listOf(order(state = state, goal = "T1").copy(approvals = waiting)))
            assertTrue(p.none { it.code == ChatActionCode.TASK_APPROVE || it.code == ChatActionCode.TASK_REJECT }, state)
        }
    }

    @Test
    fun theApprovalRuleIsPendingApprovalAndNotAPlanRoot() {
        assertTrue(isApprovable(approval("C1")))
        assertFalse(isApprovable(approval("C1", planRoot = true)))
        assertFalse(isApprovable(approval("C1", state = "QUEUED")))
    }

    @Test
    fun theApprovalReasonTextIsFixedPerKnownReason() {
        assertEquals("自動実行の範囲を超える変更があったため、承認が必要です。", approvalReasonText("AUTO_SCOPE_EXCEEDED"))
        assertEquals("この Task は実行前に承認が必要です。", approvalReasonText(null))
        assertEquals("承認待ちの Task です。", approvalReasonText("SOMETHING_NEW"))
    }
}
