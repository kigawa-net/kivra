package net.kigawa.kivra.modelgateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val API_KEY = "sk-test-SECRET-123"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

private fun textBody() = """{"id":"msg_1","type":"message","role":"assistant","model":"contract-model-name","content":[{"type":"text","text":"Hello"}],"stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":5,"cache_read_input_tokens":3}}"""
private fun toolBody() = """{"id":"msg_2","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Tokyo"}}],"stop_reason":"tool_use","usage":{"input_tokens":20,"output_tokens":9}}"""
private fun sseText() = """
event: message_start
data: {"type":"message_start","message":{"id":"m","usage":{"input_tokens":10,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

event: message_stop
data: {"type":"message_stop"}

""".trimIndent()

class AnthropicProviderTest : ModelProviderContract() {
    private fun MockRequestHandleScope.reply(s: Scenario, req: HttpRequestData): HttpResponseData = when (s) {
        Scenario.TEXT -> if (req.body.toString().contains("\"stream\":true") || (req.body as? TextContent)?.text?.contains("\"stream\":true") == true)
            respond(sseText(), HttpStatusCode.OK, sseHeaders) else respond(textBody(), HttpStatusCode.OK, jsonHeaders)
        Scenario.TOOL_CALL -> respond(toolBody(), HttpStatusCode.OK, jsonHeaders)
        Scenario.RATE_LIMITED -> respond("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", HttpStatusCode.TooManyRequests,
            headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("2")))
        Scenario.SERVER_ERROR -> respond("""{"type":"error","error":{"type":"api_error","message":"boom"}}""", HttpStatusCode.InternalServerError, jsonHeaders)
        Scenario.UNAUTHORIZED -> respond("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key $API_KEY"}}""", HttpStatusCode.Unauthorized, jsonHeaders)
        Scenario.BAD_REQUEST -> respond("""{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}""", HttpStatusCode.BadRequest, jsonHeaders)
        Scenario.CONNECTION_RESET -> throw IOException("connection reset")
        Scenario.NO_CREDENTIAL -> error("must not call the network without a credential")
    }

    private fun build(s: Scenario, captured: MutableList<HttpRequestData>? = null): AnthropicProvider {
        val engine = MockEngine { req -> captured?.add(req); reply(s, req) }
        val creds = if (s == Scenario.NO_CREDENTIAL) CredentialSource { null } else CredentialSource { if (it == target.provider.credentialRef) API_KEY else null }
        return AnthropicProvider(HttpClient(engine), creds, providerId = "contract")
    }

    override fun providerFor(scenario: Scenario): ModelProvider = build(scenario)

    private fun bodyOf(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject

    @Test fun requestShapeAndHeaders() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val req = request(maxOut = 321).copy(messages = listOf(Message(Role.SYSTEM, "sys"), Message(Role.USER, "hi")),
            generation = GenerationParams(321, temperature = 0.2, stopSequences = listOf("END")))
        build(Scenario.TEXT, seen).invoke(req, target, ctx)
        val r = seen.single()
        assertEquals("https://contract.example.test/v1/messages", r.url.toString())
        assertEquals(API_KEY, r.headers["x-api-key"]); assertNotNull(r.headers["anthropic-version"])
        val b = bodyOf(r)
        assertEquals("contract-model-name", b["model"]!!.jsonPrimitive.content, "model name comes from the registry")
        assertEquals(321, b["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("sys", b["system"]!!.jsonPrimitive.content)
        assertEquals(1, b["messages"]!!.jsonArray.size)
        assertEquals("END", b["stop_sequences"]!!.jsonArray.single().jsonPrimitive.content)
        assertNull(b["tools"])
    }

    @Test fun maxTokensIsClampedToModelLimit() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        build(Scenario.TEXT, seen).invoke(request(maxOut = 999_999), target, ctx)
        assertEquals(target.model.maxOutputTokens, bodyOf(seen.single())["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun toolsAndToolResultsAreMapped() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val req = request(tools = listOf(tool)).copy(
            toolChoice = ToolChoice.REQUIRED,
            messages = listOf(
                Message(Role.USER, "weather?"),
                Message(Role.ASSISTANT, listOf(Part.ToolCall("toolu_1", "get_weather", buildJsonObject { put("city", "Tokyo") }))),
                Message(Role.TOOL, listOf(Part.ToolResult("toolu_1", "sunny"))),
            ),
        )
        build(Scenario.TOOL_CALL, seen).invoke(req, target, ctx)
        val b = bodyOf(seen.single())
        assertEquals("any", b["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", b["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val last = b["messages"]!!.jsonArray.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        assertEquals("tool_result", last["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun toolCallArgumentsArePassedBackVerbatim() = runTest {
        val r = providerFor(Scenario.TOOL_CALL).invoke(request(tools = listOf(tool)), target, ctx)
        val call = r.message.parts.filterIsInstance<Part.ToolCall>().single()
        assertEquals("Tokyo", call.arguments.jsonObject["city"]!!.jsonPrimitive.content)
    }

    @Test fun cachedTokensAreReported() = runTest {
        assertEquals(Usage(12, 5, 3), providerFor(Scenario.TEXT).invoke(request(), target, ctx).usage)
    }

    @Test fun retryAfterHeaderIsCarriedOnRateLimit() = runTest {
        val e = assertFailsWith<GatewayException> { providerFor(Scenario.RATE_LIMITED).invoke(request(), target, ctx) }
        assertEquals(2000L, e.error.retryAfterMs)
    }

    @Test fun apiKeyNeverAppearsInErrors() = runTest {
        val e = assertFailsWith<GatewayException> { providerFor(Scenario.UNAUTHORIZED).invoke(request(), target, ctx) }
        assertFalse(e.error.message.contains(API_KEY)); assertFalse((e.message ?: "").contains(API_KEY))
    }

    @Test fun structuredOutputUsesForcedToolAndReturnsParsedJson() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            seen.add(req)
            respond("""{"content":[{"type":"tool_use","id":"t","name":"${AnthropicProvider.STRUCTURED_TOOL_NAME}","input":{"answer":42}}],"stop_reason":"tool_use","usage":{"input_tokens":5,"output_tokens":4}}""", HttpStatusCode.OK, jsonHeaders)
        }
        val p = AnthropicProvider(HttpClient(engine), { API_KEY }, "contract")
        val r = p.invoke(request().copy(responseFormat = ResponseFormat.JsonSchema("out", emptySchema)), target, ctx)
        assertEquals(42, r.structured!!.jsonObject["answer"]!!.jsonPrimitive.content.toInt())
        assertEquals(FinishReason.STOP, r.finishReason)
        assertEquals(AnthropicProvider.STRUCTURED_TOOL_NAME, bodyOf(seen.single())["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun structuredOutputWithoutToolUseIsOutputInvalid() = runTest {
        val engine = MockEngine { respond(textBody(), HttpStatusCode.OK, jsonHeaders) }
        val p = AnthropicProvider(HttpClient(engine), { API_KEY }, "contract")
        val e = assertFailsWith<GatewayException> { p.invoke(request().copy(responseFormat = ResponseFormat.JsonSchema("o", emptySchema)), target, ctx) }
        assertEquals(ErrorKind.OUTPUT_INVALID, e.error.kind)
    }

    @Test fun structuredOutputCombinedWithToolsIsUnsupported() = runTest {
        val e = assertFailsWith<GatewayException> {
            providerFor(Scenario.TEXT).invoke(request(tools = listOf(tool)).copy(responseFormat = ResponseFormat.JsonSchema("o", emptySchema)), target, ctx)
        }
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, e.error.kind)
    }

    @Test fun streamAggregatesTextAndUsage() = runTest {
        val chunks = providerFor(Scenario.TEXT).stream(request(), target, ctx).toList()
        assertEquals("Hello", chunks.filterIsInstance<StreamChunk.Text>().joinToString("") { it.text })
        val fin = chunks.last() as StreamChunk.Finished
        assertEquals(Usage(10, 7), fin.response.usage)
        assertEquals("Hello", (fin.response.message.parts.single() as Part.Text).text)
    }

    @Test fun streamToolCallIsCompletedOnlyAfterArgumentsAreAssembled() = runTest {
        val sse = """
data: {"type":"message_start","message":{"usage":{"input_tokens":4,"output_tokens":1}}}

data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_9","name":"get_weather","input":{}}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}

data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"Osaka\"}"}}

data: {"type":"content_block_stop","index":0}

data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

data: {"type":"message_stop"}

""".trimIndent()
        val p = AnthropicProvider(HttpClient(MockEngine { respond(sse, HttpStatusCode.OK, sseHeaders) }), { API_KEY }, "contract")
        val chunks = p.stream(request(tools = listOf(tool)), target, ctx).toList()
        val completedAt = chunks.indexOfFirst { it is StreamChunk.ToolCallCompleted }
        assertTrue(chunks.take(completedAt).all { it !is StreamChunk.ToolCallCompleted })
        assertTrue(chunks.subList(0, completedAt).any { it is StreamChunk.ToolCallDelta })
        val done = chunks[completedAt] as StreamChunk.ToolCallCompleted
        assertEquals("Osaka", done.args.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals(FinishReason.TOOL_CALL, (chunks.last() as StreamChunk.Finished).response.finishReason)
    }

    @Test fun truncatedStreamIsTransient() = runTest {
        val p = AnthropicProvider(HttpClient(MockEngine { respond("data: {\"type\":\"message_start\",\"message\":{\"usage\":{}}}\n\n", HttpStatusCode.OK, sseHeaders) }), { API_KEY }, "contract")
        val e = assertFailsWith<GatewayException> { p.stream(request(), target, ctx).toList() }
        assertEquals(ErrorKind.TRANSIENT, e.error.kind)
    }

    @Test fun streamErrorEventIsClassified() = runTest {
        val sse = "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}\n\n"
        val p = AnthropicProvider(HttpClient(MockEngine { respond(sse, HttpStatusCode.OK, sseHeaders) }), { API_KEY }, "contract")
        assertEquals(ErrorKind.TRANSIENT, assertFailsWith<GatewayException> { p.stream(request(), target, ctx).toList() }.error.kind)
    }

    @Test fun healthReflectsCredentialPresenceWithoutNetwork() = runTest {
        assertEquals(ProviderHealthState.UP, providerFor(Scenario.TEXT).health(target).state)
        assertEquals(ProviderHealthState.DOWN, providerFor(Scenario.NO_CREDENTIAL).health(target).state)
    }

    @Test fun listModelsParsesProviderResponse() = runTest {
        val engine = MockEngine { respond("""{"data":[{"id":"a-1","display_name":"A"},{"id":"b-2"}]}""", HttpStatusCode.OK, jsonHeaders) }
        val models = AnthropicProvider(HttpClient(engine), { API_KEY }, "contract").listModels(target)
        assertEquals(listOf("a-1", "b-2"), models.map { it.providerModelName })
    }

    @Test fun envCredentialSourceReadsOnlyNamedVariable() {
        val c = EnvCredentialSource(mapOf("ANTHROPIC_API_KEY" to "k", "EMPTY" to " "))
        assertEquals("k", c.get("ANTHROPIC_API_KEY")); assertNull(c.get("EMPTY")); assertNull(c.get("OTHER"))
    }
}
