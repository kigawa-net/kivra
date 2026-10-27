package net.kigawa.kivra.selfdev.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The stop_reason of a response, mapped to a fixed set. The raw value is never kept. */
enum class JudgeStopReason {
    END_TURN, MAX_TOKENS, REFUSAL, STOP_SEQUENCE, TOOL_USE, PAUSE_TURN, OTHER, ABSENT;

    companion object {
        fun of(raw: String?): JudgeStopReason = when (raw) {
            null -> ABSENT
            "end_turn" -> END_TURN
            "max_tokens" -> MAX_TOKENS
            "refusal" -> REFUSAL
            "stop_sequence" -> STOP_SEQUENCE
            "tool_use" -> TOOL_USE
            "pause_turn" -> PAUSE_TURN
            else -> OTHER
        }
    }
}

/** The shape of a model output, as a fixed code. No content. */
enum class JudgeOutputShape {
    EMPTY, STARTS_WITH_BRACE, FENCED, OTHER_TEXT, TOO_LONG, NON_TEXT_ONLY;

    companion object {
        fun classify(hasTextBlock: Boolean, text: String): JudgeOutputShape {
            if (!hasTextBlock) return NON_TEXT_ONLY
            val t = text.trim()
            return when {
                t.isEmpty() -> EMPTY
                text.length > StopJudgeOutputParser.MAX_OUTPUT_CHARS -> TOO_LONG
                t.startsWith("```") -> FENCED
                t.startsWith("{") -> STARTS_WITH_BRACE
                else -> OTHER_TEXT
            }
        }
    }
}

/** Diagnostics of one call: fixed codes only. [shape] is set only when the parser refused the output. */
data class JudgeDiagnostics(val stopReason: JudgeStopReason, val shape: JudgeOutputShape?) {
    companion object {
        val NONE = JudgeDiagnostics(JudgeStopReason.ABSENT, null)
    }
}

/** The JSON schema of the output, built from the enums (not written twice). One place. */
object StopJudgeOutputSchema {
    val schema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("decision", buildJsonObject {
                put("type", "string")
                put("enum", enumArray(JudgeDecision.entries.map { it.name }))
            })
            put("reason_code", buildJsonObject {
                put("type", "string")
                put("enum", enumArray(JudgeReasonCode.entries.filter { it.modelMayWrite }.map { it.name }))
            })
        })
        put("required", enumArray(listOf("decision", "reason_code")))
        put("additionalProperties", false)
    }

    private fun enumArray(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
}
