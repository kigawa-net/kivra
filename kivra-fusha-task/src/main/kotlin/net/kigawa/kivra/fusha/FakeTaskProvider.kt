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
 * In-memory [TaskProvider] for tests. Mirrors the read-only M1 contract of [FushaTaskAdapter]
 * (writes are UNSUPPORTED) so both pass the same contract tests. [viewMembers] simulates server-side views.
 */
class FakeTaskProvider(
    tasks: Collection<TaskSnapshot> = emptyList(),
    private val viewMembers: Map<Long, Set<TaskId>> = emptyMap(),
) : TaskProvider {
    private val store = ConcurrentHashMap<TaskId, TaskSnapshot>().apply { tasks.forEach { put(it.id, it) } }

    override val capabilities: Set<TaskCapability> = setOf(TaskCapability.GET, TaskCapability.LIST)

    fun put(task: TaskSnapshot) { store[task.id] = task }
    fun remove(id: TaskId) { store.remove(id) }

    override suspend fun get(id: TaskId): TaskSnapshot? = store[id]

    override suspend fun list(filter: TaskFilter): List<TaskSnapshot> =
        store.values
            .filter { filter.assigneeId == null || it.assigneeId == filter.assigneeId }
            .filter { filter.viewId == null || viewMembers[filter.viewId]?.contains(it.id) == true }
            .sortedBy { it.id.value }

    override suspend fun update(id: TaskId, patch: TaskPatch, expectedUpdatedAt: Instant?): TaskWriteResult =
        TaskWriteResult.Unsupported(TaskCapability.UPDATE, "fake is read-only")

    override suspend fun comment(id: TaskId, body: String, key: IdempotencyKey): TaskWriteResult =
        TaskWriteResult.Unsupported(TaskCapability.COMMENT, "fake is read-only")
}
