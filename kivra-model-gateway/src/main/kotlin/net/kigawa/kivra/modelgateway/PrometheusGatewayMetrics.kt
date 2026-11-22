package net.kigawa.kivra.modelgateway

import net.kigawa.kivra.observability.MetricSpec
import net.kigawa.kivra.observability.MetricType
import net.kigawa.kivra.observability.MetricsRegistry

/** Bounded, sanitized series using the shared Prometheus exposition registry. No request/principal labels. */
class PrometheusGatewayMetrics(val registry: MetricsRegistry = MetricsRegistry(SPECS)) : GatewayMetrics {
    private val requests = registry.counter("model_requests_total")
    private val tokens = registry.counter("model_tokens_total")
    private val costs = registry.counter("model_cost_total")
    private val latency = registry.histogram("model_latency_seconds")
    private val fallbacks = registry.counter("model_fallback_total")
    private val rejected = registry.counter("model_route_rejected_total")

    override fun requestCompleted(providerId: String, modelId: String, purpose: Purpose, outcome: String,
                                  errorKind: ErrorKind?, usage: Usage, cost: Cost?, latencyMs: Long) {
        val labels = mapOf("provider" to providerId, "model" to modelId, "purpose" to purpose.name)
        requests.increment(labels + mapOf("outcome" to outcome, "error_kind" to (errorKind?.name ?: "none")))
        val tokenLabels = mapOf("model" to modelId, "purpose" to purpose.name)
        tokens.increment(tokenLabels + ("direction" to "input"), (usage.inputTokens + usage.cacheCreationInputTokens).toDouble())
        tokens.increment(tokenLabels + ("direction" to "output"), usage.outputTokens.toDouble())
        tokens.increment(tokenLabels + ("direction" to "cached"), usage.cachedInputTokens.toDouble())
        // The hook has no budget-scope information. Keep currencies separate instead of inventing scope labels.
        cost?.let { costs.increment(mapOf("provider" to providerId, "model" to modelId,
            "basis" to it.basis.name.lowercase(), "currency" to it.currency, "unit" to it.unit), it.amount.toDouble()) }
        latency.observe(latencyMs.coerceAtLeast(0) / 1000.0, mapOf("phase" to "total"))
    }

    override fun fallback(from: ModelRef, to: ModelRef, reason: ErrorKind) {
        fallbacks.increment(mapOf("from" to "${from.providerId}/${from.modelId}",
            "to" to "${to.providerId}/${to.modelId}", "reason" to reason.name))
    }
    override fun routeRejected(reason: String) { rejected.increment(mapOf("reason" to reason)) }
    fun renderPrometheus(): String = registry.renderPrometheus()

    companion object {
        val SPECS: List<MetricSpec> = listOf(
            MetricSpec("model_requests_total", MetricType.COUNTER, "requests", "Gateway attempts",
                setOf("provider", "model", "purpose", "outcome", "error_kind")),
            MetricSpec("model_tokens_total", MetricType.COUNTER, "tokens", "Provider reported tokens",
                setOf("direction", "model", "purpose")),
            MetricSpec("model_cost_total", MetricType.COUNTER, "currency_units", "Gateway cost by currency and basis",
                setOf("provider", "model", "basis", "currency", "unit")),
            MetricSpec("model_latency_seconds", MetricType.HISTOGRAM, "seconds", "Gateway attempt latency",
                setOf("phase"), buckets = listOf(0.1, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0)),
            MetricSpec("model_fallback_total", MetricType.COUNTER, "fallbacks", "Gateway fallbacks", setOf("from", "to", "reason")),
            MetricSpec("model_route_rejected_total", MetricType.COUNTER, "requests", "Gateway policy rejections", setOf("reason")),
        )
    }
}
