package net.kigawa.kivra.agent.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Turns one stream-json line into the monitor's events (ADR 0012 §1.1). A pure function: no state, no logging.
 *
 * The output is limited to the fixed types in [MonitorEvent]. The rules, in the order of the line's `type`:
 * - `assistant`: one [MonitorEvent.Turn] per message, and one [MonitorEvent.ToolUse] per `tool_use` block, with the tool name matched
 *   against [MonitorTool]. The tool's `input` is never read. Text blocks are never read.
 * - `user`: one [MonitorEvent.ToolError] per `tool_result` block whose `is_error` is true. Successful results give nothing. The content
 *   is never read.
 * - `system` with subtype `init`: one [MonitorEvent.CliInit], with nothing from the line. With subtype `api_retry`: the API error kind
 *   ([ApiErrorKind]).
 * - `result`: the number of `permission_denials`, and the `total_cost_usd` (0 to 100 dollars, six decimals at most).
 *
 * Anything else, a line that is not one JSON object, and a shape that is not known, gives no event. This is the safe side: an unknown
 * shape is not recorded, and a value that does not have the expected shape is never passed through.
 */
object MonitorEventExtractor {
    /** Blocks read per line. A line with more blocks gives one [MonitorWarning.BLOCK_LIMIT] and the first [MAX_BLOCKS] only. */
    const val MAX_BLOCKS = 64

    fun extract(line: String): List<MonitorEvent> {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return emptyList()
        return when (str(obj, "type")) {
            "assistant" -> assistant(obj)
            "user" -> user(obj)
            "system" -> system(obj)
            "result" -> result(obj)
            else -> emptyList()
        }
    }

    private fun assistant(obj: JsonObject): List<MonitorEvent> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val blocks = blocks(message) ?: return emptyList()
        val events = mutableListOf<MonitorEvent>(MonitorEvent.Turn)
        for (block in blocks.take(MAX_BLOCKS)) {
            if (str(block, "type") == "tool_use") events += MonitorEvent.ToolUse(MonitorTool.ofCliName(str(block, "name")))
        }
        if (blocks.size > MAX_BLOCKS) events += MonitorEvent.Warning(MonitorWarning.BLOCK_LIMIT)
        return events
    }

    private fun user(obj: JsonObject): List<MonitorEvent> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val blocks = blocks(message) ?: return emptyList()
        val events = mutableListOf<MonitorEvent>()
        for (block in blocks.take(MAX_BLOCKS)) {
            if (str(block, "type") == "tool_result" && (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true) {
                events += MonitorEvent.ToolError
            }
        }
        if (blocks.size > MAX_BLOCKS) events += MonitorEvent.Warning(MonitorWarning.BLOCK_LIMIT)
        return events
    }

    private fun system(obj: JsonObject): List<MonitorEvent> = when (str(obj, "subtype")) {
        "init" -> listOf(MonitorEvent.CliInit)
        "api_retry" -> apiErrorKindOf(str(obj, "error"))?.let { listOf(MonitorEvent.ApiRetry(it)) } ?: emptyList()
        else -> emptyList()
    }

    private fun result(obj: JsonObject): List<MonitorEvent> {
        val events = mutableListOf<MonitorEvent>()
        (obj["permission_denials"] as? JsonArray)?.size
            ?.takeIf { it in 0..MonitorEvent.MAX_COUNT }
            ?.let { events += MonitorEvent.PermissionDenied(it) }
        val cost = (obj["total_cost_usd"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        MonitorCost.micros(cost)?.let { events += MonitorEvent.Cost(it) }
        return events
    }

    /** The `content` of a message as a list, or null when it is not a list (a plain string, for example). */
    private fun blocks(message: JsonObject): List<JsonObject>? {
        val content = message["content"] as? JsonArray ?: return null
        return content.mapNotNull { it as? JsonObject }
    }

    private fun str(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
}
