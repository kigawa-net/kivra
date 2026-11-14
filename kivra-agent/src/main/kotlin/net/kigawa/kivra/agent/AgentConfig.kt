package net.kigawa.kivra.agent

import java.math.BigDecimal
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerPatterns
import net.kigawa.kivra.agent.docker.DockerSettings
import net.kigawa.kivra.agent.k8s.K8sNames
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
    /** Where the containers of the real executor run. The default is docker. See [Backend]. */
    val backend: Backend,
) {
    /** One attempt at a time. Fixed, not configurable. */
    val maxConcurrent: Int get() = MAX_CONCURRENT

    override fun toString(): String = "AgentConfig(***)"

    /** The executor of an attempt. REAL is enabled only by `KIVRA_AGENT_EXECUTOR=real` (see the runbook, section 6.10). */
    enum class ExecutorMode { STUB, REAL }

    /**
     * Where the containers run (`KIVRA_AGENT_BACKEND`). DOCKER is the default. K8S runs one Job per run (ADR 0010 追補 7, PR 5c). The
     * settings of the chosen backend are read only when `KIVRA_AGENT_EXECUTOR=real`.
     */
    enum class Backend { DOCKER, K8S }

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
        const val ENV_BACKEND = "KIVRA_AGENT_BACKEND"
        const val ENV_DOCKER_BIN = "KIVRA_AGENT_DOCKER_BIN"
        const val ENV_DOCKER_HOST = "KIVRA_AGENT_DOCKER_HOST"
        const val ENV_DOCKER_NETWORK = "KIVRA_AGENT_DOCKER_NETWORK"
        const val ENV_DOCKER_IMAGE = "KIVRA_AGENT_DOCKER_IMAGE"
        const val ENV_DOCKER_PROXY_URL = "KIVRA_AGENT_DOCKER_PROXY_URL"
        const val ENV_K8S_NAMESPACE = "KIVRA_AGENT_K8S_NAMESPACE"
        const val ENV_K8S_IMAGE = "KIVRA_AGENT_K8S_IMAGE"
        const val ENV_K8S_PROXY_URL = "KIVRA_AGENT_K8S_PROXY_URL"
        const val ENV_GIT_BIN = "KIVRA_AGENT_GIT_BIN"
        const val ENV_REPO = "KIVRA_AGENT_REPO"
        const val ENV_MODEL = "KIVRA_AGENT_MODEL"
        const val ENV_MAX_BUDGET_USD = "KIVRA_AGENT_MAX_BUDGET_USD"
        const val ENV_NEW_ERROR_CODES = "KIVRA_AGENT_NEW_ERROR_CODES"
        const val ENV_MONITOR_EVENTS = "KIVRA_AGENT_MONITOR_EVENTS"

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
            val backend = backend(env)
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
                realExecutor = if (executorMode == ExecutorMode.REAL) realExecutorSettings(env, clientId, backend) else null,
                backend = backend,
            )
        }

        /**
         * Read on every start, also with the stub. `docker` (the default) or unset is docker, and `k8s` is the Kubernetes backend. No other
         * value is accepted. The message does not show the value.
         */
        private fun backend(env: Map<String, String>): AgentConfig.Backend = when (optional(env, ENV_BACKEND)) {
            null, "docker" -> AgentConfig.Backend.DOCKER
            "k8s" -> AgentConfig.Backend.K8S
            else -> throw ConfigException("$ENV_BACKEND must be docker or k8s")
        }

        /**
         * Read only for `KIVRA_AGENT_EXECUTOR=real`. The settings of [backend] only: docker reads the `KIVRA_AGENT_DOCKER_*` and git ones,
         * k8s reads the `KIVRA_AGENT_K8S_*` ones (ADR 0010 追補 7, PR 5c). The proxy, the model, the budget and the repository are common.
         * The repository is `owner/repo`.
         */
        private fun realExecutorSettings(env: Map<String, String>, clientId: String, backend: AgentConfig.Backend): RealExecutorSettings {
            val proxyEnv = if (backend == AgentConfig.Backend.K8S) ENV_K8S_PROXY_URL else ENV_DOCKER_PROXY_URL
            val proxyUrl = required(env, proxyEnv)
            val proxyOk = if (backend == AgentConfig.Backend.K8S) DockerPatterns.isK8sProxyUrl(proxyUrl) else DockerPatterns.PROXY_URL.matches(proxyUrl)
            if (!proxyOk) throw ConfigException("$proxyEnv must be http://host:port")
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
            return when (backend) {
                AgentConfig.Backend.DOCKER -> RealExecutorSettings(
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
                    newErrorCodes = newErrorCodes(env),
                    monitorEvents = monitorEvents(env),
                )
                AgentConfig.Backend.K8S -> RealExecutorSettings(
                    image = k8sImage(env),
                    proxyUrl = proxyUrl,
                    repo = repo,
                    model = model,
                    budgetUsd = budgetUsd,
                    debugKeep = debugKeep(env),
                    newErrorCodes = newErrorCodes(env),
                    monitorEvents = monitorEvents(env),
                    k8s = K8sExecutorSettings(
                        namespace = k8sNamespace(env),
                        ownerLabel = K8sNames.ownerLabel(clientId),
                    ),
                )
            }
        }

        /** A DNS-1123 label, as a namespace name. The message names the variable only. */
        private fun k8sNamespace(env: Map<String, String>): String {
            val namespace = required(env, ENV_K8S_NAMESPACE)
            if (!K8sNames.isDns1123Label(namespace)) throw ConfigException("$ENV_K8S_NAMESPACE must be a Kubernetes namespace name")
            return namespace
        }

        /** The run image of the k8s backend, in the same form as the docker image. */
        private fun k8sImage(env: Map<String, String>): String {
            val image = required(env, ENV_K8S_IMAGE)
            if (image.length > 255 || !DockerPatterns.IMAGE.matches(image)) throw ConfigException("$ENV_K8S_IMAGE must be an image name")
            return image
        }

        /**
         * Read only for `KIVRA_AGENT_EXECUTOR=real`. `1` sends the run's monitor events to kivra (ADR 0012). `0` or unset sends none, and
         * the attempt runs as before. Any other value refuses the start. Turn `1` on only after the kivra that accepts the events is deployed.
         */
        private fun monitorEvents(env: Map<String, String>): Boolean = when (optional(env, ENV_MONITOR_EVENTS)) {
            null, "0" -> false
            "1" -> true
            else -> throw ConfigException("$ENV_MONITOR_EVENTS must be 1 or 0")
        }

        /**
         * Read only for `KIVRA_AGENT_EXECUTOR=real`. `1` sends EMPTY_PATCH, BUDGET_EXCEEDED and OVERLOADED (Issue #231). `0` or unset
         * sends the codes kivra has always accepted. Any other value refuses the start. Turn `1` on only after the kivra that accepts
         * the new codes is deployed.
         */
        private fun newErrorCodes(env: Map<String, String>): Boolean = when (optional(env, ENV_NEW_ERROR_CODES)) {
            null, "0" -> false
            "1" -> true
            else -> throw ConfigException("$ENV_NEW_ERROR_CODES must be 1 or 0")
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
