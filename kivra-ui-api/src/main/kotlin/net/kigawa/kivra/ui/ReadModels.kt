package net.kigawa.kivra.ui

import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.OperationStatus
import net.kigawa.kivra.core.RunCompletionMode

enum class Attention {
    APPROVAL_REQUIRED, RETRY_WAIT, OPERATION_UNKNOWN, RECONCILIATION_REQUIRED,
    PROVIDER_DEGRADED, STALE_DATA,
}

enum class DataFreshness { FRESH, STALE, UNKNOWN }

data class ResourceRef(
    val type: String,
    val id: String,
    val tenantId: String,
    val workspaceId: String? = null,
    val projectId: String? = null,
)

data class ProviderRef(val name: String)
data class ResourceLink(val rel: String, val resource: ResourceRef)

data class RunSummary(
    val runId: String,
    val taskRef: ResourceRef?,
    val progress: DevelopmentRunState,
    val attention: Set<Attention>,
    val completionMode: RunCompletionMode,
    val revision: String,
    val links: List<ResourceLink> = emptyList(),
)

enum class ReplayReason {
    NOT_APPLICABLE, VERIFIED_NOT_SENT, AUTHORITATIVE_ABSENCE, AUTHORITATIVE_IDEMPOTENCY,
    EVIDENCE_REQUIRED, CAPABILITY_UNVERIFIED,
}

data class ReplayEligibility(val allowed: Boolean, val reason: ReplayReason) {
    init {
        require(!allowed || reason in SAFE_REASONS) {
            "replay may only be allowed with a verified safe reason"
        }
    }

    private companion object {
        val SAFE_REASONS = setOf(
            ReplayReason.VERIFIED_NOT_SENT,
            ReplayReason.AUTHORITATIVE_ABSENCE,
            ReplayReason.AUTHORITATIVE_IDEMPOTENCY,
        )
    }
}

data class OperationView(
    val operationId: String,
    val runId: String,
    val state: OperationStatus,
    val provider: ProviderRef,
    val replay: ReplayEligibility,
)

data class ReadEnvelope<T>(
    val schemaVersion: String = "ui.v1",
    val snapshotAt: String,
    val dataFreshness: DataFreshness,
    val partial: Boolean,
    val unavailableSources: List<ProviderRef>,
    val data: T,
)
