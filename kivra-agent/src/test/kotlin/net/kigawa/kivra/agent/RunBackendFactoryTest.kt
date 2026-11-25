package net.kigawa.kivra.agent

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kigawa.kivra.agent.docker.DockerRunBackend
import net.kigawa.kivra.agent.k8s.FakeKubeApi
import net.kigawa.kivra.agent.k8s.JobRef
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
import org.slf4j.LoggerFactory

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
        val config = k8sConfig(
            AgentConfig.ENV_CLAUDE_ACCOUNT to "repo-a",
            AgentConfig.ENV_ALLOWED_CLAUDE_ACCOUNTS to "repo-b,repo-c",
        )
        val api = FakeKubeApi()
        api.pod = { PodState("pod-1", running = false, terminated = true, exitCode = 0) }
        val recorded = synchronizedList()
        val client = KivraAgentClient(fakeKivraHttp(recorded) { Canned(204) }, KIVRA_URL, FixedTokens())
        val executor = executorFor(config, client, kubeApi = { api }, pollMillis = 5)

        val outcome = executor.execute(attempt().copy(claudeAccount = "repo-b"))

        assertEquals(1, api.created.size, "the Job of the run is created")
        assertIs<AttemptOutcome.Failed>(outcome)
        assertEquals(1, api.deleted.size, "the Job is deleted after the run")
        val (namespace, manifest) = api.created.single()
        assertEquals(K8S_NAMESPACE, namespace)
        val pod = manifest.getValue("spec").jsonObject.getValue("template").jsonObject.getValue("spec").jsonObject
        val container = pod.getValue("containers").jsonArray.single().jsonObject
        val env = container.getValue("env").jsonArray.associate {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("value").jsonPrimitive.content
        }
        assertEquals("$K8S_PROXY/anthropic/repo-b", env["ANTHROPIC_BASE_URL"])
        assertEquals(K8sNames.ownerLabel("kivra-agent-pc1"), config.realExecutor!!.k8s!!.ownerLabel)
    }

    /** An allowed set without a default id: a claim that omits the account uses the conventional /anthropic path. */
    @Test
    fun aClaimWithoutAnAccountUsesTheDefaultPathWhenOnlyTheAllowedSetIsConfigured() = runBlocking {
        val config = k8sConfig(AgentConfig.ENV_ALLOWED_CLAUDE_ACCOUNTS to "repo-b")
        val api = FakeKubeApi()
        api.pod = { PodState("pod-1", running = false, terminated = true, exitCode = 0) }
        val recorded = synchronizedList()
        val client = KivraAgentClient(fakeKivraHttp(recorded) { Canned(204) }, KIVRA_URL, FixedTokens())
        val executor = executorFor(config, client, kubeApi = { api }, pollMillis = 5)

        executor.execute(attempt().copy(claudeAccount = null))

        val (_, manifest) = api.created.single()
        val pod = manifest.getValue("spec").jsonObject.getValue("template").jsonObject.getValue("spec").jsonObject
        val container = pod.getValue("containers").jsonArray.single().jsonObject
        val env = container.getValue("env").jsonArray.associate {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("value").jsonPrimitive.content
        }
        assertEquals("$K8S_PROXY/anthropic", env["ANTHROPIC_BASE_URL"])
    }

    /** No default id and no allowed set: any claimed account is outside the (empty) set, so no Job is created. */
    @Test
    fun anAgentWithoutAnyAccountSettingRefusesAClaimedAccount() = runBlocking {
        val config = k8sConfig()
        val api = FakeKubeApi()
        val recorded = synchronizedList()
        val client = KivraAgentClient(fakeKivraHttp(recorded) { Canned(204) }, KIVRA_URL, FixedTokens())
        val executor = executorFor(config, client, kubeApi = { api }, pollMillis = 5)

        val outcome = executor.execute(attempt().copy(claudeAccount = "repo-a"))

        assertIs<AttemptOutcome.Failed>(outcome)
        assertEquals(emptyList(), api.created)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anUnlistedClaimFailsWithoutCreatingAJob() = runTest {
        val logger = LoggerFactory.getLogger(RealAttemptExecutor::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val configs = listOf(
                k8sConfig(),
                k8sConfig(AgentConfig.ENV_CLAUDE_ACCOUNT to "repo-a", AgentConfig.ENV_ALLOWED_CLAUDE_ACCOUNTS to "repo-b"),
            )
            for (config in configs) {
                val api = FakeKubeApi()
                val recorded = synchronizedList()
                val claim = CLAIM_BODY.replaceFirst("{", """{"claudeAccount":"repo-unlisted",""")
                var claimServed = false
                val http = fakeKivraHttp(recorded) {
                    when (it.path) {
                        "/api/agent/v1/claim" -> if (claimServed) Canned(204) else Canned(200, claim).also { claimServed = true }
                        "/api/agent/v1/attempts/att-1/fail" -> Canned(200, """{"attemptId":"att-1","state":"FAILED"}""")
                        else -> error("unexpected request ${it.path}")
                    }
                }
                val client = KivraAgentClient(http, KIVRA_URL, FixedTokens())
                val executor = executorFor(config, client, kubeApi = { api })
                val job = backgroundScope.launch { AgentLoop(config, client, executor).run() }
                try {
                    advanceTimeBy(1_000)
                    assertEquals(emptyList(), api.created)
                    val fail = recorded.single { it.path.endsWith("/fail") }
                    assertEquals("""{"generation":3,"errorCode":"NEEDS_HUMAN"}""", fail.body)
                } finally {
                    job.cancel()
                    http.close()
                }
            }
            val deniedLogs = appender.list.map { it.formattedMessage }.filter { it.contains("ACCOUNT_NOT_ALLOWED") }
            assertEquals(configs.size, deniedLogs.size)
            assertTrue(deniedLogs.all { it.contains("claudeAccount=repo-unlisted") })
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    /** A process that was killed in a run leaves its Job. The next process removes it at start, before any attempt is claimed. */
    @Test
    fun theStartRemovesTheJobsThatAPreviousProcessLeft() {
        val api = FakeKubeApi()
        api.jobs = { listOf(JobRef("job-left", "RUN-OLD")) }
        val client = KivraAgentClient(fakeKivraHttp(synchronizedList()) { Canned(204) }, KIVRA_URL, FixedTokens())

        executorFor(k8sConfig(), client, kubeApi = { api }, pollMillis = 5)

        assertEquals(listOf("job-left"), api.deleted.toList())
        assertEquals(0, api.created.size)
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
