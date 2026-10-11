package net.kigawa.kivra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatesTest {
    @Test
    fun `development run terminal states have no transitions`() {
        assertEquals(setOf("COMPLETED", "FAILED", "CANCELLED"), DevelopmentRunState.terminalNames)
        DevelopmentRunState.entries.filter { it.isTerminal }.forEach { assertTrue(it.next.isEmpty()) }
    }

    @Test
    fun `development run follows design 9 section 3 3`() {
        assertTrue(DevelopmentRunState.CREATED.canTransitionTo(DevelopmentRunState.PLANNING))
        assertTrue(DevelopmentRunState.TESTING.canTransitionTo(DevelopmentRunState.DEVELOPING), "fix loop")
        assertTrue(DevelopmentRunState.REVIEWING.canTransitionTo(DevelopmentRunState.DEVELOPING), "changes requested")
        assertTrue(DevelopmentRunState.PULL_REQUEST.canTransitionTo(DevelopmentRunState.TESTING), "headSha changed")
        assertTrue(DevelopmentRunState.RETRY_WAITING.canTransitionTo(DevelopmentRunState.PLANNING))
        assertFalse(DevelopmentRunState.CREATED.canTransitionTo(DevelopmentRunState.DEVELOPING))
        assertFalse(DevelopmentRunState.RELEASING.canTransitionTo(DevelopmentRunState.CANCELLED), "release is rolled back through DEC, not cancelled")
        assertFalse(DevelopmentRunState.COMPLETED.canTransitionTo(DevelopmentRunState.DEVELOPING))
    }

    @Test
    fun `operation journal follows design 11 section 4 1`() {
        assertTrue(OperationStatus.PREPARED.canTransitionTo(OperationStatus.IN_FLIGHT))
        assertTrue(OperationStatus.IN_FLIGHT.canTransitionTo(OperationStatus.UNKNOWN))
        assertTrue(OperationStatus.UNKNOWN.canTransitionTo(OperationStatus.RECONCILIATION_REQUIRED))
        // Fail-closed: UNKNOWN never reaches RETRYABLE / CONFIRMED / FAILED without evidence.
        assertFalse(OperationStatus.UNKNOWN.canTransitionTo(OperationStatus.RETRYABLE))
        assertFalse(OperationStatus.UNKNOWN.canTransitionTo(OperationStatus.CONFIRMED))
        assertEquals(setOf(OperationStatus.CONFIRMED, OperationStatus.RETRYABLE, OperationStatus.FAILED), OperationStatus.UNKNOWN.evidenceGated)
        assertFalse(OperationStatus.RECONCILIATION_REQUIRED.isTerminal)
        assertFalse(OperationStatus.PREPARED.canTransitionTo(OperationStatus.CONFIRMED))
        assertFalse(OperationStatus.CONFIRMED.canTransitionTo(OperationStatus.IN_FLIGHT))
        assertTrue(OperationStatus.CONFIRMED.isTerminal && OperationStatus.FAILED.isTerminal)
    }

    @Test
    fun `evidence must match the target state`() {
        fun ev(o: ExternalObservation, c: ProviderCapability = ProviderCapability.LOOKUP_BY_STABLE_REF) =
            ReconciliationEvidence(o, "ref", java.time.LocalDateTime.now(), c)
        assertTrue(ev(ExternalObservation.EXISTS).justifies(OperationStatus.CONFIRMED))
        assertFalse(ev(ExternalObservation.EXISTS).justifies(OperationStatus.RETRYABLE), "exists: never resend")
        assertTrue(ev(ExternalObservation.ABSENT).justifies(OperationStatus.RETRYABLE))
        assertFalse(ev(ExternalObservation.ABSENT, ProviderCapability.MANUAL_OPERATOR).justifies(OperationStatus.RETRYABLE))
        assertTrue(ev(ExternalObservation.FAILED_CONFIRMED).justifies(OperationStatus.FAILED))
        assertFailsWith<IllegalArgumentException> { ReconciliationEvidence(ExternalObservation.EXISTS, " ", java.time.LocalDateTime.now(), ProviderCapability.LOOKUP_BY_STABLE_REF) }
    }

    @Test
    fun `agent and session machines have terminal states`() {
        assertTrue(AgentState.COMPLETED.isTerminal)
        assertTrue(AgentState.RUNNING.canTransitionTo(AgentState.SUSPENDED))
        assertTrue(ExecutionSessionState.RELEASED.isTerminal)
        assertTrue(ExecutionSessionState.ORPHANED.canTransitionTo(ExecutionSessionState.RELEASING))
    }
}
