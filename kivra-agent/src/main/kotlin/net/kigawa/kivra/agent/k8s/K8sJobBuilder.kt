package net.kigawa.kivra.agent.k8s

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerConstants
import net.kigawa.kivra.agent.docker.ProxyEnv

/**
 * Builds the Job manifest of one Run (ADR 0010 追補 7 §4, the docker `DockerCommandBuilder` of the k8s side). The result is a JSON tree
 * ([JsonObject]); no client is involved, so nothing is sent to the cluster here (PR 4).
 *
 * The output has the same shape in every run: one Job with `backoffLimit: 0`, one Pod with one container, three emptyDir volumes, and the
 * fixed argv and env names of the docker side. The output is checked by [K8sJobGuard] before it is used. The task text is not an input:
 * [K8sJobSpec] has no field for it, and the container gets only [CliCommand.argv] as its command.
 *
 * The command is the in-Pod entrypoint ([K8sConstants.POD_ENTRYPOINT]) followed by [CliCommand.argv]. The entrypoint fills the work
 * directory, runs the argv, collects the diff and writes the result file (PR 4, `k8s.pod.PodEntrypoint`).
 *
 * The init container is not built (the entrypoint does its work), and no `args`, `envFrom`, `hostPath` or service account
 * is written. [K8sJobGuard] refuses each of them.
 */
class K8sJobBuilder(private val settings: K8sSettings) {

    fun build(spec: K8sJobSpec): JsonObject {
        if (spec.namespace != settings.namespace) throw K8sSpecException("namespace is not allowed")
        if (spec.image !in settings.allowedImages) throw K8sSpecException("image is not allowed")
        if (spec.model !in settings.allowedModels) throw K8sSpecException("model is not allowed")
        val labels = labels(spec)
        val limitMi = K8sQuantities.dockerMemoryToMi(spec.memory)
        val requestMi = minOf(limitMi, K8sConstants.REQUEST_MEMORY_MI)
        val requestCpu = if (BigDecimal(spec.cpus) <= BigDecimal(K8sConstants.REQUEST_CPU)) spec.cpus else K8sConstants.REQUEST_CPU
        val command = CliCommand.argv(spec.maxTurns, spec.model, spec.budgetUsd)
        val env = envEntries(spec.proxyUrl)

        return buildJsonObject {
            put("apiVersion", K8sConstants.API_VERSION)
            put("kind", K8sConstants.KIND)
            putJsonObject("metadata") {
                put("name", spec.jobName)
                put("namespace", spec.namespace)
                putJsonObject("labels") { labels.forEach { (k, v) -> put(k, v) } }
            }
            putJsonObject("spec") {
                put("backoffLimit", 0)
                put("activeDeadlineSeconds", spec.timeoutSeconds)
                put("ttlSecondsAfterFinished", K8sConstants.TTL_SECONDS_AFTER_FINISHED)
                putJsonObject("template") {
                    putJsonObject("metadata") {
                        putJsonObject("labels") { labels.forEach { (k, v) -> put(k, v) } }
                    }
                    putJsonObject("spec") {
                        put("restartPolicy", "Never")
                        put("automountServiceAccountToken", false)
                        put("hostNetwork", false)
                        put("hostPID", false)
                        put("hostIPC", false)
                        put("terminationGracePeriodSeconds", K8sConstants.TERMINATION_GRACE_SECONDS)
                        putJsonObject("nodeSelector") { put(K8sConstants.NODE_SELECTOR_KEY, K8sConstants.NODE_SELECTOR_VALUE) }
                        putJsonObject("securityContext") {
                            put("runAsNonRoot", true)
                            put("runAsUser", DockerConstants.AGENT_UID)
                            put("runAsGroup", DockerConstants.AGENT_GID)
                            put("fsGroup", DockerConstants.AGENT_GID)
                            putJsonObject("seccompProfile") { put("type", K8sConstants.SECCOMP_TYPE) }
                        }
                        putJsonArray("containers") {
                            addJsonObject {
                                put("name", K8sConstants.CONTAINER_NAME)
                                put("image", spec.image)
                                put("imagePullPolicy", K8sConstants.IMAGE_PULL_POLICY)
                                put("workingDir", K8sConstants.WORKDIR)
                                putJsonArray("command") {
                                    add(JsonPrimitive(K8sConstants.POD_ENTRYPOINT))
                                    command.forEach { add(JsonPrimitive(it)) }
                                }
                                putJsonArray("env") {
                                    env.forEach { (name, value) ->
                                        addJsonObject {
                                            put("name", name)
                                            put("value", value)
                                        }
                                    }
                                }
                                putJsonObject("securityContext") {
                                    put("privileged", false)
                                    put("allowPrivilegeEscalation", false)
                                    put("readOnlyRootFilesystem", true)
                                    putJsonObject("capabilities") {
                                        putJsonArray("drop") { add(JsonPrimitive("ALL")) }
                                    }
                                }
                                putJsonObject("resources") {
                                    putJsonObject("limits") {
                                        put("memory", K8sQuantities.formatMi(limitMi))
                                        put("cpu", spec.cpus)
                                    }
                                    putJsonObject("requests") {
                                        put("memory", K8sQuantities.formatMi(requestMi))
                                        put("cpu", requestCpu)
                                    }
                                }
                                putJsonArray("volumeMounts") {
                                    addJsonObject {
                                        put("name", K8sConstants.VOLUME_WORK)
                                        put("mountPath", K8sConstants.WORKDIR)
                                    }
                                    addJsonObject {
                                        put("name", K8sConstants.VOLUME_TMP)
                                        put("mountPath", K8sConstants.PATH_TMP)
                                    }
                                    addJsonObject {
                                        put("name", K8sConstants.VOLUME_HOME)
                                        put("mountPath", K8sConstants.PATH_HOME)
                                    }
                                }
                            }
                        }
                        putJsonArray("volumes") {
                            emptyDirVolume(K8sConstants.VOLUME_WORK, K8sConstants.SIZE_WORK)
                            emptyDirVolume(K8sConstants.VOLUME_TMP, K8sConstants.SIZE_TMP)
                            emptyDirVolume(K8sConstants.VOLUME_HOME, K8sConstants.SIZE_HOME)
                        }
                    }
                }
            }
        }
    }

    /** The labels of the Job and of its Pod template. The same map is written to both, so the selectors agree. */
    private fun labels(spec: K8sJobSpec): Map<String, String> = linkedMapOf(
        K8sConstants.LABEL_AGENT_KEY to K8sConstants.LABEL_AGENT_VALUE,
        K8sConstants.LABEL_OWNER_KEY to settings.ownerLabel,
        K8sConstants.LABEL_RUN_KEY to spec.runId,
        K8sConstants.LABEL_ATTEMPT_KEY to spec.attemptId,
    )

    /**
     * The env entries in the docker order, with the same values (see `DockerCommandBuilder`). The proxy is the only input, and the
     * values are built by the same helper as the docker side ([ProxyEnv]).
     */
    private fun envEntries(proxy: String): List<Pair<String, String>> = listOf(
        "ANTHROPIC_BASE_URL" to "$proxy${DockerConstants.PROXY_PATH}",
        "ANTHROPIC_API_KEY" to DockerConstants.DUMMY_API_KEY,
        "HTTPS_PROXY" to proxy,
        "HTTP_PROXY" to proxy,
        "NO_PROXY" to DockerConstants.NO_PROXY,
        "JAVA_TOOL_OPTIONS" to ProxyEnv.k8sJavaToolOptions(proxy),
    )

    private fun JsonArrayBuilder.emptyDirVolume(name: String, sizeLimit: String) {
        addJsonObject {
            put("name", name)
            putJsonObject("emptyDir") { put("sizeLimit", sizeLimit) }
        }
    }
}
