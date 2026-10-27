package net.kigawa.kivra.selfdev.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class StopJudgeOutputParserTest {
    private val abstain = ParsedJudgment.INVALID
    private val approve = ParsedJudgment(JudgeDecision.APPROVE, JudgeReasonCode.CLEAR_STOP_REQUEST)

    @Test
    fun `a correct approve passes, with surrounding white space and either key order`() {
        assertEquals(approve, StopJudgeOutputParser.parse("""{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}"""))
        assertEquals(approve, StopJudgeOutputParser.parse("  \n{ \"decision\" : \"APPROVE\" , \"reason_code\" : \"CLEAR_STOP_REQUEST\" }\n"))
        assertEquals(approve, StopJudgeOutputParser.parse("""{"reason_code":"CLEAR_STOP_REQUEST","decision":"APPROVE"}"""))
    }

    @Test
    fun `an abstain with a fixed reason passes`() {
        for (r in listOf("INSUFFICIENT_EVIDENCE", "CONFLICTING_INPUT", "SUSPECTED_INJECTION", "OUT_OF_POLICY")) {
            assertEquals(
                ParsedJudgment(JudgeDecision.ABSTAIN, JudgeReasonCode.valueOf(r)),
                StopJudgeOutputParser.parse("""{"decision":"ABSTAIN","reason_code":"$r"}"""),
            )
        }
    }

    @Test
    fun `everything else is ABSTAIN with INVALID_OUTPUT`() {
        val bad = listOf(
            "",
            "   ",
            "APPROVE",
            """{"decision":"APPROVE"}""",
            """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST","note":"x"}""",
            """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"} extra""",
            """text {"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}
{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}""",
            "```json\n{\"decision\":\"APPROVE\",\"reason_code\":\"CLEAR_STOP_REQUEST\"}\n```",
            """{"decision":"REJECT","reason_code":"OUT_OF_POLICY"}""",
            """{"decision":"REJECT","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"approve","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"APPROVE","reason_code":"UNKNOWN_CODE"}""",
            """{"decision":"APPROVE","reason_code":"INVALID_OUTPUT"}""",
            """{"decision":"ABSTAIN","reason_code":"INVALID_OUTPUT"}""",
            """{"decision":"APPROVE","reason_code":"OUT_OF_POLICY"}""",
            """{"decision":"ABSTAIN","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"APPROVE","decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"ABSTAIN","decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":["APPROVE"],"reason_code":"CLEAR_STOP_REQUEST"}""",
            """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST\u0000"}""",
            "{\"decision\":\"APPROVE\u200B\",\"reason_code\":\"CLEAR_STOP_REQUEST\"}",
            "\uFEFF{\"decision\":\"APPROVE\",\"reason_code\":\"CLEAR_STOP_REQUEST\"}",
            "{\"decision\":\"APPROVE\",\"reason_code\":\"CLEAR_STOP_REQUEST\"}" + " ".repeat(500),
            "{\"decision\":\"ABSTAIN\",\"reason_code\":\"" + "A".repeat(100_000) + "\"}",
        )
        for (s in bad) assertEquals(abstain, StopJudgeOutputParser.parse(s), "must be refused: ${s.take(60)}")
    }

    @Test
    fun `invalid UTF-8 and a huge byte output are refused`() {
        val good = """{"decision":"APPROVE","reason_code":"CLEAR_STOP_REQUEST"}""".toByteArray()
        assertEquals(approve, StopJudgeOutputParser.parse(good))
        assertEquals(abstain, StopJudgeOutputParser.parse(good + byteArrayOf(0xC3.toByte())))
        assertEquals(abstain, StopJudgeOutputParser.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals(abstain, StopJudgeOutputParser.parse(ByteArray(10_000_000) { 'a'.code.toByte() }))
        assertEquals(abstain, StopJudgeOutputParser.parse(ByteArray(0)))
    }

    @Test
    fun `no decision value other than approve and abstain exists`() {
        assertEquals(setOf("APPROVE", "ABSTAIN"), JudgeDecision.entries.map { it.name }.toSet())
    }
}
