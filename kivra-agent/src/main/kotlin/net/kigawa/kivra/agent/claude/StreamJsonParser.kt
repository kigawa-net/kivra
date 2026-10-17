package net.kigawa.kivra.agent.claude

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * The usage that kivra receives in the `usage` field of the result and fail bodies (runbook-selfdev section 6.10c). It holds numbers and
 * one model name only. [totalCostUsd] is a decimal string in 0 to 100 with six decimals at most. All fields are checked by
 * [StreamJsonParser] before a value of this type exists, so a value here is always well-formed. [toString] shows no value.
 */
data class ReportedUsage(
    val totalCostUsd: String,
    val model: String,
    val numTurns: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
) {
    override fun toString(): String = "ReportedUsage(***)"
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
    /** The number of entries in `permission_denials` (the tools the permission mode refused). The entries themselves are not kept. */
    val permissionDenials: Int? = null,
    /** `stop_reason` and `terminal_reason`, kept only when they have the shape of an enum name (see [CliResultLog]). */
    val stopReason: String? = null,
    val terminalReason: String? = null,
    /** The usage for kivra, or null when any part of it is missing or malformed (then no usage is sent at all). */
    val reportedUsage: ReportedUsage? = null,
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
        val numTurns = (obj["num_turns"] as? JsonPrimitive)?.intOrNull
        return CliResult(
            subtype = str(obj, "subtype"),
            isError = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false,
            resultText = str(obj, "result")?.take(MAX_RESULT_CHARS),
            numTurns = numTurns,
            sessionId = sessionId,
            usage = usage,
            permissionDenials = (obj["permission_denials"] as? JsonArray)?.size,
            stopReason = str(obj, "stop_reason")?.takeIf { ENUM_NAME.matches(it) },
            terminalReason = str(obj, "terminal_reason")?.takeIf { ENUM_NAME.matches(it) },
            reportedUsage = reportedUsage(obj, usageObj, numTurns),
        )
    }

    /**
     * Builds the usage for kivra from the final result. All of it or nothing: a missing value, a value out of range, a cost that is not a
     * plain JSON number, or a model name that does not have the expected shape makes the whole usage null.
     */
    private fun reportedUsage(obj: JsonObject, usageObj: JsonObject?, numTurns: Int?): ReportedUsage? {
        val cost = decimalCost((obj["total_cost_usd"] as? JsonPrimitive)?.takeIf { !it.isString }?.content) ?: return null
        val model = modelName(obj["modelUsage"] as? JsonObject) ?: return null
        val turns = numTurns?.takeIf { it in 0..MAX_TURNS } ?: return null
        val input = tokens(usageObj, "input_tokens") ?: return null
        val output = tokens(usageObj, "output_tokens") ?: return null
        val cacheCreation = tokens(usageObj, "cache_creation_input_tokens") ?: return null
        val cacheRead = tokens(usageObj, "cache_read_input_tokens") ?: return null
        return ReportedUsage(cost, model, turns, input, output, cacheCreation, cacheRead)
    }

    /** A plain decimal in 0 to 100, rounded to six decimals. Null for anything else (including NaN, a sign, or an exponent out of range). */
    private fun decimalCost(text: String?): String? {
        if (text == null) return null
        val value = runCatching { BigDecimal(text) }.getOrNull() ?: return null
        if (value.signum() < 0 || value > MAX_COST) return null
        return value.setScale(COST_SCALE, RoundingMode.HALF_UP).toPlainString()
    }

    /**
     * The model that cost the most, from the keys of `modelUsage`. Every key must have the model-name shape, or there is no model at all.
     * A tie goes to the smaller name, so the choice does not depend on the order of the keys.
     */
    private fun modelName(models: JsonObject?): String? {
        if (models == null || models.isEmpty()) return null
        if (!models.keys.all { MODEL_NAME.matches(it) }) return null
        return models.entries
            .map { (name, value) -> name to ((value as? JsonObject)?.get("costUSD") as? JsonPrimitive)?.doubleOrNull.orZero() }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .first().first
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    private fun tokens(obj: JsonObject?, key: String): Long? = long(obj, key)?.takeIf { it in 0..MAX_TOKENS }

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
        private const val MAX_TURNS = 1000
        private const val MAX_TOKENS = 10_000_000_000L
        private const val COST_SCALE = 6
        private val MAX_COST = BigDecimal("100")
        private val SESSION_ID = Regex("[A-Za-z0-9-]{1,128}")
        private val MODEL_NAME = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        val ENUM_NAME = Regex("[a-z][a-z_]{0,63}")
    }
}
