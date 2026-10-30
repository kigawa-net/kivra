package net.kigawa.kivra.recl

import net.kigawa.kivra.core.IdempotencyKey
import net.kigawa.kivra.memory.AccessContext
import net.kigawa.kivra.memory.MemoryErrorKind
import net.kigawa.kivra.memory.MemoryException
import net.kigawa.kivra.memory.MemoryId
import net.kigawa.kivra.memory.MemoryKind
import net.kigawa.kivra.memory.MemoryProvider
import net.kigawa.kivra.memory.MemoryProvenance
import net.kigawa.kivra.memory.NewMemoryEntry
import net.kigawa.kivra.memory.ProviderHealth
import net.kigawa.kivra.memory.ScopeRef
import net.kigawa.kivra.memory.Sensitivity
import net.kigawa.kivra.memory.SourceRef
import net.kigawa.kivra.memory.SourceType
import net.kigawa.kivra.memory.WriteProvenance
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Wraps MemoryProvider save for Agent Manager (design #3 section 10.1, C4).
 *
 * When a Run completes, the Agent Manager writes a summary to recl so that
 * future tasks can retrieve the past experience. The summary includes the
 * Run outcome, key decisions, and any failure reasons.
 *
 * Best-effort: if recl is unavailable, the write is logged but does not
 * fail the Run. Writes are queued via Outbox for retry if needed.
 */
class ReclSummaryWriter(
    private val provider: MemoryProvider?,
) {
    private val logger = LoggerFactory.getLogger(ReclSummaryWriter::class.java)

    /**
     * Write a Run summary to recl after completion.
     *
     * Returns the MemoryId if successful, null if skipped or failed.
     * Never throws — all failures are logged.
     */
    suspend fun writeRunSummary(
        runId: String,
        taskId: String,
        scope: ScopeRef,
        title: String,
        summary: String,
        outcome: RunOutcome,
        traceId: String,
        createdAt: Instant = Instant.now(),
    ): MemoryId? {
        val provider = provider ?: run {
            logger.debug("recl summary writer: no provider configured, skipping")
            return null
        }

        return try {
            val health = provider.health()
            if (health == ProviderHealth.DOWN) {
                logger.warn("recl summary writer: provider is DOWN, skipping write for run={}", runId)
                return null
            }

            val provenance = when (outcome) {
                RunOutcome.SUCCEEDED -> MemoryProvenance.AGENT_OBSERVED
                RunOutcome.FAILED, RunOutcome.CANCELLED -> MemoryProvenance.AGENT_OBSERVED
            }

            val sources = listOf(
                SourceRef(
                    type = SourceType.FUSHA_TASK,
                    uri = "fusha-task:$taskId",
                    retrievedAt = createdAt,
                )
            )

            val entry = NewMemoryEntry(
                kind = MemoryKind.EPISODIC,
                scope = listOf(scope),
                title = title.take(200),
                summary = summary.take(2000),
                body = null,
                sources = sources,
                provenance = provenance,
                confidence = 0.9,
                importance = when (outcome) {
                    RunOutcome.SUCCEEDED -> 0.7
                    RunOutcome.FAILED -> 0.5
                    RunOutcome.CANCELLED -> 0.3
                },
                sensitivity = Sensitivity.INTERNAL,
                retention = "default",
                writtenBy = WriteProvenance(
                    taskId = taskId,
                    executionId = runId,
                    createdBy = "orchestrator",
                ),
            )

            val key = IdempotencyKey("kivra-run-summary-$runId")
            val ctx = AccessContext(
                allowedScopes = setOf(scope),
                executionId = runId,
                taskId = taskId,
                agentProfile = "orchestrator",
                traceId = traceId,
                purpose = "run-summary-write",
            )

            val id = provider.save(entry, key, ctx)
            logger.info("recl summary writer: wrote run={} as memory={}", runId, id.value)
            id

        } catch (e: MemoryException) {
            if (e.kind == MemoryErrorKind.UNSUPPORTED) {
                logger.debug("recl summary writer: mutations unsupported, skipping run={}", runId)
            } else {
                logger.warn("recl summary writer: failed for run={}, kind={}", runId, e.kind)
            }
            null
        } catch (e: Exception) {
            logger.warn("recl summary writer: unexpected error for run={}", runId, e)
            null
        }
    }

    /**
     * Outcome of a completed Run.
     */
    enum class RunOutcome {
        SUCCEEDED,
        FAILED,
        CANCELLED,
    }
}
