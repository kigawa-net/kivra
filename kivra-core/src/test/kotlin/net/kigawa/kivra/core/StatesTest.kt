package net.kigawa.kivra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatesTest {
    @Test
    fun `task run terminal states have no transitions`() {
        assertEquals(setOf("SUCCEEDED", "FAILED", "CANCELLED"), TaskRunState.terminalNames)
        TaskRunState.entries.filter { it.isTerminal }.forEach { assertTrue(it.next.isEmpty()) }
    }

    @Test
    fun `task run follows the documented transitions`() {
        assertTrue(TaskRunState.ACCEPTED.canTransitionTo(TaskRunState.PLANNING))
        assertTrue(TaskRunState.RUNNING.canTransitionTo(TaskRunState.RECONCILING))
        assertTrue(TaskRunState.RECONCILING.canTransitionTo(TaskRunState.FAILED))
        assertFalse(TaskRunState.ACCEPTED.canTransitionTo(TaskRunState.RUNNING))
        assertFalse(TaskRunState.SUCCEEDED.canTransitionTo(TaskRunState.RUNNING))
    }

    @Test
    fun `operation journal follows design 11 section 4 1`() {
        assertTrue(OperationStatus.PREPARED.canTransitionTo(OperationStatus.IN_FLIGHT))
        assertTrue(OperationStatus.IN_FLIGHT.canTransitionTo(OperationStatus.UNKNOWN))
        assertTrue(OperationStatus.UNKNOWN.canTransitionTo(OperationStatus.RECONCILIATION_REQUIRED))
        assertFalse(OperationStatus.PREPARED.canTransitionTo(OperationStatus.CONFIRMED))
        assertFalse(OperationStatus.CONFIRMED.canTransitionTo(OperationStatus.IN_FLIGHT))
        assertTrue(OperationStatus.CONFIRMED.isTerminal && OperationStatus.FAILED.isTerminal)
    }

    @Test
    fun `agent and session machines have terminal states`() {
        assertTrue(AgentState.COMPLETED.isTerminal)
        assertTrue(AgentState.RUNNING.canTransitionTo(AgentState.SUSPENDED))
        assertTrue(ExecutionSessionState.RELEASED.isTerminal)
        assertTrue(ExecutionSessionState.ORPHANED.canTransitionTo(ExecutionSessionState.RELEASING))
    }
}
