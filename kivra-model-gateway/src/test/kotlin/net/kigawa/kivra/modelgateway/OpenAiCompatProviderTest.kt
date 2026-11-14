package net.kigawa.kivra.modelgateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NO_KEY_REF = "LOCAL_LLM_NONE"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

private val localProvider = ProviderEntry(
    "local", "openai", TrustZone.LOCAL,
    DataHandling(DataHandlingStatus.CONFIRMED, "ops", java.time.LocalDate.of(2026, 10, 1), "https://example.test/llm"),
    true, "http://llm.test:8000", NO_KEY_REF,
)
private val localTarget = ResolvedTarget(localProvider, model("local-chat", "local", caps = setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT)))
private val ctx = CallContext("req-1", "att-1", "dec-1")
private val boundSource = BoundCredentialSource(listOf(CredentialBinding("local", "llm.test", NO_KEY_REF, null)), emptyMap())

private fun textBody() = """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":3}}}"""
private fun toolBody() = """{"id":"c2","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Tokyo\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":9}}"""
private fun sseText() = """
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":0}}}

data: [DONE]

""".trimIndent()
private fun sseTool() = """
data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Tokyo\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]

""".trimIndent()

private fun provider(engine: MockEngine, creds: CredentialSource = boundSource) =
    OpenAiCompatProvider(HttpClient(engine), creds, providerId = "local")

private fun bodyOf(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject

class OpenAiCompatProviderTest {
    @Test
    fun `invoke posts to the chat completions path with no credential header and maps text and usage`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val p = provider(MockEngine { req -> seen.add(req); respond(textBody(), HttpStatusCode.OK, jsonHeaders) })
        val r = p.invoke(request(), localTarget, ctx)
        val sent = seen.single()
        assertEquals("http://llm.test:8000/v1/chat/completions", sent.url.toString())
        assertNull(sent.headers[HttpHeaders.Authorization], "a LOCAL endpoint without a credential gets no Authorization header")
        val b = bodyOf(sent)
        assertEquals("local-chat-name", b["model"]!!.jsonPrimitive.content, "model name comes from the registry")
        assertEquals(false, b["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Hello", (r.message.parts.single() as Part.Text).text)
        assertEquals(FinishReason.STOP, r.finishReason)
        // prompt_tokens includes the 3 cached tokens; the Gateway input count excludes them.
        assertEquals(Usage(inputTokens = 9, outputTokens = 5, cachedInputTokens = 3), r.usage)
        assertTrue(r.usageComplete)
        assertEquals(Cost.zero(), r.cost)
    }

    @Test
    fun `tool calls are parsed from the message and the arguments are sent back as a JSON string`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val p = provider(MockEngine { req -> seen.add(req); respond(toolBody(), HttpStatusCode.OK, jsonHeaders) })
        val tool = ToolSpec("get_weather", "weather", buildJsonObject { put("type", "object") })
        val r = p.invoke(request(tools = listOf(tool)).copy(toolChoice = ToolChoice.REQUIRED), localTarget, ctx)
        val call = r.message.parts.filterIsInstance<Part.ToolCall>().single()
        assertEquals("call_1", call.id)
        assertEquals("Tokyo", call.arguments.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals(FinishReason.TOOL_CALL, r.finishReason)
        val b = bodyOf(seen.single())
        assertEquals("required", b["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("function", b["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool results and the system prompt are mapped to chat roles`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val p = provider(MockEngine { req -> seen.add(req); respond(textBody(), HttpStatusCode.OK, jsonHeaders) })
        val req = request().copy(messages = listOf(
            Message(Role.SYSTEM, "sys"),
            Message(Role.USER, "weather?"),
            Message(Role.ASSISTANT, listOf(Part.ToolCall("call_1", "get_weather", buildJsonObject { put("city", "Tokyo") }))),
            Message(Role.TOOL, listOf(Part.ToolResult("call_1", "sunny"))),
        ))
        p.invoke(req, localTarget, ctx)
        val msgs = bodyOf(seen.single())["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("call_1", msgs[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny", msgs[3]["content"]!!.jsonPrimitive.content)
        assertEquals("get_weather", msgs[2]["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `HTTP errors are classified and never keep the provider message`() = runTest {
        val echoed = "PROMPT-FRAGMENT-SECRET"
        suspend fun kindOf(status: HttpStatusCode, headers: io.ktor.http.Headers = jsonHeaders): GatewayError {
            val p = provider(MockEngine { respond("""{"error":{"type":"x","message":"$echoed"}}""", status, headers) })
            val e = assertFailsWith<GatewayException> { p.invoke(request(), localTarget, ctx) }
            assertFalse(echoed in e.error.message, "the provider message must not be kept")
            return e.error
        }
        val limited = kindOf(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("2")))
        assertEquals(ErrorKind.RATE_LIMITED, limited.kind)
        assertEquals(2000L, limited.retryAfterMs)
        assertEquals(ErrorKind.TRANSIENT, kindOf(HttpStatusCode.InternalServerError).kind)
        assertEquals(ErrorKind.INVALID_REQUEST, kindOf(HttpStatusCode.BadRequest).kind)
        assertEquals(ErrorKind.AUTH, kindOf(HttpStatusCode.Unauthorized).kind)
    }

    @Test
    fun `a refused connection before sending is NOT_SENT, a mid-call IO failure is MAYBE_SENT`() = runTest {
        val refused = assertFailsWith<GatewayException> {
            provider(MockEngine { throw ConnectException("refused") }).invoke(request(), localTarget, ctx)
        }
        assertEquals(SendState.NOT_SENT, refused.error.sendState)
        val reset = assertFailsWith<GatewayException> {
            provider(MockEngine { throw IOException("connection reset") }).invoke(request(), localTarget, ctx)
        }
        assertEquals(SendState.MAYBE_SENT, reset.error.sendState)
    }

    @Test
    fun `an unbound endpoint is refused before any network call`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond(textBody(), HttpStatusCode.OK, jsonHeaders) }
        val unbound = BoundCredentialSource(listOf(CredentialBinding("local", "other.test", NO_KEY_REF, null)), emptyMap())
        val e = assertFailsWith<GatewayException> { provider(engine, unbound).invoke(request(), localTarget, ctx) }
        assertEquals(ErrorKind.AUTH, e.error.kind)
        assertEquals(SendState.NOT_SENT, e.error.sendState)
        assertEquals(0, calls)
        assertEquals(ProviderHealthState.DOWN, provider(engine, unbound).health(localTarget).state)
        assertEquals(ProviderHealthState.UP, provider(engine).health(localTarget).state)
    }

    @Test
    fun `image input and structured output combined with tools are refused as unsupported without a call`() = runTest {
        var calls = 0
        val p = provider(MockEngine { calls++; respond(textBody(), HttpStatusCode.OK, jsonHeaders) })
        val tool = ToolSpec("t", "d", JsonObject(emptyMap()))
        val both = request(tools = listOf(tool)).copy(responseFormat = ResponseFormat.JsonSchema("s", JsonObject(emptyMap())))
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, assertFailsWith<GatewayException> { p.invoke(both, localTarget, ctx) }.error.kind)
        val image = request().copy(messages = listOf(Message(Role.USER, listOf(Part.Image("image/png", "AAAA")))))
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, assertFailsWith<GatewayException> { p.invoke(image, localTarget, ctx) }.error.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `a stream yields text chunks, usage and a finished message`() = runTest {
        val p = provider(MockEngine { respond(sseText(), HttpStatusCode.OK, sseHeaders) })
        val chunks = p.stream(request(), localTarget, ctx).toList()
        assertEquals(listOf("Hel", "lo"), chunks.filterIsInstance<StreamChunk.Text>().map { it.text })
        val fin = assertIs<StreamChunk.Finished>(chunks.last()).response
        assertEquals("Hello", (fin.message.parts.single() as Part.Text).text)
        assertEquals(FinishReason.STOP, fin.finishReason)
        assertEquals(Usage(inputTokens = 10, outputTokens = 7), fin.usage)
        assertTrue(fin.usageComplete)
    }

    @Test
    fun `a streamed tool call is assembled from fragments and completed once`() = runTest {
        val p = provider(MockEngine { respond(sseTool(), HttpStatusCode.OK, sseHeaders) })
        val chunks = p.stream(request(), localTarget, ctx).toList()
        val done = chunks.filterIsInstance<StreamChunk.ToolCallCompleted>().single()
        assertEquals("call_9", done.callId)
        assertEquals("Tokyo", done.args.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals(FinishReason.TOOL_CALL, assertIs<StreamChunk.Finished>(chunks.last()).response.finishReason)
    }

    @Test
    fun `a stream that ends without a finish or the end marker is a truncated stream`() = runTest {
        val cut = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n"
        val p = provider(MockEngine { respond(cut, HttpStatusCode.OK, sseHeaders) })
        val e = assertFailsWith<GatewayException> { p.stream(request(), localTarget, ctx).toList() }
        assertEquals("TRUNCATED_STREAM", e.error.code)
    }

    @Test
    fun `a stream error event fails the call without keeping its message`() = runTest {
        val body = "data: {\"error\":{\"message\":\"LEAK-ME\",\"type\":\"server_error\"}}\n\n"
        val p = provider(MockEngine { respond(body, HttpStatusCode.OK, sseHeaders) })
        val e = assertFailsWith<GatewayException> { p.stream(request(), localTarget, ctx).toList() }
        assertEquals(ErrorKind.TRANSIENT, e.error.kind)
        assertFalse("LEAK-ME" in e.error.message)
    }

    @Test
    fun `the stream call is refused for an unbound endpoint without a network call`() = runTest {
        var calls = 0
        val unbound = BoundCredentialSource(emptyList(), emptyMap())
        val e = assertFailsWith<GatewayException> {
            provider(MockEngine { calls++; respond(sseText(), HttpStatusCode.OK, sseHeaders) }, unbound).stream(request(), localTarget, ctx).toList()
        }
        assertEquals(ErrorKind.AUTH, e.error.kind)
        assertEquals(0, calls)
    }
}

class OpenAiCompatCredentialTest {
    private fun local(endpoint: String, zone: TrustZone = TrustZone.LOCAL) = ProviderEntry(
        "local", "openai", zone, DataHandling(DataHandlingStatus.CONFIRMED, "ops", java.time.LocalDate.of(2026, 10, 1), "https://example.test"),
        true, endpoint, NO_KEY_REF,
    )

    @Test
    fun `an unauthenticated binding releases no secret and is honoured only for LOCAL on the bound host`() {
        val src = BoundCredentialSource(listOf(CredentialBinding("local", "llm.test", NO_KEY_REF, null)), emptyMap())
        assertTrue(src.unauthenticated(local("http://llm.test:8000")))
        assertTrue(src.unauthenticated(local("https://llm.test")))
        assertNull(src.get(local("http://llm.test:8000")))
        assertFalse(src.unauthenticated(local("http://evil.test:8000")), "another host is not bound")
        assertFalse(src.unauthenticated(local("http://user:pw@llm.test:8000")), "userinfo is refused")
        assertFalse(src.unauthenticated(local("http://llm.test", zone = TrustZone.EXTERNAL)), "only LOCAL may be unauthenticated")
        assertFalse(src.unauthenticated(local("http://llm.test", zone = TrustZone.INTERNAL)))
    }

    @Test
    fun `a secret binding is still HTTPS only and never unauthenticated`() {
        val src = BoundCredentialSource(listOf(CredentialBinding("local", "llm.test", NO_KEY_REF, "LLM_KEY")), mapOf("LLM_KEY" to "k"))
        assertFalse(src.unauthenticated(local("http://llm.test:8000")))
        assertEquals("k", src.get(local("https://llm.test")))
        assertNull(src.get(local("http://llm.test:8000")))
    }

    @Test
    fun `the registry allows http only for trustZone LOCAL, and https for everything else`() {
        fun yaml(zone: String, endpoint: String) = """
            registryVersion: v1
            providers:
              - id: p
                adapter: openai
                trustZone: $zone
                dataHandling: { status: UNKNOWN }
                enabled: true
                endpoint: $endpoint
                credentialRef: NONE
            models: []
        """.trimIndent()
        assertEquals("http://llm.svc:8000", RegistryLoader.fromYaml(yaml("LOCAL", "http://llm.svc:8000")).providers.getValue("p").endpoint)
        assertEquals(TrustZone.LOCAL, RegistryLoader.fromYaml(yaml("LOCAL", "https://llm.svc")).providers.getValue("p").trustZone)
        for (zone in listOf("INTERNAL", "EXTERNAL")) {
            assertFailsWith<RegistryException>(zone) { RegistryLoader.fromYaml(yaml(zone, "http://llm.svc:8000")) }
        }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(yaml("LOCAL", "http://u:p@llm.svc")) }
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(yaml("LOCAL", "ftp://llm.svc")) }
    }
}

private fun schemaRequest() = request().copy(responseFormat = ResponseFormat.JsonSchema("verdict", buildJsonObject {
    put("type", "object")
}))

private fun completionWith(content: String?): String {
    val c = content?.let { Json.encodeToString(String.serializer(), it) } ?: "null"
    return """{"choices":[{"index":0,"message":{"role":"assistant","content":$c},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3}}"""
}

/** SSE frames for the given content pieces, then (optionally) a stop and the end marker. */
private fun sseContent(vararg pieces: String, finish: Boolean = true): String = buildString {
    for (p in pieces) {
        val c = Json.encodeToString(String.serializer(), p)
        append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":$c},\"finish_reason\":null}]}\n\n")
    }
    if (finish) append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
}

class OpenAiCompatStructuredTest {
    @Test
    fun `the request carries response_format as a strict json_schema, and the JSON answer is the structured output`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val p = provider(MockEngine { req -> seen.add(req); respond(completionWith("""{"answer":"ok"}"""), HttpStatusCode.OK, jsonHeaders) })
        val r = p.invoke(schemaRequest(), localTarget, ctx)
        val rf = bodyOf(seen.single())["response_format"]!!.jsonObject
        assertEquals("json_schema", rf["type"]!!.jsonPrimitive.content)
        val js = rf["json_schema"]!!.jsonObject
        assertEquals("verdict", js["name"]!!.jsonPrimitive.content)
        assertEquals("true", js["strict"]!!.jsonPrimitive.content)
        assertEquals("object", js["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ok", r.structured!!.jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain request sends no response_format`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val p = provider(MockEngine { req -> seen.add(req); respond(completionWith("hi"), HttpStatusCode.OK, jsonHeaders) })
        assertNull(p.invoke(request(), localTarget, ctx).structured)
        assertNull(bodyOf(seen.single())["response_format"])
    }

    @Test
    fun `an answer that is not one JSON object is OUTPUT_INVALID`() = runTest {
        for (bad in listOf("not json", "[1,2]", null)) {
            val p = provider(MockEngine { respond(completionWith(bad), HttpStatusCode.OK, jsonHeaders) })
            assertEquals(ErrorKind.OUTPUT_INVALID, assertFailsWith<GatewayException>(bad ?: "null") { p.invoke(schemaRequest(), localTarget, ctx) }.error.kind)
        }
    }

    @Test
    fun `a streamed JSON answer is the structured output, and a streamed invalid one fails`() = runTest {
        val p = provider(MockEngine { respond(sseContent("""{"answer":""", """"ok"}"""), HttpStatusCode.OK, sseHeaders) })
        val fin = assertIs<StreamChunk.Finished>(p.stream(schemaRequest(), localTarget, ctx).toList().last()).response
        assertEquals("ok", fin.structured!!.jsonObject["answer"]!!.jsonPrimitive.content)

        val p2 = provider(MockEngine { respond(sseContent("not json"), HttpStatusCode.OK, sseHeaders) })
        assertEquals(ErrorKind.OUTPUT_INVALID, assertFailsWith<GatewayException> { p2.stream(schemaRequest(), localTarget, ctx).toList() }.error.kind)
    }

    @Test
    fun `a server error from the on-premises endpoint is NOT_SENT (retry or fallback), a bad request is not`() = runTest {
        val p500 = provider(MockEngine { respond("{}", HttpStatusCode.InternalServerError, jsonHeaders) })
        assertEquals(SendState.NOT_SENT, assertFailsWith<GatewayException> { p500.invoke(request(), localTarget, ctx) }.error.sendState)
        val p400 = provider(MockEngine { respond("{}", HttpStatusCode.BadRequest, jsonHeaders) })
        assertEquals(SendState.MAYBE_SENT, assertFailsWith<GatewayException> { p400.invoke(request(), localTarget, ctx) }.error.sendState)
    }
}

class OpenAiCompatToolTest {
    private val tool = ToolSpec("get_weather", "weather", buildJsonObject { put("type", "object") })

    private fun toolCompletion(arguments: String, name: String = "get_weather") =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_7","type":"function","function":{"name":"$name","arguments":${Json.encodeToString(String.serializer(), arguments)}}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}"""

    @Test
    fun `tool_choice is mapped for AUTO and NONE, and the tools are sent as OpenAI functions`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val p = provider(MockEngine { req -> seen.add(req); respond(completionWith("hi"), HttpStatusCode.OK, jsonHeaders) })
        p.invoke(request(tools = listOf(tool)).copy(toolChoice = ToolChoice.AUTO), localTarget, ctx)
        p.invoke(request(tools = listOf(tool)).copy(toolChoice = ToolChoice.NONE), localTarget, ctx)
        val first = bodyOf(seen[0])
        assertEquals("auto", first["tool_choice"]!!.jsonPrimitive.content)
        assertEquals("get_weather", first["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("none", bodyOf(seen[1])["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid tool arguments are OUTPUT_INVALID on invoke and on stream`() = runTest {
        val p = provider(MockEngine { respond(toolCompletion("{not json"), HttpStatusCode.OK, jsonHeaders) })
        assertEquals(ErrorKind.OUTPUT_INVALID, assertFailsWith<GatewayException> { p.invoke(request(tools = listOf(tool)), localTarget, ctx) }.error.kind)

        val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_8\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{oops\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        val p2 = provider(MockEngine { respond(sse, HttpStatusCode.OK, sseHeaders) })
        assertEquals(ErrorKind.OUTPUT_INVALID, assertFailsWith<GatewayException> { p2.stream(request(tools = listOf(tool)), localTarget, ctx).toList() }.error.kind)
    }

    @Test
    fun `a tool name the caller did not declare is passed through as a call, never executed, like the Anthropic adapter`() = runTest {
        val p = provider(MockEngine { respond(toolCompletion("{}", name = "delete_everything"), HttpStatusCode.OK, jsonHeaders) })
        val call = p.invoke(request(tools = listOf(tool)), localTarget, ctx).message.parts.filterIsInstance<Part.ToolCall>().single()
        assertEquals("delete_everything", call.name)
    }

    @Test
    fun `a streamed tool call with fragments split across chunks is joined into one call`() = runTest {
        val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\"}}]},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Osaka\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        val p = provider(MockEngine { respond(sse, HttpStatusCode.OK, sseHeaders) })
        val done = p.stream(request(tools = listOf(tool)), localTarget, ctx).toList().filterIsInstance<StreamChunk.ToolCallCompleted>().single()
        assertEquals("Osaka", done.args.jsonObject["city"]!!.jsonPrimitive.content)
    }
}
