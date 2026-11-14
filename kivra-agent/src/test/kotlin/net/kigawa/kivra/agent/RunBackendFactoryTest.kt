package net.kigawa.kivra.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.agent.docker.DockerRunBackend
import net.kigawa.kivra.agent.k8s.FakeKubeApi
import net.kigawa.kivra.agent.k8s.HttpKubeApi
import net.kigawa.kivra.agent.k8s.K8S_ATTEMPT_ID
import net.kigawa.kivra.agent.k8s.K8S_IMAGE
import net.kigawa.kivra.agent.k8s.K8S_NAMESPACE
import net.kigawa.kivra.agent.k8s.K8S_PROXY
import net.kigawa.kivra.agent.k8s.K8S_RUN_ID
import net.kigawa.kivra.agent.k8s.K8sNames
import net.kigawa.kivra.agent.k8s.K8sRunBackend
import net.kigawa.kivra.agent.k8s.KubeApiException
import net.kigawa.kivra.agent.k8s.PodState
import net.kigawa.kivra.agent.workdir.RepoSource

/**
 * The k8s wiring of the start (ADR 0010 追補 7 PR 5c): the backend is assembled from valid settings, an agent that is not in the cluster
 * fails the start, and the backend and the executor share one [AttemptPodInputs]. No test here reaches a cluster.
 */
class RunBackendFactoryTest {
    private val root: Path = Files.createTempDirectory("kivra-factory").toRealPath()
    private val dir: Path = Files.createTempDirectory("kivra-factory-cfg")
    private val secret = dir.resolve("secret").toString()

    private fun k8sConfig(vararg overrides: Pair<String, String?>): AgentConfig {
        val base = mutableMapOf(
            AgentConfig.ENV_KIVRA_URL to KIVRA_URL,
            AgentConfig.ENV_TOKEN_URL to "https://auth.example/token",
            AgentConfig.ENV_CLIENT_ID to "kivra-agent-pc1",
            AgentConfig.ENV_CLIENT_SECRET_FILE to secret,
            AgentConfig.ENV_WORK_DIR to root.toString(),
            AgentConfig.ENV_BACKEND to "k8s",
            AgentConfig.ENV_EXECUTOR to "real",
            AgentConfig.ENV_K8S_NAMESPACE to K8S_NAMESPACE,
            AgentConfig.ENV_K8S_IMAGE to K8S_IMAGE,
            AgentConfig.ENV_K8S_PROXY_URL to K8S_PROXY,
            AgentConfig.ENV_REPO to "kigawa-net/kivra",
        )
        for ((k, v) in overrides) if (v == null) base.remove(k) else base[k] = v
        return AgentConfig.fromEnv(base)
    }

    @Test
    fun k8sBackendIsAssembledFromValidSettings() {
        val config = k8sConfig()
        val api = FakeKubeApi()
        val backend = createRunBackend(config.backend, config.realExecutor!!, root, kubeApi = { api })
        assertIs<K8sRunBackend>(backend)
        assertFalse(backend.usesHostWorkDir)
    }

    @Test
    fun dockerBackendStillUsesTheHostWorkDir() {
        val settings = RealExecutorSettings(
            dockerBinary = Path.of("/usr/bin/docker"),
            network = "kivra-agent-internal",
            image = K8S_IMAGE,
            proxyUrl = K8S_PROXY,
            gitBinary = Path.of("/usr/bin/git"),
            repo = RepoSource.PublicGithub("kigawa-net", "kivra"),
        )
        val backend = createRunBackend(AgentConfig.Backend.DOCKER, settings, root)
        assertIs<DockerRunBackend>(backend)
        assertTrue(backend.usesHostWorkDir)
    }

    @Test
    fun anAgentOutsideTheClusterFailsTheStartWithAConfigError() {
        val config = k8sConfig()
        val e = assertFailsWith<ConfigException> {
            createRunBackend(config.backend, config.realExecutor!!, root, kubeApi = { throw KubeApiException("NOT_IN_CLUSTER") })
        }
        assertFalse(e.message.orEmpty().contains("NOT_IN_CLUSTER"), "the message names the condition, not the code")
    }

    @Test
    fun aMissingServiceAccountFailsTheStartWithAConfigError() {
        // The real client, as on a host outside the cluster: no KUBERNETES_SERVICE_HOST and no ServiceAccount directory.
        val config = k8sConfig()
        val e = assertFailsWith<ConfigException> {
            createRunBackend(config.backend, config.realExecutor!!, root, kubeApi = { HttpKubeApi.inCluster(emptyMap(), dir.resolve("none")) })
        }
        assertTrue(e.message!!.startsWith("the k8s backend must run in the cluster"))
    }

    @Test
    fun k8sSettingsAreCheckedAtTheStart() {
        val config = k8sConfig()
        val withoutNamespace = config.realExecutor!!.let {
            RealExecutorSettings(image = it.image, proxyUrl = it.proxyUrl, repo = it.repo, model = it.model, budgetUsd = it.budgetUsd)
        }
        val e = assertFailsWith<ConfigException> {
            createRunBackend(AgentConfig.Backend.K8S, withoutNamespace, root, kubeApi = { FakeKubeApi() })
        }
        assertEquals("${AgentConfig.ENV_K8S_NAMESPACE} is required for the k8s backend", e.message)
    }

    /**
     * Start → executor → one attempt, with the k8s backend and a fake cluster. If the backend and the executor had two different
     * [AttemptPodInputs], the run would fail before the Job is created ("no input is registered"), and no Job would be created.
     */
    @Test
    fun theBackendAndTheExecutorShareOneInputRegistry() = runBlocking {
        val config = k8sConfig()
        val api = FakeKubeApi()
        api.pod = { PodState("pod-1", running = false, terminated = true, exitCode = 0) }
        val recorded = synchronizedList()
        val client = KivraAgentClient(fakeKivraHttp(recorded) { Canned(204) }, KIVRA_URL, FixedTokens())
        val executor = executorFor(config, client, kubeApi = { api }, pollMillis = 5)

        val outcome = executor.execute(attempt())

        assertEquals(1, api.created.size, "the Job of the run is created")
        assertIs<AttemptOutcome.Failed>(outcome)
        assertEquals(1, api.deleted.size, "the Job is deleted after the run")
        assertTrue(api.created.single().first == K8S_NAMESPACE)
        assertEquals(K8sNames.ownerLabel("kivra-agent-pc1"), config.realExecutor!!.k8s!!.ownerLabel)
    }

    private fun attempt() = ClaimedAttempt(
        runId = K8S_RUN_ID,
        attemptId = K8S_ATTEMPT_ID,
        generation = 3,
        baseSha = "c".repeat(40),
        task = AgentTask("task_title", "Fix docs", TRUST_UNTRUSTED, false, "", false),
        limits = AgentLimits(
            maxRuntimeSeconds = 600,
            maxTurns = 30,
            maxOutputBytes = 1_048_576,
            maxChangedFiles = 20,
            maxFileBytes = 200_000,
            maxTotalBytes = 1_000_000,
            maxDeletes = 5,
        ),
        allowedPathPatterns = listOf("kivra-*/src/**"),
        deniedPathPatterns = listOf(".github/**"),
    )

    private companion object {
        const val KIVRA_URL = "https://kivra.example"
    }
}
