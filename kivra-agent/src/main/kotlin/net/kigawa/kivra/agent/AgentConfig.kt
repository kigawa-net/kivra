package net.kigawa.kivra.agent

import java.math.BigDecimal
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerPatterns
import net.kigawa.kivra.agent.docker.DockerSettings
import net.kigawa.kivra.agent.workdir.RepoSource

/**
 * A setting is missing or invalid. The message names the variable only, never its value: a URL, a path or a secret must not reach
 * the journal through an error.
 */
class ConfigException(message: String) : IllegalArgumentException(message)

/**
 * Settings of kivra-agent, read from environment variables (ADR 0010, design #187 section 7.2). Environment variables are used, not
 * a TOML file: kivra already configures itself this way, systemd passes them with an EnvironmentFile, and it avoids a new parser
 * dependency. Only the *path* of the client secret is a setting; the secret itself is read from that file and never stored here.
 *
 * Validation is fail-closed: [fromEnv] throws [ConfigException] for any missing or invalid value. [toString] shows no value.
 */
class AgentConfig private constructor(
    /** The kivra base URL (https; http only for localhost). */
    val kivraUrl: String,
    /** The Keycloak token endpoint (https; http only for localhost). */
    val tokenUrl: String,
    /** The Keycloak client id of this PC (for example `kivra-agent-<device>`). */
    val clientId: String,
    /** A file holding the client secret. It must not be readable by group or others. */
    val clientSecretFile: Path,
    /** The work directory. The lock file and, later, the runs live here. */
    val workDir: Path,
    /** The wait between two claims when there is nothing to do. */
    val pollInterval: Duration,
    /** The wait between two heartbeats of a running attempt. */
    val heartbeatInterval: Duration,
    /** While this file exists, the agent does not claim (the kill switch). */
    val stopFile: Path,
    /** Which executor runs the attempts. The default is the stub, which fails each attempt with NEEDS_HUMAN. */
    val executorMode: ExecutorMode,
    /** The settings of the real executor. Null unless [executorMode] is [ExecutorMode.REAL]. */
    val realExecutor: RealExecutorSettings?,
) {
    /** One attempt at a time. Fixed, not configurable. */
    val maxConcurrent: Int get() = MAX_CONCURRENT

    override fun toString(): String = "AgentConfig(***)"

    /** The executor of an attempt. REAL is enabled only by `KIVRA_AGENT_EXECUTOR=real` (see the runbook, section 6.10). */
    enum class ExecutorMode { STUB, REAL }

    companion object {
        const val ENV_KIVRA_URL = "KIVRA_AGENT_KIVRA_URL"
        const val ENV_TOKEN_URL = "KIVRA_AGENT_TOKEN_URL"
        const val ENV_CLIENT_ID = "KIVRA_AGENT_CLIENT_ID"
        const val ENV_CLIENT_SECRET_FILE = "KIVRA_AGENT_CLIENT_SECRET_FILE"
        const val ENV_WORK_DIR = "KIVRA_AGENT_WORK_DIR"
        const val ENV_POLL_INTERVAL_MS = "KIVRA_AGENT_POLL_INTERVAL_MS"
        const val ENV_HEARTBEAT_INTERVAL_MS = "KIVRA_AGENT_HEARTBEAT_INTERVAL_MS"
        const val ENV_STOP_FILE = "KIVRA_AGENT_STOP_FILE"
        const val ENV_EXECUTOR = "KIVRA_AGENT_EXECUTOR"
        const val ENV_DOCKER_BIN = "KIVRA_AGENT_DOCKER_BIN"
        const val ENV_DOCKER_HOST = "KIVRA_AGENT_DOCKER_HOST"
        const val ENV_DOCKER_NETWORK = "KIVRA_AGENT_DOCKER_NETWORK"
        const val ENV_DOCKER_IMAGE = "KIVRA_AGENT_DOCKER_IMAGE"
        const val ENV_DOCKER_PROXY_URL = "KIVRA_AGENT_DOCKER_PROXY_URL"
        const val ENV_GIT_BIN = "KIVRA_AGENT_GIT_BIN"
        const val ENV_REPO = "KIVRA_AGENT_REPO"
        const val ENV_MODEL = "KIVRA_AGENT_MODEL"
        const val ENV_MAX_BUDGET_USD = "KIVRA_AGENT_MAX_BUDGET_USD"

        const val MAX_CONCURRENT = 1
        const val DEFAULT_STOP_FILE_NAME = "STOP"

        const val POLL_MIN_MS = 1_000L
        const val POLL_MAX_MS = 60_000L
        const val POLL_DEFAULT_MS = 5_000L
        const val HEARTBEAT_MIN_MS = 5_000L
        const val HEARTBEAT_MAX_MS = 120_000L
        const val HEARTBEAT_DEFAULT_MS = 30_000L

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
        private val CLIENT_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val BUDGET_INPUT = Regex("[0-9]{1,2}(?:\\.[0-9]{1,2})?")

        fun fromEnv(env: Map<String, String>): AgentConfig {
            val kivraUrl = httpsUrl(env, ENV_KIVRA_URL)
            val tokenUrl = httpsUrl(env, ENV_TOKEN_URL)
            val clientId = required(env, ENV_CLIENT_ID)
            if (!CLIENT_ID.matches(clientId)) throw ConfigException("$ENV_CLIENT_ID is not a valid client id")
            val clientSecretFile = absolutePath(env, ENV_CLIENT_SECRET_FILE)
            val workDir = absolutePath(env, ENV_WORK_DIR)
            val stopFile = optional(env, ENV_STOP_FILE)?.let { absolutePath(env, ENV_STOP_FILE) }
                ?: workDir.resolve(DEFAULT_STOP_FILE_NAME)
            val executorMode = when (optional(env, ENV_EXECUTOR)) {
                null, "stub" -> ExecutorMode.STUB
                "real" -> ExecutorMode.REAL
                else -> throw ConfigException("$ENV_EXECUTOR must be stub or real")
            }
            return AgentConfig(
                kivraUrl = kivraUrl,
                tokenUrl = tokenUrl,
                clientId = clientId,
                clientSecretFile = clientSecretFile,
                workDir = workDir,
                pollInterval = Duration.ofMillis(millis(env, ENV_POLL_INTERVAL_MS, POLL_DEFAULT_MS, POLL_MIN_MS, POLL_MAX_MS)),
                heartbeatInterval = Duration.ofMillis(
                    millis(env, ENV_HEARTBEAT_INTERVAL_MS, HEARTBEAT_DEFAULT_MS, HEARTBEAT_MIN_MS, HEARTBEAT_MAX_MS),
                ),
                stopFile = stopFile,
                executorMode = executorMode,
                realExecutor = if (executorMode == ExecutorMode.REAL) realExecutorSettings(env) else null,
            )
        }

        /** Read only for `KIVRA_AGENT_EXECUTOR=real`. The docker and git paths are absolute; the repository is `owner/repo`. */
        private fun realExecutorSettings(env: Map<String, String>): RealExecutorSettings {
            val proxyUrl = required(env, ENV_DOCKER_PROXY_URL)
            if (!DockerPatterns.PROXY_URL.matches(proxyUrl)) throw ConfigException("$ENV_DOCKER_PROXY_URL must be http://host:port")
            val model = optional(env, ENV_MODEL) ?: CliCommand.DEFAULT_MODEL
            if (!DockerPatterns.MODEL.matches(model)) throw ConfigException("$ENV_MODEL must be a model id (letters, digits, dots, hyphens)")
            val budgetUsd = budgetUsd(env)
            val repoName = required(env, ENV_REPO)
            val parts = repoName.split('/')
            val repo = try {
                if (parts.size != 2) throw IllegalArgumentException()
                RepoSource.PublicGithub(parts[0], parts[1])
            } catch (e: IllegalArgumentException) {
                throw ConfigException("$ENV_REPO must be owner/repo")
            }
            return RealExecutorSettings(
                dockerBinary = absolutePath(env, ENV_DOCKER_BIN),
                dockerHost = optional(env, ENV_DOCKER_HOST),
                network = required(env, ENV_DOCKER_NETWORK),
                image = required(env, ENV_DOCKER_IMAGE),
                proxyUrl = proxyUrl,
                gitBinary = absolutePath(env, ENV_GIT_BIN),
                repo = repo,
                model = model,
                budgetUsd = budgetUsd,
                debugKeep = debugKeep(env),
            )
        }

        /**
         * Read only for `KIVRA_AGENT_EXECUTOR=real`. `1` keeps the debug output of a failed attempt, `0` or unset does not. Any other value
         * refuses the start (see `AttemptDebugKeep`).
         */
        private fun debugKeep(env: Map<String, String>): Boolean = when (optional(env, AttemptDebugKeep.ENV_DEBUG_KEEP)) {
            null, "0" -> false
            "1" -> true
            else -> throw ConfigException("${AttemptDebugKeep.ENV_DEBUG_KEEP} must be 1 or 0")
        }

        /**
         * The cost limit of one attempt, in dollars. It is written with exactly two decimals (`0.50`), so an input of `0.5` is accepted
         * and written as `0.50`. The range is 0.01 to 20.00.
         */
        private fun budgetUsd(env: Map<String, String>): String {
            val value = optional(env, ENV_MAX_BUDGET_USD) ?: return CliCommand.DEFAULT_BUDGET_USD
            val amount = if (BUDGET_INPUT.matches(value)) BigDecimal(value) else null
            if (amount == null || amount < BigDecimal("0.01") || amount > BigDecimal("20.00")) {
                throw ConfigException("$ENV_MAX_BUDGET_USD must be a dollar amount from 0.01 to 20.00 with at most two decimals")
            }
            return amount.setScale(2).toPlainString()
        }

        private fun optional(env: Map<String, String>, name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun required(env: Map<String, String>, name: String): String =
            optional(env, name) ?: throw ConfigException("$name is required")

        private fun absolutePath(env: Map<String, String>, name: String): Path {
            val value = required(env, name)
            val path = try {
                Path.of(value)
            } catch (e: InvalidPathException) {
                throw ConfigException("$name is not a valid path")
            }
            if (!path.isAbsolute) throw ConfigException("$name must be an absolute path")
            return path.normalize()
        }

        /** https only. http is allowed for localhost, for tests. No user info, query or fragment. */
        private fun httpsUrl(env: Map<String, String>, name: String): String {
            val value = required(env, name)
            val uri = try {
                URI(value)
            } catch (e: Exception) {
                throw ConfigException("$name is not a valid URL")
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            val schemeOk = scheme == "https" || (scheme == "http" && host in LOCAL_HOSTS)
            val shapeOk = host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
            if (!schemeOk || !shapeOk) throw ConfigException("$name must be an https URL (http is allowed for localhost only)")
            return value
        }

        private fun millis(env: Map<String, String>, name: String, default: Long, min: Long, max: Long): Long {
            val value = optional(env, name) ?: return default
            val parsed = value.toLongOrNull()
            if (parsed == null || parsed !in min..max) {
                throw ConfigException("$name must be an integer of milliseconds between $min and $max")
            }
            return parsed
        }
    }
}
