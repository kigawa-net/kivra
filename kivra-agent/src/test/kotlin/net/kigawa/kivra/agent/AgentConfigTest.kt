package net.kigawa.kivra.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConfigTest {
    private val dir = Files.createTempDirectory("kivra-agent-cfg")
    private val secret = dir.resolve("secret").toString()

    private fun env(vararg overrides: Pair<String, String?>): Map<String, String> {
        val base = mutableMapOf(
            AgentConfig.ENV_KIVRA_URL to "https://kivra.example",
            AgentConfig.ENV_TOKEN_URL to "https://auth.example/token",
            AgentConfig.ENV_CLIENT_ID to "kivra-agent-pc1",
            AgentConfig.ENV_CLIENT_SECRET_FILE to secret,
            AgentConfig.ENV_WORK_DIR to dir.toString(),
        )
        for ((k, v) in overrides) if (v == null) base.remove(k) else base[k] = v
        return base
    }

    private fun rejected(vararg overrides: Pair<String, String?>): ConfigException =
        assertFailsWith<ConfigException> { AgentConfig.fromEnv(env(*overrides)) }

    @Test
    fun validEnvironmentGivesDefaults() {
        val config = AgentConfig.fromEnv(env())
        assertEquals("https://kivra.example", config.kivraUrl)
        assertEquals(5_000L, config.pollInterval.toMillis())
        assertEquals(30_000L, config.heartbeatInterval.toMillis())
        assertEquals(dir.resolve("STOP").normalize(), config.stopFile)
        assertEquals(1, config.maxConcurrent)
    }

    @Test
    fun missingVariableIsRejectedByNameOnly() {
        val e = rejected(AgentConfig.ENV_TOKEN_URL to null)
        assertEquals("${AgentConfig.ENV_TOKEN_URL} is required", e.message)
    }

    @Test
    fun plainHttpIsRejectedExceptForLocalhost() {
        val e = rejected(AgentConfig.ENV_KIVRA_URL to "http://kivra.example")
        assertFalse(e.message!!.contains("kivra.example"), "the error must not echo the value")
        assertEquals(AgentConfig.ENV_KIVRA_URL, e.message!!.substringBefore(" "))
        AgentConfig.fromEnv(env(AgentConfig.ENV_KIVRA_URL to "http://localhost:8080"))
        AgentConfig.fromEnv(env(AgentConfig.ENV_TOKEN_URL to "http://127.0.0.1:8081/token"))
    }

    @Test
    fun urlsWithUserInfoQueryOrFragmentAreRejected() {
        for (bad in listOf("https://u:p@kivra.example", "https://kivra.example?x=1", "https://kivra.example#f", "ftp://kivra.example", "not a url")) {
            assertTrue(rejected(AgentConfig.ENV_KIVRA_URL to bad).message!!.startsWith(AgentConfig.ENV_KIVRA_URL))
        }
    }

    @Test
    fun relativePathsAreRejected() {
        assertTrue(rejected(AgentConfig.ENV_CLIENT_SECRET_FILE to "secret").message!!.contains("absolute"))
        assertTrue(rejected(AgentConfig.ENV_WORK_DIR to "relative/dir").message!!.contains("absolute"))
        assertTrue(rejected(AgentConfig.ENV_STOP_FILE to "STOP").message!!.contains("absolute"))
    }

    @Test
    fun intervalsAreBoundedAndNonNumericIsRejected() {
        assertEquals(AgentConfig.ENV_POLL_INTERVAL_MS, rejected(AgentConfig.ENV_POLL_INTERVAL_MS to "10").message!!.substringBefore(" "))
        assertEquals(
            AgentConfig.ENV_HEARTBEAT_INTERVAL_MS,
            rejected(AgentConfig.ENV_HEARTBEAT_INTERVAL_MS to "1000").message!!.substringBefore(" "),
        )
        assertEquals(AgentConfig.ENV_POLL_INTERVAL_MS, rejected(AgentConfig.ENV_POLL_INTERVAL_MS to "fast").message!!.substringBefore(" "))
        val ok = AgentConfig.fromEnv(env(AgentConfig.ENV_HEARTBEAT_INTERVAL_MS to "5000"))
        assertEquals(5_000L, ok.heartbeatInterval.toMillis())
    }

    @Test
    fun clientIdShapeIsChecked() {
        assertTrue(rejected(AgentConfig.ENV_CLIENT_ID to "bad id with spaces").message!!.contains("not a valid client id"))
    }

    @Test
    fun toStringShowsNoValue() {
        val config = AgentConfig.fromEnv(env())
        assertEquals("AgentConfig(***)", config.toString())
        assertFalse(config.toString().contains("kivra.example"))
    }

    @Test
    fun executorIsTheStubUnlessRealIsAskedFor() {
        val config = AgentConfig.fromEnv(env())
        assertEquals(AgentConfig.ExecutorMode.STUB, config.executorMode)
        assertEquals(null, config.realExecutor)
        assertEquals(AgentConfig.ExecutorMode.STUB, AgentConfig.fromEnv(env(AgentConfig.ENV_EXECUTOR to "stub")).executorMode)
    }

    @Test
    fun unknownExecutorValueIsRejectedByNameOnly() {
        val error = rejected(AgentConfig.ENV_EXECUTOR to "docker")
        assertEquals("KIVRA_AGENT_EXECUTOR must be stub or real", error.message)
    }

    @Test
    fun realExecutorReadsItsSettingsOnlyWhenAskedFor() {
        val real = env(
            AgentConfig.ENV_EXECUTOR to "real",
            AgentConfig.ENV_DOCKER_BIN to "/usr/bin/docker",
            AgentConfig.ENV_DOCKER_NETWORK to "kivra-agent-internal",
            AgentConfig.ENV_DOCKER_IMAGE to "kivra-agent-runtime:local",
            AgentConfig.ENV_DOCKER_PROXY_URL to "http://kivra-agent-proxy:8080",
            AgentConfig.ENV_GIT_BIN to "/usr/bin/git",
            AgentConfig.ENV_REPO to "kigawa-net/kivra",
        )
        val config = AgentConfig.fromEnv(real)
        assertEquals(AgentConfig.ExecutorMode.REAL, config.executorMode)
        assertEquals("kivra-agent-internal", config.realExecutor!!.network)
        assertFalse(config.toString().contains("kivra-agent-internal"))
    }

    @Test
    fun realExecutorRequiresItsSettings() {
        val base = arrayOf(
            AgentConfig.ENV_EXECUTOR to "real",
            AgentConfig.ENV_DOCKER_BIN to "/usr/bin/docker",
            AgentConfig.ENV_DOCKER_NETWORK to "kivra-agent-internal",
            AgentConfig.ENV_DOCKER_IMAGE to "kivra-agent-runtime:local",
            AgentConfig.ENV_DOCKER_PROXY_URL to "http://kivra-agent-proxy:8080",
            AgentConfig.ENV_GIT_BIN to "/usr/bin/git",
            AgentConfig.ENV_REPO to "kigawa-net/kivra",
        )
        assertEquals("KIVRA_AGENT_DOCKER_IMAGE is required", rejected(*base.map { if (it.first == AgentConfig.ENV_DOCKER_IMAGE) it.first to null else it }.toTypedArray()).message)
        assertEquals("KIVRA_AGENT_DOCKER_PROXY_URL must be http://host:port", rejected(*base.map { if (it.first == AgentConfig.ENV_DOCKER_PROXY_URL) it.first to "https://proxy.example" else it }.toTypedArray()).message)
        assertEquals("KIVRA_AGENT_REPO must be owner/repo", rejected(*base.map { if (it.first == AgentConfig.ENV_REPO) it.first to "kivra" else it }.toTypedArray()).message)
        assertEquals("KIVRA_AGENT_DOCKER_BIN must be an absolute path", rejected(*base.map { if (it.first == AgentConfig.ENV_DOCKER_BIN) it.first to "docker" else it }.toTypedArray()).message)
    }
}
