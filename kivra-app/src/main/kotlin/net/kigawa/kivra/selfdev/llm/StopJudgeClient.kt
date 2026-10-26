package net.kigawa.kivra.selfdev.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import net.kigawa.kivra.modelgateway.AnthropicAuth
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.ModelEntry
import net.kigawa.kivra.modelgateway.ProviderEntry
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.TrustZone
import org.slf4j.LoggerFactory

/** Tokens the API reported for one call. */
data class JudgeUsage(val inputTokens: Long, val outputTokens: Long)

/** The fixed failure codes of a call. Never an exception message, never part of a response. */
enum class JudgeErrorCode { AUTH, RATE_LIMITED, TIMEOUT, UPSTREAM, TRANSPORT, CLIENT_ERROR, BAD_RESPONSE, CIRCUIT_OPEN }

/** The result of one judgement. A failure is a value, not an exception. */
sealed interface JudgeResult {
    /** The call worked. [judgment] is ABSTAIN with INVALID_OUTPUT when the output was refused by the parser. */
    class Judged(val judgment: ParsedJudgment, val usage: JudgeUsage) : JudgeResult {
        override fun toString(): String = "Judged(${judgment.decision}, ${judgment.reason})"
    }

    /** The call did not give a judgement. Treated as ABSTAIN by the caller. */
    class Error(val code: JudgeErrorCode) : JudgeResult {
        override fun toString(): String = "Error($code)"
    }
}

/** The port of the model call. The network sits behind it; tests use a fake. */
interface StopJudgeClient {
    suspend fun judge(input: JudgeInput): JudgeResult
}

/** After [failureThreshold] failures in a row the circuit opens for [openMillis]; then one trial call is let through. */
class JudgeCircuitBreaker(
    private val clock: Clock,
    private val failureThreshold: Int = 5,
    private val openMillis: Long = 60_000,
) {
    private var failures = 0
    private var openUntil: Instant? = null

    @Synchronized
    fun allow(): Boolean {
        val until = openUntil ?: return true
        if (clock.instant().isBefore(until)) return false
        // Half open: one trial. A failure re-opens at once, a success closes.
        openUntil = null
        failures = failureThreshold - 1
        return true
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
        openUntil = null
    }

    @Synchronized
    fun onFailure() {
        failures++
        if (failures >= failureThreshold) openUntil = clock.instant().plusMillis(openMillis)
    }
}

/** Settings of [AnthropicStopJudgeClient]. There is no default model: it must be given. */
class StopJudgeClientConfig(
    val model: String,
    val endpoint: String = "https://api.anthropic.com",
    val maxOutputTokens: Int = 100,
    val attemptTimeoutMillis: Long = 15_000,
    val maxAttempts: Int = 3,
    val backoffMillis: Long = 300,
) {
    init {
        require(model.isNotBlank()) { "model is required" }
        require(maxAttempts in 1..5 && maxOutputTokens in 1..1000 && attemptTimeoutMillis > 0 && backoffMillis >= 0) { "invalid StopJudgeClientConfig" }
    }

    override fun toString(): String = "StopJudgeClientConfig(model=$model)"
}

/**
 * Anthropic Messages API behind [StopJudgeClient] (ADR 0021 L3). The credential comes from the existing [AnthropicAuth] (the
 * Workload Identity Federation exchange of the model gateway); nothing of it is copied here. It retries only 5xx, 429, timeouts
 * and transport failures, a few times; an authentication failure is never retried. Every failure is a [JudgeResult.Error] with a
 * fixed code. No credential, free text, request body, response body or model output is logged or put in an exception message.
 */
class AnthropicStopJudgeClient(
    client: HttpClient,
    private val auth: AnthropicAuth,
    private val config: StopJudgeClientConfig,
    private val breaker: JudgeCircuitBreaker,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : StopJudgeClient {
    // Never follow redirects: the credential header must not be forwarded to another host.
    private val http: HttpClient = client.config { followRedirects = false }
    private val log = LoggerFactory.getLogger(AnthropicStopJudgeClient::class.java)
    private val target = ResolvedTarget(
        ProviderEntry("anthropic", "anthropic", TrustZone.EXTERNAL, DataHandling(DataHandlingStatus.UNKNOWN), true, config.endpoint, "stop-judge"),
        ModelEntry(config.model, "anthropic", config.model, Lifecycle.ACTIVE, null, emptySet(), 0, config.maxOutputTokens, emptyList(), null),
    )

    override suspend fun judge(input: JudgeInput): JudgeResult {
        if (!breaker.allow()) return fail(JudgeErrorCode.CIRCUIT_OPEN, 0, countsForBreaker = false)
        var last = JudgeErrorCode.TRANSPORT
        var made = 0
        for (attempt in 1..config.maxAttempts) {
            made = attempt
            when (val outcome = attemptOnce(input)) {
                is Attempt.Done -> {
                    breaker.onSuccess()
                    return outcome.result
                }
                is Attempt.Failed -> {
                    last = outcome.code
                    if (!outcome.retryable || attempt == config.maxAttempts) break
                    pause(config.backoffMillis * (1L shl (attempt - 1)))
                }
            }
        }
        return fail(last, made, countsForBreaker = true)
    }

    private fun fail(code: JudgeErrorCode, attempts: Int, countsForBreaker: Boolean): JudgeResult {
        if (countsForBreaker) breaker.onFailure()
        log.warn("stop judge call failed code={} attempts={}", code, attempts)
        return JudgeResult.Error(code)
    }

    private sealed interface Attempt {
        class Done(val result: JudgeResult) : Attempt
        class Failed(val code: JudgeErrorCode, val retryable: Boolean) : Attempt
    }

    private suspend fun attemptOnce(input: JudgeInput): Attempt {
        val authz = try {
            auth.authorize(target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Attempt.Failed(JudgeErrorCode.AUTH, retryable = false)
        }
        return try {
            val outcome = withTimeoutOrNull(config.attemptTimeoutMillis) {
                val resp = http.post("${config.endpoint.trimEnd('/')}/v1/messages") {
                    header(authz.headerName, authz.headerValue)
                    header("anthropic-version", API_VERSION)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody(input))
                }
                val status = resp.status.value
                when {
                    status in 200..299 -> readSuccess(resp.bodyAsText())
                    status == 401 || status == 403 -> {
                        if (status == 401) auth.onUnauthorized(authz)
                        Attempt.Failed(JudgeErrorCode.AUTH, retryable = false)
                    }
                    status == 429 -> Attempt.Failed(JudgeErrorCode.RATE_LIMITED, retryable = true)
                    status >= 500 -> Attempt.Failed(JudgeErrorCode.UPSTREAM, retryable = true)
                    else -> Attempt.Failed(JudgeErrorCode.CLIENT_ERROR, retryable = false)
                }
            }
            outcome ?: Attempt.Failed(JudgeErrorCode.TIMEOUT, retryable = true)
        } catch (e: CancellationException) {
            // The HTTP client can surface our own timeout as a plain cancellation. Only a cancelled caller is rethrown.
            if (!currentCoroutineContext().isActive) throw e
            Attempt.Failed(JudgeErrorCode.TIMEOUT, retryable = true)
        } catch (_: Exception) {
            Attempt.Failed(JudgeErrorCode.TRANSPORT, retryable = true)
        }
    }

    private fun requestBody(input: JudgeInput): String = buildJsonObject {
        put("model", config.model)
        put("max_tokens", config.maxOutputTokens)
        put("system", input.systemPrompt)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", input.userMessage)
            })
        })
    }.toString()

    private fun readSuccess(text: String): Attempt {
        val obj: JsonObject = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return Attempt.Failed(JudgeErrorCode.BAD_RESPONSE, retryable = false)
        val usage = obj["usage"] as? JsonObject
        val inTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull
        val outTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.longOrNull
        val content = obj["content"] as? JsonArray
        if (inTokens == null || outTokens == null || inTokens < 0 || outTokens < 0 || content == null) {
            return Attempt.Failed(JudgeErrorCode.BAD_RESPONSE, retryable = false)
        }
        val modelText = content.mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return Attempt.Done(JudgeResult.Judged(StopJudgeOutputParser.parse(modelText), JudgeUsage(inTokens, outTokens)))
    }

    private companion object {
        const val API_VERSION = "2023-06-01"
    }
}
