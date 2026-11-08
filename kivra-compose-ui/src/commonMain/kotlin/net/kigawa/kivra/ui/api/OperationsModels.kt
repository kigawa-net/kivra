package net.kigawa.kivra.ui.api

import kotlinx.serialization.Serializable

/*
 * 運用の補助の応答の型(予算の為替、昇格条件、実行の経過)。Models.kt と分けて置く(共有ファイルの競合を減らすため)。
 * Unknown keys are ignored (ignoreUnknownKeys = true in ApiClient).
 */

/** `GET /api/admin/budget/fx` = server `BudgetFxResponse`。[fx] は為替が未設定なら null(USD を使わない構成)。 */
@Serializable
data class ApiBudgetFxResponse(
    val fx: ApiFxExpiry? = null,
)

/** Server `FxExpiryBody`: 基準日、残りの日数(失効日当日は 0 でまだ使える)、失効済みか。 */
@Serializable
data class ApiFxExpiry(
    val asOf: String,
    val daysLeft: Long,
    val expired: Boolean,
)

/** `GET /api/selfdev/autopilot/promotion` = server `PromotionReadoutResponse`。 */
@Serializable
data class ApiPromotionReadout(
    val conditions: List<ApiPromotionCondition>,
)

/**
 * One condition. [measured] false means the DB cannot show it: [current], [required] and [met] are then null.
 * Shape rules are in PromotionRules.kt (promotionShapeOk).
 */
@Serializable
data class ApiPromotionCondition(
    val id: String,
    val measured: Boolean,
    val current: Long? = null,
    val required: Long? = null,
    val met: Boolean? = null,
)

/**
 * `GET /api/admin/runs/{id}/execution` = server `RunExecutionSnapshot`(CLI 経路の Run の経過)。
 * Money fields are decimal strings and nullable here, so a malformed value is shown as unknown (see ExecutionRules.kt usdText).
 */
@Serializable
data class ApiRunExecution(
    val runId: String,
    val runState: String,
    val snapshotAt: String,
    val revision: ApiExecutionRevision,
    val freshness: String,
    val lastReceivedAt: String? = null,
    val attempts: List<ApiExecutionAttempt>,
    val stop: ApiExecutionStop,
    val tools: ApiExecutionTools,
    val turns: Int,
    val permissionDenied: Int,
    val warnings: Int,
    val dropped: Int,
    val truncated: Boolean,
    val lastPhase: String? = null,
    val testingReported: Boolean,
    val apiRetries: Map<String, Int> = emptyMap(),
    val cost: ApiExecutionCost,
)

@Serializable
data class ApiExecutionRevision(
    val attemptVersion: Long,
    val eventCount: Long,
)

@Serializable
data class ApiExecutionAttempt(
    val attemptId: String,
    val attemptNo: Int,
    val state: String,
    val leaseGeneration: Long,
    val lastReceivedAt: String? = null,
)

@Serializable
data class ApiExecutionStop(
    val stage: String,
    val runCancelRequested: Boolean,
    val agentAcknowledged: Boolean,
    val processExitConfirmed: Boolean,
)

@Serializable
data class ApiExecutionTools(
    val total: Int,
    val byName: Map<String, Int> = emptyMap(),
    val errors: Int,
)

@Serializable
data class ApiExecutionCost(
    val confirmedUsd: String? = null,
    val estimatedUsd: String? = null,
    val confirmedBasis: String? = null,
    val estimatedBasis: String? = null,
)
