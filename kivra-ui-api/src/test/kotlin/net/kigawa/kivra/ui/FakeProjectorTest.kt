package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.OperationStatus

class FakeProjectorTest {
    @Test
    fun `unknown operation does not overwrite run progress`() {
        val view = FakeProjector.run(
            "run-1",
            DevelopmentRunState.DEVELOPING,
            listOf(OperationStatus.UNKNOWN),
        )
        assertEquals(DevelopmentRunState.DEVELOPING, view.progress)
        assertEquals(setOf(Attention.OPERATION_UNKNOWN), view.attention)
    }

    @Test
    fun `reconciliation required is attention and replay stays disabled`() {
        val view = FakeProjector.run(
            "run-2",
            DevelopmentRunState.TESTING,
            listOf(OperationStatus.RECONCILIATION_REQUIRED),
        )
        assertTrue(Attention.RECONCILIATION_REQUIRED in view.attention)
        assertFalse(FakeProjector.replay(OperationStatus.RECONCILIATION_REQUIRED).allowed)
    }

    @Test
    fun `phase zero DTO rejects any caller-minted replay approval`() {
        for (reason in ReplayReason.entries) {
            assertFailsWith<IllegalArgumentException> {
                ReplayEligibility(allowed = true, reason = reason)
            }
        }
        assertFalse(ReplayEligibility(false, ReplayReason.VERIFIED_NOT_SENT).allowed)
    }

    @Test
    fun `fake projector cannot mint replay authorization even for retryable state`() {
        val retryable = FakeProjector.replay(OperationStatus.RETRYABLE)
        assertFalse(retryable.allowed)
        assertEquals(ReplayReason.EVIDENCE_REQUIRED, retryable.reason)

        val unknown = FakeProjector.replay(OperationStatus.UNKNOWN)
        assertFalse(unknown.allowed)
        assertEquals(ReplayReason.NOT_APPLICABLE, unknown.reason)
        assertFalse(FakeProjector.replay(OperationStatus.RECONCILIATION_REQUIRED).allowed)
    }
}
