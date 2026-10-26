package net.kigawa.kivra.selfdev.llm

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The only two decisions. There is no reject: anything else is [ABSTAIN] (ADR 0021 P1). */
enum class JudgeDecision { APPROVE, ABSTAIN }

/**
 * The fixed reason codes (ADR 0021 design 3). [INVALID_OUTPUT] is ours: the model cannot produce it, it marks an output that was
 * refused by the parser.
 */
enum class JudgeReasonCode(val modelMayWrite: Boolean) {
    CLEAR_STOP_REQUEST(true),
    INSUFFICIENT_EVIDENCE(true),
    CONFLICTING_INPUT(true),
    SUSPECTED_INJECTION(true),
    OUT_OF_POLICY(true),
    INVALID_OUTPUT(false),
}

/** A parsed judgement: nothing but the two enums. */
data class ParsedJudgment(val decision: JudgeDecision, val reason: JudgeReasonCode) {
    companion object {
        val INVALID = ParsedJudgment(JudgeDecision.ABSTAIN, JudgeReasonCode.INVALID_OUTPUT)
    }
}

/**
 * Parses the model output (ADR 0021 P1, P2). It accepts one shape only: a JSON object with exactly the keys decision and reason_code,
 * with plain upper-case values, and nothing before or after it except white space. Everything else (extra keys, a second JSON, a
 * code fence, text around it, an unknown value, a duplicate key, a too long output, invalid UTF-8) is [ParsedJudgment.INVALID]. It
 * never throws, and it never keeps or logs any part of the output.
 */
object StopJudgeOutputParser {
    const val MAX_OUTPUT_CHARS = 256
    private const val VALUE = "([A-Z_]{1,40})"
    private const val WS = "[ \\t\\r\\n]*"
    private val DECISION_FIRST = Regex("\\{$WS\"decision\"$WS:$WS\"$VALUE\"$WS,$WS\"reason_code\"$WS:$WS\"$VALUE\"$WS}")
    private val REASON_FIRST = Regex("\\{$WS\"reason_code\"$WS:$WS\"$VALUE\"$WS,$WS\"decision\"$WS:$WS\"$VALUE\"$WS}")

    fun parse(bytes: ByteArray): ParsedJudgment {
        if (bytes.size > MAX_OUTPUT_CHARS * 4) return ParsedJudgment.INVALID
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return ParsedJudgment.INVALID
        }
        return parse(text)
    }

    fun parse(output: String): ParsedJudgment {
        if (output.length > MAX_OUTPUT_CHARS) return ParsedJudgment.INVALID
        val text = output.trim(' ', '\t', '\r', '\n')
        val a = DECISION_FIRST.matchEntire(text)
        val b = REASON_FIRST.matchEntire(text)
        val (decisionRaw, reasonRaw) = when {
            a != null -> a.groupValues[1] to a.groupValues[2]
            b != null -> b.groupValues[2] to b.groupValues[1]
            else -> return ParsedJudgment.INVALID
        }
        val decision = JudgeDecision.entries.firstOrNull { it.name == decisionRaw } ?: return ParsedJudgment.INVALID
        val reason = JudgeReasonCode.entries.firstOrNull { it.name == reasonRaw && it.modelMayWrite } ?: return ParsedJudgment.INVALID
        return when {
            decision == JudgeDecision.APPROVE && reason == JudgeReasonCode.CLEAR_STOP_REQUEST -> ParsedJudgment(decision, reason)
            decision == JudgeDecision.ABSTAIN && reason != JudgeReasonCode.CLEAR_STOP_REQUEST -> ParsedJudgment(decision, reason)
            else -> ParsedJudgment.INVALID
        }
    }
}
