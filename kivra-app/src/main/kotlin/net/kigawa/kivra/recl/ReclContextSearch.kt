package net.kigawa.kivra.recl

import net.kigawa.kivra.core.TaskSnapshot
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.ContextBundle
import net.kigawa.kivra.memory.MemoryErrorKind
import net.kigawa.kivra.memory.MemoryException
import net.kigawa.kivra.memory.MemoryKind
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryQuery
import net.kigawa.kivra.memory.ProviderHealth
import net.kigawa.kivra.memory.RecallResult
import net.kigawa.kivra.memory.ScopeRef
import net.kigawa.kivra.memory.Sensitivity
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Wraps MemoryProvider search for the Orchestrator (design #3 section 10.1, C4).
 *
 * When the Orchestrator admits a task, it searches recl for past experience
 * related to that task. The result is a ContextBundle that can be handed off
 * to the Agent as reference data.
 *
 * Best-effort: if recl is unavailable, the search returns an empty bundle with
 * a MEMORY_UNAVAILABLE warning. The Orchestrator does not block on recl.
 */
class ReclContextSearch(
    private val provider: MemoryProvider?,
) {
    private val logger = LoggerFactory.getLogger(ReclContextSearch::class.java)

    /**
     * Search for context related to a task before admitting it.
     *
     * Returns a ContextBundle that may be empty (with warnings) if recl is unavailable.
     * Never throws — all failures are captured as warnings in the bundle.
     */
    suspend fun searchForTask(
        task: TaskSnapshot,
        scope: ScopeRef,
        traceId: String,
        executionId: String,
    ): ContextBundle {
        val provider = provider ?: run {
            logger.debug("recl context search: no provider configured, returning empty bundle")
            return emptyBundle(task, scope, traceId, executionId)
        }

        return try {
            val health = provider.health()
            if (health == ProviderHealth.DOWN) {
                logger.warn("recl context search: provider is DOWN, returning empty bundle")
                return emptyBundle(task, scope, traceId, executionId)
            }

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

            val hits = provider.search(query, ctx)
            val entries = hits.map { it.entry }
            val recall = RecallResult(entries, degraded = health == ProviderHealth.DEGRADED)
            ContextBundle.from(ctx, recall, maxSensitivity = Sensitivity.INTERNAL)

        } catch (e: MemoryException) {
            logger.warn("recl context search: failed for task={}, kind={}", task.id.value, e.kind)
            emptyBundle(task, scope, traceId, executionId)
        } catch (e: Exception) {
            logger.warn("recl context search: unexpected error for task={}", task.id.value, e)
            emptyBundle(task, scope, traceId, executionId)
        }
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

    /**
     * Return an empty bundle with MEMORY_UNAVAILABLE warning.
     */
    private fun emptyBundle(
        task: TaskSnapshot,
        scope: ScopeRef,
        traceId: String,
        executionId: String,
    ): ContextBundle {
        val ctx = AccessContext(
            allowedScopes = setOf(scope),
            executionId = executionId,
            taskId = task.id.value,
            agentProfile = "orchestrator",
            traceId = traceId,
            purpose = "task-admission-context",
        )
        return ContextBundle.from(
            ctx = ctx,
            recall = RecallResult(emptyList(), degraded = true),
            maxSensitivity = Sensitivity.INTERNAL,
            bundleId = UUID.randomUUID().toString(),
        )
    }
}
