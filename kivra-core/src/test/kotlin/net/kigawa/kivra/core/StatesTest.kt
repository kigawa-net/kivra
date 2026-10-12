package net.kigawa.kivra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `IN_FLIGHT has no evidence-free exit except CONFIRMED and UNKNOWN`() {
        // A timeout / 503 after sending does not prove non-execution, so RETRYABLE and FAILED need a proof API.
        assertEquals(setOf(OperationStatus.CONFIRMED, OperationStatus.UNKNOWN), OperationStatus.IN_FLIGHT.next)
        assertFalse(OperationStatus.IN_FLIGHT.canTransitionTo(OperationStatus.RETRYABLE))
        assertFalse(OperationStatus.IN_FLIGHT.canTransitionTo(OperationStatus.FAILED))
    }

    @Test
    fun `evidence must match the target state`() {
        fun ev(o: ExternalObservation, c: ProviderCapability = ProviderCapability.AUTHORITATIVE_ABSENCE) =
            ReconciliationEvidence(RunId("r"), "op", "h", "exkes-adapter", o, "ref", java.time.LocalDateTime.now(), c)
        assertTrue(ev(ExternalObservation.EXISTS).justifies(OperationStatus.CONFIRMED))
        assertFalse(ev(ExternalObservation.EXISTS).justifies(OperationStatus.RETRYABLE), "exists: never resend")
        assertTrue(ev(ExternalObservation.ABSENT).justifies(OperationStatus.RETRYABLE))
        assertTrue(ev(ExternalObservation.ABSENT, ProviderCapability.AUTHORITATIVE_IDEMPOTENCY).justifies(OperationStatus.RETRYABLE))
        assertFalse(ev(ExternalObservation.ABSENT, ProviderCapability.MANUAL_OPERATOR).justifies(OperationStatus.RETRYABLE))
        assertTrue(ev(ExternalObservation.FAILED_CONFIRMED).justifies(OperationStatus.FAILED))
        assertFailsWith<IllegalArgumentException> {
            ReconciliationEvidence(RunId("r"), "op", "h", "exkes-adapter", ExternalObservation.EXISTS, " ", java.time.LocalDateTime.now(), ProviderCapability.LOOKUP_BY_STABLE_REF)
        }
    }

    @Test
    fun `a plain lookup miss never justifies a re-dispatch (exkes POST has no Idempotency-Key)`() {
        // LOOKUP_BY_STABLE_REF list/get that finds nothing is only eventual-consistency evidence.
        val notFound = ReconciliationEvidence(
            RunId("r"), "op", "h", "exkes-adapter", ExternalObservation.ABSENT, "list:kivra-run-x", java.time.LocalDateTime.now(),
            ProviderCapability.LOOKUP_BY_STABLE_REF,
        )
        assertFalse(notFound.justifies(OperationStatus.RETRYABLE))
    }

    @Test
    fun `proofs require a non-blank issuer and evidence reference`() {
        assertFailsWith<IllegalArgumentException> {
            NotSentProof(RunId("r"), "op", "h", "", "ref", java.time.LocalDateTime.now())
        }
        assertFailsWith<IllegalArgumentException> {
            RejectedProof(RunId("r"), "op", "h", "exkes-adapter", "x".repeat(256), java.time.LocalDateTime.now())
        }
        assertTrue("exkes-adapter" in TrustedEvidenceIssuers.DEFAULT)
    }

    @Test
    fun `execution session UNKNOWN is left only on an observed state, never by a blind re-request`() {
        assertTrue(ExecutionSessionState.UNKNOWN.next.isEmpty())
        assertFalse(ExecutionSessionState.UNKNOWN.isTerminal, "UNKNOWN is not terminal: it can be observed out of")
        assertFalse(ExecutionSessionState.UNKNOWN.canTransitionTo(ExecutionSessionState.PROVISIONING), "no evidence-free exit")
        assertFalse(ExecutionSessionState.UNKNOWN.canTransitionTo(ExecutionSessionState.REQUESTED), "no evidence-free exit")
        assertEquals(ExecutionSessionState.PROVISIONING, ExecutionSessionState.UNKNOWN.leaveUnknownOn(ExecutionSessionState.PROVISIONING))
        assertEquals(ExecutionSessionState.EXECUTING, ExecutionSessionState.UNKNOWN.leaveUnknownOn(ExecutionSessionState.EXECUTING))
        assertNull(ExecutionSessionState.UNKNOWN.leaveUnknownOn(ExecutionSessionState.REQUESTED), "a REQUESTED observation does not re-request")
        assertNull(ExecutionSessionState.READY.leaveUnknownOn(ExecutionSessionState.EXECUTING), "only UNKNOWN has observed exits")
    }

    @Test
    fun `PR_ONLY runs complete only through the PR completion sources, never from VERIFYING or RELEASING`() {
        assertEquals(setOf(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.WAITING_APPROVAL), DevelopmentRunState.prOnlyCompletionSources)
        for (from in DevelopmentRunState.prOnlyCompletionSources) {
            assertFalse(from.canTransitionTo(DevelopmentRunState.COMPLETED), "$from -> COMPLETED is not a generic transition")
        }
        assertTrue(DevelopmentRunState.VERIFYING.canTransitionTo(DevelopmentRunState.COMPLETED), "RELEASE path is unchanged")
    }

    @Test
    fun `agent and session machines have terminal states`() {
        assertTrue(AgentState.COMPLETED.isTerminal)
        assertTrue(AgentState.RUNNING.canTransitionTo(AgentState.SUSPENDED))
        assertTrue(ExecutionSessionState.RELEASED.isTerminal)
        assertTrue(ExecutionSessionState.ORPHANED.canTransitionTo(ExecutionSessionState.RELEASING))
    }
}
