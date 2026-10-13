package net.kigawa.kivra.modelgateway

import java.time.Instant

/** Audit record: metadata only. Prompt/response bodies are never included (design 05 section 9.3). */
data class AuditRecord(
    val timestamp: Instant,
    val requestId: String,
    val attemptId: String?,
    val taskId: String?,
    val executionId: String?,
    val principal: String,
    val purpose: Purpose,
    val dataClass: DataClass,
    val selected: ModelRef?,
    val routingDecisionId: String?,
    val usage: Usage,
    val cost: Cost?,
    val latencyMs: Long,
    val finishReason: FinishReason?,
    val errorKind: ErrorKind?,
    val errorCode: String? = null,
)

/** Persistence is a separate issue; implementations must be append-only. */
fun interface AuditSink { fun record(record: AuditRecord) }

interface GatewayMetrics {
    fun requestCompleted(providerId: String, modelId: String, purpose: Purpose, outcome: String, errorKind: ErrorKind?,
                         usage: Usage, cost: Cost?, latencyMs: Long) {}
    fun fallback(from: ModelRef, to: ModelRef, reason: ErrorKind) {}
    fun routeRejected(reason: String) {}

    companion object { val NOOP: GatewayMetrics = object : GatewayMetrics {} }
}

class InMemoryAuditSink : AuditSink {
    private val items = java.util.concurrent.CopyOnWriteArrayList<AuditRecord>()
    val records: List<AuditRecord> get() = items.toList()
    override fun record(record: AuditRecord) { items.add(record) }
}
