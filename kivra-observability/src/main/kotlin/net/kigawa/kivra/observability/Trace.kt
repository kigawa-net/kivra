package net.kigawa.kivra.observability

import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

/** W3C trace-id: 32 lowercase hex characters, never all zero. */
@JvmInline
value class TraceId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[0-9a-f]{32}$")
        private val random = SecureRandom()

        fun generate(): TraceId {
            while (true) {
                val bytes = ByteArray(16).also(random::nextBytes)
                if (bytes.any { it != 0.toByte() }) {
                    return TraceId(bytes.joinToString("") { "%02x".format(it) })
                }
            }
        }

        /** Returns null for anything that is not a well-formed trace-id. */
        fun parse(raw: String): TraceId? = if (pattern.matches(raw)) TraceId(raw) else null
    }
}

/** Correlation keys that connect a span to fusha-task, Run, Agent and exkes Execution. */
data class Correlation(
    val traceId: TraceId,
    val taskId: String? = null,
    val runId: String? = null,
    val executionId: String? = null,
    val agentId: String? = null,
) {
    internal fun asAttributes(): Map<String, String> = buildMap {
        put("traceId", traceId.value)
        taskId?.let { put("taskId", it) }
        runId?.let { put("runId", it) }
        executionId?.let { put("executionId", it) }
        agentId?.let { put("agentId", it) }
    }
}

enum class SpanStatus { OK, ERROR }

data class SpanRecord(
    val name: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val status: SpanStatus,
    /** Already passed through the allowlist filter. */
    val attributes: Map<String, String>,
)

/** Port for external trace backends (OpenTelemetry etc. are not chosen yet, design #1 section 8). */
fun interface SpanExporter {
    fun export(span: SpanRecord)

    companion object {
        val NOOP: SpanExporter = SpanExporter { }
    }
}

/**
 * Minimal tracer. It has no SDK dependency: it times a block, filters attributes and hands the
 * finished span to a [SpanExporter]. Exporter failures are swallowed so that observation never
 * stops the business operation.
 */
class Tracer(
    private val exporter: SpanExporter = SpanExporter.NOOP,
    private val attributeFilter: AttributeFilter = AttributeFilter(AttributeFilter.SPAN_ATTRIBUTE_ALLOWLIST),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun <T> withSpan(
        name: String,
        correlation: Correlation,
        attributes: Map<String, String> = emptyMap(),
        block: () -> T,
    ): T {
        val startedAt = clock.instant()
        var status = SpanStatus.OK
        try {
            return block()
        } catch (e: Throwable) {
            status = SpanStatus.ERROR
            throw e
        } finally {
            val merged = attributeFilter.filter(correlation.asAttributes() + attributes)
            val span = SpanRecord(name, startedAt, clock.instant(), status, merged)
            runCatching { exporter.export(span) }
        }
    }
}
