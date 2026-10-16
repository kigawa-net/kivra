package net.kigawa.kivra.modelgateway

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
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

/** Adapter for the Anthropic Messages API. Never talks to the network unless an [HttpClient] with a real engine is given. */
class AnthropicProvider(
    client: HttpClient,
    private val auth: AnthropicAuth,
    override val providerId: String = "anthropic",
    private val apiVersion: String = "2023-06-01",
) : ModelProvider {
    /** Static API key (`x-api-key`) through a [CredentialSource]. */
    constructor(
        client: HttpClient,
        credentials: CredentialSource,
        providerId: String = "anthropic",
        apiVersion: String = "2023-06-01",
    ) : this(client, StaticKeyAuth(credentials), providerId, apiVersion)

    // Never follow redirects: the credential header must not be forwarded to another host.
    private val client: HttpClient = client.config { followRedirects = false }

    companion object {
        const val STRUCTURED_TOOL_NAME = "kivra_structured_output"
        private val json = Json { ignoreUnknownKeys = true }

        /** Install the connect / idle / total request timeouts on a client builder. */
        fun installDefaults(config: io.ktor.client.HttpClientConfig<*>) {
            config.install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 120_000
            }
        }
    }

    /** Local check only (credential configured); makes no network call and incurs no cost. */
    override suspend fun health(target: ResolvedTarget): ProviderHealth =
        if (auth.isConfigured(target)) ProviderHealth(ProviderHealthState.UP)
        else ProviderHealth(ProviderHealthState.DOWN, "credential not configured")

    /** Lists models available to the credential (free call). Used for registry sync. */
    override suspend fun listModels(target: ResolvedTarget): List<ProviderModelInfo> {
        val authz = auth.authorize(target)
        val resp = wrap(authz) { client.get("${target.provider.endpoint.trimEnd('/')}/v1/models") { common(authz) } }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw httpError(resp, text, authz)
        return json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            ProviderModelInfo(o.getValue("id").jsonPrimitive.content, o["display_name"]?.jsonPrimitive?.contentOrNull)
        }
    }

    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
        val authz = auth.authorize(target)
        val started = System.nanoTime()
        val resp = wrap(authz) {
            client.post("${target.provider.endpoint.trimEnd('/')}/v1/messages") {
                common(authz)
                contentType(ContentType.Application.Json)
                setBody(buildBody(req, target, stream = false).toString())
            }
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw httpError(resp, text, authz)
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "unparseable provider response", code = "BAD_RESPONSE"))
        }
        return parseResponse(obj, req, target, ctx, (System.nanoTime() - started) / 1_000_000)
    }

    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = flow {
        val authz = auth.authorize(target)
        val started = System.nanoTime()
        val acc = StreamAccumulator()
        try {
            client.prepareRequest("${target.provider.endpoint.trimEnd('/')}/v1/messages") {
                method = io.ktor.http.HttpMethod.Post
                common(authz)
                contentType(ContentType.Application.Json)
                setBody(buildBody(req, target, stream = true).toString())
            }.execute { resp ->
                if (!resp.status.isSuccess()) throw httpError(resp, resp.bodyAsText(), authz)
                val ch = resp.bodyAsChannel()
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val ev = try { json.parseToJsonElement(data).jsonObject } catch (e: Exception) { continue }
                    for (chunk in acc.accept(ev)) emit(chunk)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            throw e
        } catch (e: Exception) {
            throw GatewayException(ErrorClassifier.fromThrowable(e, authz.secrets), e)
        }
        val message = acc.toMessage()
        if (!acc.finished) throw GatewayException(GatewayError(ErrorKind.TRANSIENT, "stream ended before message_stop", code = "TRUNCATED_STREAM"))
        emit(StreamChunk.Finished(
            ModelResponse(
                requestId = ctx.requestId, attemptId = ctx.attemptId, selected = target.model.ref, message = message,
                structured = null, finishReason = acc.finishReason, usage = acc.usage, cost = Cost.zero(),
                latencyMs = (System.nanoTime() - started) / 1_000_000, routingDecisionId = ctx.routingDecisionId,
                usageComplete = acc.usageComplete,
            ),
        ))
    }

    // ---- request building -------------------------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.common(authz: AnthropicAuthorization) {
        header(authz.headerName, authz.headerValue)
        header("anthropic-version", apiVersion)
    }

    internal fun buildBody(req: ModelRequest, target: ResolvedTarget, stream: Boolean): JsonObject {
        val structured = req.responseFormat as? ResponseFormat.JsonSchema
        if (structured != null && req.tools.isNotEmpty()) {
            // Structured output is implemented with a forced tool; combining it with caller tools is ambiguous.
            throw GatewayException(GatewayError(ErrorKind.CAPABILITY_UNSUPPORTED, "structured output cannot be combined with tools on this adapter", sendState = SendState.NOT_SENT))
        }
        val system = req.messages.filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { m -> m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text } }
        return buildJsonObject {
            put("model", target.model.providerModelName)
            put("max_tokens", Estimator.outputTokens(req, target.model))
            if (stream) put("stream", true)
            if (system.isNotEmpty()) put("system", system)
            req.generation.temperature?.let { put("temperature", it) }
            req.generation.thinking?.let { mode ->
                put("thinking", buildJsonObject { put("type", when (mode) { ThinkingMode.DISABLED -> "disabled" }) })
            }
            if (req.generation.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(req.generation.stopSequences.map(::JsonPrimitive)))
            put("messages", buildJsonArray {
                for (m in req.messages.filter { it.role != Role.SYSTEM }) add(messageJson(m))
            })
            if (structured != null) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        put("name", STRUCTURED_TOOL_NAME)
                        put("description", "Return the final answer as JSON matching the schema.")
                        put("input_schema", structured.schema)
                    })
                }
                put("tool_choice", buildJsonObject { put("type", "tool"); put("name", STRUCTURED_TOOL_NAME) })
            } else if (req.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    req.tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name); put("description", t.description); put("input_schema", t.inputSchema)
                        })
                    }
                }
                put("tool_choice", buildJsonObject {
                    put("type", when (req.toolChoice) { ToolChoice.AUTO -> "auto"; ToolChoice.REQUIRED -> "any"; ToolChoice.NONE -> "none" })
                })
            }
        }
    }

    private fun messageJson(m: Message): JsonObject = buildJsonObject {
        put("role", if (m.role == Role.ASSISTANT) "assistant" else "user")
        put("content", buildJsonArray {
            for (p in m.parts) add(when (p) {
                is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                is Part.Image -> buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject { put("type", "base64"); put("media_type", p.mediaType); put("data", p.base64Data) })
                }
                is Part.ToolCall -> buildJsonObject { put("type", "tool_use"); put("id", p.id); put("name", p.name); put("input", p.arguments) }
                is Part.ToolResult -> buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", p.callId); put("content", p.content)
                    if (p.isError) put("is_error", true)
                }
            })
        })
    }

    // ---- response parsing ---------------------------------------------------------------------------

    private fun parseResponse(obj: JsonObject, req: ModelRequest, target: ResolvedTarget, ctx: CallContext, latencyMs: Long): ModelResponse {
        val parts = ArrayList<Part>()
        var structured: JsonElement? = null
        for (b in obj["content"]?.jsonArray.orEmpty()) {
            val o = b.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> parts.add(Part.Text(o.getValue("text").jsonPrimitive.content))
                "tool_use" -> {
                    val name = o.getValue("name").jsonPrimitive.content
                    val input = o["input"] ?: JsonObject(emptyMap())
                    if (req.responseFormat is ResponseFormat.JsonSchema && name == STRUCTURED_TOOL_NAME) {
                        structured = input
                        parts.add(Part.Text(input.toString()))
                    } else parts.add(Part.ToolCall(o.getValue("id").jsonPrimitive.content, name, input))
                }
            }
        }
        val finish = mapStop(obj["stop_reason"]?.jsonPrimitive?.contentOrNull)
        if (req.responseFormat is ResponseFormat.JsonSchema && finish != FinishReason.CONTENT_FILTER) {
            if (structured !is JsonObject) throw GatewayException(GatewayError(ErrorKind.OUTPUT_INVALID, "model did not return structured output"))
        }
        return ModelResponse(
            requestId = ctx.requestId, attemptId = ctx.attemptId, selected = target.model.ref,
            message = Message(Role.ASSISTANT, parts), structured = structured,
            finishReason = if (structured != null && finish == FinishReason.TOOL_CALL) FinishReason.STOP else finish,
            usage = parseUsage(obj["usage"]), cost = Cost.zero(), latencyMs = latencyMs, routingDecisionId = ctx.routingDecisionId,
            usageComplete = hasTokens(obj["usage"], "input_tokens") && hasTokens(obj["usage"], "output_tokens"),
        )
    }

    private fun mapStop(s: String?): FinishReason = when (s) {
        "end_turn", "stop_sequence", "pause_turn" -> FinishReason.STOP
        "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH
        "tool_use" -> FinishReason.TOOL_CALL
        "refusal" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.STOP
    }

    private fun hasTokens(u: JsonElement?, key: String): Boolean =
        ((u as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull != null

    /**
     * Token counts from a Message or message_start usage block. Cache writes: the top-level cache_creation_input_tokens is the
     * total; cache_creation.ephemeral_1h_input_tokens gives the 1-hour part. Without a breakdown the whole total is treated as
     * 1-hour (the higher rate), so the cost is never under-counted. A missing count is 0.
     */
    internal fun parseUsage(u: JsonElement?): Usage {
        val o = (u as? JsonObject) ?: return Usage.ZERO
        fun n(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        val breakdown = o["cache_creation"] as? JsonObject
        fun b(k: String): Long? = (breakdown?.get(k) as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L)
        val fiveMin = b("ephemeral_5m_input_tokens")
        val oneHour = b("ephemeral_1h_input_tokens")
        val total = (o["cache_creation_input_tokens"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L)
            ?: ((fiveMin ?: 0L) + (oneHour ?: 0L)).takeIf { breakdown != null } ?: 0L
        // Without a 5-minute count, everything not explicitly 5-minute is priced as 1-hour (the higher rate).
        val writes1h = if (breakdown == null) total else (total - (fiveMin ?: 0L)).coerceIn(0L, total)
        return Usage(
            n("input_tokens"), n("output_tokens"), n("cache_read_input_tokens"),
            cacheCreationInputTokens = total, cacheCreation1hInputTokens = writes1h,
        )
    }

    // ---- errors --------------------------------------------------------------------------------------

    private suspend fun wrap(authz: AnthropicAuthorization, block: suspend () -> HttpResponse): HttpResponse =
        try { block() } catch (e: CancellationException) { throw e } catch (e: GatewayException) { throw e } catch (e: Exception) {
            throw GatewayException(ErrorClassifier.fromThrowable(e, authz.secrets), e)
        }

    /** A 401 drops the cached credential (no retry here: the next request re-authenticates). */
    private fun httpError(resp: HttpResponse, body: String, authz: AnthropicAuthorization): GatewayException {
        if (resp.status.value == 401) auth.onUnauthorized(authz)
        return GatewayException(ErrorClassifier.fromHttp(resp.status.value, body, resp.headers[HttpHeaders.RetryAfter], authz.secrets))
    }

    /** Accumulates SSE events into chunks and a final message. */
    private inner class StreamAccumulator {
        private val text = StringBuilder()
        private val toolNames = HashMap<Int, Pair<String, String>>()
        private val toolArgs = HashMap<Int, StringBuilder>()
        private val parts = ArrayList<Part>()
        var usage = Usage.ZERO
        var finishReason = FinishReason.STOP
        var finished = false
        /** Input and output token counts were both reported (message_start carries input; output may come later). */
        private var sawInput = false
        private var sawOutput = false
        val usageComplete: Boolean get() = finished && sawInput && sawOutput

        fun toMessage(): Message {
            val all = ArrayList<Part>()
            if (text.isNotEmpty()) all.add(Part.Text(text.toString()))
            all.addAll(parts)
            return Message(Role.ASSISTANT, all)
        }

        fun accept(ev: JsonObject): List<StreamChunk> {
            val out = ArrayList<StreamChunk>()
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val u = ev["message"]?.jsonObject?.get("usage")
                    usage = parseUsage(u)
                    sawInput = sawInput || hasTokens(u, "input_tokens")
                    sawOutput = sawOutput || hasTokens(u, "output_tokens")
                    out.add(StreamChunk.UsageUpdate(usage))
                }
                "content_block_start" -> {
                    val idx = ev["index"]!!.jsonPrimitive.content.toInt()
                    val b = ev["content_block"]!!.jsonObject
                    if (b["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        val id = b.getValue("id").jsonPrimitive.content
                        val name = b.getValue("name").jsonPrimitive.content
                        toolNames[idx] = id to name
                        toolArgs[idx] = StringBuilder()
                        out.add(StreamChunk.ToolCallDelta(id, name, ""))
                    }
                }
                "content_block_delta" -> {
                    val idx = ev["index"]!!.jsonPrimitive.content.toInt()
                    val d = ev["delta"]!!.jsonObject
                    when (d["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> { val t = d.getValue("text").jsonPrimitive.content; text.append(t); out.add(StreamChunk.Text(t)) }
                        "input_json_delta" -> {
                            val frag = d.getValue("partial_json").jsonPrimitive.content
                            toolArgs[idx]?.append(frag)
                            toolNames[idx]?.let { out.add(StreamChunk.ToolCallDelta(it.first, null, frag)) }
                        }
                    }
                }
                "content_block_stop" -> {
                    val idx = ev["index"]!!.jsonPrimitive.content.toInt()
                    val tn = toolNames.remove(idx)
                    if (tn != null) {
                        val raw = toolArgs.remove(idx)?.toString().orEmpty().ifBlank { "{}" }
                        val args = try { json.parseToJsonElement(raw) } catch (e: Exception) {
                            throw GatewayException(GatewayError(ErrorKind.OUTPUT_INVALID, "tool call arguments are not valid JSON"))
                        }
                        parts.add(Part.ToolCall(tn.first, tn.second, args))
                        out.add(StreamChunk.ToolCallCompleted(tn.first, tn.second, args))
                    }
                }
                "message_delta" -> {
                    ev["delta"]?.jsonObject?.get("stop_reason")?.takeIf { it !is JsonNull }?.let { finishReason = mapStop(it.jsonPrimitive.content) }
                    val u = ev["usage"] as? JsonObject
                    if (u != null) {
                        sawOutput = sawOutput || hasTokens(u, "output_tokens")
                        val outTokens = (u["output_tokens"] as? JsonPrimitive)?.longOrNull ?: usage.outputTokens
                        usage = usage.copy(outputTokens = outTokens)
                        out.add(StreamChunk.UsageUpdate(usage))
                    }
                }
                "message_stop" -> finished = true
                "error" -> {
                    // Only error.type is kept. The message is not read, so it cannot reach an error or a log.
                    val err = ev["error"]?.jsonObject
                    val type = ProviderErrorType.normalize(err?.get("type")?.jsonPrimitive?.contentOrNull)
                    val kind = when (type) {
                        "rate_limit_error" -> ErrorKind.RATE_LIMITED
                        "authentication_error", "permission_error" -> ErrorKind.AUTH
                        "invalid_request_error", "not_found_error" -> ErrorKind.INVALID_REQUEST
                        else -> ErrorKind.TRANSIENT
                    }
                    throw GatewayException(GatewayError(kind, "stream error", providerErrorType = type))
                }
            }
            return out
        }
    }
}
