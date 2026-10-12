package net.kigawa.kivra.fusha

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Subset of fusha-task `Task` (src/domain/entities/Task.ts) that Kivra reads. Unknown keys are ignored. */
@Serializable
internal data class FushaTaskDto(
    val id: Long,
    val title: String,
    val description: String? = null,
    val assigneeId: String? = null,
    val dueDate: String? = null,
    val customFields: Map<String, JsonElement> = emptyMap(),
    val relatedTaskIds: List<Long> = emptyList(),
    val nextTaskIds: List<Long> = emptyList(),
    val updatedAt: String,
)
