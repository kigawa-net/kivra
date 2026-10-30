package net.kigawa.kivra.fusha

import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.core.TaskCapability
import net.kigawa.kivra.core.TaskFilter
import net.kigawa.kivra.core.TaskId
import net.kigawa.kivra.core.TaskPatch
import net.kigawa.kivra.core.TaskProvider
import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.core.TaskWriteResult
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [TaskProvider] for tests. Supports GET, LIST, UPDATE (customFields merge) and COMMENT.
 * [viewMembers] simulates server-side views.
 */
class FakeTaskProvider(
    tasks: Collection<TaskSnapshot> = emptyList(),
    private val viewMembers: Map<Long, Set<TaskId>> = emptyMap(),
) : TaskProvider {
    private val store = ConcurrentHashMap<TaskId, TaskSnapshot>().apply { tasks.forEach { put(it.id, it) } }
    private val comments = ConcurrentHashMap.newKeySet<String>()

    override val capabilities: Set<TaskCapability> = setOf(
        TaskCapability.GET, TaskCapability.LIST, TaskCapability.UPDATE, TaskCapability.COMMENT, TaskCapability.CREATE,
    )

    fun put(task: TaskSnapshot) { store[task.id] = task }
    fun remove(id: TaskId) { store.remove(id) }

    override suspend fun get(id: TaskId): TaskSnapshot? = store[id]

    override suspend fun list(filter: TaskFilter): List<TaskSnapshot> =
        store.values
            .filter { filter.assigneeId == null || it.assigneeId == filter.assigneeId }
            .filter { filter.viewId == null || viewMembers[filter.viewId]?.contains(it.id) == true }
            .sortedBy { it.id.value }

    override suspend fun update(id: TaskId, patch: TaskPatch, expectedUpdatedAt: Instant?): TaskWriteResult {
        val current = store[id] ?: return TaskWriteResult.Rejected("task not found", 404)
        if (expectedUpdatedAt != null && current.updatedAt.isAfter(expectedUpdatedAt)) {
            return TaskWriteResult.Rejected("task was modified after expectedUpdatedAt", null)
        }
        val mergedFields = current.fields.toMutableMap()
        for ((key, value) in patch.fields) mergedFields[key] = value
        store[id] = current.copy(fields = mergedFields)
        return TaskWriteResult.Ok(null)
    }

    override suspend fun comment(id: TaskId, body: String, key: IdempotencyKey): TaskWriteResult {
        if (store[id] == null) return TaskWriteResult.Rejected("task not found", 404)
        val marker = "kivra:${key.value}"
        if (comments.contains("$id:$marker")) return TaskWriteResult.Ok(null)
        comments.add("$id:$marker")
        return TaskWriteResult.Ok(null)
    }

    override suspend fun create(
        title: String,
        description: String?,
        parentId: TaskId?,
        key: IdempotencyKey,
    ): TaskWriteResult {
        val marker = "create:${key.value}"
        if (comments.contains(marker)) {
            val existingId = comments
                .filter { it.startsWith("create_map:$marker:") }
                .firstOrNull()
                ?.removePrefix("create_map:$marker:")
                ?.let { TaskId(it) }
                ?.let { store[it] }
                ?.let { return TaskWriteResult.Ok(it) }
        }
        val id = TaskId("fake:${store.size + 1}")
        val task = TaskSnapshot(
            id = id,
            title = title,
            description = description,
            assigneeId = null,
            dueDate = null,
            fields = emptyMap(),
            nextTaskIds = emptyList(),
            relatedTaskIds = emptyList(),
            updatedAt = Instant.now(),
            fetchedAt = Instant.now(),
        )
        store[id] = task
        comments.add(marker)
        comments.add("create_map:$marker:${id.value}")
        return TaskWriteResult.Ok(task)
    }
}
