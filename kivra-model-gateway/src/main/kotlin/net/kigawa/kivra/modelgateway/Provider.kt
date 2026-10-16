package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

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

    /** The upper-bound estimate, or the reason it cannot be made (fail closed). */
    sealed interface MaxCost {
        data class Priced(val amount: BudgetAmount) : MaxCost
        data class Unknown(val reason: UnknownCostReason) : MaxCost
    }

    /**
     * Upper-bound amount for budget admission in the budget currency. Upper bound: the highest-tier rate is used.
     * Unknown (never zero) when no price version is in force at [now], the cost has cache writes without a rate, or the
     * cost cannot be expressed in the budget currency.
     */
    fun maxCost(
        req: ModelRequest, model: ModelEntry, provider: ProviderEntry, registryVersion: String, now: Instant, converter: CostConverter?,
    ): MaxCost {
        val price = model.priceAt(now) ?: return MaxCost.Unknown(UnknownCostReason.PRICE_MISSING)
        val usage = Usage(inputTokens(req), outputTokens(req, model).toLong())
        val native = CostCalculator.cost(usage, price.rateFor(null), price.currency, CostBasis.ESTIMATED)?.amount
            ?: return MaxCost.Unknown(UnknownCostReason.CACHE_WRITE_UNPRICED)
        val amount = toBudget(native, price, model.ref, provider, registryVersion, now, converter)
            ?: return MaxCost.Unknown(UnknownCostReason.FX_UNUSABLE)
        return MaxCost.Priced(amount)
    }

    fun maxBudgetAmount(
        req: ModelRequest, model: ModelEntry, provider: ProviderEntry, registryVersion: String, now: Instant, converter: CostConverter?,
    ): BudgetAmount? = (maxCost(req, model, provider, registryVersion, now, converter) as? MaxCost.Priced)?.amount

    /**
     * Converts a native-currency amount into a budget amount. A local model with an explicitly zero price is free: it is
     * booked as zero without a conversion, so it needs no exchange rate. Otherwise the amount is converted through
     * [converter] when one is injected; without one the amount stays in the price currency (the USD budget path).
     */
    fun toBudget(
        native: BigDecimal, price: Price, ref: ModelRef, provider: ProviderEntry, registryVersion: String, now: Instant,
        converter: CostConverter?,
    ): BudgetAmount? {
        if (provider.trustZone == TrustZone.LOCAL && price.isExplicitlyZero()) {
            val currency = if (converter == null) price.currency else CostConverter.YEN
            return BudgetAmount(BigDecimal.ZERO, currency, Cost.UNIT_CURRENCY_UNITS, "$registryVersion;free-local", ref)
        }
        if (converter == null) return BudgetAmount(native, price.currency, Cost.UNIT_CURRENCY_UNITS, registryVersion, ref)
        val yen = converter.toYen(native, price.currency, now) ?: return null
        return BudgetAmount(
            yen.amount, CostConverter.YEN, Cost.UNIT_CURRENCY_UNITS,
            "$registryVersion;${yen.evidence};priceFrom=${price.effectiveFrom ?: "always"}", ref,
        )
    }
}

object CostCalculator {
    private val MILLION = BigDecimal(1_000_000)

    /**
     * Cost of [usage] at [rate]. Null when cache write tokens are present but the registry has no rate for their TTL: the cost
     * is then unknown and must not be booked as zero (fail closed, the caller records it as unpriced).
     */
    fun cost(usage: Usage, rate: Rate, currency: String, basis: CostBasis): Cost? {
        fun part(tokens: Long, perMtok: BigDecimal) = BigDecimal(tokens).multiply(perMtok).divide(MILLION, 10, RoundingMode.UP)
        val writes1h = usage.cacheCreation1hInputTokens
        val writes5m = usage.cacheCreationInputTokens - writes1h
        val rate5m = rate.cacheWrite5mPerMtok
        val rate1h = rate.cacheWrite1hPerMtok
        if (writes5m > 0 && rate5m == null) return null
        if (writes1h > 0 && rate1h == null) return null
        val total = part(usage.inputTokens, rate.inputPerMtok) +
            part(usage.outputTokens, rate.outputPerMtok) +
            part(usage.cachedInputTokens, rate.cachedInputPerMtok) +
            (if (writes5m > 0) part(writes5m, rate5m!!) else BigDecimal.ZERO) +
            (if (writes1h > 0) part(writes1h, rate1h!!) else BigDecimal.ZERO)
        return Cost(total, currency, basis)
    }
}
