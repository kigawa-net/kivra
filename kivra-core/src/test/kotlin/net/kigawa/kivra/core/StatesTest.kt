package net.kigawa.kivra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatesTest {
    /**
     * The full DevelopmentRun transition table (design #9 section 3.3 + ADR 0008 item 6), written out here on purpose:
     * a change to States.kt that is not reflected here fails the all-pairs test below.
     */
    private val expectedEdges: Map<DevelopmentRunState, Set<DevelopmentRunState>> = mapOf(
        DevelopmentRunState.CREATED to setOf(DevelopmentRunState.PLANNING, DevelopmentRunState.DEVELOPING, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.PLANNING to setOf(DevelopmentRunState.PREPARING, DevelopmentRunState.RETRY_WAITING, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.PREPARING to setOf(DevelopmentRunState.DEVELOPING, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.DEVELOPING to setOf(
            DevelopmentRunState.TESTING, DevelopmentRunState.RETRY_WAITING, DevelopmentRunState.PATCH_READY,
            DevelopmentRunState.UNKNOWN, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED,
        ),
        DevelopmentRunState.TESTING to setOf(DevelopmentRunState.REVIEWING, DevelopmentRunState.DEVELOPING, DevelopmentRunState.RETRY_WAITING, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.REVIEWING to setOf(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.PATCH_READY to setOf(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.UNKNOWN, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.PULL_REQUEST to setOf(
            DevelopmentRunState.WAITING_APPROVAL, DevelopmentRunState.RELEASING, DevelopmentRunState.TESTING, DevelopmentRunState.RETRY_WAITING,
            DevelopmentRunState.ABANDONED, DevelopmentRunState.UNKNOWN, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED,
        ),
        DevelopmentRunState.WAITING_APPROVAL to setOf(DevelopmentRunState.RELEASING, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        DevelopmentRunState.RELEASING to setOf(DevelopmentRunState.VERIFYING, DevelopmentRunState.FAILED),
        DevelopmentRunState.VERIFYING to setOf(DevelopmentRunState.COMPLETED, DevelopmentRunState.FAILED),
        DevelopmentRunState.RETRY_WAITING to setOf(DevelopmentRunState.PLANNING, DevelopmentRunState.DEVELOPING, DevelopmentRunState.CANCELLED, DevelopmentRunState.FAILED),
        // UNKNOWN has no evidence-free exit. Its exits are in evidenceGated and are refused by the generic transition().
        DevelopmentRunState.UNKNOWN to emptySet(),
        DevelopmentRunState.COMPLETED to emptySet(),
        DevelopmentRunState.FAILED to emptySet(),
        DevelopmentRunState.CANCELLED to emptySet(),
        DevelopmentRunState.ABANDONED to emptySet(),
    )

    @Test
    fun `every pair of development run states is allowed exactly when the table says so`() {
        assertEquals(DevelopmentRunState.entries.toSet(), expectedEdges.keys, "the table covers every state")
        for (from in DevelopmentRunState.entries) {
            for (to in DevelopmentRunState.entries) {
                val allowed = to in (expectedEdges.getValue(from))
                assertEquals(allowed, from.canTransitionTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `self-development states are not terminal except ABANDONED, and UNKNOWN is active without an evidence-free exit`() {
        assertTrue(DevelopmentRunState.ABANDONED.isTerminal)
        assertFalse(DevelopmentRunState.UNKNOWN.isTerminal, "UNKNOWN is active: it holds the one-active-run key")
        assertTrue(DevelopmentRunState.UNKNOWN.next.isEmpty())
        assertEquals(setOf(DevelopmentRunState.PULL_REQUEST, DevelopmentRunState.FAILED, DevelopmentRunState.ABANDONED), DevelopmentRunState.UNKNOWN.evidenceGated)
        for (to in DevelopmentRunState.UNKNOWN.evidenceGated) {
            assertFalse(DevelopmentRunState.UNKNOWN.canTransitionTo(to), "$to needs verified evidence")
        }
        assertFalse(DevelopmentRunState.PATCH_READY.isTerminal)
        assertTrue(DevelopmentRunState.PATCH_READY.canTransitionTo(DevelopmentRunState.PULL_REQUEST))
        assertTrue(DevelopmentRunState.DEVELOPING.canTransitionTo(DevelopmentRunState.PATCH_READY))
        assertTrue(DevelopmentRunState.CREATED.canTransitionTo(DevelopmentRunState.DEVELOPING), "self-development start (ADR 0008)")
    }

    @Test
    fun `fromStorage round-trips every known name and returns null for an unknown one`() {
        for (state in DevelopmentRunState.entries) {
            assertEquals(state, DevelopmentRunState.fromStorage(state.name))
        }
        assertNull(DevelopmentRunState.fromStorage("FUTURE_STATE"))
        assertNull(DevelopmentRunState.fromStorage("completed"), "names are exact, not case folded")
    }

    @Test
    fun `development run terminal states have no transitions`() {
        assertEquals(setOf("COMPLETED", "FAILED", "CANCELLED", "ABANDONED"), DevelopmentRunState.terminalNames)
        DevelopmentRunState.entries.filter { it.isTerminal }.forEach { assertTrue(it.next.isEmpty()) }
    }

    @Test
    fun `development run follows design 9 section 3 3`() {
        assertTrue(DevelopmentRunState.CREATED.canTransitionTo(DevelopmentRunState.PLANNING))
        assertTrue(DevelopmentRunState.TESTING.canTransitionTo(DevelopmentRunState.DEVELOPING), "fix loop")
        assertTrue(DevelopmentRunState.REVIEWING.canTransitionTo(DevelopmentRunState.DEVELOPING), "changes requested")
        assertTrue(DevelopmentRunState.PULL_REQUEST.canTransitionTo(DevelopmentRunState.TESTING), "headSha changed")
        assertTrue(DevelopmentRunState.RETRY_WAITING.canTransitionTo(DevelopmentRunState.PLANNING))
        assertFalse(DevelopmentRunState.CREATED.canTransitionTo(DevelopmentRunState.TESTING))
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

    @Test
    fun `evidence refs are opaque and must be non-blank and bounded`() {
        assertFailsWith<IllegalArgumentException> { VerifiedEvidenceRef(" ") }
        assertFailsWith<IllegalArgumentException> { VerifiedEvidenceRef("x".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { EvidenceObservationRef("") }
    }
}
