package net.kigawa.kivra.exkes

import kotlinx.serialization.Serializable

/*
 * Wire DTOs, mirroring exkes-api routes Dtos files on origin/main. Timestamps are not needed and are
 * ignored on read (the Json instance uses ignoreUnknownKeys).
 */

@Serializable
internal data class CreateWorkspaceBody(
    val name: String,
    val storageSizeBytes: Long? = null,
    val labels: Map<String, String>? = null,
)

@Serializable
internal data class WorkspaceDto(
    val id: String,
    val name: String,
    val status: String,
    val errorMessage: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
internal data class CreateRuntimeBody(
    val templateId: String,
    val resourcesJson: String? = null,
    val envJson: String? = null,
    val ttlSeconds: Long? = null,
    val labels: Map<String, String>? = null,
)

@Serializable
internal data class RuntimeDto(
    val id: String,
    val workspaceId: String,
    val templateId: String,
    val status: String,
    val errorMessage: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
internal data class CreateExecutionBody(
    val type: String,
    val commandJson: String? = null,
    val agentConfigJson: String? = null,
)

@Serializable
internal data class ExecutionDto(
    val id: String,
    val runtimeId: String,
    val type: String,
    val status: String,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val logObjectKey: String? = null,
)

@Serializable
internal data class ListDto<T>(val items: List<T>, val total: Long = 0, val limit: Int = 0, val offset: Int = 0)

@Serializable
internal data class ErrorDto(val code: String? = null, val message: String? = null)
