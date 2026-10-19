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
    fun loggedFieldsAreCountsAndEnumNamesOnly() {
        val denied = """{"type":"result","subtype":"success","is_error":false,"num_turns":5,"result":"MARKER-TEXT",""" +
            """"permission_denials":[{"tool_name":"Bash","tool_input":{"command":"MARKER-INPUT"}},{"tool_name":"Write"}],""" +
            """"stop_reason":"end_turn","terminal_reason":"Bad Value With Spaces"}"""
        val result = assertNotNull(parser.parse(stream(denied)).result)
        assertEquals(2, result.permissionDenials)
        assertEquals("end_turn", result.stopReason)
        assertNull(result.terminalReason, "a value that is not an enum name is not kept")
        val described = CliResultLog.describe(result)
        assertTrue(described.contains("permissionDenials=2"), described)
        assertTrue(described.contains("numTurns=5"), described)
        assertTrue(described.contains("terminalReason=none"), described)
        assertFalse(described.contains("MARKER"), described)
        assertEquals("hasResult=false", CliResultLog.describe(null))
    }

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

    /** One valid stream-json event of exactly [bytes] bytes, newline included. */
    private fun eventOf(bytes: Int): String = "{\"type\":\"user\",\"pad\":\"" + "a".repeat(bytes - 25) + "\"}\n"

    @Test
    fun theDefaultTotalLimitIsEightMiB() {
        val half = 512 * 1024
        assertFalse(StreamJsonParser().parse(eventOf(half).repeat(4)).outputLimitReached, "2 MiB is read to the end")
        assertFalse(StreamJsonParser().parse(eventOf(half).repeat(16)).outputLimitReached, "exactly 8 MiB is read to the end")
        assertTrue(StreamJsonParser().parse(eventOf(half).repeat(16) + "x").outputLimitReached, "the byte after 8 MiB stops the reading")
    }

    @Test
    fun theDefaultLineLimitStaysOneMiB() {
        val oneMiB = 1024 * 1024
        val exact = StreamJsonParser().parse("x".repeat(oneMiB) + "\n")
        assertEquals(0, exact.oversizedLines)
        assertEquals(1, exact.invalidLines)
        val over = StreamJsonParser().parse("x".repeat(oneMiB + 1) + "\n")
        assertEquals(1, over.oversizedLines)
        assertEquals(0, over.invalidLines)
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

    private fun resultJson(
        cost: String = "0.004268",
        turns: String = "3",
        usage: String = """{"input_tokens":120,"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":300}""",
        models: String? = """{"claude-haiku-5-5":{"costUSD":0.004268,"inputTokens":120}}""",
    ): String {
        val modelPart = if (models == null) "" else ""","modelUsage":$models"""
        return """{"type":"result","subtype":"success","is_error":false,"num_turns":$turns,"result":"Done.",""" +
            """"total_cost_usd":$cost,"usage":$usage$modelPart}"""
    }

    private fun reported(json: String): ReportedUsage? = parser.parse(stream(json)).result?.reportedUsage

    @Test
    fun reportedUsageIsBuiltFromTheFinalResult() {
        val usage = assertNotNull(reported(resultJson()))
        assertEquals("0.004268", usage.totalCostUsd)
        assertEquals("claude-haiku-5-5", usage.model)
        assertEquals(3, usage.numTurns)
        assertEquals(120L, usage.inputTokens)
        assertEquals(45L, usage.outputTokens)
        assertEquals(10L, usage.cacheCreationTokens)
        assertEquals(300L, usage.cacheReadTokens)
    }

    @Test
    fun costIsRoundedToSixDecimalsAndKeptInZeroToOneHundred() {
        assertEquals("0.000001", assertNotNull(reported(resultJson(cost = "0.0000005"))).totalCostUsd)
        assertEquals("0.000000", assertNotNull(reported(resultJson(cost = "0.0000001"))).totalCostUsd)
        assertEquals("100.000000", assertNotNull(reported(resultJson(cost = "100"))).totalCostUsd)
        assertEquals("0.000000", assertNotNull(reported(resultJson(cost = "0"))).totalCostUsd)
        assertNull(reported(resultJson(cost = "100.0000001")), "above 100 is refused, not clipped")
        assertNull(reported(resultJson(cost = "-0.01")))
        assertNull(reported(resultJson(cost = "\"0.004\"")), "a cost that is a JSON string is not a number")
    }

    @Test
    fun aMissingOrMalformedPartMakesTheWholeUsageAbsent() {
        val inputMissing = """{"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":300}"""
        val inputNegative = """{"input_tokens":-1,"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":300}"""
        val inputTooBig = """{"input_tokens":10000000001,"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":300}"""
        val inputFloat = """{"input_tokens":1.5,"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":300}"""
        for (usage in listOf(inputMissing, inputNegative, inputTooBig, inputFloat)) {
            assertNull(reported(resultJson(usage = usage)), usage)
        }
        assertNull(reported(resultJson(turns = "1001")), "more than 1000 turns")
        assertNull(reported(resultJson(turns = "-1")), "negative turns")
        assertNull(reported(resultJson(models = null)), "no modelUsage")
        assertNull(reported(resultJson(models = "{}")), "an empty modelUsage")
        assertNull(reported(resultJson(models = """{"Claude-Haiku":{"costUSD":0.1}}""")), "a model name with capitals")
        assertNull(
            reported(resultJson(models = """{"claude-haiku-5-5":{"costUSD":0.1},"bad name":{"costUSD":0.2}}""")),
            "one bad model name makes the usage absent",
        )
    }

    @Test
    fun theModelThatCostTheMostIsReported() {
        val models = """{"claude-haiku-5-5":{"costUSD":0.001},"claude-opus-5-5":{"costUSD":0.003}}"""
        assertEquals("claude-opus-5-5", assertNotNull(reported(resultJson(models = models))).model)
        val tie = """{"claude-sonnet-5-5":{"costUSD":0.002},"claude-haiku-5-5":{"costUSD":0.002}}"""
        assertEquals("claude-haiku-5-5", assertNotNull(reported(resultJson(models = tie))).model, "a tie goes to the smaller name")
    }

    @Test
    fun noResultMeansNoReportedUsage() {
        assertNull(parser.parse(stream(init, assistant)).result)
        assertNull(reported("""{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":4}"""))
    }

    @Test
    fun reportedUsageToStringShowsNoValue() {
        val usage = assertNotNull(reported(resultJson()))
        assertFalse(usage.toString().contains("0.004268"))
        assertFalse(usage.toString().contains("haiku"))
    }
}
