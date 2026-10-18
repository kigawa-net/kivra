package net.kigawa.kivra.agent.claude

import kotlin.test.Test
import net.kigawa.kivra.agent.MonitorWire
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extractor reads stream-json lines. Each fixture below carries values that must never reach the monitor: a secret-looking token, a
 * prompt, a path, the model's text and the tool's input and output. The output is checked for each of them (the negative tests).
 */
class MonitorEventExtractorTest {
    private val secretToken = "sk-ant-api03-SECRETTOKEN0123456789"
    private val bearer = "Bearer SECRETBEARERVALUE"
    private val path = "/home/kigawa/secret-project/.env"
    private val prompt = "PROMPTSECRET do the thing"
    private val modelText = "MODELTEXTSECRET the answer"

    private fun extract(line: String): List<MonitorEvent> = MonitorEventExtractor.extract(line)

    @Test
    fun assistantGivesATurnAndTheToolNameOnly() {
        val line = """{"type":"assistant","message":{"role":"assistant","content":[""" +
            """{"type":"text","text":"$modelText $secretToken $path"},""" +
            """{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"$path","token":"$secretToken"}}]}}"""
        assertEquals(listOf(MonitorEvent.Turn, MonitorEvent.ToolUse(MonitorTool.READ)), extract(line))
    }

    @Test
    fun everyAllowedToolNameMapsToItsOwnTool() {
        val names = mapOf(
            "Read" to MonitorTool.READ,
            "Edit" to MonitorTool.EDIT,
            "Write" to MonitorTool.WRITE,
            "Bash" to MonitorTool.BASH,
            "Grep" to MonitorTool.GREP,
            "Glob" to MonitorTool.GLOB,
            "Task" to MonitorTool.TASK,
            "TodoWrite" to MonitorTool.TODO_WRITE,
        )
        for ((name, tool) in names) {
            val line = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$name","input":{}}]}}"""
            assertEquals(listOf(MonitorEvent.Turn, MonitorEvent.ToolUse(tool)), extract(line), name)
        }
    }

    @Test
    fun aToolNameOutsideTheAllowListIsOther() {
        val names = listOf("mcp__secret__tool", "Read ", "read", "$path", "Bash;rm")
        for (name in names) {
            val line = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$name","input":{}}]}}"""
            assertEquals(listOf(MonitorEvent.Turn, MonitorEvent.ToolUse(MonitorTool.OTHER)), extract(line), name)
        }
    }

    @Test
    fun aHugeToolNameIsOtherAndDoesNotGrowTheEvent() {
        val huge = "x".repeat(200_000)
        val line = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"$huge","input":{}}]}}"""
        assertEquals(listOf(MonitorEvent.Turn, MonitorEvent.ToolUse(MonitorTool.OTHER)), extract(line))
    }

    @Test
    fun userToolResultGivesAToolErrorOnlyWhenItIsAnError() {
        val line = """{"type":"user","message":{"content":[""" +
            """{"type":"tool_result","tool_use_id":"t1","content":"$bearer $path $secretToken","is_error":true},""" +
            """{"type":"tool_result","tool_use_id":"t2","content":"ok $prompt"}]}}"""
        assertEquals(listOf(MonitorEvent.ToolError), extract(line))
    }

    @Test
    fun initGivesACliInitWithNothingFromTheLine() {
        val line = """{"type":"system","subtype":"init","cwd":"$path","model":"claude-haiku-5-5","claude_code_version":"2.1.3",""" +
            """"tools":["Read"],"session_id":"$secretToken"}"""
        assertEquals(listOf(MonitorEvent.CliInit), extract(line))
    }

    @Test
    fun initWithAnyValueStillGivesOnlyTheFixedCliInit() {
        assertEquals(listOf(MonitorEvent.CliInit), extract("""{"type":"system","subtype":"init","model":"$prompt"}"""))
        assertEquals(listOf(MonitorEvent.CliInit), extract("""{"type":"system","subtype":"init","tools":["Read"]}"""))
    }

    @Test
    fun apiRetryGivesTheKindOfTheCategory() {
        val cases = mapOf(
            "authentication_failed" to ApiErrorKind.AUTHENTICATION,
            "rate_limit" to ApiErrorKind.RATE_LIMIT,
            "overloaded" to ApiErrorKind.OVERLOADED,
            "billing_error" to ApiErrorKind.BILLING,
            "some_new_category_$secretToken" to ApiErrorKind.OTHER,
        )
        for ((category, kind) in cases) {
            val line = """{"type":"system","subtype":"api_retry","attempt":1,"error":"$category","error_message":"$bearer"}"""
            assertEquals(listOf(MonitorEvent.ApiRetry(kind)), extract(line), category)
        }
    }

    @Test
    fun resultGivesThePermissionDenialCountAndTheCost() {
        val line = """{"type":"result","subtype":"success","is_error":false,"num_turns":3,"result":"$modelText $path",""" +
            """"total_cost_usd":0.123456789,"permission_denials":[{"tool_name":"Bash","tool_input":{"command":"cat $path"}}],""" +
            """"session_id":"sess-1"}"""
        assertEquals(
            listOf(MonitorEvent.PermissionDenied(1), MonitorEvent.Cost(123_457)),
            extract(line),
        )
    }

    @Test
    fun resultWithoutCostOrDenialsGivesNoEvent() {
        assertEquals(emptyList(), extract("""{"type":"result","subtype":"success","result":"$modelText"}"""))
    }

    @Test
    fun aCostOutOfRangeOrNotAPlainNumberIsNotRecorded() {
        val values = listOf("\"0.5\"", "-0.01", "100.000001", "1e400", "null")
        for (value in values) {
            val line = """{"type":"result","subtype":"success","total_cost_usd":$value}"""
            assertEquals(emptyList(), extract(line), value)
        }
    }

    @Test
    fun aCostAtTheLimitsIsRecorded() {
        assertEquals(listOf(MonitorEvent.Cost(0)), extract("""{"type":"result","total_cost_usd":0}"""))
        assertEquals(listOf(MonitorEvent.Cost(100_000_000)), extract("""{"type":"result","total_cost_usd":100}"""))
    }

    @Test
    fun aLineThatIsNotOneJsonObjectGivesNothing() {
        val lines = listOf("", "not json $secretToken", "[1,2,3]", "\"$path\"", "{\"type\":", "42")
        for (line in lines) {
            assertEquals(emptyList(), extract(line), line)
        }
    }

    @Test
    fun aShapeThatIsNotKnownGivesNothing() {
        val lines = listOf(
            """{"type":"stream_event","event":{"delta":"$modelText"}}""",
            """{"type":"system","subtype":"hook_started","hook":"$path"}""",
            """{"type":"assistant"}""",
            """{"type":"assistant","message":"$prompt"}""",
            """{"type":"assistant","message":{"content":"$prompt $path"}}""",
            """{"type":"user","message":{"content":"$prompt"}}""",
            """{"type":"unknown","data":"$secretToken"}""",
        )
        for (line in lines) {
            assertEquals(emptyList(), extract(line), line)
        }
    }

    @Test
    fun aMessageWithTooManyBlocksIsCappedWithOneWarning() {
        val blocks = (1..100).joinToString(",") { """{"type":"tool_use","name":"Read","input":{}}""" }
        val events = extract("""{"type":"assistant","message":{"content":[$blocks]}}""")
        assertEquals(MonitorEvent.Turn, events.first())
        assertEquals(MonitorEventExtractor.MAX_BLOCKS, events.count { it is MonitorEvent.ToolUse })
        assertEquals(MonitorEvent.Warning(MonitorWarning.BLOCK_LIMIT), events.last())
    }

    /**
     * The negative test (ADR 0012 §1.1, §7): every line that carries a secret, a prompt, a path, the model's text, or a tool's input and
     * output gives events whose content, written out, has none of those values.
     */
    @Test
    fun noSecretPromptPathTextOrToolValueReachesAnEvent() {
        val lines = listOf(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"$modelText $secretToken"}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"curl -H '$bearer' $path"}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"$bearer $secretToken $path","is_error":true}]}}""",
            """{"type":"system","subtype":"init","model":"$secretToken","claude_code_version":"$path","cwd":"$path"}""",
            """{"type":"system","subtype":"api_retry","error":"$bearer $path"}""",
            """{"type":"result","subtype":"error_during_execution","result":"$secretToken $prompt","total_cost_usd":1.5,""" +
                """"permission_denials":[{"tool_name":"Edit","tool_input":{"file_path":"$path","new_string":"$secretToken"}}]}""",
        )
        val forbidden = listOf(secretToken, bearer, "Bearer ", "sk-ant-", "/home/", "SECRET", "PROMPT", "MODELTEXT", "file_path", "command")
        for (line in lines) {
            // The wire JSON is what would be sent, so that is what must not carry a value (toString hides values on purpose).
            val rendered = extract(line).mapIndexed { i, event -> MonitorWire.event(i + 1L, event).toString() }.joinToString(" | ")
            for (value in forbidden) {
                assertTrue(!rendered.contains(value, ignoreCase = true), "value '$value' reached the monitor event for line: $line")
            }
        }
    }
}
