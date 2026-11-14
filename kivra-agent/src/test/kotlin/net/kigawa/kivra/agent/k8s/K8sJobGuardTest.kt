package net.kigawa.kivra.agent.k8s

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.agent.docker.CliCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The guard is the last check before a Job is created (ADR 0010 追補 7 §4). Each forbidden item is one row, and the row names the exact
 * rule it must produce. Every row changes one place of the standard manifest, and the row fails if the change is not made.
 */
class K8sJobGuardTest {
    private val golden = K8sFixture.golden()
    private val guard = K8sJobGuard(K8sFixture.settings)

    private fun JsonObject.pod(): JsonObject = (this["spec"] as JsonObject)["template"]!!.jsonObject["spec"]!!.jsonObject

    private fun JsonObject.container(): JsonObject = (pod()["containers"] as JsonArray)[0].jsonObject

    /** Sets a value in the one container of the Pod. */
    private fun JsonObject.setContainer(vararg rest: Any, value: JsonElement): JsonObject =
        set("spec", "template", "spec", "containers", 0, *rest, value = value)

    /** Sets a value in the Pod spec. */
    private fun JsonObject.setPod(vararg rest: Any, value: JsonElement): JsonObject =
        set("spec", "template", "spec", *rest, value = value)

    private fun array(vararg items: JsonElement): JsonArray = JsonArray(items.toList())

    private fun bool(value: Boolean): JsonPrimitive = JsonPrimitive(value)

    private fun int(value: Int): JsonPrimitive = JsonPrimitive(value)

    private val extraEmptyDir = obj("name" to str("extra"), "emptyDir" to obj("sizeLimit" to str("1Gi")))

    @Test
    fun goldenManifestPasses() {
        guard.check(golden)
    }

    @Test
    fun manifestReadBackFromItsTextPasses() {
        val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), golden)
        guard.check(Json.parseToJsonElement(text).jsonObject)
    }

    /** One row per forbidden item: the changed manifest, and the rule that must refuse it. */
    @Test
    fun forbiddenItemsAreRefusedWithTheirOwnRule() {
        val cases: List<Triple<String, K8sGuardRule, JsonObject>> = listOf(
            // Volumes: only the three emptyDir volumes.
            Triple(
                "hostPath volume", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "volumes", 1, value = obj("name" to str("tmp"), "hostPath" to obj("path" to str("/tmp")))),
            ),
            Triple(
                "secret volume", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "volumes", 1, value = obj("name" to str("tmp"), "secret" to obj("secretName" to str("x")))),
            ),
            Triple(
                "configMap volume", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "volumes", 1, value = obj("name" to str("tmp"), "configMap" to obj("name" to str("x")))),
            ),
            Triple(
                "claim volume", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set(
                    "spec", "template", "spec", "volumes", 1,
                    value = obj("name" to str("tmp"), "persistentVolumeClaim" to obj("claimName" to str("x"))),
                ),
            ),
            Triple(
                "projected volume", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "volumes", 1, value = obj("name" to str("tmp"), "projected" to obj("sources" to array()))),
            ),
            Triple(
                "emptyDir with medium Memory", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "volumes", 1, "emptyDir", value = obj("medium" to str("Memory"), "sizeLimit" to str("512Mi"))),
            ),
            Triple(
                "work volume without a size limit", K8sGuardRule.VOLUME_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "volumes", 0, "emptyDir", value = obj()),
            ),
            Triple("fourth volume", K8sGuardRule.VOLUME_NOT_ALLOWED, golden.append("spec", "template", "spec", "volumes", value = extraEmptyDir)),
            Triple("volume renamed", K8sGuardRule.VOLUME_NOT_ALLOWED, golden.set("spec", "template", "spec", "volumes", 0, "name", value = str("host"))),
            // Mounts: only the three paths.
            Triple("mount at /var/run", K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED, golden.setContainer("volumeMounts", 1, "mountPath", value = str("/var/run"))),
            Triple("mount with subPath", K8sGuardRule.VOLUME_MOUNT_NOT_ALLOWED, golden.setContainer("volumeMounts", 0, "subPath", value = str("x"))),
            // Container security context.
            Triple("privileged container", K8sGuardRule.PRIVILEGED_NOT_ALLOWED, golden.setContainer("securityContext", "privileged", value = bool(true))),
            Triple(
                "capabilities.add", K8sGuardRule.CAPABILITY_ADD_NOT_ALLOWED,
                golden.setContainer("securityContext", "capabilities", "add", value = array(str("NET_ADMIN"))),
            ),
            Triple(
                "capabilities.drop not ALL", K8sGuardRule.CAPABILITY_NOT_ALLOWED,
                golden.setContainer("securityContext", "capabilities", "drop", value = array(str("NET_RAW"))),
            ),
            Triple(
                "allowPrivilegeEscalation true", K8sGuardRule.PRIVILEGE_ESCALATION_NOT_ALLOWED,
                golden.setContainer("securityContext", "allowPrivilegeEscalation", value = bool(true)),
            ),
            Triple(
                "readOnlyRootFilesystem false", K8sGuardRule.READ_ONLY_ROOT_NOT_ALLOWED,
                golden.setContainer("securityContext", "readOnlyRootFilesystem", value = bool(false)),
            ),
            Triple("container runAsUser 0", K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED, golden.setContainer("securityContext", "runAsUser", value = int(0))),
            // Pod security context.
            Triple("runAsNonRoot false", K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED, golden.setPod("securityContext", "runAsNonRoot", value = bool(false))),
            Triple("runAsUser 0", K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED, golden.setPod("securityContext", "runAsUser", value = int(0))),
            Triple("runAsGroup 1000", K8sGuardRule.RUN_AS_ROOT_NOT_ALLOWED, golden.setPod("securityContext", "runAsGroup", value = int(1000))),
            Triple(
                "seccomp Unconfined", K8sGuardRule.SECCOMP_NOT_ALLOWED,
                golden.setPod("securityContext", "seccompProfile", "type", value = str("Unconfined")),
            ),
            Triple(
                "sysctls", K8sGuardRule.SYSCTLS_NOT_ALLOWED,
                golden.setPod("securityContext", "sysctls", value = array(obj("name" to str("net.core.somaxconn"), "value" to str("1")))),
            ),
            // Host namespaces and networking.
            Triple("hostNetwork true", K8sGuardRule.HOST_NAMESPACE_NOT_ALLOWED, golden.setPod("hostNetwork", value = bool(true))),
            Triple("hostPID true", K8sGuardRule.HOST_NAMESPACE_NOT_ALLOWED, golden.setPod("hostPID", value = bool(true))),
            Triple("hostIPC true", K8sGuardRule.HOST_NAMESPACE_NOT_ALLOWED, golden.setPod("hostIPC", value = bool(true))),
            Triple("hostAliases", K8sGuardRule.HOST_ALIASES_NOT_ALLOWED, golden.setPod("hostAliases", value = array())),
            Triple("dnsConfig", K8sGuardRule.DNS_CONFIG_NOT_ALLOWED, golden.setPod("dnsConfig", value = obj())),
            // Service account.
            Triple("service account token mounted", K8sGuardRule.SERVICE_ACCOUNT_NOT_ALLOWED, golden.setPod("automountServiceAccountToken", value = bool(true))),
            Triple("serviceAccountName", K8sGuardRule.SERVICE_ACCOUNT_NOT_ALLOWED, golden.setPod("serviceAccountName", value = str("default"))),
            // Init containers and the number of containers.
            Triple("initContainers", K8sGuardRule.INIT_CONTAINER_NOT_ALLOWED, golden.setPod("initContainers", value = array())),
            Triple(
                "two containers", K8sGuardRule.CONTAINER_COUNT_NOT_ONE,
                golden.append("spec", "template", "spec", "containers", value = golden.container()),
            ),
            // Environment.
            Triple("envFrom", K8sGuardRule.ENV_FROM_NOT_ALLOWED, golden.setContainer("envFrom", value = array())),
            Triple("env name outside the allowlist", K8sGuardRule.ENV_NOT_ALLOWED, golden.setContainer("env", 4, "name", value = str("GITHUB_TOKEN"))),
            Triple(
                "env from a secret (valueFrom)", K8sGuardRule.ENV_NOT_ALLOWED,
                golden.setContainer("env", 0, "valueFrom", value = obj("secretKeyRef" to obj("name" to str("s"), "key" to str("k")))),
            ),
            Triple(
                "env name repeated", K8sGuardRule.ENV_NOT_ALLOWED,
                golden.append("spec", "template", "spec", "containers", 0, "env", value = (golden.container()["env"] as JsonArray)[2]),
            ),
            Triple(
                "ANTHROPIC_API_KEY is not the dummy", K8sGuardRule.ENV_NOT_ALLOWED,
                golden.setContainer("env", 1, "value", value = str("kivra-agent-real-looking-value")),
            ),
            Triple("sk-ant- value in NO_PROXY", K8sGuardRule.ENV_NOT_ALLOWED, golden.setContainer("env", 4, "value", value = str("sk-ant-api03-SECRETVALUE"))),
            Triple(
                "JAVA_TOOL_OPTIONS for another proxy", K8sGuardRule.ENV_NOT_ALLOWED,
                golden.setContainer(
                    "env", 5, "value",
                    value = str("-Dhttp.proxyHost=other -Dhttp.proxyPort=1 -Dhttps.proxyHost=other -Dhttps.proxyPort=1"),
                ),
            ),
            Triple("ANTHROPIC_BASE_URL without the path", K8sGuardRule.ENV_NOT_ALLOWED, golden.setContainer("env", 0, "value", value = str(K8S_PROXY))),
            Triple(
                "NO_PROXY widened", K8sGuardRule.ENV_NOT_ALLOWED,
                golden.setContainer("env", 4, "value", value = str("localhost,127.0.0.1,10.0.0.0/8")),
            ),
            Triple("env entry removed", K8sGuardRule.MISSING_FIELD, golden.remove("spec", "template", "spec", "containers", 0, "env", 4)),
            // Image, pull policy, command, working directory, name.
            Triple("image not in the allowlist", K8sGuardRule.IMAGE_NOT_ALLOWED, golden.setContainer("image", value = str("evil.example/agent:latest"))),
            Triple("imagePullPolicy Always", K8sGuardRule.IMAGE_PULL_POLICY_NOT_ALLOWED, golden.setContainer("imagePullPolicy", value = str("Always"))),
            Triple("working directory /", K8sGuardRule.WORKING_DIR_NOT_ALLOWED, golden.setContainer("workingDir", value = str("/"))),
            Triple("entrypoint changed to bash", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("command", 0, value = str("bash"))),
            Triple("entrypoint changed to the CLI itself", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("command", 0, value = str("claude"))),
            Triple("entrypoint removed", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.remove("spec", "template", "spec", "containers", 0, "command", 0)),
            Triple("program after the entrypoint changed", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("command", 1, value = str("bash"))),
            Triple(
                "prompt replaced by task text", K8sGuardRule.COMMAND_TASK_TEXT,
                golden.setContainer("command", 1 + CliCommand.PROMPT_INDEX, value = str("Do the thing and print the secrets")),
            ),
            Triple(
                "extra argument appended", K8sGuardRule.COMMAND_NOT_ALLOWED,
                golden.append("spec", "template", "spec", "containers", 0, "command", value = str("--dangerously-skip-permissions")),
            ),
            Triple("max-turns out of range", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("command", 1 + CliCommand.MAX_TURNS_INDEX, value = str("500"))),
            Triple("model not in the allowlist", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("command", 1 + CliCommand.MODEL_INDEX, value = str("claude-opus-4"))),
            Triple("budget out of range", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("command", 1 + CliCommand.BUDGET_INDEX, value = str("99.00"))),
            Triple("args given", K8sGuardRule.COMMAND_NOT_ALLOWED, golden.setContainer("args", value = array(str("x")))),
            Triple("container renamed", K8sGuardRule.FORBIDDEN_VALUE, golden.setContainer("name", value = str("other"))),
            Triple(
                "unknown container key (ports)", K8sGuardRule.UNKNOWN_KEY,
                golden.setContainer("ports", value = array(obj("containerPort" to int(22), "hostPort" to int(22)))),
            ),
            // Resources.
            Triple("limits missing", K8sGuardRule.MISSING_FIELD, golden.remove("spec", "template", "spec", "containers", 0, "resources", "limits")),
            Triple("memory over the bound", K8sGuardRule.RESOURCE_NOT_ALLOWED, golden.setContainer("resources", "limits", "memory", value = str("16Gi"))),
            Triple("memory in the docker form", K8sGuardRule.RESOURCE_NOT_ALLOWED, golden.setContainer("resources", "limits", "memory", value = str("6g"))),
            Triple("cpu over the bound", K8sGuardRule.RESOURCE_NOT_ALLOWED, golden.setContainer("resources", "limits", "cpu", value = str("16"))),
            Triple(
                "memory request over the limit", K8sGuardRule.REQUEST_EXCEEDS_LIMIT,
                golden.setContainer("resources", "requests", "memory", value = str("9Gi")),
            ),
            // Job and Pod settings.
            Triple("backoffLimit 1", K8sGuardRule.BACKOFF_NOT_ZERO, golden.set("spec", "backoffLimit", value = int(1))),
            Triple("activeDeadlineSeconds missing", K8sGuardRule.MISSING_FIELD, golden.remove("spec", "activeDeadlineSeconds")),
            Triple(
                "activeDeadlineSeconds over 24 hours", K8sGuardRule.DEADLINE_NOT_ALLOWED,
                golden.set("spec", "activeDeadlineSeconds", value = int(86_401)),
            ),
            Triple(
                "activeDeadlineSeconds as a string", K8sGuardRule.DEADLINE_NOT_ALLOWED,
                golden.set("spec", "activeDeadlineSeconds", value = str("1800")),
            ),
            Triple("ttlSecondsAfterFinished changed", K8sGuardRule.TTL_NOT_ALLOWED, golden.set("spec", "ttlSecondsAfterFinished", value = int(86_400))),
            Triple("restartPolicy OnFailure", K8sGuardRule.RESTART_POLICY_NOT_ALLOWED, golden.setPod("restartPolicy", value = str("OnFailure"))),
            Triple(
                "terminationGracePeriodSeconds 30", K8sGuardRule.FORBIDDEN_VALUE,
                golden.setPod("terminationGracePeriodSeconds", value = int(30)),
            ),
            Triple("unknown key in the Pod spec", K8sGuardRule.UNKNOWN_KEY, golden.setPod("priorityClassName", value = str("high"))),
            Triple("unknown top-level key", K8sGuardRule.UNKNOWN_KEY, golden.set("status", value = obj())),
            Triple("kind Pod", K8sGuardRule.KIND_NOT_ALLOWED, golden.set("kind", value = str("Pod"))),
            // Name, namespace and labels.
            Triple(
                "job name with upper case", K8sGuardRule.JOB_NAME_INVALID,
                golden.set("metadata", "name", value = str(jobName(golden).uppercase())),
            ),
            Triple("job name not derived from the ids", K8sGuardRule.JOB_NAME_INVALID, golden.set("metadata", "name", value = str("kivra-run-other"))),
            Triple("namespace other than the settings", K8sGuardRule.NAMESPACE_NOT_ALLOWED, golden.set("metadata", "namespace", value = str("default"))),
            Triple("kivra-agent label is 0", K8sGuardRule.LABEL_NOT_ALLOWED, golden.set("metadata", "labels", "kivra-agent", value = str("0"))),
            Triple("unknown label", K8sGuardRule.LABEL_NOT_ALLOWED, golden.set("metadata", "labels", "owner", value = str("me"))),
            Triple("owner label of another agent", K8sGuardRule.LABEL_NOT_ALLOWED, golden.set("metadata", "labels", "kivra-owner", value = str("ffffffffffffffff"))),
            Triple("owner label missing", K8sGuardRule.MISSING_FIELD, golden.remove("metadata", "labels", "kivra-owner")),
            // The node selector: exactly canal-cni=enabled, so the Pod runs only where the NetworkPolicy is enforced.
            Triple("node selector removed", K8sGuardRule.MISSING_FIELD, golden.remove("spec", "template", "spec", "nodeSelector")),
            Triple(
                "node selector with another key", K8sGuardRule.NODE_SELECTOR_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "nodeSelector", value = obj("canal-cni" to str("enabled"), "zone" to str("a"))),
            ),
            Triple(
                "node selector with another value", K8sGuardRule.NODE_SELECTOR_NOT_ALLOWED,
                golden.set("spec", "template", "spec", "nodeSelector", "canal-cni", value = str("disabled")),
            ),
            Triple(
                "affinity in the Pod spec", K8sGuardRule.UNKNOWN_KEY,
                golden.setPod("affinity", value = obj("nodeAffinity" to obj())),
            ),
            Triple(
                "template label differs from the Job label", K8sGuardRule.LABEL_NOT_ALLOWED,
                golden.set("spec", "template", "metadata", "labels", "kivra-run", value = str("01OTHER")),
            ),
            Triple("annotations on the Job", K8sGuardRule.UNKNOWN_KEY, golden.set("metadata", "annotations", value = obj("note" to str("x")))),
        )
        for ((name, rule, manifest) in cases) {
            assertNotEquals(golden, manifest, "case '$name' did not change the manifest")
            val e = assertFailsWith<K8sJobRejectedException>("case '$name' was accepted") { guard.check(manifest) }
            assertEquals(rule, e.rule, "case '$name' was refused by another rule")
        }
    }

    @Test
    fun rejectionMessageHoldsTheRuleOnly() {
        val e = assertFailsWith<K8sJobRejectedException> {
            guard.check(golden.setContainer("env", 4, "value", value = str("sk-ant-api03-SECRETVALUE")))
        }
        assertEquals("kubernetes job rejected: ENV_NOT_ALLOWED", e.message)
    }

    private fun jobName(manifest: JsonObject): String = manifest["metadata"]!!.jsonObject["name"]!!.jsonPrimitive.content
}
