package net.kigawa.kivra.agent.claude

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.docker.DockerRunOutcome
import net.kigawa.kivra.agent.docker.DockerRunResult

class StreamJsonParserTest {
    private val parser = StreamJsonParser()

    private val init = """{"type":"system","subtype":"init","session_id":"sess-42","model":"x"}"""
    private val assistant = """{"type":"assistant","message":{"content":[{"type":"text","text":"working"}]}}"""
    private val success = """{"type":"result","subtype":"success","is_error":false,"num_turns":3,"result":"Done.",""" +
        """"session_id":"sess-42","total_cost_usd":0.0123,"usage":{"input_tokens":120,"output_tokens":45,""" +
        """"cache_creation_input_tokens":10,"cache_read_input_tokens":300}}"""

    private fun stream(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    private fun run(exit: Int?, stdout: String, outputTruncated: Boolean = false, outcome: DockerRunOutcome = DockerRunOutcome.EXITED) =
        DockerRunResult(outcome, exit, stdout, "", outputTruncated)

    private fun classify(stdout: String, exit: Int? = 0, parser: StreamJsonParser = this.parser): AgentRunOutcome =
        CliOutcomeClassifier.classify(run(exit, stdout), parser.parse(stdout))

    @Test
    fun successfulStreamGivesFinalResultUsageAndSession() {
        val text = stream(init, assistant, success)
        val report = parser.parse(text)
        val result = assertNotNull(report.result)
        assertEquals("success", result.subtype)
        assertFalse(result.isError)
        assertEquals("Done.", result.resultText)
        assertEquals(3, result.numTurns)
        assertEquals("sess-42", result.sessionId)
        assertEquals(120L, result.usage.inputTokens)
        assertEquals(45L, result.usage.outputTokens)
        assertEquals(10L, result.usage.cacheCreationInputTokens)
        assertEquals(300L, result.usage.cacheReadInputTokens)
        assertEquals(0.0123, result.usage.costUsd)
        assertEquals(3, report.eventCount)
        assertEquals(0, report.invalidLines)
        assertEquals(0, report.oversizedLines)
        assertFalse(report.outputLimitReached)
        assertEquals(AgentRunOutcome.SUCCEEDED, classify(text))
    }

    @Test
    fun lastResultWinsAndMissingUsageIsNull() {
        val report = parser.parse(stream("""{"type":"result","subtype":"error_max_turns","is_error":true}""", success))
        assertEquals("success", report.result!!.subtype)
        val bare = parser.parse(stream("""{"type":"result","subtype":"success"}"""))
        assertNull(bare.result!!.usage.inputTokens)
        assertNull(bare.result!!.usage.costUsd)
        assertFalse(bare.result!!.isError)
    }

    @Test
    fun sessionIdWithUnexpectedShapeIsDropped() {
        val report = parser.parse(stream("""{"type":"result","subtype":"success","session_id":"a;rm -rf /"}"""))
        assertNull(report.result!!.sessionId)
    }

    @Test
    fun apiErrorCategoriesAreMappedToTheirOwnClassification() {
        val auth = stream(
            """{"type":"system","subtype":"api_retry","attempt":1,"error":"authentication_failed"}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true}""",
        )
        assertEquals(AgentRunOutcome.AUTH_FAILED, classify(auth, exit = 1))
        val rate = stream(
            """{"type":"system","subtype":"api_retry","error":"rate_limit"}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true}""",
        )
        assertEquals(AgentRunOutcome.RATE_LIMITED, classify(rate, exit = 1))
        val overloaded = stream("""{"type":"system","subtype":"api_retry","error":"overloaded"}""")
        assertEquals(AgentRunOutcome.OVERLOADED, classify(overloaded, exit = 1), "no result line, an API category decides")
    }

    @Test
    fun authenticationOutranksRateLimitWhenBothAppear() {
        val text = stream(
            """{"type":"system","subtype":"api_retry","error":"overloaded"}""",
            """{"type":"system","subtype":"api_retry","error":"rate_limit"}""",
            """{"type":"system","subtype":"api_retry","error":"authentication_failed"}""",
            """{"type":"result","subtype":"error_during_execution","is_error":true}""",
        )
        assertEquals(AgentRunOutcome.AUTH_FAILED, classify(text, exit = 1))
    }

    @Test
    fun errorResultWithoutCategoryIsCliFailed() {
        assertEquals(AgentRunOutcome.CLI_FAILED, classify(stream("""{"type":"result","subtype":"success","is_error":true}"""), exit = 0))
        assertEquals(AgentRunOutcome.CLI_FAILED, classify(stream("""{"type":"result","subtype":"error_max_turns","is_error":false}""")))
        assertEquals(AgentRunOutcome.CLI_FAILED, classify(stream("""{"type":"result"}""")), "no subtype is not success")
    }

    @Test
    fun nonZeroExitWithSuccessResultIsCliFailed() {
        assertEquals(AgentRunOutcome.CLI_FAILED, classify(stream(success), exit = 1))
        assertEquals(AgentRunOutcome.CLI_FAILED, classify(stream(success), exit = null))
    }

    @Test
    fun noResultLineAndNonZeroExitIsCliFailed() {
        assertEquals(AgentRunOutcome.CLI_FAILED, classify(stream(init, assistant), exit = 2))
    }

    @Test
    fun invalidLinesAreCountedNotParsedAndMakeTheOutputMalformed() {
        val text = stream(init, "this is not json", "[1,2,3]", "42", """{"type":"result"""", success)
        val report = parser.parse(text)
        assertEquals(4, report.invalidLines)
        assertNotNull(report.result, "a later valid result is still read")
        assertEquals(AgentRunOutcome.MALFORMED_OUTPUT, classify(text))
    }

    @Test
    fun cutStreamWithoutResultIsMalformedWhenTheExitIsZero() {
        val cut = init + "\n" + """{"type":"assistant","message":{"content":[{"type":"te"""
        val report = parser.parse(cut)
        assertEquals(1, report.invalidLines, "the cut last line has no newline and is still counted once")
        assertNull(report.result)
        assertEquals(AgentRunOutcome.MALFORMED_OUTPUT, classify(cut, exit = 0))
    }

    @Test
    fun blankLinesAndCarriageReturnsAreAccepted() {
        val report = parser.parse("\r\n" + success + "\r\n\r\n")
        assertEquals(0, report.invalidLines)
        assertEquals(AgentRunOutcome.SUCCEEDED, classify("\r\n" + success + "\r\n"))
    }

    @Test
    fun oversizedLineIsSkippedAndTheOutputIsOutputLimit() {
        val small = StreamJsonParser(maxLineBytes = 1024)
        val huge = """{"type":"assistant","message":"${"x".repeat(5000)}"}"""
        val text = stream(init, huge, success)
        val report = small.parse(text)
        assertEquals(1, report.oversizedLines)
        assertEquals(0, report.invalidLines, "the oversized line is not parsed at all")
        assertNotNull(report.result, "the lines after it are still read")
        assertEquals(AgentRunOutcome.OUTPUT_LIMIT, CliOutcomeClassifier.classify(run(0, text), report))
    }

    @Test
    fun lineExactlyAtTheLimitIsNotOversized() {
        val line = success
        val exact = StreamJsonParser(maxLineBytes = line.length)
        assertEquals(0, exact.parse(line + "\n").oversizedLines)
        assertEquals(1, StreamJsonParser(maxLineBytes = line.length - 1).parse(line + "\n").oversizedLines)
    }

    @Test
    fun streamLongerThanTheTotalLimitStopsAndIsOutputLimit() {
        val limited = StreamJsonParser(maxTotalBytes = 200)
        val text = stream(init, assistant, assistant, assistant, success)
        val report = limited.parse(text)
        assertTrue(report.outputLimitReached)
        assertNull(report.result, "the result after the limit is not read")
        assertEquals(AgentRunOutcome.OUTPUT_LIMIT, CliOutcomeClassifier.classify(run(0, text), report))
    }

    @Test
    fun runnerOutputTruncationIsOutputLimitEvenWithAGoodResult() {
        val text = stream(success)
        val report = parser.parse(text)
        assertEquals(AgentRunOutcome.OUTPUT_LIMIT, CliOutcomeClassifier.classify(run(0, text, outputTruncated = true), report))
    }

    @Test
    fun unknownEventsAreCountedAndIgnored() {
        val report = parser.parse(stream("""{"type":"stream_event","event":{}}""", """{"type":"user"}""", success))
        assertEquals(3, report.eventCount)
        assertEquals(0, report.invalidLines)
    }

    @Test
    fun deeplyNestedInputIsRejectedAsInvalidWithoutFailing() {
        val nested = "[".repeat(200_000)
        val report = parser.parse(stream(nested, success))
        assertEquals(1, report.invalidLines)
    }

    @Test
    fun readsFromAStreamAndFromAString() {
        val text = stream(init, success)
        val fromStream = parser.parse(ByteArrayInputStream(text.toByteArray()))
        assertEquals(parser.parse(text).toString(), fromStream.toString())
    }

    @Test
    fun resultTextIsNotInTheToStringOfTheResult() {
        val secret = "SECRET-RESULT-TEXT-7731"
        val report = parser.parse(stream("""{"type":"result","subtype":"success","result":"$secret"}"""))
        assertEquals(secret, report.result!!.resultText)
        assertFalse(report.result.toString().contains(secret))
        assertFalse(report.toString().contains(secret))
    }
}
