package net.kigawa.kivra.modelgateway

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Data classification (design 05 section 9.1). Callers set it; the Gateway never lowers it. */
enum class DataClass { PUBLIC, INTERNAL, RESTRICTED, SECRET }

/** Where a provider lives. [outwardness] is higher the further data leaves our control. */
enum class TrustZone(val outwardness: Int) { LOCAL(0), INTERNAL(1), EXTERNAL(2) }

enum class Capability {
    TEXT, VISION_INPUT, TOOL_CALLING, PARALLEL_TOOL_CALLS, STRUCTURED_OUTPUT_NATIVE,
    STREAMING, SYSTEM_PROMPT, LONG_CONTEXT, REASONING, PROMPT_CACHING, EMBEDDINGS, FILE_INPUT,
}

enum class Purpose { PLANNING, CODING, REVIEW, SUMMARIZE, CLASSIFY, EMBED, OTHER }

enum class QualityTier { C, B, A, S }

enum class Locality { ANY, LOCAL_ONLY }

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

sealed interface Part {
    data class Text(val text: String) : Part
    data class Image(val mediaType: String, val base64Data: String) : Part
    /** A tool call proposed by the model. The Gateway never executes it. */
    data class ToolCall(val id: String, val name: String, val arguments: JsonElement) : Part
    data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : Part
}

data class Message(val role: Role, val parts: List<Part>) {
    constructor(role: Role, text: String) : this(role, listOf(Part.Text(text)))
}

data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

enum class ToolChoice { AUTO, REQUIRED, NONE }

sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data class JsonSchema(val name: String, val schema: JsonObject) : ResponseFormat
}

data class GenerationParams(
    val maxOutputTokens: Int = 1024,
    val temperature: Double? = null,
    val stopSequences: List<String> = emptyList(),
)

data class Requirements(
    val requiredCapabilities: Set<Capability> = emptySet(),
    val minContextTokens: Int? = null,
    val minQualityTier: QualityTier? = null,
    val allowedProviders: Set<String>? = null,
    val deniedProviders: Set<String> = emptySet(),
    val locality: Locality = Locality.ANY,
)

/** Soft preference only. Never relaxes a hard constraint. */
data class RoutingPreference(val quality: Double = 0.5, val cost: Double = 0.5)

data class ModelRequest(
    val requestId: String,
    val principal: String,
    val purpose: Purpose,
    val messages: List<Message>,
    /**
     * Mandatory; there is no default so callers must classify explicitly (fail closed). Must be the authentic
     * classification fixed by the trusted Orchestrator / Policy Gateway, never a value an Agent can lower.
     */
    val dataClass: DataClass,
    /** Budget scope path from the outermost (org) to the innermost (task / execution). */
    val budgetScopes: List<String>,
    val tools: List<ToolSpec> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.AUTO,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val generation: GenerationParams = GenerationParams(),
    val requirements: Requirements = Requirements(),
    val preference: RoutingPreference = RoutingPreference(),
    /** Total deadline shared by retries and fallbacks. */
    val timeout: Duration = 60.seconds,
    val taskId: String? = null,
    val executionId: String? = null,
    val agentId: String? = null,
)

data class ModelRef(val providerId: String, val modelId: String, val providerModelName: String)

enum class FinishReason { STOP, LENGTH, TOOL_CALL, CONTENT_FILTER, CANCELLED, ERROR }

/**
 * Provider-reported token counts. [cachedInputTokens] are cache reads. [cacheCreationInputTokens] are cache writes (all TTLs),
 * billed at the cache write rate. [cacheCreation1hInputTokens] is the part of them written with the 1-hour TTL; the rest is
 * the 5-minute TTL. Invariant: 0 <= cacheCreation1hInputTokens <= cacheCreationInputTokens.
 */
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
    val cacheCreation1hInputTokens: Long = 0,
) {
    companion object { val ZERO = Usage() }
}

enum class CostBasis { ACTUAL, ESTIMATED }

/**
 * A cost bound to the evidence it was computed from. [unit] is whole currency units; [priceRevision] is the registry
 * revision whose price produced [amount]; [model] and [requestId] identify the call. Amounts are only summed when
 * currency and unit match (see [BudgetAmount]).
 */
data class Cost(
    val amount: BigDecimal,
    val currency: String,
    val basis: CostBasis,
    val unit: String = UNIT_CURRENCY_UNITS,
    val priceRevision: String? = null,
    val model: ModelRef? = null,
    val requestId: String? = null,
) {
    companion object {
        const val UNIT_CURRENCY_UNITS = "currency_units"
        fun zero(currency: String = "USD") = Cost(BigDecimal.ZERO, currency, CostBasis.ESTIMATED)
    }
}

data class GatewayWarning(val code: String, val message: String)

data class ModelResponse(
    val requestId: String,
    val attemptId: String,
    val selected: ModelRef,
    val message: Message,
    val structured: JsonElement?,
    val finishReason: FinishReason,
    val usage: Usage,
    val cost: Cost,
    val latencyMs: Long,
    val routingDecisionId: String,
    val warnings: List<GatewayWarning> = emptyList(),
    /**
     * True only when the provider reported complete usage (input and output tokens). When false, [cost] is the
     * conservative estimate and the call is recorded as unpriced by the budget.
     */
    val usageComplete: Boolean = false,
)

sealed interface ModelEvent {
    val requestId: String
    val attemptId: String
    val seq: Long

    data class Started(override val requestId: String, override val attemptId: String, override val seq: Long,
                       val selected: ModelRef, val routingDecisionId: String) : ModelEvent
    data class TextDelta(override val requestId: String, override val attemptId: String, override val seq: Long,
                         val text: String) : ModelEvent
    data class ToolCallDelta(override val requestId: String, override val attemptId: String, override val seq: Long,
                             val callId: String, val name: String?, val argsJsonDelta: String) : ModelEvent
    /** Only after this event may the caller hand the call to the Tool Gateway. */
    data class ToolCallCompleted(override val requestId: String, override val attemptId: String, override val seq: Long,
                                 val callId: String, val name: String, val args: JsonElement) : ModelEvent
    data class UsageUpdate(override val requestId: String, override val attemptId: String, override val seq: Long,
                           val usage: Usage) : ModelEvent
    data class Completed(override val requestId: String, override val attemptId: String, override val seq: Long,
                         val response: ModelResponse) : ModelEvent
    data class Failed(override val requestId: String, override val attemptId: String, override val seq: Long,
                      val error: GatewayError) : ModelEvent
}
