package net.kigawa.kivra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `retryable still requires verified replay proof`() {
        assertFalse(FakeProjector.replay(OperationStatus.RETRYABLE).allowed)
        assertTrue(
            FakeProjector.replay(
                OperationStatus.RETRYABLE,
                ReplayReason.AUTHORITATIVE_IDEMPOTENCY,
            ).allowed,
        )
    }
}
