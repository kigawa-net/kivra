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

/** PATCH body for updating customFields (design #3 section 7.2, A6). */
@Serializable
internal data class CustomFieldsPatch(val customFields: Map<String, JsonElement>)

/** POST body for adding a comment to a task (design #3 section 7.2, A6). */
@Serializable
internal data class CommentBody(val body: String)

/** POST body for creating a task (design #3 section 5.4, C3). */
@Serializable
internal data class CreateTaskBody(
    val title: String,
    val description: String? = null,
    val parentId: Long? = null,
    val idempotencyKey: String? = null,
)
