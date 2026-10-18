package net.kigawa.kivra.coding

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The run-execution monitor's event schema (ADR 0012 section 1, PR 1). The agent sends fixed codes and numbers only. This file is the
 * server's check: it accepts exactly the kinds and fields below, and refuses everything else as a whole (the caller stores nothing).
 * Unknown fields, free strings, wrong types, out-of-range values and values outside the fixed lists are all refused. No value is ever
 * echoed in an error and nothing here logs.
 *
 * The kinds and the codes are copied from the agent's own enums (kivra-agent does not depend on kivra-app, so they are not shared).
 * A test pins each list to its agent-side source so the two cannot drift apart unnoticed.
 */

/** The kinds an agent may send. [TRUNCATED] is written by the server only (the cap mark), so it is not in [clientKinds]. */
enum class MonitorKind(val fields: Set<String>) {
    ATTEMPT_STARTED(emptySet()),
    /** The CLI's init. No model id or version is stored yet (ADR 0012 U-5). */
    CLI_INIT(emptySet()),
    PHASE(setOf(FIELD_PHASE)),
    TOOL_USE(setOf(FIELD_TOOL_NAME)),
    TOOL_RESULT(setOf(FIELD_COUNT)),
    TURN(setOf(FIELD_COUNT)),
    PERMISSION_DENIED(setOf(FIELD_COUNT)),
    API_RETRY(setOf(FIELD_CODE)),
    COST(setOf(FIELD_COST_USD)),
    STOP_REQUESTED(emptySet()),
    PROCESS_EXIT_CONFIRMED(setOf(FIELD_CODE)),
    WARNING(setOf(FIELD_COUNT)),
    DROPPED(setOf(FIELD_COUNT)),
    TRUNCATED(emptySet()),
}

/** One event, after the check. Only the fields of its kind are set; the rest are null. */
data class MonitorEvent(
    val seq: Int,
    val kind: MonitorKind,
    val phase: String? = null,
    val toolName: String? = null,
    val code: String? = null,
    val count: Int? = null,
    val costUsd: BigDecimal? = null,
)

/** A checked request body: one generation and its consecutive events (1 to [MAX_EVENTS] of them). */
data class MonitorBatch(val generation: Long, val events: List<MonitorEvent>) {
    companion object {
        const val MAX_EVENTS = 50
        const val MAX_EVENT_BYTES = 256

        /** Returns the checked batch, or null when the body is refused in any way. Never throws. */
        fun parseOrNull(text: String): MonitorBatch? = MonitorEventSchema.parse(text)
    }
}

internal object MonitorEventSchema {
    /** Fixed lists. Each one is the agent-side enum's own values. */
    val PHASES: Set<String> = setOf("STARTED", "EDITING", "TESTING", "ROUND_COMPLETED", "FINALIZING")
    val TOOL_NAMES: Set<String> = setOf("Read", "Edit", "Write", "Bash", "Grep", "Glob", "Task", "TodoWrite", "OTHER")
    val API_ERROR_KINDS: Set<String> = setOf("AUTHENTICATION", "RATE_LIMIT", "OVERLOADED", "BILLING", "OTHER")
    val DOCKER_RUN_OUTCOMES: Set<String> = setOf("EXITED", "TIMEOUT", "CANCELLED", "OUTPUT_LIMIT")

    const val MAX_COUNT = 10_000
    val MAX_COST_USD: BigDecimal = BigDecimal("100")
    private const val COST_SCALE = 6

    private val BATCH_KEYS = setOf("generation", "events")
    private val EVENT_KEYS = setOf(FIELD_SEQ, FIELD_KIND) + setOf(FIELD_PHASE, FIELD_TOOL_NAME, FIELD_CODE, FIELD_COUNT, FIELD_COST_USD)
    private val GENERATION = Regex("^[1-9][0-9]{0,18}$")
    private val SEQ = Regex("^[1-9][0-9]{0,9}$")
    private val COUNT = Regex("^(0|[1-9][0-9]{0,4})$")
    private val COST = Regex("^[0-9]{1,3}(\\.[0-9]{1,6})?$")

    /**
     * Strict JSON. A duplicate key keeps the last value (this kotlinx version has no switch for it). That is harmless here: each value that
     * is kept is checked below, so a duplicate can never carry a free string through.
     */
    private val json = Json

    fun parse(text: String): MonitorBatch? = try {
        parseBatch(json.parseToJsonElement(text))
    } catch (_: Exception) {
        null
    }

    private fun parseBatch(root: JsonElement): MonitorBatch? {
        val obj = root as? JsonObject ?: return null
        if (obj.keys != BATCH_KEYS) return null
        val generation = integerText(obj.getValue("generation"), GENERATION)?.toLongOrNull() ?: return null
        val array = obj.getValue("events") as? JsonArray ?: return null
        if (array.size !in 1..MonitorBatch.MAX_EVENTS) return null
        val events = mutableListOf<MonitorEvent>()
        for (element in array) {
            val event = parseEvent(element) ?: return null
            // The batch is one run of consecutive seq values, starting wherever the first one starts.
            if (events.isNotEmpty() && event.seq != events.last().seq + 1) return null
            events += event
        }
        return MonitorBatch(generation, events)
    }

    private fun parseEvent(element: JsonElement): MonitorEvent? {
        val obj = element as? JsonObject ?: return null
        if (!EVENT_KEYS.containsAll(obj.keys) || !obj.keys.containsAll(setOf(FIELD_SEQ, FIELD_KIND))) return null
        val seq = integerText(obj.getValue(FIELD_SEQ), SEQ)?.toIntOrNull() ?: return null
        val kindName = stringValue(obj.getValue(FIELD_KIND)) ?: return null
        val kind = MonitorKind.entries.firstOrNull { it.name == kindName }?.takeIf { it != MonitorKind.TRUNCATED } ?: return null
        // The event carries exactly the fields of its kind: no more, no fewer.
        if (obj.keys - setOf(FIELD_SEQ, FIELD_KIND) != kind.fields) return null
        val event = when (kind) {
            MonitorKind.PHASE -> MonitorEvent(seq, kind, phase = fixed(obj, FIELD_PHASE, PHASES) ?: return null)
            MonitorKind.TOOL_USE -> MonitorEvent(seq, kind, toolName = fixed(obj, FIELD_TOOL_NAME, TOOL_NAMES) ?: return null)
            MonitorKind.API_RETRY ->
                MonitorEvent(seq, kind, code = fixed(obj, FIELD_CODE, API_ERROR_KINDS) ?: return null)
            MonitorKind.PROCESS_EXIT_CONFIRMED ->
                MonitorEvent(seq, kind, code = fixed(obj, FIELD_CODE, DOCKER_RUN_OUTCOMES) ?: return null)
            MonitorKind.TOOL_RESULT, MonitorKind.TURN, MonitorKind.PERMISSION_DENIED, MonitorKind.WARNING, MonitorKind.DROPPED ->
                MonitorEvent(seq, kind, count = count(obj) ?: return null)
            MonitorKind.COST -> MonitorEvent(seq, kind, costUsd = cost(obj) ?: return null)
            MonitorKind.ATTEMPT_STARTED, MonitorKind.CLI_INIT, MonitorKind.STOP_REQUESTED -> MonitorEvent(seq, kind)
            MonitorKind.TRUNCATED -> return null
        }
        if (obj.toString().toByteArray(Charsets.UTF_8).size > MonitorBatch.MAX_EVENT_BYTES) return null
        return event
    }

    private fun fixed(obj: JsonObject, field: String, allowed: Set<String>): String? =
        stringValue(obj.getValue(field))?.takeIf { it in allowed }

    private fun count(obj: JsonObject): Int? {
        val value = integerText(obj.getValue(FIELD_COUNT), COUNT)?.toIntOrNull() ?: return null
        return value.takeIf { it in 0..MAX_COUNT }
    }

    private fun cost(obj: JsonObject): BigDecimal? {
        val primitive = obj.getValue(FIELD_COST_USD) as? JsonPrimitive ?: return null
        if (primitive.isString || !COST.matches(primitive.content)) return null
        val value = BigDecimal(primitive.content)
        if (value > MAX_COST_USD) return null
        return value.setScale(COST_SCALE, RoundingMode.UNNECESSARY)
    }

    /** A JSON string value, or null. A number, a boolean or null is not a string here. */
    private fun stringValue(element: JsonElement): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    /** A JSON number written as a plain integer (no quotes, no fraction, no exponent) that matches [pattern]. */
    private fun integerText(element: JsonElement, pattern: Regex): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || !pattern.matches(primitive.content)) return null
        return primitive.content
    }
}

private const val FIELD_SEQ = "seq"
private const val FIELD_KIND = "kind"
private const val FIELD_PHASE = "phase"
private const val FIELD_TOOL_NAME = "toolName"
private const val FIELD_CODE = "code"
private const val FIELD_COUNT = "count"
private const val FIELD_COST_USD = "costUsd"
