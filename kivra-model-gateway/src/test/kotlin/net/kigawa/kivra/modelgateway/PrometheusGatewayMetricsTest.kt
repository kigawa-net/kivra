package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertTrue

class PrometheusGatewayMetricsTest {
    @Test fun exportsCountersHistogramsAndSeparatesCurrencies() {
        val metrics = PrometheusGatewayMetrics()
        metrics.requestCompleted("p", "m", Purpose.CODING, "success", null, Usage(10, 20, 3, 4),
            Cost(BigDecimal("1.5"), "USD", CostBasis.ACTUAL), 1500)
        metrics.requestCompleted("p", "m", Purpose.CODING, "error", ErrorKind.RATE_LIMITED, Usage.ZERO,
            Cost(BigDecimal("100"), "JPY", CostBasis.ESTIMATED), 500)
        metrics.fallback(ModelRef("p", "m", "m"), ModelRef("q", "n", "n"), ErrorKind.RATE_LIMITED)
        metrics.routeRejected("SECRET_DETECTED")
        val text = metrics.renderPrometheus()
        listOf(
            "model_requests_total{error_kind=\"none\",model=\"m\",outcome=\"success\",provider=\"p\",purpose=\"CODING\"} 1",
            "model_tokens_total{direction=\"input\",model=\"m\",purpose=\"CODING\"} 14",
            "model_tokens_total{direction=\"cached\",model=\"m\",purpose=\"CODING\"} 3",
            "currency=\"USD\"", "currency=\"JPY\"",
            "model_latency_seconds_count{phase=\"total\"} 2",
            "model_latency_seconds_sum{phase=\"total\"} 2",
            "model_latency_seconds_bucket{phase=\"total\",le=\"+Inf\"} 2",
            "model_fallback_total{from=\"p/m\",reason=\"RATE_LIMITED\",to=\"q/n\"} 1",
            "model_route_rejected_total{reason=\"SECRET_DETECTED\"} 1",
        ).forEach { assertTrue(text.contains(it), "missing sample: $it") }
    }
}
