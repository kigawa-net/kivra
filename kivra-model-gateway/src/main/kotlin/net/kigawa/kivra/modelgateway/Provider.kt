package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.math.RoundingMode

data class CallContext(val requestId: String, val attemptId: String, val routingDecisionId: String)

enum class ProviderHealthState { UP, DOWN, UNKNOWN }

data class ProviderHealth(val state: ProviderHealthState, val detail: String? = null)

data class ProviderModelInfo(val providerModelName: String, val displayName: String? = null)

/** Provider-neutral stream chunks; the Gateway converts them into sequenced [ModelEvent]s. */
sealed interface StreamChunk {
    data class Text(val text: String) : StreamChunk
    data class ToolCallDelta(val callId: String, val name: String?, val argsJsonDelta: String) : StreamChunk
    data class ToolCallCompleted(val callId: String, val name: String, val args: JsonElement) : StreamChunk
    data class UsageUpdate(val usage: Usage) : StreamChunk
    /** Final chunk. Its [ModelResponse.cost] is overwritten by the Gateway. */
    data class Finished(val response: ModelResponse) : StreamChunk
}

/**
 * Adapter contract (design 05 section 3.1). Implementations must throw [GatewayException] with a classified
 * [GatewayError] for every failure, must not execute tool calls, and must not leak credentials in errors.
 */
interface ModelProvider {
    val providerId: String
    /** Cheap, non-billing availability check. */
    suspend fun health(target: ResolvedTarget): ProviderHealth
    suspend fun listModels(target: ResolvedTarget): List<ProviderModelInfo>
    suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse
    /** Cancelling the collector must close the underlying HTTP connection. */
    fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk>
}

/** Rough, conservative estimators used for budget reservation and context filtering. */
object Estimator {
    fun inputTokens(req: ModelRequest): Long {
        var chars = 0L
        for (m in req.messages) for (p in m.parts) chars += when (p) {
            is Part.Text -> p.text.length
            is Part.Image -> 1500 * 3 // flat allowance
            is Part.ToolCall -> p.arguments.toString().length + p.name.length
            is Part.ToolResult -> p.content.length
        }
        for (t in req.tools) chars += t.name.length + t.description.length + t.inputSchema.toString().length
        (req.responseFormat as? ResponseFormat.JsonSchema)?.let { chars += it.schema.toString().length }
        return chars / 3 + 32
    }

    fun outputTokens(req: ModelRequest, model: ModelEntry): Int = minOf(req.generation.maxOutputTokens, model.maxOutputTokens)

    /** Upper-bound cost used for reservation; null when the model has no price (fail closed). */
    fun maxCost(req: ModelRequest, model: ModelEntry): BigDecimal? {
        val price = model.price ?: return null
        return CostCalculator.cost(Usage(inputTokens(req), outputTokens(req, model).toLong()), price, CostBasis.ESTIMATED).amount
    }

    /**
     * Upper-bound amount for budget admission, bound to the price revision and model. Null when the model has no price:
     * an unknown cost is never admitted as zero.
     */
    fun maxBudgetAmount(req: ModelRequest, model: ModelEntry, priceRevision: String): BudgetAmount? {
        val price = model.price ?: return null
        val c = CostCalculator.cost(Usage(inputTokens(req), outputTokens(req, model).toLong()), price, CostBasis.ESTIMATED)
        return BudgetAmount(c.amount, c.currency, c.unit, priceRevision, model.ref)
    }
}

object CostCalculator {
    private val MILLION = BigDecimal(1_000_000)

    fun cost(usage: Usage, price: Price, basis: CostBasis): Cost {
        fun part(tokens: Long, perMtok: BigDecimal) = BigDecimal(tokens).multiply(perMtok).divide(MILLION, 10, RoundingMode.UP)
        val total = part(usage.inputTokens, price.inputPerMtok) +
            part(usage.outputTokens, price.outputPerMtok) +
            part(usage.cachedInputTokens, price.cachedInputPerMtok ?: price.inputPerMtok)
        return Cost(total, price.currency, basis)
    }
}
