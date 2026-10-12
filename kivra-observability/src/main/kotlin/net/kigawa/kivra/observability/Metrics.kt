package net.kigawa.kivra.observability

import java.util.concurrent.ConcurrentHashMap

enum class MetricType(val promType: String) {
    COUNTER("counter"),
    GAUGE("gauge"),
    HISTOGRAM("histogram"),
}

/**
 * Declaration of one metric.
 *
 * - [labels] is the only set of label keys a sample may carry; anything else is dropped at record time.
 * - [labelValues] enumerates the allowed values of a label key. A value outside the enumeration is
 *   recorded as [MetricsRegistry.OTHER]. Keys without an enumeration accept any value that passes
 *   [Redaction.isSafeValue], and are bounded only by [maxSeries].
 * - [maxSeries] caps the number of distinct label sets (time series) of this family. A sample that
 *   would create a series beyond the cap is dropped and counted in
 *   `kivra_metrics_dropped_series_total`. Existing series keep updating.
 * - [unit] is documentation only and appears in the HELP text.
 */
data class MetricSpec(
    val name: String,
    val type: MetricType,
    val unit: String,
    val help: String,
    val labels: Set<String> = emptySet(),
    val buckets: List<Double> = emptyList(),
    val labelValues: Map<String, Set<String>> = emptyMap(),
    val maxSeries: Int = DEFAULT_MAX_SERIES,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "invalid metric name: $name" }
        labels.forEach { require(NAME_PATTERN.matches(it)) { "invalid label name: $it" } }
        labelValues.forEach { (key, values) ->
            require(key in labels) { "label values declared for undeclared label $key in $name" }
            require(values.isNotEmpty()) { "empty value enumeration for $key in $name" }
        }
        require(maxSeries >= 1) { "maxSeries must be positive: $name" }
        require(type != MetricType.COUNTER || name.endsWith("_total")) { "counter must end with _total: $name" }
        if (type == MetricType.HISTOGRAM) {
            require(buckets.isNotEmpty() && buckets == buckets.sorted()) { "histogram needs sorted buckets: $name" }
        }
    }

    companion object {
        internal val NAME_PATTERN = Regex("^[a-z_][a-z0-9_]*$")
        const val DEFAULT_MAX_SERIES = 64
    }
}

/**
 * In-process metric registry with Prometheus text exposition.
 *
 * Recording methods never throw: an unknown label is dropped, an unsafe label value is replaced
 * with [Redaction.REDACTED], an unlisted enumerated value becomes [OTHER], and a sample beyond the
 * series cap is dropped and counted in [DROPPED_SERIES_NAME]. Only [counter] / [gauge] / [histogram]
 * lookups throw, because an unknown metric name is a wiring bug to catch at startup.
 */
class MetricsRegistry(specs: List<MetricSpec> = MetricCatalog.ALL) {
    private val droppedSeries: MetricSpec = MetricSpec(
        DROPPED_SERIES_NAME, MetricType.COUNTER, "1",
        "Samples dropped because their metric reached maxSeries", setOf("metric"),
        labelValues = mapOf("metric" to specs.map { it.name }.toSet()),
        maxSeries = specs.size + 1,
    )

    private val families: Map<String, Family> = run {
        require(specs.map { it.name }.toSet().size == specs.size) { "duplicate metric names" }
        require(specs.none { it.name == DROPPED_SERIES_NAME }) { "$DROPPED_SERIES_NAME is reserved" }
        val map = specs.associate { it.name to Family(it, onDrop = { name -> dropped.increment(mapOf("metric" to name)) }) }
        map + (DROPPED_SERIES_NAME to Family(droppedSeries, onDrop = {}))
    }

    private val dropped: Counter get() = Counter(families.getValue(DROPPED_SERIES_NAME))

    fun counter(name: String): Counter = Counter(family(name, MetricType.COUNTER))
    fun gauge(name: String): Gauge = Gauge(family(name, MetricType.GAUGE))
    fun histogram(name: String): Histogram = Histogram(family(name, MetricType.HISTOGRAM))

    /** Prometheus text format 0.0.4 for the whole registry, families sorted by name. */
    fun renderPrometheus(): String = buildString {
        families.values.sortedBy { it.spec.name }.forEach { it.render(this) }
    }

    private fun family(name: String, type: MetricType): Family {
        val family = families[name] ?: throw IllegalArgumentException("unknown metric: $name")
        require(family.spec.type == type) { "$name is ${family.spec.type}, not $type" }
        return family
    }

    companion object {
        /** Value recorded for a label value that is not in the metric's enumeration. */
        const val OTHER = "other"

        /** Meta counter, one series per metric family, incremented when [MetricSpec.maxSeries] drops a sample. */
        const val DROPPED_SERIES_NAME = "kivra_metrics_dropped_series_total"
    }

    class Counter internal constructor(private val family: Family) {
        /** Negative or NaN increments are ignored: counters only grow. */
        fun increment(labels: Map<String, String> = emptyMap(), by: Double = 1.0) {
            if (by.isNaN() || by < 0) return
            family.update(labels) { it.value += by }
        }
    }

    class Gauge internal constructor(private val family: Family) {
        fun set(value: Double, labels: Map<String, String> = emptyMap()) {
            if (value.isNaN()) return
            family.update(labels) { it.value = value }
        }
    }

    class Histogram internal constructor(private val family: Family) {
        fun observe(value: Double, labels: Map<String, String> = emptyMap()) {
            if (value.isNaN()) return
            family.update(labels) { s ->
                s.sum += value
                s.count++
                val idx = family.spec.buckets.indexOfFirst { value <= it }
                if (idx >= 0) s.bucketCounts[idx]++
            }
        }
    }

    internal class Series(bucketCount: Int) {
        var value: Double = 0.0
        var sum: Double = 0.0
        var count: Long = 0
        /** Non-cumulative per-bucket counts; cumulated at render time. */
        val bucketCounts: LongArray = LongArray(bucketCount)
    }

    internal class Family(val spec: MetricSpec, private val onDrop: (String) -> Unit) {
        private val series = ConcurrentHashMap<List<Pair<String, String>>, Series>()
        private val creationLock = Any()

        fun update(labels: Map<String, String>, action: (Series) -> Unit) {
            val key = sanitize(labels)
            val s = series[key] ?: createSeries(key) ?: run {
                onDrop(spec.name)
                return
            }
            synchronized(s) { action(s) }
        }

        /** Returns null when the family is at [MetricSpec.maxSeries] and [key] would be a new series. */
        private fun createSeries(key: List<Pair<String, String>>): Series? = synchronized(creationLock) {
            series[key] ?: if (series.size >= spec.maxSeries) {
                null
            } else {
                Series(spec.buckets.size).also { series[key] = it }
            }
        }

        private fun sanitize(labels: Map<String, String>): List<Pair<String, String>> =
            labels.entries
                .filter { it.key in spec.labels }
                .map { (k, v) -> k to normalizeValue(k, v) }
                .sortedBy { it.first }

        private fun normalizeValue(key: String, value: String): String {
            val allowed = spec.labelValues[key]
            if (allowed != null) return if (value in allowed) value else OTHER
            return if (Redaction.isSafeValue(value)) value else Redaction.REDACTED
        }

        fun render(out: StringBuilder) {
            out.append("# HELP ").append(spec.name).append(' ').append(spec.help)
                .append(" (unit: ").append(spec.unit).append(")\n")
            out.append("# TYPE ").append(spec.name).append(' ').append(spec.type.promType).append('\n')
            series.entries.sortedBy { it.key.toString() }.forEach { (labels, s) ->
                synchronized(s) {
                    when (spec.type) {
                        MetricType.HISTOGRAM -> renderHistogram(out, labels, s)
                        else -> line(out, spec.name, labels, s.value)
                    }
                }
            }
        }

        private fun renderHistogram(out: StringBuilder, labels: List<Pair<String, String>>, s: Series) {
            var cumulative = 0L
            spec.buckets.forEachIndexed { i, bound ->
                cumulative += s.bucketCounts[i]
                line(out, "${spec.name}_bucket", labels + ("le" to formatDouble(bound)), cumulative.toDouble())
            }
            line(out, "${spec.name}_bucket", labels + ("le" to "+Inf"), s.count.toDouble())
            line(out, "${spec.name}_sum", labels, s.sum)
            line(out, "${spec.name}_count", labels, s.count.toDouble())
        }

        private fun line(out: StringBuilder, name: String, labels: List<Pair<String, String>>, value: Double) {
            out.append(name)
            if (labels.isNotEmpty()) {
                out.append('{')
                labels.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    out.append(k).append("=\"").append(escape(v)).append('"')
                }
                out.append('}')
            }
            out.append(' ').append(formatDouble(value)).append('\n')
        }

        private fun escape(v: String): String =
            v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

        private fun formatDouble(v: Double): String = when {
            v == Double.POSITIVE_INFINITY -> "+Inf"
            v == Double.NEGATIVE_INFINITY -> "-Inf"
            v == Math.floor(v) && Math.abs(v) < 1e15 -> v.toLong().toString()
            else -> v.toString()
        }
    }
}
