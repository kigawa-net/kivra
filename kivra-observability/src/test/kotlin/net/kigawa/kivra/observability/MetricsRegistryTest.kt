package net.kigawa.kivra.observability

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetricsRegistryTest {
    @Test
    fun `counter renders HELP, TYPE and labelled samples`() {
        val registry = MetricsRegistry()
        val counter = registry.counter("kivra_run_total")
        counter.increment(mapOf("outcome" to "completed"))
        counter.increment(mapOf("outcome" to "completed"), by = 2.0)
        counter.increment(mapOf("outcome" to "failed"))

        val text = registry.renderPrometheus()
        assertContains(text, "# HELP kivra_run_total Finished DevelopmentRuns by outcome (success rate = completed / all) (unit: 1)\n")
        assertContains(text, "# TYPE kivra_run_total counter\n")
        assertContains(text, "kivra_run_total{outcome=\"completed\"} 3\n")
        assertContains(text, "kivra_run_total{outcome=\"failed\"} 1\n")
    }

    @Test
    fun `labels outside the spec are dropped and unsafe values are replaced`() {
        val registry = MetricsRegistry()
        registry.counter("kivra_run_total").increment(
            mapOf(
                "outcome" to "sk-abcdefghijklmnop1234",
                "runId" to "01HZRUNXYZ",
                "prompt" to "hello",
            ),
        )
        val text = registry.renderPrometheus()
        assertContains(text, "kivra_run_total{outcome=\"${Redaction.REDACTED}\"} 1\n")
        assertFalse(text.contains("sk-abcdef"))
        assertFalse(text.contains("01HZRUNXYZ"))
        assertFalse(text.contains("hello"))
    }

    @Test
    fun `histogram renders cumulative buckets, +Inf, sum and count`() {
        val registry = MetricsRegistry()
        val h = registry.histogram("kivra_approval_wait_seconds")
        h.observe(0.5, mapOf("kind" to "merge"))
        h.observe(10.0, mapOf("kind" to "merge"))
        h.observe(1_000_000.0, mapOf("kind" to "merge"))

        val text = registry.renderPrometheus()
        assertContains(text, "kivra_approval_wait_seconds_bucket{kind=\"merge\",le=\"1\"} 1\n")
        assertContains(text, "kivra_approval_wait_seconds_bucket{kind=\"merge\",le=\"60\"} 2\n")
        assertContains(text, "kivra_approval_wait_seconds_bucket{kind=\"merge\",le=\"+Inf\"} 3\n")
        assertContains(text, "kivra_approval_wait_seconds_count{kind=\"merge\"} 3\n")
        assertContains(text, "kivra_approval_wait_seconds_sum{kind=\"merge\"} 1000010.5\n")
    }

    @Test
    fun `gauge keeps the last value and invalid updates are ignored`() {
        val registry = MetricsRegistry()
        val g = registry.gauge("kivra_recovery_queue_depth")
        g.set(5.0)
        g.set(Double.NaN)
        registry.counter("kivra_rollback_total").increment(by = -1.0)
        val text = registry.renderPrometheus()
        assertContains(text, "kivra_recovery_queue_depth 5\n")
        assertTrue(text.lines().none { it.startsWith("kivra_rollback_total") && !it.startsWith("#") })
    }

    @Test
    fun `label values are escaped for the exposition format`() {
        val registry = MetricsRegistry()
        registry.counter("kivra_model_tokens_total").increment(mapOf("provider" to "a\"b\\c", "direction" to "input"))
        assertContains(registry.renderPrometheus(), "provider=\"a\\\"b\\\\c\"")
    }

    @Test
    fun `unknown metric and type mismatch fail at lookup`() {
        val registry = MetricsRegistry()
        assertFailsWith<IllegalArgumentException> { registry.counter("not_declared_total") }
        assertFailsWith<IllegalArgumentException> { registry.gauge("kivra_run_total") }
    }

    @Test
    fun `catalog is well-formed and every metric is exposed in HELP and TYPE form`() {
        val names = MetricCatalog.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("kivra_") })
        val text = MetricsRegistry().renderPrometheus()
        names.forEach { assertContains(text, "# TYPE $it ") }
    }

    @Test
    fun `invalid spec is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            MetricSpec("bad_counter", MetricType.COUNTER, "1", "x")
        }
        assertFailsWith<IllegalArgumentException> {
            MetricSpec("h_seconds", MetricType.HISTOGRAM, "s", "x", buckets = emptyList())
        }
    }

    @Test
    fun `series cap drops new label sets, keeps existing ones updating and counts the drops`() {
        val spec = MetricSpec("kivra_test_total", MetricType.COUNTER, "1", "test", setOf("provider"), maxSeries = 3)
        val registry = MetricsRegistry(listOf(spec))
        val counter = registry.counter("kivra_test_total")
        repeat(10) { counter.increment(mapOf("provider" to "p$it")) }
        counter.increment(mapOf("provider" to "p0"), by = 4.0)

        val text = registry.renderPrometheus()
        val samples = text.lines().filter { it.startsWith("kivra_test_total{") }
        assertEquals(3, samples.size)
        assertContains(text, "kivra_test_total{provider=\"p0\"} 5\n")
        assertFalse(text.contains("provider=\"p9\""))
        assertContains(text, "${MetricsRegistry.DROPPED_SERIES_NAME}{metric=\"kivra_test_total\"} 7\n")
    }

    @Test
    fun `random high-cardinality values stay bounded in memory and output`() {
        val registry = MetricsRegistry()
        val counter = registry.counter("kivra_provider_request_total")
        val random = java.util.Random(42)
        repeat(20_000) {
            counter.increment(mapOf("provider" to "p-${random.nextLong()}", "outcome" to "o-${random.nextLong()}"))
        }
        val text = registry.renderPrometheus()
        val samples = text.lines().filter { it.startsWith("kivra_provider_request_total{") }
        assertTrue(samples.size <= MetricSpec.DEFAULT_MAX_SERIES)
        assertContains(text, "${MetricsRegistry.DROPPED_SERIES_NAME}{metric=\"kivra_provider_request_total\"} ")
        assertTrue(text.length < 64 * 1024, "exposition grew to ${text.length} bytes")
    }

    @Test
    fun `values outside an enumeration are recorded as other`() {
        val spec = MetricSpec(
            "kivra_test_total", MetricType.COUNTER, "1", "test", setOf("outcome"),
            labelValues = mapOf("outcome" to setOf("completed", "failed")),
        )
        val registry = MetricsRegistry(listOf(spec))
        val counter = registry.counter("kivra_test_total")
        counter.increment(mapOf("outcome" to "completed"))
        counter.increment(mapOf("outcome" to "run-01HZRUNXYZ reason: something free-form"))
        val text = registry.renderPrometheus()
        assertContains(text, "kivra_test_total{outcome=\"completed\"} 1\n")
        assertContains(text, "kivra_test_total{outcome=\"other\"} 1\n")
        assertFalse(text.contains("free-form"))
    }

    @Test
    fun `enumeration must refer to a declared label`() {
        assertFailsWith<IllegalArgumentException> {
            MetricSpec("kivra_test_total", MetricType.COUNTER, "1", "x", emptySet(), labelValues = mapOf("outcome" to setOf("a")))
        }
    }

    @Test
    fun `identifier-like label keys are never catalog labels and identifier values create no extra series`() {
        val declared = MetricCatalog.ALL.flatMap { it.labels }.toSet()
        assertTrue(declared.none { it in MetricCatalog.FORBIDDEN_LABEL_KEYS }, "forbidden label in catalog: ${declared.intersect(MetricCatalog.FORBIDDEN_LABEL_KEYS)}")

        val registry = MetricsRegistry()
        val counter = registry.counter("kivra_run_total")
        repeat(100) { counter.increment(mapOf("outcome" to "completed", "runId" to "01HZRUN$it", "executionId" to "exec-$it")) }
        val text = registry.renderPrometheus()
        assertEquals(1, text.lines().count { it.startsWith("kivra_run_total{") })
        assertFalse(text.contains("01HZRUN"))
        assertFalse(text.contains("exec-"))
    }

    @Test
    fun `dropped series meta counter is always exposed`() {
        assertContains(MetricsRegistry().renderPrometheus(), "# TYPE ${MetricsRegistry.DROPPED_SERIES_NAME} counter\n")
    }
}
