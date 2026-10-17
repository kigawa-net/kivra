package net.kigawa.kivra.agent.claude

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/** The kind of an API error that the CLI reported in a `system` event with subtype `api_retry`. Only these kinds are kept. */
enum class ApiErrorKind { AUTHENTICATION, RATE_LIMIT, OVERLOADED, BILLING, OTHER }

/** Token counts and cost as the CLI reports them. The cost is the CLI's own estimate, not the bill. Nothing here is a credential. */
class CliUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheCreationInputTokens: Long?,
    val cacheReadInputTokens: Long?,
    val costUsd: Double?,
) {
    override fun toString(): String = "CliUsage(inputTokens=$inputTokens, outputTokens=$outputTokens, costUsd=$costUsd)"
}

/**
 * The final `result` event. [resultText] is the model's final text, which may contain anything the task touched. It is kept for the
 * caller, and never logged. [toString] shows no text.
 */
class CliResult(
    val subtype: String?,
    val isError: Boolean,
    val resultText: String?,
    val numTurns: Int?,
    /** The session id, only when it has the expected shape. */
    val sessionId: String?,
    val usage: CliUsage,
) {
    override fun toString(): String =
        "CliResult(subtype=$subtype, isError=$isError, numTurns=$numTurns, resultChars=${resultText?.length ?: 0}, usage=$usage)"
}

/**
 * What the parser found in one stdout stream. Counts only, and the last result. [invalidLines] counts lines that are not one JSON
 * object (including a cut last line). [oversizedLines] counts lines over the per-line limit, and [outputLimitReached] tells that the
 * stream was longer than the total limit and was not read to the end.
 */
class StreamReport(
    val result: CliResult?,
    val apiErrors: Set<ApiErrorKind>,
    val eventCount: Int,
    val invalidLines: Int,
    val oversizedLines: Int,
    val outputLimitReached: Boolean,
) {
    override fun toString(): String =
        "StreamReport(hasResult=${result != null}, eventCount=$eventCount, invalidLines=$invalidLines, " +
            "oversizedLines=$oversizedLines, outputLimitReached=$outputLimitReached)"
}

/**
 * Reads the CLI's `--output-format stream-json` output: one JSON object per line, read one line at a time (ADR 0010 decision 9).
 *
 * - A line over [maxLineBytes] is skipped to its end and counted as oversized. Its content is not kept.
 * - Reading stops after [maxTotalBytes] bytes, and [StreamReport.outputLimitReached] is set.
 * - A line that is not one JSON object is counted as invalid and never parsed further. Its text is not logged.
 * - Only the fields that the classifier needs are read. Unknown events are counted and ignored.
 *
 * Nothing from the output is logged: only the counts. The logger receives the counts and nothing else.
 */
class StreamJsonParser(
    private val maxLineBytes: Int = MAX_LINE_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    private val logger = LoggerFactory.getLogger(StreamJsonParser::class.java)

    fun parse(text: String): StreamReport = parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    fun parse(input: InputStream): StreamReport {
        val lines = LineSource(BufferedInputStream(input), maxLineBytes, maxTotalBytes)
        var result: CliResult? = null
        val apiErrors = mutableSetOf<ApiErrorKind>()
        var events = 0
        var invalid = 0
        var oversized = 0
        while (true) {
            val line = lines.next() ?: break
            when (line) {
                LineSource.Line.Oversized -> oversized++
                is LineSource.Line.Text -> {
                    val text = line.value.trimEnd('\r')
                    if (text.isBlank()) continue
                    val obj = parseObject(text)
                    if (obj == null) {
                        invalid++
                        continue
                    }
                    events++
                    when (str(obj, "type")) {
                        "result" -> result = toResult(obj)
                        "system" -> if (str(obj, "subtype") == "api_retry") apiErrorKind(str(obj, "error"))?.let { apiErrors += it }
                    }
                }
            }
        }
        val report = StreamReport(result, apiErrors.toSet(), events, invalid, oversized, lines.limitReached)
        logger.info(
            "stream-json parsed: hasResult={} eventCount={} invalidLines={} oversizedLines={} outputLimitReached={}",
            report.result != null,
            events,
            invalid,
            oversized,
            lines.limitReached,
        )
        return report
    }

    private fun parseObject(text: String): JsonObject? = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()

    private fun toResult(obj: JsonObject): CliResult {
        val usageObj = obj["usage"] as? JsonObject
        val usage = CliUsage(
            inputTokens = long(usageObj, "input_tokens"),
            outputTokens = long(usageObj, "output_tokens"),
            cacheCreationInputTokens = long(usageObj, "cache_creation_input_tokens"),
            cacheReadInputTokens = long(usageObj, "cache_read_input_tokens"),
            costUsd = (obj["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull,
        )
        val sessionId = str(obj, "session_id")?.takeIf { SESSION_ID.matches(it) }
        return CliResult(
            subtype = str(obj, "subtype"),
            isError = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false,
            resultText = str(obj, "result")?.take(MAX_RESULT_CHARS),
            numTurns = (obj["num_turns"] as? JsonPrimitive)?.intOrNull,
            sessionId = sessionId,
            usage = usage,
        )
    }

    private fun apiErrorKind(category: String?): ApiErrorKind? = when (category) {
        "authentication_failed" -> ApiErrorKind.AUTHENTICATION
        "rate_limit" -> ApiErrorKind.RATE_LIMIT
        "overloaded" -> ApiErrorKind.OVERLOADED
        "billing_error" -> ApiErrorKind.BILLING
        null -> null
        else -> ApiErrorKind.OTHER
    }

    private fun str(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun long(obj: JsonObject?, key: String): Long? = (obj?.get(key) as? JsonPrimitive)?.longOrNull

    /** Reads lines from [input] without ever holding more than [maxLine] bytes of one line. */
    private class LineSource(private val input: InputStream, private val maxLine: Int, private val maxTotal: Long) {
        sealed interface Line {
            class Text(val value: String) : Line
            data object Oversized : Line
        }

        var limitReached = false
            private set
        private var totalBytes = 0L

        /** The next line, or null at the end of the stream or at the total limit. */
        fun next(): Line? {
            val buffer = ByteArrayOutputStream()
            var oversized = false
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (!sawAny) return null
                    break
                }
                sawAny = true
                totalBytes++
                if (totalBytes > maxTotal) {
                    limitReached = true
                    return null
                }
                if (b == '\n'.code) break
                if (oversized) continue
                if (buffer.size() >= maxLine) {
                    oversized = true
                    buffer.reset()
                    continue
                }
                buffer.write(b)
            }
            return if (oversized) Line.Oversized else Line.Text(buffer.toString(Charsets.UTF_8))
        }
    }

    companion object {
        const val MAX_LINE_BYTES = 1024 * 1024
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
        const val MAX_RESULT_CHARS = 64 * 1024
        private val SESSION_ID = Regex("[A-Za-z0-9-]{1,128}")
    }
}
