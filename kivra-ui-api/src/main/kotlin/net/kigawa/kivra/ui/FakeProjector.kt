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

    fun replay(state: OperationStatus, verifiedReason: ReplayReason? = null): ReplayEligibility =
        when {
            state != OperationStatus.RETRYABLE -> ReplayEligibility(false, ReplayReason.NOT_APPLICABLE)
            verifiedReason == null -> ReplayEligibility(false, ReplayReason.EVIDENCE_REQUIRED)
            else -> ReplayEligibility(verifiedReason in setOf(
                ReplayReason.VERIFIED_NOT_SENT,
                ReplayReason.AUTHORITATIVE_ABSENCE,
                ReplayReason.AUTHORITATIVE_IDEMPOTENCY,
            ), verifiedReason)
        }
}
