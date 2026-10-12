package net.kigawa.kivra.ui

import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunCompletionMode

object FakeProjector {
    fun run(
        runId: String,
        progress: DevelopmentRunState,
        operationStates: Collection<OperationStatus> = emptyList(),
        approvalRequired: Boolean = false,
        providerDegraded: Boolean = false,
        stale: Boolean = false,
        completionMode: RunCompletionMode = RunCompletionMode.PR_ONLY,
        revision: String = "fake-1",
    ): RunSummary {
        val attention = buildSet {
            if (approvalRequired) add(Attention.APPROVAL_REQUIRED)
            if (progress == DevelopmentRunState.RETRY_WAITING) add(Attention.RETRY_WAIT)
            if (OperationStatus.UNKNOWN in operationStates) add(Attention.OPERATION_UNKNOWN)
            if (OperationStatus.RECONCILIATION_REQUIRED in operationStates) add(Attention.RECONCILIATION_REQUIRED)
            if (providerDegraded) add(Attention.PROVIDER_DEGRADED)
            if (stale) add(Attention.STALE_DATA)
        }
        return RunSummary(runId, null, progress, attention, completionMode, revision)
    }

    /**
     * Fake data cannot issue an authoritative replay decision. A reason enum is
     * display metadata, NOT proof. Only a trusted Evidence Authority backed by
     * an authenticated, subject-bound reference may later enable a real action.
     * See Kivra #82 and #79. Phase 0 intentionally exposes no command API.
     */
    fun replay(state: OperationStatus): ReplayEligibility =
        if (state == OperationStatus.RETRYABLE)
            ReplayEligibility(false, ReplayReason.EVIDENCE_REQUIRED)
        else
            ReplayEligibility(false, ReplayReason.NOT_APPLICABLE)
}
