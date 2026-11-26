package net.kigawa.kivra.recl

import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.ContextBundle
import net.kigawa.kivra.memory.MemoryKind
import net.kigawa.kivra.memory.MemoryService
import net.kigawa.kivra.memory.MemoryQuery
import net.kigawa.kivra.memory.RecallResult
import net.kigawa.kivra.memory.ScopeRef
import net.kigawa.kivra.memory.Sensitivity
import org.slf4j.LoggerFactory

/**
 * Wraps MemoryService recall for the Orchestrator (design #3 section 10.1, C4).
 *
 * When the Orchestrator admits a task, it searches recl for past experience
 * related to that task. The result is a ContextBundle that can be handed off
 * to the Agent as reference data.
 *
 * Best-effort: if recl is unavailable, the search returns an empty bundle with
 * a MEMORY_UNAVAILABLE warning. The Orchestrator does not block on recl.
 */
class ReclContextSearch(
    private val memoryService: MemoryService?,
) {
    private val logger = LoggerFactory.getLogger(ReclContextSearch::class.java)

    /**
     * Search for context related to a task before admitting it.
     *
     * Returns a ContextBundle that may be empty (with warnings) if recl is unavailable.
     * Provider failures become warnings. Coroutine cancellation is propagated.
     */
    suspend fun searchForTask(
        task: TaskSnapshot,
        scope: ScopeRef,
        traceId: String,
        executionId: String,
    ): ContextBundle {
        val queryText = buildQueryText(task)
        val query = MemoryQuery(
            text = queryText,
            scopes = listOf(scope),
            kinds = setOf(MemoryKind.EPISODIC, MemoryKind.SEMANTIC, MemoryKind.PROCEDURAL),
            limit = 10,
            maxSensitivity = Sensitivity.INTERNAL,
        )

        val ctx = AccessContext(
            allowedScopes = setOf(scope),
            executionId = executionId,
            taskId = task.id.value,
            agentProfile = "orchestrator",
            traceId = traceId,
            purpose = "task-admission-context",
        )

        val recall = memoryService?.recall(query, ctx) ?: RecallResult.degraded()
        val bundle = ContextBundle.from(ctx, recall, maxSensitivity = Sensitivity.INTERNAL)
        if (recall.degraded) {
            logger.warn("recl context search: MEMORY_UNAVAILABLE taskId={}", task.id.value)
        }
        return bundle
    }

    /**
     * Build a search query from the task title and description.
     */
    private fun buildQueryText(task: TaskSnapshot): String {
        val parts = mutableListOf<String>()
        if (task.title.isNotBlank()) parts.add(task.title)
        val desc = task.description
        if (!desc.isNullOrBlank()) parts.add(desc)
        // Include custom field values that might be relevant
        for ((key, value) in task.fields) {
            if (key.startsWith("kivra_") || key == "priority" || key == "category") {
                parts.add("$key:$value")
            }
        }
        return parts.joinToString(" ")
    }
}
