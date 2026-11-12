package net.kigawa.kivra.agent.k8s

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerConstants
import net.kigawa.kivra.agent.docker.DockerPatterns

/**
 * A value of a Kubernetes Job spec is invalid. The message names the field only, never the value (as in `DockerSpecException`).
 */
class K8sSpecException(message: String) : IllegalArgumentException(message)

/**
 * The cluster-side settings of the k8s backend (ADR 0010 追補 7, PR 3). They are checked once, and the builder and the guard both use
 * them. [namespace] is the only namespace a Job may be created in. [allowedImages] and [allowedModels] are the same kind of list as
 * the docker settings. [toString] shows no value.
 */
class K8sSettings(
    namespace: String,
    allowedImages: Set<String>,
    allowedModels: Set<String> = setOf(CliCommand.DEFAULT_MODEL),
) {
    val namespace: String = if (K8sNames.isDns1123Label(namespace)) namespace else throw K8sSpecException("namespace is invalid")
    val allowedImages: Set<String> = checkImages(allowedImages)
    val allowedModels: Set<String> = checkModels(allowedModels)

    override fun toString(): String = "K8sSettings(***)"

    private companion object {
        fun checkImages(images: Set<String>): Set<String> {
            if (images.isEmpty()) throw K8sSpecException("allowedImages must not be empty")
            for (image in images) {
                if (image.length > 255 || !DockerPatterns.IMAGE.matches(image)) throw K8sSpecException("allowedImages contains an invalid image")
            }
            return images.toSet()
        }

        fun checkModels(models: Set<String>): Set<String> {
            if (models.isEmpty()) throw K8sSpecException("allowedModels must not be empty")
            for (model in models) {
                if (!DockerPatterns.MODEL.matches(model)) throw K8sSpecException("allowedModels contains an invalid model")
            }
            return models.toSet()
        }
    }
}

/**
 * What one Run (attempt) needs as a Kubernetes Job (ADR 0010 追補 7 §4). It mirrors [net.kigawa.kivra.agent.docker.DockerRunSpec] and
 * has the same validation. There is no field for free text, and no work directory: the work volume is an emptyDir inside the Pod, so
 * the host path never appears (PR 4 decides how the work directory is filled).
 *
 * [memory] and [cpus] use the docker form (`6g`, `4`) so that both backends share one number; [K8sQuantities] converts it. [timeout]
 * is rounded up to whole seconds for `activeDeadlineSeconds`. [toString] shows the ids only.
 */
class K8sJobSpec(
    val runId: String,
    val attemptId: String,
    val namespace: String,
    val image: String,
    /** The egress proxy, `http://host:port`. It is the Service of the proxy, as in the docker side. */
    val proxyUrl: String,
    /** The run's time limit, which becomes `activeDeadlineSeconds`. */
    val timeout: Duration,
    val memory: String = DockerConstants.DEFAULT_MEMORY,
    val cpus: String = DockerConstants.DEFAULT_CPUS,
    val maxTurns: Int = CliCommand.DEFAULT_MAX_TURNS,
    val model: String = CliCommand.DEFAULT_MODEL,
    val budgetUsd: String = CliCommand.DEFAULT_BUDGET_USD,
) {
    /** The Job name, derived from [runId] and [attemptId] (see [K8sNames.jobName]). */
    val jobName: String = K8sNames.jobName(runId, attemptId)

    /** The timeout in whole seconds, rounded up. */
    val timeoutSeconds: Long = (timeout.toNanos() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND

    init {
        requireLabelId("runId", runId)
        requireLabelId("attemptId", attemptId)
        if (!K8sNames.isDns1123Label(namespace)) throw K8sSpecException("namespace is invalid")
        if (image.length > 255 || !DockerPatterns.IMAGE.matches(image)) throw K8sSpecException("image is invalid")
        if (!DockerPatterns.PROXY_URL.matches(proxyUrl)) throw K8sSpecException("proxyUrl must be http://host:port")
        if (timeout < MIN_TIMEOUT || timeout > MAX_TIMEOUT) throw K8sSpecException("timeout is out of range")
        if (!DockerPatterns.MEMORY.matches(memory)) throw K8sSpecException("memory is invalid")
        if (K8sQuantities.dockerMemoryToMi(memory) > K8sConstants.MAX_MEMORY_MI) throw K8sSpecException("memory is out of range")
        if (!DockerPatterns.CPUS.matches(cpus)) throw K8sSpecException("cpus is invalid")
        if (BigDecimal(cpus) > BigDecimal.valueOf(K8sConstants.MAX_CPU.toLong())) throw K8sSpecException("cpus is out of range")
        if (maxTurns !in 1..CliCommand.MAX_MAX_TURNS) throw K8sSpecException("maxTurns is out of range")
        if (!DockerPatterns.MODEL.matches(model)) throw K8sSpecException("model is invalid")
        if (!DockerPatterns.isBudgetInRange(budgetUsd)) throw K8sSpecException("budgetUsd is out of range")
    }

    override fun toString(): String = "K8sJobSpec(runId=$runId, attemptId=$attemptId)"

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        val MIN_TIMEOUT: Duration = Duration.ofSeconds(1)
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(K8sConstants.MAX_ACTIVE_DEADLINE_SECONDS)

        fun requireLabelId(field: String, value: String) {
            if (!K8sNames.isLabelId(value)) throw K8sSpecException("$field is invalid")
        }
    }
}

/** Names and label values. Job names must be DNS-1123 labels; the label values are the run and attempt ids. */
internal object K8sNames {
    private val DNS_LABEL = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
    private const val JOB_NAME_PREFIX = "kivra-run-"
    private const val HEAD_CHARS = 30
    private const val HASH_BYTES = 8

    fun isDns1123Label(value: String): Boolean = DNS_LABEL.matches(value)

    /** A run or attempt id as a label value: the docker id pattern, at most 63 characters, not ending with `-`. */
    fun isLabelId(value: String): Boolean = value.length <= 63 && DockerPatterns.ID.matches(value) && !value.endsWith('-')

    /**
     * `kivra-run-<lower(runId), at most 30 chars>-<16 hex of SHA-256(runId, attemptId)>`. The hash keeps the name unique for any pair
     * of ids (the ULID is upper case, and an attempt id can be long), and the name is at most 57 characters. It is deterministic.
     */
    fun jobName(runId: String, attemptId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$runId\u0000$attemptId".toByteArray(Charsets.UTF_8))
        val hash = digest.take(HASH_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return JOB_NAME_PREFIX + runId.lowercase().take(HEAD_CHARS) + "-" + hash
    }
}

/** Kubernetes quantities for the values that come from the docker form. */
internal object K8sQuantities {
    private val MEMORY_MI = Regex("([1-9][0-9]{0,4})(Mi|Gi)")
    private val CPU = Regex("[0-9]{1,2}(\\.[0-9]{1,2})?")

    /** `6g` becomes 6144 (Mi), `512m` becomes 512. The docker form is validated by [DockerPatterns.MEMORY] before this call. */
    fun dockerMemoryToMi(value: String): Long {
        val n = value.dropLast(1).toLong()
        return if (value.last() == 'g') n * 1024 else n
    }

    /** A Mi amount as a quantity: a whole number of Gi is written as Gi (`6144` becomes `6Gi`). */
    fun formatMi(mi: Long): String = if (mi % 1024 == 0L) "${mi / 1024}Gi" else "${mi}Mi"

    /** The Mi amount of a memory quantity (`6Gi` or `512Mi`), or null when the form is not one of these. */
    fun parseMi(quantity: String): Long? {
        val m = MEMORY_MI.matchEntire(quantity) ?: return null
        val n = m.groupValues[1].toLong()
        return if (m.groupValues[2] == "Gi") n * 1024 else n
    }

    /** The value of a cpu quantity (`4`, `1.5`), or null when it is not a positive number in this form. */
    fun parseCpu(quantity: String): BigDecimal? =
        if (CPU.matches(quantity)) BigDecimal(quantity).takeIf { it.signum() > 0 } else null
}
