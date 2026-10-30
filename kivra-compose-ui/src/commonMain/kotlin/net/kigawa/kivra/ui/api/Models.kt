package net.kigawa.kivra.ui.api

import kotlinx.serialization.Serializable

/**
 * API response models for the Compose UI.
 * These mirror the server-side models but are defined in the UI module
 * to avoid depending on kivra-app.
 */

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
)

@Serializable
data class ApiProject(
    val projectId: String,
    val name: String,
    val description: String?,
)

@Serializable
data class ApiGoal(
    val goalId: String,
    val title: String,
    val state: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ApiOrder(
    val orderId: String,
    val runId: String?,
    val type: String,
    val state: String,
    val createdAt: String,
    val projectId: String? = null,
)

@Serializable
data class ApiMemory(
    val memoryId: String,
    val scope: String,
    val key: String,
    val value: String,
    val updatedAt: String,
)

@Serializable
data class ApiBudget(
    val month: String,
    val limit: Double,
    val used: Double,
    val pending: Double,
)

@Serializable
data class ApiAssignee(
    val assigneeId: String,
    val name: String,
    val token: String?,
    val createdAt: String,
    val projectId: String? = null,
)
