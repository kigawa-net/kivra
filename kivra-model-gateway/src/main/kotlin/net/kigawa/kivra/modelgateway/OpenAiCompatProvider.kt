package net.kigawa.kivra.modelgateway

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Adapter for an OpenAI-compatible Chat Completions API (vLLM, Ollama, llama.cpp server). Adapter name `openai`.
 *
 * It is meant for an on-premises LOCAL provider reached inside the cluster. Such an endpoint carries no credential, so the
 * adapter sends no `Authorization` header, and it makes a call only when the trusted [CredentialSource] binds this provider to
 * its endpoint host ([CredentialSource.unauthenticated]). Anything else fails closed before any byte is sent. Redirects are
 * never followed.
 *
 * Capabilities: text, streaming, system prompt, and structured output (`response_format` json_schema, strict; the answer must be one
 * JSON object). Tool calling is passed through when the registry declares it, and the local model's declaration decides that.
 * Image input is refused with CAPABILITY_UNSUPPORTED. Structured output combined with tools is refused, as for Anthropic.
 */
class OpenAiCompatProvider(
    client: HttpClient,
    private val credentials: CredentialSource,
    override val providerId: String = "openai",
) : ModelProvider {
    // Never follow redirects: a request must reach only the endpoint the trusted binding allows.
    private val client: HttpClient = client.config { followRedirects = false }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Local check only (the endpoint is bound); makes no network call and incurs no cost. */
    override suspend fun health(target: ResolvedTarget): ProviderHealth =
        if (credentials.unauthenticated(target.provider)) ProviderHealth(ProviderHealthState.UP)
        else ProviderHealth(ProviderHealthState.DOWN, "endpoint not bound")

    /** Lists models served by the endpoint (free call). */
    override suspend fun listModels(target: ResolvedTarget): List<ProviderModelInfo> {
        authorize(target)
        val resp = wrap { client.get(url(target, "/v1/models")) }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw httpError(resp, text)
        return json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty().map {
            ProviderModelInfo(it.jsonObject.getValue("id").jsonPrimitive.content)
        }
    }

    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
        authorize(target)
        val started = System.nanoTime()
        val resp = wrap {
            client.post(url(target, "/v1/chat/completions")) {
                contentType(ContentType.Application.Json)
                setBody(buildBody(req, target, stream = false).toString())
            }
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw httpError(resp, text)
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "unparseable provider response", code = "BAD_RESPONSE"))
        }
        return parseResponse(obj, req, target, ctx, (System.nanoTime() - started) / 1_000_000)
    }

    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = flow {
        authorize(target)
        val started = System.nanoTime()
        val acc = StreamAccumulator()
        try {
            client.prepareRequest(url(target, "/v1/chat/completions")) {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(buildBody(req, target, stream = true).toString())
            }.execute { resp ->
                if (!resp.status.isSuccess()) throw httpError(resp, resp.bodyAsText())
                val ch = resp.bodyAsChannel()
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") { acc.done = true; break }
                    val ev = try { json.parseToJsonElement(data).jsonObject } catch (e: Exception) { continue }
                    for (chunk in acc.accept(ev)) emit(chunk)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            throw e
        } catch (e: Exception) {
            throw GatewayException(ErrorClassifier.fromThrowable(e), e)
        }
        if (!acc.finished) throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "stream ended before the end marker", code = "TRUNCATED_STREAM"))
        for (chunk in acc.completeToolCalls()) emit(chunk)
        val structured = if (req.responseFormat is ResponseFormat.JsonSchema) structuredOf(acc.text.toString()) else null
        emit(StreamChunk.Finished(
            ModelResponse(
                requestId = ctx.requestId, attemptId = ctx.attemptId, selected = target.model.ref, message = acc.toMessage(),
                structured = structured, finishReason = acc.finishReason(), usage = acc.usage, cost = Cost.zero(),
                latencyMs = (System.nanoTime() - started) / 1_000_000, routingDecisionId = ctx.routingDecisionId,
                usageComplete = acc.usageComplete,
            ),
        ))
    }

    // ---- request building -------------------------------------------------------------------------

    /** Refuses the call unless the trusted binding allows this unauthenticated endpoint (fail closed, nothing is sent). */
    private fun authorize(target: ResolvedTarget) {
        if (!credentials.unauthenticated(target.provider)) {
            throw GatewayException(GatewayError(ErrorKind.AUTH, "endpoint for '${target.provider.id}' is not bound", code = "NO_CREDENTIAL", sendState = SendState.NOT_SENT))
        }
    }

    private fun url(target: ResolvedTarget, path: String): String = "${target.provider.endpoint.trimEnd('/')}$path"

    internal fun buildBody(req: ModelRequest, target: ResolvedTarget, stream: Boolean): JsonObject {
        val structured = req.responseFormat as? ResponseFormat.JsonSchema
        if (structured != null && req.tools.isNotEmpty()) {
            // Same rule as the Anthropic adapter: a schema-constrained answer and caller tools are not combined.
            throw GatewayException(GatewayError(ErrorKind.CAPABILITY_UNSUPPORTED, "structured output cannot be combined with tools on this adapter", sendState = SendState.NOT_SENT))
        }
        if (req.messages.any { m -> m.parts.any { it is Part.Image } }) {
            throw GatewayException(GatewayError(ErrorKind.CAPABILITY_UNSUPPORTED, "image input is not supported on this adapter", sendState = SendState.NOT_SENT))
        }
        return buildJsonObject {
            put("model", target.model.providerModelName)
            put("max_tokens", Estimator.outputTokens(req, target.model))
            put("stream", stream)
            if (stream) put("stream_options", buildJsonObject { put("include_usage", true) })
            req.generation.temperature?.let { put("temperature", it) }
            if (req.generation.stopSequences.isNotEmpty()) put("stop", JsonArray(req.generation.stopSequences.map(::JsonPrimitive)))
            if (structured != null) put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", structured.name); put("schema", structured.schema); put("strict", true)
                })
            })
            put("messages", buildJsonArray {
                for (m in req.messages) for (obj in messageJson(m)) add(obj)
            })
            if (req.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    req.tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name); put("description", t.description); put("parameters", t.inputSchema)
                            })
                        })
                    }
                }
                put("tool_choice", when (req.toolChoice) { ToolChoice.AUTO -> "auto"; ToolChoice.REQUIRED -> "required"; ToolChoice.NONE -> "none" })
            }
        }
    }

    /** One Gateway message becomes zero or more chat messages: tool results first (they must follow the tool call), then text. */
    private fun messageJson(m: Message): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        for (r in m.parts.filterIsInstance<Part.ToolResult>()) {
            out.add(buildJsonObject { put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content) })
        }
        when (m.role) {
            Role.SYSTEM -> out.add(buildJsonObject { put("role", "system"); put("content", text) })
            Role.ASSISTANT -> if (text.isNotEmpty() || calls.isNotEmpty()) out.add(buildJsonObject {
                put("role", "assistant")
                put("content", if (text.isEmpty()) JsonNull else JsonPrimitive(text))
                if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                    calls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id); put("type", "function")
                            put("function", buildJsonObject { put("name", c.name); put("arguments", c.arguments.toString()) })
                        })
                    }
                }
            })
            Role.USER, Role.TOOL -> if (text.isNotEmpty()) out.add(buildJsonObject { put("role", "user"); put("content", text) })
        }
        return out
    }

    // ---- response parsing ---------------------------------------------------------------------------

    /**
     * The structured answer: the text must be one JSON object (the schema is the model's constraint, checked the same way as the
     * Anthropic adapter, which accepts any JSON object). Anything else is OUTPUT_INVALID, with no text kept.
     */
    private fun structuredOf(text: String?): JsonObject =
        (try { text?.let { json.parseToJsonElement(it) } } catch (e: Exception) { null }) as? JsonObject
            ?: throw GatewayException(GatewayError(ErrorKind.OUTPUT_INVALID, "model did not return structured output"))

    private fun parseResponse(obj: JsonObject, req: ModelRequest, target: ResolvedTarget, ctx: CallContext, latencyMs: Long): ModelResponse {
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "provider response has no choice", code = "BAD_RESPONSE"))
        val msg = choice["message"]?.jsonObject
            ?: throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "provider response has no message", code = "BAD_RESPONSE"))
        val parts = ArrayList<Part>()
        msg["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts.add(Part.Text(it)) }
        msg["tool_calls"]?.jsonArray?.forEach { parts.add(toolCall(it.jsonObject)) }
        val finish = finishOf(choice["finish_reason"]?.jsonPrimitive?.contentOrNull, parts.any { it is Part.ToolCall })
        val structured = if (req.responseFormat is ResponseFormat.JsonSchema) structuredOf(msg["content"]?.jsonPrimitive?.contentOrNull) else null
        return ModelResponse(
            requestId = ctx.requestId, attemptId = ctx.attemptId, selected = target.model.ref,
            message = Message(Role.ASSISTANT, parts), structured = structured,
            finishReason = finish, usage = parseUsage(obj["usage"]), cost = Cost.zero(), latencyMs = latencyMs,
            routingDecisionId = ctx.routingDecisionId,
            usageComplete = hasTokens(obj["usage"], "prompt_tokens") && hasTokens(obj["usage"], "completion_tokens"),
        )
    }

    private fun toolCall(o: JsonObject): Part.ToolCall {
        val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val fn = o["function"]?.jsonObject
        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val raw = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "{}" }
        val args = try { json.parseToJsonElement(raw) } catch (e: Exception) {
            throw GatewayException(GatewayError(ErrorKind.OUTPUT_INVALID, "tool call arguments are not valid JSON"))
        }
        return Part.ToolCall(id, name, args)
    }

    /** Maps a finish_reason. A tool call reported with a plain "stop" is still a tool call. */
    private fun finishOf(s: String?, hasToolCall: Boolean): FinishReason = when (s) {
        "length" -> FinishReason.LENGTH
        "content_filter" -> FinishReason.CONTENT_FILTER
        "tool_calls", "function_call" -> FinishReason.TOOL_CALL
        else -> if (hasToolCall) FinishReason.TOOL_CALL else FinishReason.STOP
    }

    /**
     * Token counts from a usage object. OpenAI-style prompt_tokens includes cache reads, while the Gateway's input count
     * excludes them, so cached tokens are split out. A missing count is 0.
     */
    internal fun parseUsage(u: JsonElement?): Usage {
        val o = (u as? JsonObject) ?: return Usage.ZERO
        val prompt = longOf(o["prompt_tokens"])
        val completion = longOf(o["completion_tokens"])
        val cached = (o["prompt_tokens_details"] as? JsonObject)?.let { longOf(it["cached_tokens"]) }?.coerceIn(0L, prompt) ?: 0L
        return Usage(inputTokens = prompt - cached, outputTokens = completion, cachedInputTokens = cached)
    }

    private fun longOf(e: JsonElement?): Long = (e as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L) ?: 0L

    private fun hasTokens(u: JsonElement?, key: String): Boolean =
        ((u as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull != null

    // ---- errors --------------------------------------------------------------------------------------

    private suspend fun wrap(block: suspend () -> HttpResponse): HttpResponse =
        try { block() } catch (e: CancellationException) { throw e } catch (e: GatewayException) { throw e } catch (e: Exception) {
            throw GatewayException(ErrorClassifier.fromThrowable(e), e)
        }

    /**
     * Only the status and the retry-after header are used. The body is never kept (it may echo the prompt).
     *
     * An on-premises endpoint has no charge and produces no output for an error status, so a 408, 409, 429 or 5xx answer is marked
     * [SendState.NOT_SENT]: the gateway may retry or fall back to another provider, as for a refused connection. This is a deliberate
     * judgement for LOCAL endpoints (see the class comment), not a claim about hosted providers.
     */
    private fun httpError(resp: HttpResponse, body: String): GatewayException {
        val status = resp.status.value
        val error = ErrorClassifier.fromHttp(status, body, resp.headers[HttpHeaders.RetryAfter])
        val retryable = status == 408 || status == 409 || status == 429 || status >= 500
        return GatewayException(if (retryable) error.copy(sendState = SendState.NOT_SENT) else error)
    }

    /** Accumulates chat-completion SSE chunks into text, tool calls, usage and a final message. */
    private inner class StreamAccumulator {
        val text = StringBuilder()
        private val callIds = HashMap<Int, String>()
        private val callNames = HashMap<Int, String>()
        private val callArgs = HashMap<Int, StringBuilder>()
        private val completed = ArrayList<Part.ToolCall>()
        private var toolCallsDone = false
        private var finishSeen: String? = null
        var usage = Usage.ZERO
        /** The end marker `data: [DONE]` was received. */
        var done = false
        private var sawPrompt = false
        private var sawCompletion = false
        /** The stream reached its end: the end marker, or a finish_reason. A cut connection is not an end. */
        val finished: Boolean get() = done || finishSeen != null
        val usageComplete: Boolean get() = finished && sawPrompt && sawCompletion

        fun accept(ev: JsonObject): List<StreamChunk> {
            if (ev["error"] != null) {
                // Only the fact of an error is kept, never its message.
                throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "stream error", code = "STREAM_ERROR"))
            }
            val out = ArrayList<StreamChunk>()
            ev["usage"]?.takeIf { it is JsonObject }?.let { u ->
                sawPrompt = sawPrompt || hasTokens(u, "prompt_tokens")
                sawCompletion = sawCompletion || hasTokens(u, "completion_tokens")
                usage = parseUsage(u)
                out.add(StreamChunk.UsageUpdate(usage))
            }
            val choice = ev["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return out
            val delta = choice["delta"] as? JsonObject
            delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                out.add(StreamChunk.Text(it))
            }
            delta?.get("tool_calls")?.jsonArray?.forEach { el ->
                val tc = el.jsonObject
                val idx = (tc["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                val fn = tc["function"] as? JsonObject
                val newId = tc["id"]?.jsonPrimitive?.contentOrNull
                val newName = fn?.get("name")?.jsonPrimitive?.contentOrNull
                if (!callArgs.containsKey(idx)) {
                    callIds[idx] = newId ?: "call_$idx"
                    callNames[idx] = newName.orEmpty()
                    callArgs[idx] = StringBuilder()
                    out.add(StreamChunk.ToolCallDelta(callIds.getValue(idx), newName, ""))
                }
                val frag = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (frag.isNotEmpty()) {
                    callArgs.getValue(idx).append(frag)
                    out.add(StreamChunk.ToolCallDelta(callIds.getValue(idx), null, frag))
                }
            }
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishSeen = it }
            return out
        }

        /** Completes the accumulated tool calls once, in index order. Invalid argument JSON fails the call. */
        fun completeToolCalls(): List<StreamChunk> {
            if (toolCallsDone) return emptyList()
            toolCallsDone = true
            val out = ArrayList<StreamChunk>()
            for (idx in callArgs.keys.sorted()) {
                val raw = callArgs.getValue(idx).toString().ifBlank { "{}" }
                val args = try { json.parseToJsonElement(raw) } catch (e: Exception) {
                    throw GatewayException(GatewayError(ErrorKind.OUTPUT_INVALID, "tool call arguments are not valid JSON"))
                }
                val call = Part.ToolCall(callIds.getValue(idx), callNames.getValue(idx), args)
                completed.add(call)
                out.add(StreamChunk.ToolCallCompleted(call.id, call.name, args))
            }
            return out
        }

        fun finishReason(): FinishReason = finishOf(finishSeen, completed.isNotEmpty())

        fun toMessage(): Message {
            val all = ArrayList<Part>()
            if (text.isNotEmpty()) all.add(Part.Text(text.toString()))
            all.addAll(completed)
            return Message(Role.ASSISTANT, all)
        }
    }
}
