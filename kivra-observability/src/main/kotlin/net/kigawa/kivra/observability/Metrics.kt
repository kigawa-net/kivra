package net.kigawa.kivra.observability

import java.util.concurrent.ConcurrentHashMap

enum class MetricType(val promType: String) {
    COUNTER("counter"),
    GAUGE("gauge"),
    HISTOGRAM("histogram"),
}

/**
 * Declaration of one metric. [labels] is the only set of label keys a sample may carry; anything
 * else is dropped at record time. [unit] is documentation only and appears in the HELP text.
 */
data class MetricSpec(
    val name: String,
    val type: MetricType,
    val unit: String,
    val help: String,
    val labels: Set<String> = emptySet(),
    val buckets: List<Double> = emptyList(),
) {
    init {
        require(NAME_PATTERN.matches(name)) { "invalid metric name: $name" }
        labels.forEach { require(NAME_PATTERN.matches(it)) { "invalid label name: $it" } }
        require(type != MetricType.COUNTER || name.endsWith("_total")) { "counter must end with _total: $name" }
        if (type == MetricType.HISTOGRAM) {
            require(buckets.isNotEmpty() && buckets == buckets.sorted()) { "histogram needs sorted buckets: $name" }
        }
    }

    companion object {
        internal val NAME_PATTERN = Regex("^[a-z_][a-z0-9_]*$")
    }
}

/**
 * In-process metric registry with Prometheus text exposition.
 *
 * Recording methods never throw: an unknown label is dropped and an unsafe label value is replaced
 * with [Redaction.REDACTED]. Only [counter] / [gauge] / [histogram] lookups throw, because an
 * unknown metric name is a wiring bug to catch at startup.
 */
class MetricsRegistry(specs: List<MetricSpec> = MetricCatalog.ALL) {
    private val families: Map<String, Family> = specs.associate { it.name to Family(it) }
        .also { require(it.size == specs.size) { "duplicate metric names" } }

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

    internal class Family(val spec: MetricSpec) {
        private val series = ConcurrentHashMap<List<Pair<String, String>>, Series>()

        fun update(labels: Map<String, String>, action: (Series) -> Unit) {
            val key = sanitize(labels)
            val s = series.computeIfAbsent(key) { Series(spec.buckets.size) }
            synchronized(s) { action(s) }
        }

        private fun sanitize(labels: Map<String, String>): List<Pair<String, String>> =
            labels.entries
                .filter { it.key in spec.labels }
                .map { (k, v) -> k to (if (Redaction.isSafeValue(v)) v else Redaction.REDACTED) }
                .sortedBy { it.first }

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
