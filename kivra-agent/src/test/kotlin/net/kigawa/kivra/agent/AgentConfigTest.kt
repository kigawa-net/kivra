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

    private fun realEnv(vararg extra: Pair<String, String?>): Map<String, String> = env(
        AgentConfig.ENV_EXECUTOR to "real",
        AgentConfig.ENV_DOCKER_BIN to "/usr/bin/docker",
        AgentConfig.ENV_DOCKER_NETWORK to "kivra-agent-internal",
        AgentConfig.ENV_DOCKER_IMAGE to "kivra-agent-runtime:local",
        AgentConfig.ENV_DOCKER_PROXY_URL to "http://kivra-agent-proxy:8080",
        AgentConfig.ENV_GIT_BIN to "/usr/bin/git",
        AgentConfig.ENV_REPO to "kigawa-net/kivra",
        *extra,
    )

    @Test
    fun modelAndBudgetDefaultToHaikuAndFiftyCents() {
        val config = AgentConfig.fromEnv(realEnv())
        assertEquals("claude-haiku-5-5", config.realExecutor!!.model)
        assertEquals("0.50", config.realExecutor!!.budgetUsd)
    }

    @Test
    fun modelAndBudgetAreReadFromTheEnvironment() {
        val config = AgentConfig.fromEnv(realEnv(AgentConfig.ENV_MODEL to "claude-sonnet-5-0", AgentConfig.ENV_MAX_BUDGET_USD to "5"))
        assertEquals("claude-sonnet-5-0", config.realExecutor!!.model)
        assertEquals("5.00", config.realExecutor!!.budgetUsd, "the budget is written with two decimals")
        assertEquals("0.50", AgentConfig.fromEnv(realEnv(AgentConfig.ENV_MAX_BUDGET_USD to "0.5")).realExecutor!!.budgetUsd)
        assertEquals("20.00", AgentConfig.fromEnv(realEnv(AgentConfig.ENV_MAX_BUDGET_USD to "20")).realExecutor!!.budgetUsd)
    }

    @Test
    fun invalidModelAndBudgetAreRejectedByNameOnly() {
        for (budget in listOf("0", "0.00", "0.001", "20.01", "21", "-1", "abc", "1e2", "1.")) {
            val e = assertFailsWith<ConfigException> { AgentConfig.fromEnv(realEnv(AgentConfig.ENV_MAX_BUDGET_USD to budget)) }
            assertEquals("KIVRA_AGENT_MAX_BUDGET_USD must be a dollar amount from 0.01 to 20.00 with at most two decimals", e.message, budget)
        }
        for (model in listOf("Claude-Haiku", "claude haiku", "claude/x", "-x", "a".repeat(65))) {
            val e = assertFailsWith<ConfigException> { AgentConfig.fromEnv(realEnv(AgentConfig.ENV_MODEL to model)) }
            assertEquals("KIVRA_AGENT_MODEL must be a model id (letters, digits, dots, hyphens)", e.message, model)
        }
    }

    @Test
    fun modelAndBudgetAreIgnoredWhenTheStubIsUsed() {
        // They are read only for KIVRA_AGENT_EXECUTOR=real, so an invalid value does not stop the stub.
        val config = AgentConfig.fromEnv(env(AgentConfig.ENV_MAX_BUDGET_USD to "999", AgentConfig.ENV_MODEL to "BAD MODEL"))
        assertEquals(null, config.realExecutor)
    }

    @Test
    fun debugKeepIsOffByDefaultAndOnlyOneEnablesIt() {
        assertFalse(AgentConfig.fromEnv(realEnv()).realExecutor!!.debugKeep)
        assertFalse(AgentConfig.fromEnv(realEnv(AttemptDebugKeep.ENV_DEBUG_KEEP to "0")).realExecutor!!.debugKeep)
        assertTrue(AgentConfig.fromEnv(realEnv(AttemptDebugKeep.ENV_DEBUG_KEEP to "1")).realExecutor!!.debugKeep)
    }

    @Test
    fun debugKeepRejectsAnyOtherValueByNameOnly() {
        for (value in listOf("yes", "true", "2", "on", " 11 ")) {
            val e = assertFailsWith<ConfigException> { AgentConfig.fromEnv(realEnv(AttemptDebugKeep.ENV_DEBUG_KEEP to value)) }
            assertEquals("KIVRA_AGENT_DEBUG_KEEP must be 1 or 0", e.message, value)
        }
    }

    @Test
    fun debugKeepIsIgnoredWhenTheStubIsUsed() {
        // Read only for KIVRA_AGENT_EXECUTOR=real, so even an invalid value does not stop the stub.
        assertEquals(null, AgentConfig.fromEnv(env(AttemptDebugKeep.ENV_DEBUG_KEEP to "yes")).realExecutor)
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
