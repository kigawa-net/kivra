package net.kigawa.kivra.agent.k8s

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerConstants
import net.kigawa.kivra.agent.docker.DockerPatterns
import net.kigawa.kivra.agent.docker.DockerSpecException
import net.kigawa.kivra.agent.docker.ProxyEnv

/** Why a Job manifest was refused. The rule is the only detail: no value, name or secret is ever in the message. */
enum class K8sGuardRule {
    MALFORMED,
    /** A key that is not in the allowlist of its object. */
    UNKNOWN_KEY,
    /** A key of the allowlist is missing. */
    MISSING_FIELD,
    KIND_NOT_ALLOWED,
    JOB_NAME_INVALID,
    NAMESPACE_NOT_ALLOWED,
    LABEL_NOT_ALLOWED,
    BACKOFF_NOT_ZERO,
    DEADLINE_NOT_ALLOWED,
    TTL_NOT_ALLOWED,
    RESTART_POLICY_NOT_ALLOWED,
    /** The service account token is mounted, or a service account is named. */
    SERVICE_ACCOUNT_NOT_ALLOWED,
    HOST_NAMESPACE_NOT_ALLOWED,
    /** A node selector other than the fixed one (the Pod must run where the NetworkPolicy is enforced). */
    NODE_SELECTOR_NOT_ALLOWED,
    SYSCTLS_NOT_ALLOWED,
    HOST_ALIASES_NOT_ALLOWED,
    DNS_CONFIG_NOT_ALLOWED,
    /** An init container or an ephemeral container. PR 4 decides whether one is needed. */
    INIT_CONTAINER_NOT_ALLOWED,
    CONTAINER_COUNT_NOT_ONE,
    IMAGE_NOT_ALLOWED,
    IMAGE_PULL_POLICY_NOT_ALLOWED,
    WORKING_DIR_NOT_ALLOWED,
    /** The command or the args differ from [CliCommand.argv] in a way that is not the task text. */
    COMMAND_NOT_ALLOWED,
    /** The prompt position holds something other than the fixed prompt, which is how task text would enter the command. */
    COMMAND_TASK_TEXT,
    ENV_FROM_NOT_ALLOWED,
    /** An environment name outside the allowlist, a duplicate, a `valueFrom`, or a value that is not in its fixed form. */
    ENV_NOT_ALLOWED,
    PRIVILEGED_NOT_ALLOWED,
    CAPABILITY_ADD_NOT_ALLOWED,
    CAPABILITY_NOT_ALLOWED,
    PRIVILEGE_ESCALATION_NOT_ALLOWED,
    READ_ONLY_ROOT_NOT_ALLOWED,
    /** A user other than 10001, or `runAsNonRoot` not true. */
    RUN_AS_ROOT_NOT_ALLOWED,
    SECCOMP_NOT_ALLOWED,
    /** A volume that is not one of the three emptyDir volumes (hostPath, secret, configMap, claim, projected, medium Memory, ...). */
    VOLUME_NOT_ALLOWED,
    VOLUME_MOUNT_NOT_ALLOWED,
    /** A limit that is out of the allowed range, or a wrong form. */
    RESOURCE_NOT_ALLOWED,
    REQUEST_EXCEEDS_LIMIT,
    /** A value with no more specific rule. */
    FORBIDDEN_VALUE,
}

class K8sJobRejectedException(val rule: K8sGuardRule) : IllegalArgumentException("kubernetes job rejected: ${rule.name}")

/**
 * The last check before a Job is created (ADR 0010 追補 7 §4, the k8s counterpart of [net.kigawa.kivra.agent.docker.DockerArgsGuard]).
 * It works on the whole manifest tree, so an item is refused even when the builder did not write it. The guard is an allowlist: every
 * object has the exact set of keys it may have, and every value is compared with the fixed value or the fixed form. An unknown key, a
 * missing key, or a value outside its form is refused. Each forbidden item has its own [K8sGuardRule].
 *
 * Rules, in the order the tree is walked (fail-closed; the first failure throws):
 * 1. Root: `batch/v1` `Job`. Metadata: the namespace is the settings', the labels are the three fixed keys, and the name is the one
 *    derived from the run and attempt labels, in DNS-1123 form.
 * 2. Job spec: `backoffLimit: 0`, `activeDeadlineSeconds` in 1..24h, the fixed TTL, and the template labels equal the Job labels.
 * 3. Pod: `restartPolicy: Never`, the fixed `nodeSelector` (`canal-cni=enabled`), no service account (`automountServiceAccountToken: false`, no `serviceAccountName`), no host
 *    namespace, no `hostAliases`, `dnsConfig`, `sysctls`, `initContainers`. The Pod security context runs as 10001 and is RuntimeDefault.
 * 4. Container: exactly one, the fixed name, the allowed image, the fixed pull policy and working directory, the fixed argv (the
 *    `--max-turns`, `--model` and `--max-budget-usd` values checked by the same rules as the docker side), the fixed env names and
 *    values (the same allowlist as the docker side, with no `envFrom` and no `valueFrom`), the container security context (not
 *    privileged, no escalation, read-only root, `drop: [ALL]`, no `add`), the resource limits and requests within bounds, and exactly the
 *    three mounts.
 * 5. Volumes: exactly three emptyDir volumes, `work`, `tmp` and `home`, with their size limits and no other key (no hostPath).
 *
 * Not in this PR: the init container (refused), the work directory preparation and the diff (PR 4), the NetworkPolicy (PR 5, a cluster
 * setting, not a field of the Job), the pids limit (the kubelet's `podPidsLimit`, a cluster setting), and the PodSecurity `restricted`
 * label of the namespace (a cluster setting).
 */
class K8sJobGuard(private val settings: K8sSettings) {

    fun check(manifest: JsonObject) {
        manifest.exactKeys(ROOT_KEYS)
        if (manifest.text("apiVersion", K8sGuardRule.KIND_NOT_ALLOWED) != K8sConstants.API_VERSION) fail(K8sGuardRule.KIND_NOT_ALLOWED)
        if (manifest.text("kind", K8sGuardRule.KIND_NOT_ALLOWED) != K8sConstants.KIND) fail(K8sGuardRule.KIND_NOT_ALLOWED)
        val labels = checkMetadata(manifest.obj("metadata"))
        checkJobSpec(manifest.obj("spec"), labels)
    }

    /** Returns the Job labels, which the template labels must equal. */
    private fun checkMetadata(metadata: JsonObject): JsonObject {
        metadata.exactKeys(setOf("name", "namespace", "labels"))
        val labels = metadata.obj("labels")
        labels.exactKeys(LABEL_KEYS, unknown = K8sGuardRule.LABEL_NOT_ALLOWED)
        if (labels.text(K8sConstants.LABEL_AGENT_KEY, K8sGuardRule.LABEL_NOT_ALLOWED) != K8sConstants.LABEL_AGENT_VALUE) {
            fail(K8sGuardRule.LABEL_NOT_ALLOWED)
        }
        if (labels.text(K8sConstants.LABEL_OWNER_KEY, K8sGuardRule.LABEL_NOT_ALLOWED) != settings.ownerLabel) {
            fail(K8sGuardRule.LABEL_NOT_ALLOWED)
        }
        val runId = labels.text(K8sConstants.LABEL_RUN_KEY, K8sGuardRule.LABEL_NOT_ALLOWED)
        val attemptId = labels.text(K8sConstants.LABEL_ATTEMPT_KEY, K8sGuardRule.LABEL_NOT_ALLOWED)
        if (!K8sNames.isLabelId(runId) || !K8sNames.isLabelId(attemptId)) fail(K8sGuardRule.LABEL_NOT_ALLOWED)
        val name = metadata.text("name", K8sGuardRule.JOB_NAME_INVALID)
        if (!K8sNames.isDns1123Label(name) || name != K8sNames.jobName(runId, attemptId)) fail(K8sGuardRule.JOB_NAME_INVALID)
        if (metadata.text("namespace", K8sGuardRule.NAMESPACE_NOT_ALLOWED) != settings.namespace) {
            fail(K8sGuardRule.NAMESPACE_NOT_ALLOWED)
        }
        return labels
    }

    private fun checkJobSpec(spec: JsonObject, labels: JsonObject) {
        spec.exactKeys(setOf("backoffLimit", "activeDeadlineSeconds", "ttlSecondsAfterFinished", "template"))
        if (spec.integer("backoffLimit", K8sGuardRule.BACKOFF_NOT_ZERO) != 0L) fail(K8sGuardRule.BACKOFF_NOT_ZERO)
        val deadline = spec.integer("activeDeadlineSeconds", K8sGuardRule.DEADLINE_NOT_ALLOWED)
        if (deadline !in 1L..K8sConstants.MAX_ACTIVE_DEADLINE_SECONDS) fail(K8sGuardRule.DEADLINE_NOT_ALLOWED)
        if (spec.integer("ttlSecondsAfterFinished", K8sGuardRule.TTL_NOT_ALLOWED) != K8sConstants.TTL_SECONDS_AFTER_FINISHED.toLong()) {
            fail(K8sGuardRule.TTL_NOT_ALLOWED)
        }
        val template = spec.obj("template")
        template.exactKeys(setOf("metadata", "spec"))
        val templateMetadata = template.obj("metadata")
        templateMetadata.exactKeys(setOf("labels"))
        if (templateMetadata.obj("labels") != labels) fail(K8sGuardRule.LABEL_NOT_ALLOWED)
        checkPodSpec(template.obj("spec"))
    }

    private fun checkPodSpec(pod: JsonObject) {
        pod.exactKeys(POD_KEYS, POD_FORBIDDEN)
        pod.expectText("restartPolicy", "Never", K8sGuardRule.RESTART_POLICY_NOT_ALLOWED)
        if (pod.bool("automountServiceAccountToken", K8sGuardRule.SERVICE_ACCOUNT_NOT_ALLOWED)) {
            fail(K8sGuardRule.SERVICE_ACCOUNT_NOT_ALLOWED)
        }
        for (key in listOf("hostNetwork", "hostPID", "hostIPC")) {
            if (pod.bool(key, K8sGuardRule.HOST_NAMESPACE_NOT_ALLOWED)) fail(K8sGuardRule.HOST_NAMESPACE_NOT_ALLOWED)
        }
        if (pod.integer("terminationGracePeriodSeconds", K8sGuardRule.FORBIDDEN_VALUE) != K8sConstants.TERMINATION_GRACE_SECONDS.toLong()) {
            fail(K8sGuardRule.FORBIDDEN_VALUE)
        }
        checkNodeSelector(pod.obj("nodeSelector"))
        checkPodSecurity(pod.obj("securityContext"))
        checkContainers(pod.arr("containers"))
        checkVolumes(pod.arr("volumes"))
    }

    /** Exactly the fixed selector: the Pod runs only on a node where the NetworkPolicy is enforced. */
    private fun checkNodeSelector(selector: JsonObject) {
        selector.exactKeys(setOf(K8sConstants.NODE_SELECTOR_KEY), unknown = K8sGuardRule.NODE_SELECTOR_NOT_ALLOWED)
        if (selector.text(K8sConstants.NODE_SELECTOR_KEY, K8sGuardRule.NODE_SELECTOR_NOT_ALLOWED) != K8sConstants.NODE_SELECTOR_VALUE) {
            fail(K8sGuardRule.NODE_SELECTOR_NOT_ALLOWED)
        }
    }

    private fun checkPodSecurity(sc: JsonObject) {
        sc.exactKeys(
            setOf("runAsNonRoot", "runAsUser", "runAsGroup", "fsGroup", "seccompProfile"),
            mapOf("sysctls" to K8sGuardRule.SYSCTLS_NOT_ALLOWED),
        )
        if (!sc.bool("runAsNonRoot", K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED)) fail(K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED)
        sc.expectInt("runAsUser", AGENT_UID, K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED)
        sc.expectInt("runAsGroup", AGENT_UID, K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED)
        sc.expectInt("fsGroup", AGENT_UID, K8sGuardRule.FORBIDDEN_VALUE)
        val seccomp = sc.obj("seccompProfile")
        seccomp.exactKeys(setOf("type"))
        seccomp.expectText("type", K8sConstants.SECCOMP_TYPE, K8sGuardRule.SECCOMP_NOT_ALLOWED)
    }

    private fun checkContainers(containers: List<JsonElement>) {
        if (containers.size != 1) fail(K8sGuardRule.CONTAINER_COUNT_NOT_ONE)
        val c = containers[0] as? JsonObject ?: fail(K8sGuardRule.MALFORMED)
        c.exactKeys(CONTAINER_KEYS, CONTAINER_FORBIDDEN)
        c.expectText("name", K8sConstants.CONTAINER_NAME, K8sGuardRule.FORBIDDEN_VALUE)
        if (c.text("image", K8sGuardRule.IMAGE_NOT_ALLOWED) !in settings.allowedImages) fail(K8sGuardRule.IMAGE_NOT_ALLOWED)
        c.expectText("imagePullPolicy", K8sConstants.IMAGE_PULL_POLICY, K8sGuardRule.IMAGE_PULL_POLICY_NOT_ALLOWED)
        c.expectText("workingDir", K8sConstants.WORKDIR, K8sGuardRule.WORKING_DIR_NOT_ALLOWED)
        checkCommand(c.arr("command"))
        checkEnv(c.arr("env"))
        checkContainerSecurity(c.obj("securityContext"))
        checkResources(c.obj("resources"))
        checkMounts(c.arr("volumeMounts"))
    }

    /**
     * The command must be [CliCommand.argv] with the three value positions checked by the docker rules. The prompt position may hold
     * only the fixed prompt. Every other token is compared with the fixed one, so no shell syntax can pass either.
     */
    private fun checkCommand(command: List<JsonElement>) {
        val all = command.map { it.stringOrFail(K8sGuardRule.COMMAND_NOT_ALLOWED) }
        // The first token is the in-Pod entrypoint (PR 4), which runs the rest; the rest is the CLI argv and is checked as before.
        if (all.firstOrNull() != K8sConstants.POD_ENTRYPOINT) fail(K8sGuardRule.COMMAND_NOT_ALLOWED)
        val tokens = all.drop(1)
        val template = CliCommand.argv(CliCommand.DEFAULT_MAX_TURNS)
        if (tokens.size != template.size) fail(K8sGuardRule.COMMAND_NOT_ALLOWED)
        for ((i, token) in tokens.withIndex()) {
            when (i) {
                CliCommand.PROMPT_INDEX -> if (token != template[i]) fail(K8sGuardRule.COMMAND_TASK_TEXT)
                CliCommand.MAX_TURNS_INDEX -> {
                    val turns = token.toIntOrNull()
                    if (turns == null || token != turns.toString() || turns !in 1..CliCommand.MAX_MAX_TURNS) {
                        fail(K8sGuardRule.COMMAND_NOT_ALLOWED)
                    }
                }
                CliCommand.MODEL_INDEX -> if (token !in settings.allowedModels || !DockerPatterns.MODEL.matches(token)) {
                    fail(K8sGuardRule.COMMAND_NOT_ALLOWED)
                }
                CliCommand.BUDGET_INDEX -> if (!DockerPatterns.isBudgetInRange(token)) fail(K8sGuardRule.COMMAND_NOT_ALLOWED)
                else -> if (token != template[i]) fail(K8sGuardRule.COMMAND_NOT_ALLOWED)
            }
        }
    }

    /**
     * Each entry has a name from the docker allowlist, and no entry may repeat a name. Then the set of names must be the whole
     * allowlist, and each value must be in its fixed form, with the same proxy checks as the docker side.
     */
    private fun checkEnv(env: List<JsonElement>) {
        val values = LinkedHashMap<String, String>()
        for (element in env) {
            val entry = element as? JsonObject ?: fail(K8sGuardRule.ENV_NOT_ALLOWED)
            entry.exactKeys(setOf("name", "value"), mapOf("valueFrom" to K8sGuardRule.ENV_NOT_ALLOWED), K8sGuardRule.ENV_NOT_ALLOWED)
            val name = entry.text("name", K8sGuardRule.ENV_NOT_ALLOWED)
            if (name !in DockerConstants.ENV_NAMES) fail(K8sGuardRule.ENV_NOT_ALLOWED)
            val value = entry.text("value", K8sGuardRule.ENV_NOT_ALLOWED)
            if (values.put(name, value) != null) fail(K8sGuardRule.ENV_NOT_ALLOWED)
        }
        if (values.keys != DockerConstants.ENV_NAMES) fail(K8sGuardRule.MISSING_FIELD)
        checkEnvValues(values)
    }

    private fun checkEnvValues(values: Map<String, String>) {
        // A value with a control character or the Anthropic key prefix is refused without its text in the message.
        for (value in values.values) {
            if (value.any { it.isISOControl() } || value.contains("sk-ant-", ignoreCase = true)) fail(K8sGuardRule.ENV_NOT_ALLOWED)
        }
        if (values["ANTHROPIC_API_KEY"] != DockerConstants.DUMMY_API_KEY) fail(K8sGuardRule.ENV_NOT_ALLOWED)
        val proxy = values.getValue("HTTPS_PROXY")
        if (!DockerPatterns.PROXY_URL.matches(proxy) || values["HTTP_PROXY"] != proxy) fail(K8sGuardRule.ENV_NOT_ALLOWED)
        if (values["ANTHROPIC_BASE_URL"] != proxy + DockerConstants.PROXY_PATH) fail(K8sGuardRule.ENV_NOT_ALLOWED)
        if (values["NO_PROXY"] != DockerConstants.NO_PROXY) fail(K8sGuardRule.ENV_NOT_ALLOWED)
        val javaOptions = try {
            ProxyEnv.javaToolOptions(proxy)
        } catch (e: DockerSpecException) {
            fail(K8sGuardRule.ENV_NOT_ALLOWED)
        }
        if (values["JAVA_TOOL_OPTIONS"] != javaOptions) fail(K8sGuardRule.ENV_NOT_ALLOWED)
    }

    private fun checkContainerSecurity(sc: JsonObject) {
        sc.exactKeys(
            setOf("privileged", "allowPrivilegeEscalation", "readOnlyRootFilesystem", "capabilities"),
            mapOf(
                "runAsUser" to K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED,
                "runAsGroup" to K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED,
                "runAsNonRoot" to K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED,
                "seccompProfile" to K8sGuardRule.SECCOMP_NOT_ALLOWED,
            ),
        )
        if (sc.bool("privileged", K8sGuardRule.PRIVILEGED_NOT_ALLOWED)) fail(K8sGuardRule.PRIVILEGED_NOT_ALLOWED)
        if (sc.bool("allowPrivilegeEscalation", K8sGuardRule.PRIVILEGE_ESCALATION_NOT_ALLOWED)) {
            fail(K8sGuardRule.PRIVILEGE_ESCALATION_NOT_ALLOWED)
        }
        if (!sc.bool("readOnlyRootFilesystem", K8sGuardRule.READ_ONLY_ROOT_NOT_ALLOWED)) fail(K8sGuardRule.READ_ONLY_ROOT_NOT_ALLOWED)
        val capabilities = sc.obj("capabilities")
        capabilities.exactKeys(setOf("drop"), mapOf("add" to K8sGuardRule.CAPABILITY_ADD_NOT_ALLOWED))
        if (capabilities.arr("drop").map { it.stringOrFail(K8sGuardRule.CAPABILITY_NOT_ALLOWED) } != listOf("ALL")) {
            fail(K8sGuardRule.CAPABILITY_NOT_ALLOWED)
        }
    }

    private fun checkResources(resources: JsonObject) {
        resources.exactKeys(setOf("limits", "requests"))
        val limits = resources.obj("limits")
        limits.exactKeys(setOf("memory", "cpu"))
        val limitMi = K8sQuantities.parseMi(limits.text("memory", K8sGuardRule.RESOURCE_NOT_ALLOWED))
            ?: fail(K8sGuardRule.RESOURCE_NOT_ALLOWED)
        if (limitMi !in 1L..K8sConstants.MAX_MEMORY_MI) fail(K8sGuardRule.RESOURCE_NOT_ALLOWED)
        val limitCpu = K8sQuantities.parseCpu(limits.text("cpu", K8sGuardRule.RESOURCE_NOT_ALLOWED))
            ?: fail(K8sGuardRule.RESOURCE_NOT_ALLOWED)
        if (limitCpu > BigDecimal.valueOf(K8sConstants.MAX_CPU.toLong())) fail(K8sGuardRule.RESOURCE_NOT_ALLOWED)

        val requests = resources.obj("requests")
        requests.exactKeys(setOf("memory", "cpu"))
        val requestMi = K8sQuantities.parseMi(requests.text("memory", K8sGuardRule.REQUEST_EXCEEDS_LIMIT))
            ?: fail(K8sGuardRule.REQUEST_EXCEEDS_LIMIT)
        if (requestMi > limitMi) fail(K8sGuardRule.REQUEST_EXCEEDS_LIMIT)
        val requestCpu = K8sQuantities.parseCpu(requests.text("cpu", K8sGuardRule.REQUEST_EXCEEDS_LIMIT))
            ?: fail(K8sGuardRule.REQUEST_EXCEEDS_LIMIT)
        if (requestCpu > limitCpu) fail(K8sGuardRule.REQUEST_EXCEEDS_LIMIT)
    }

    /** Exactly the three mounts, in order. A mount has only a name and a path: no subPath, no readOnly, no propagation. */
    private fun checkMounts(mounts: List<JsonElement>) {
        if (mounts.size != MOUNTS.size) fail(K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED)
        for ((i, element) in mounts.withIndex()) {
            val mount = element as? JsonObject ?: fail(K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED)
            mount.exactKeys(
                setOf("name", "mountPath"),
                mapOf(
                    "subPath" to K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED,
                    "subPathExpr" to K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED,
                    "readOnly" to K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED,
                    "mountPropagation" to K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED,
                ),
                K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED,
            )
            val (name, path) = MOUNTS[i]
            if (mount.text("name", K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED) != name) fail(K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED)
            if (mount.text("mountPath", K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED) != path) fail(K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED)
        }
    }

    /**
     * Exactly the three emptyDir volumes, in order, with their size limits. A volume may have only `name` and `emptyDir`, and an emptyDir
     * only `sizeLimit`, so a hostPath, a secret, a medium of Memory, or any other source is refused.
     */
    private fun checkVolumes(volumes: List<JsonElement>) {
        if (volumes.size != VOLUMES.size) fail(K8sGuardRule.VOLUME_NOT_ALLOWED)
        for ((i, element) in volumes.withIndex()) {
            val volume = element as? JsonObject ?: fail(K8sGuardRule.VOLUME_NOT_ALLOWED)
            if (volume.keys != setOf("name", "emptyDir")) fail(K8sGuardRule.VOLUME_NOT_ALLOWED)
            val (name, sizeLimit) = VOLUMES[i]
            if (volume.text("name", K8sGuardRule.VOLUME_NOT_ALLOWED) != name) fail(K8sGuardRule.VOLUME_NOT_ALLOWED)
            val emptyDir = volume.obj("emptyDir", K8sGuardRule.VOLUME_NOT_ALLOWED)
            if (emptyDir.keys != setOf("sizeLimit")) fail(K8sGuardRule.VOLUME_NOT_ALLOWED)
            if (emptyDir.text("sizeLimit", K8sGuardRule.VOLUME_NOT_ALLOWED) != sizeLimit) fail(K8sGuardRule.VOLUME_NOT_ALLOWED)
        }
    }

    private companion object {
        const val AGENT_UID = DockerConstants.AGENT_UID.toLong()

        val ROOT_KEYS = setOf("apiVersion", "kind", "metadata", "spec")
        val LABEL_KEYS = setOf(
            K8sConstants.LABEL_AGENT_KEY, K8sConstants.LABEL_OWNER_KEY, K8sConstants.LABEL_RUN_KEY, K8sConstants.LABEL_ATTEMPT_KEY,
        )
        val POD_KEYS = setOf(
            "restartPolicy", "automountServiceAccountToken", "hostNetwork", "hostPID", "hostIPC",
            "terminationGracePeriodSeconds", "nodeSelector", "securityContext", "containers", "volumes",
        )
        val POD_FORBIDDEN = mapOf(
            "serviceAccountName" to K8sGuardRule.SERVICE_ACCOUNT_NOT_ALLOWED,
            "serviceAccount" to K8sGuardRule.SERVICE_ACCOUNT_NOT_ALLOWED,
            "initContainers" to K8sGuardRule.INIT_CONTAINER_NOT_ALLOWED,
            "ephemeralContainers" to K8sGuardRule.INIT_CONTAINER_NOT_ALLOWED,
            "hostAliases" to K8sGuardRule.HOST_ALIASES_NOT_ALLOWED,
            "dnsConfig" to K8sGuardRule.DNS_CONFIG_NOT_ALLOWED,
            "dnsPolicy" to K8sGuardRule.DNS_CONFIG_NOT_ALLOWED,
        )
        val CONTAINER_KEYS = setOf(
            "name", "image", "imagePullPolicy", "workingDir", "command", "env", "securityContext", "resources", "volumeMounts",
        )
        val CONTAINER_FORBIDDEN = mapOf(
            "envFrom" to K8sGuardRule.ENV_FROM_NOT_ALLOWED,
            "args" to K8sGuardRule.COMMAND_NOT_ALLOWED,
            "volumeDevices" to K8sGuardRule.VOLUME_NOT_ALLOWED,
        )

        /** The mounts in the order of the manifest: volume name and mount path. */
        val MOUNTS = listOf(
            K8sConstants.VOLUME_WORK to K8sConstants.WORKDIR,
            K8sConstants.VOLUME_TMP to K8sConstants.PATH_TMP,
            K8sConstants.VOLUME_HOME to K8sConstants.PATH_HOME,
        )

        /** The volumes in the order of the manifest: name and size limit. */
        val VOLUMES = listOf(
            K8sConstants.VOLUME_WORK to K8sConstants.SIZE_WORK,
            K8sConstants.VOLUME_TMP to K8sConstants.SIZE_TMP,
            K8sConstants.VOLUME_HOME to K8sConstants.SIZE_HOME,
        )

        fun fail(rule: K8sGuardRule): Nothing = throw K8sJobRejectedException(rule)

        /** The keys must be the expected ones. A forbidden key has its own rule, an unknown one has [unknown], a missing one is refused. */
        fun JsonObject.exactKeys(
            expected: Set<String>,
            forbidden: Map<String, K8sGuardRule> = emptyMap(),
            unknown: K8sGuardRule = K8sGuardRule.UNKNOWN_KEY,
        ) {
            for (key in keys) forbidden[key]?.let { fail(it) }
            for (key in keys) if (key !in expected) fail(unknown)
            for (key in expected) if (key !in keys) fail(K8sGuardRule.MISSING_FIELD)
        }

        fun JsonObject.field(key: String): JsonElement = this[key] ?: fail(K8sGuardRule.MISSING_FIELD)

        fun JsonObject.obj(key: String, rule: K8sGuardRule = K8sGuardRule.MALFORMED): JsonObject =
            field(key) as? JsonObject ?: fail(rule)

        fun JsonObject.arr(key: String, rule: K8sGuardRule = K8sGuardRule.MALFORMED): List<JsonElement> =
            (field(key) as? JsonArray ?: fail(rule)).toList()

        fun JsonObject.text(key: String, rule: K8sGuardRule): String =
            (field(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(rule)

        fun JsonObject.bool(key: String, rule: K8sGuardRule): Boolean =
            (field(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail(rule)

        fun JsonObject.integer(key: String, rule: K8sGuardRule): Long =
            (field(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: fail(rule)

        fun JsonObject.expectText(key: String, expected: String, rule: K8sGuardRule) {
            if (text(key, rule) != expected) fail(rule)
        }

        fun JsonObject.expectInt(key: String, expected: Long, rule: K8sGuardRule) {
            if (integer(key, rule) != expected) fail(rule)
        }

        fun JsonElement.stringOrFail(rule: K8sGuardRule): String =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(rule)
    }
}
