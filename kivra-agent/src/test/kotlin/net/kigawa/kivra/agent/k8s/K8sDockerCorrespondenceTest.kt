package net.kigawa.kivra.agent.k8s

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerConstants
import net.kigawa.kivra.agent.docker.DockerFixture
import net.kigawa.kivra.agent.docker.TEST_ATTEMPT_ID
import net.kigawa.kivra.agent.docker.TEST_IMAGE
import net.kigawa.kivra.agent.docker.TEST_PROXY
import net.kigawa.kivra.agent.docker.TEST_RUN_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The docker side and the k8s side must agree on every value that both have (ADR 0010 追補 7 §4). The docker command of the standard spec
 * is built here, and each of its options is compared with the Job field that holds the same value. The values that k8s cannot express
 * are listed in the last test, so that each is a known difference and not an oversight.
 */
class K8sDockerCorrespondenceTest {
    private val dockerFixture = DockerFixture()
    private val docker: List<String> = dockerFixture.golden()
    private val k8s: JsonObject = K8sFixture.golden()
    private val podSpec: JsonObject = (k8s["spec"] as JsonObject)["template"]!!.jsonObject["spec"]!!.jsonObject
    private val container: JsonObject = podSpec["containers"]!!.jsonArray[0].jsonObject

    @Test
    fun sharedInputsAreTheSameValues() {
        assertEquals(TEST_IMAGE, K8S_IMAGE)
        assertEquals(TEST_PROXY, K8S_PROXY)
        assertEquals(TEST_RUN_ID, K8S_RUN_ID)
        assertEquals(TEST_ATTEMPT_ID, K8S_ATTEMPT_ID)
    }

    @Test
    fun envNamesAndValuesAreTheSame() {
        val dockerEnv = optionValues("--env=").associate { it.substringBefore('=') to it.substringAfter('=') }
        val k8sEnv = container["env"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content
        }
        assertEquals(dockerEnv, k8sEnv)
        assertEquals(DockerConstants.ENV_NAMES, k8sEnv.keys)
    }

    @Test
    fun labelsAreTheSame() {
        val dockerLabels = optionValues("--label=").associate { it.substringBefore('=') to it.substringAfter('=') }
        val jobLabels = stringMap((k8s["metadata"] as JsonObject)["labels"]!!.jsonObject)
        val podLabels = stringMap((k8s["spec"] as JsonObject)["template"]!!.jsonObject["metadata"]!!.jsonObject["labels"]!!.jsonObject)
        assertEquals(dockerLabels, jobLabels.filterKeys { it != K8sConstants.LABEL_ATTEMPT_KEY })
        assertEquals(jobLabels, podLabels)
    }

    @Test
    fun commandIsTheSameArgv() {
        val dockerCommand = docker.subList(docker.indexOf(TEST_IMAGE) + 1, docker.size)
        val k8sFull = container["command"]!!.jsonArray.map { it.jsonPrimitive.content }
        // The k8s command is the in-Pod entrypoint and then the docker argv.
        assertEquals(K8sConstants.POD_ENTRYPOINT, k8sFull.first())
        val k8sCommand = k8sFull.drop(1)
        assertEquals(dockerCommand, k8sCommand)
        assertEquals(CliCommand.argv(CliCommand.DEFAULT_MAX_TURNS), k8sCommand)
    }

    @Test
    fun userAndWorkingDirectoryAreTheSame() {
        assertTrue(docker.contains("--user=${DockerConstants.USER}"))
        val sc = podSpec["securityContext"]!!.jsonObject
        assertEquals(DockerConstants.AGENT_UID.toString(), sc["runAsUser"]!!.jsonPrimitive.content)
        assertEquals(DockerConstants.AGENT_GID.toString(), sc["runAsGroup"]!!.jsonPrimitive.content)
        assertTrue(docker.contains("--workdir=${DockerConstants.WORKDIR}"))
        assertEquals(DockerConstants.WORKDIR, container["workingDir"]!!.jsonPrimitive.content)
        assertTrue(docker.contains("--mount=type=bind,source=${dockerFixture.workDir.toRealPath()},target=${DockerConstants.WORKDIR}"))
        assertTrue(mountPaths().contains(DockerConstants.WORKDIR))
    }

    @Test
    fun securityOptionsAreTheSame() {
        val sc = container["securityContext"]!!.jsonObject
        assertTrue(docker.contains("--cap-drop=ALL"))
        assertEquals(listOf("ALL"), sc["capabilities"]!!.jsonObject["drop"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(docker.contains("--security-opt=no-new-privileges"))
        assertEquals("false", sc["allowPrivilegeEscalation"]!!.jsonPrimitive.content)
        assertTrue(docker.contains("--read-only"))
        assertEquals("true", sc["readOnlyRootFilesystem"]!!.jsonPrimitive.content)
        assertEquals("false", sc["privileged"]!!.jsonPrimitive.content)
    }

    @Test
    fun memoryAndCpusAreTheSameLimit() {
        val dockerMemory = docker.single { it.startsWith("--memory=") }.substringAfter('=')
        val limits = container["resources"]!!.jsonObject["limits"]!!.jsonObject
        assertEquals(DockerConstants.DEFAULT_MEMORY, dockerMemory)
        assertEquals(K8sQuantities.dockerMemoryToMi(dockerMemory), K8sQuantities.parseMi(limits["memory"]!!.jsonPrimitive.content))
        assertTrue(docker.contains("--cpus=${DockerConstants.DEFAULT_CPUS}"))
        assertEquals(DockerConstants.DEFAULT_CPUS, limits["cpu"]!!.jsonPrimitive.content)
    }

    @Test
    fun tmpfsSizesAreTheSameAsEmptyDirSizeLimits() {
        // The docker tmpfs has `size=512m` and `size=2g`; the emptyDir has `512Mi` and `2Gi`.
        val tmp = docker.single { it.startsWith("--tmpfs=/tmp:") }.substringAfter("size=")
        val home = docker.single { it.startsWith("--tmpfs=/home/agent:") }.substringAfter("size=").substringBefore(',')
        assertEquals(K8sQuantities.dockerMemoryToMi(tmp), volumeSizeMi(K8sConstants.VOLUME_TMP))
        assertEquals(K8sQuantities.dockerMemoryToMi(home), volumeSizeMi(K8sConstants.VOLUME_HOME))
        assertEquals(512L, volumeSizeMi(K8sConstants.VOLUME_TMP))
        assertEquals(2048L, volumeSizeMi(K8sConstants.VOLUME_HOME))
    }

    @Test
    fun stopTimeoutIsTheSameGracePeriod() {
        assertTrue(docker.contains("--stop-timeout=${DockerConstants.DEFAULT_STOP_TIMEOUT_SECONDS}"))
        assertEquals(DockerConstants.DEFAULT_STOP_TIMEOUT_SECONDS.toString(), podSpec["terminationGracePeriodSeconds"]!!.jsonPrimitive.content)
    }

    /**
     * The known differences. `--pids-limit` has no field in a Pod (the kubelet's `podPidsLimit` is a cluster setting), and
     * `--memory-swap` has no field (k8s has no swap limit per container; the swap is off by default). Neither is in the manifest.
     */
    @Test
    fun knownDifferencesAreNotInTheManifest() {
        assertEquals(
            listOf("--memory-swap", "--pids-limit"),
            docker.filter { it.startsWith("--pids-limit=") || it.startsWith("--memory-swap=") }.map { it.substringBefore('=') }.sorted(),
        )
        val text = k8s.toString()
        assertFalse(text.contains("pids"))
        assertFalse(text.contains("swap"))
    }

    /** The values of the docker options with [prefix], in order. */
    private fun optionValues(prefix: String): List<String> = docker.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

    private fun stringMap(obj: JsonObject): Map<String, String> = obj.mapValues { it.value.jsonPrimitive.content }

    private fun mountPaths(): List<String> = container["volumeMounts"]!!.jsonArray.map { it.jsonObject["mountPath"]!!.jsonPrimitive.content }

    /** The size limit of the emptyDir [name], in Mi. */
    private fun volumeSizeMi(name: String): Long {
        val volume = podSpec["volumes"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }
        return K8sQuantities.parseMi(volume["emptyDir"]!!.jsonObject["sizeLimit"]!!.jsonPrimitive.content)!!
    }
}
