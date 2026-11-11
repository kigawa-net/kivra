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
        assertEquals(2, ChatActionCode.entries.size)
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
}
