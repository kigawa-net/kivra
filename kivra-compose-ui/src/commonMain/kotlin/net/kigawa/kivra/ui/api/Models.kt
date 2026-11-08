package net.kigawa.kivra.ui.api

import kotlinx.serialization.Serializable

/**
 * API response models for the Compose UI.
 * These mirror the server-side @Serializable classes but are defined in the UI module
 * to avoid depending on kivra-app. Each class names the server class it mirrors.
 * Unknown keys are ignored (ignoreUnknownKeys = true in ApiClient), so only the fields the UI reads are listed where noted.
 */

/**
 * `GET /api/selfdev/me` (server `MeResponse`). mode は off / shadow / enforce、role と permissions は権限の名前(文字列)。
 * 判定は PermissionRules.kt の CallerPermissions.of で行う。
 */
@Serializable
data class ApiMe(
    val mode: String,
    val role: String,
    val permissions: List<String>,
    val source: String? = null,
)

@Serializable
data class ApiRun(
    val runId: String,
    val taskId: String,
    val title: String,
    val state: String,
    val mode: String,
    val executed: Boolean?,
    val effect: String,
    val reasonCode: String?,
    val createdAt: String,
    val updatedAt: String,
    val pullRequestRepository: String? = null,
    val pullRequestNumber: Long? = null,
    val pullRequestUrl: String? = null,
    val taskSource: String? = null,
    val createdBy: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    /** Only in a detail response (`GET /api/runs/{id}`): the run's audit events, in order. Null in a list. */
    val events: List<ApiRunEvent>? = null,
)

/** Server `RunEventResponse`: one audit event of a run. [toState] and [reasonCode] are set only for a state transition. */
@Serializable
data class ApiRunEvent(
    val seq: Long,
    val occurredAt: String,
    val action: String,
    val result: String,
    val toState: String? = null,
    val reasonCode: String? = null,
)

@Serializable
data class ApiProject(
    val projectId: String,
    val name: String,
    val description: String?,
)

/**
 * `GET /api/selfdev/goals` element = server `TaskSummaryResponse` (a goal is a task in the V40 model).
 */
@Serializable
data class ApiGoal(
    val taskId: String,
    val title: String,
    val state: String,
    val stateReason: String? = null,
    val revision: Int,
    val version: Long,
    val targetRepo: String,
    val requiresApproval: Boolean,
    val dependencies: List<String>,
    val runId: String? = null,
    val attempts: Int,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * `GET /api/selfdev/goals/{id}` = server `TaskDetailResponse`: the goal ([task]) and its body (the plan text, null when empty).
 */
@Serializable
data class ApiGoalDetail(
    val task: ApiGoal,
    val body: String? = null,
)

/**
 * `GET /api/selfdev/orders/{id}` = server `OrderDetailResponse`. The server's title and policy are not listed: the old UI does not
 * show them on this screen either.
 */
@Serializable
data class ApiOrderDetail(
    val order: ApiOrder,
    val pendingStopRequest: ApiStopRequest? = null,
)

/** `GET /api/selfdev/orders/{id}/cycles` element = server `CycleResponse`. [goalId] is the goal the cycle started. */
@Serializable
data class ApiOrderCycle(
    val cycleNo: Int,
    val goalId: String,
    val goalState: String,
    val stateReason: String? = null,
    val createdAt: String,
)

/**
 * `GET /api/selfdev/orders` element = server `OrderSummaryResponse`. The summary has no title (the detail view has it).
 * Amounts are whole yen as JSON numbers.
 */
@Serializable
data class ApiOrder(
    val orderId: String,
    val parentOrderId: String? = null,
    val depth: Int,
    val state: String,
    val stateReason: String? = null,
    val cyclesStarted: Int,
    val maxCycles: Int,
    val currentGoalId: String? = null,
    val consecutiveFailedCycles: Int,
    val expiresAt: String,
    val version: Long,
    val createdAt: String,
    val updatedAt: String,
    val orderBudgetJpy: Long,
    val allocatedJpy: Long,
    val childCount: Int,
    val spentJpy: Long,
    val cycleBudgetJpy: Long,
    val remainingJpy: Long,
    /** Null means unassigned to a project. */
    val projectId: String? = null,
)

/**
 * `GET /api/selfdev/stop-requests?state=PENDING` element = server `StopRequestResponse`. These are the approvals of the
 * old UI's approval page. [digest] is shown to the root approver only (null otherwise).
 */
@Serializable
data class ApiStopRequest(
    val requestId: String,
    val orderId: String,
    val requesterKind: String,
    val reasonCode: String,
    val state: String,
    val orderVersion: Long,
    val digest: String? = null,
    val expiresAt: String,
    val createdAt: String,
    val targetIsRoot: Boolean,
)

/**
 * `GET /api/selfdev/memory` element = server `MemoryItemResponse`. The list wrapper (`MemoryListResponse`) is unwrapped in ApiClient.
 * [body] is null for an untrusted note and for a fact; [bodyWithheld] is true when an untrusted note has a text that is not returned.
 */
@Serializable
data class ApiMemory(
    val recordId: String,
    val kind: String,
    val source: String,
    val tier: String,
    val goalId: String? = null,
    val body: String? = null,
    val bodyWithheld: Boolean,
    val code: String? = null,
    val countValue: Int? = null,
    val createdAt: String,
)

/**
 * `GET /api/admin/budget/status` = server `BudgetStatusResponse`. Money fields are decimal strings in yen (e.g. "100000"),
 * not numbers. The `*Value` helpers parse them and return null when a value is not a number.
 */
@Serializable
data class ApiBudget(
    val period: String,
    val limitJpy: String,
    val committedJpy: String,
    val reservedJpy: String,
    val remainingJpy: String,
    val paths: ApiBudgetPaths,
    val unpricedCount: Long,
    val unpriced: List<ApiUnpricedReservation>,
) {
    val limitValue: Double? get() = limitJpy.toDoubleOrNull()

    /** 確定 + 予約中 (committed + reserved), the amount the limit is compared with (the old UI shows the same two). */
    val usedValue: Double? get() {
        val committed = committedJpy.toDoubleOrNull() ?: return null
        val reserved = reservedJpy.toDoubleOrNull() ?: return null
        return committed + reserved
    }
}

@Serializable
data class ApiBudgetPaths(val api: ApiBudgetPathAmount, val cli: ApiBudgetPathAmount)

@Serializable
data class ApiBudgetPathAmount(val committedJpy: String)

/** Server `UnpricedBody`. */
@Serializable
data class ApiUnpricedReservation(
    val reservationKey: String,
    val scopeId: String,
    val period: String,
    val state: String,
    val path: String,
    val amountJpy: String,
    val reason: String,
)

/**
 * `GET /api/selfdev/assignees` element = server `AssigneeResponse`.
 */
@Serializable
data class ApiAssignee(
    val assigneeId: String,
    val displayName: String,
    val role: String,
    val state: String,
    val version: Long,
    val memberCount: Int,
    val activeTokenCount: Int,
    /** Null means unassigned to a project. */
    val projectId: String? = null,
)
