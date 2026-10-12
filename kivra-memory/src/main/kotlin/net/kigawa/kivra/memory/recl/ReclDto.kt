package net.kigawa.kivra.memory.recl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Planned wire format (Kivra's EXPECTED contract, design #6 section 3.2 / 4.2). 要確認: none of this exists in recl
 * yet; field names, the metadata area (kind / provenance / confidence / sensitivity / retention) and the id type are
 * assumptions to be re-checked against recl#1 (docs/design.md) and recl#3/#4.
 */

@Serializable
data class ReclSource(
    val type: String,
    val uri: String,
    val locator: String? = null,
    @SerialName("retrieved_at") val retrievedAt: String,
    @SerialName("content_hash") val contentHash: String? = null,
)

@Serializable
data class ReclContext(
    val id: String,
    val version: Int,
    val status: String,
    val kind: String,
    val scopes: List<String>,
    val title: String,
    val summary: String,
    val body: String? = null,
    val sources: List<ReclSource> = emptyList(),
    val provenance: String,
    val confidence: Double,
    val importance: Double,
    val sensitivity: String,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    val retention: String,
    val supersedes: List<String> = emptyList(),
    val pinned: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("execution_id") val executionId: String,
)

@Serializable
data class ReclNewContext(
    val kind: String,
    val scopes: List<String>,
    val title: String,
    val summary: String,
    val body: String? = null,
    val sources: List<ReclSource>,
    val provenance: String,
    val confidence: Double,
    val importance: Double,
    val sensitivity: String,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_until") val validUntil: String? = null,
    val retention: String,
    val supersedes: List<String> = emptyList(),
    val pinned: Boolean = false,
    @SerialName("created_by") val createdBy: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("execution_id") val executionId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
)

@Serializable
data class ReclSearchRequest(
    val query: String,
    val scopes: List<String>,
    val kinds: List<String> = emptyList(),
    val limit: Int,
    @SerialName("max_sensitivity") val maxSensitivity: String,
)

@Serializable
data class ReclSearchHit(val context: ReclContext, val score: Double)

@Serializable
data class ReclSearchResponse(val results: List<ReclSearchHit>)

@Serializable
data class ReclPinnedResponse(val results: List<ReclContext>)

@Serializable
data class ReclIdResponse(val id: String)

@Serializable
data class ReclPatch(
    val summary: String? = null,
    val body: String? = null,
    val importance: Double? = null,
    val pinned: Boolean? = null,
    @SerialName("valid_until") val validUntil: String? = null,
)

@Serializable
data class ReclUpdateRequest(@SerialName("expected_version") val expectedVersion: Int, val patch: ReclPatch)

@Serializable
data class ReclSupersedeRequest(@SerialName("expected_version") val expectedVersion: Int, val new: ReclNewContext)
