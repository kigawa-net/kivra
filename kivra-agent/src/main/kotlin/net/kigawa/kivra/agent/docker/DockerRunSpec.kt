package net.kigawa.kivra.agent.docker

import java.nio.file.Path
import java.time.Duration

/**
 * What one container run needs (ADR 0010, design #187 section 3.3). Every string is validated here, so nothing reaches the docker
 * command line unchecked. There is deliberately no field for free text: the task title and body never become an argument, an
 * environment value or a container name. They go to the work directory as a file ([TaskFile]).
 *
 * The container name is `kivra-run-<runId>-<attemptId>`. The labels are derived by the builder from [runId].
 */
class DockerRunSpec(
    val runId: String,
    val attemptId: String,
    val image: String,
    /** The host path of the work directory. It is resolved and checked against the work root by [WorkDirPolicy] at build time. */
    val workDir: Path,
    /** The internal network name. It must be in [DockerSettings.allowedNetworks]. */
    val network: String,
    /** The egress proxy, `http://host:port`. The container reaches the API only through it. */
    val proxyUrl: String,
    /** The run's time limit. The runner kills the container when it passes. */
    val timeout: Duration,
    val memory: String = DockerConstants.DEFAULT_MEMORY,
    val cpus: String = DockerConstants.DEFAULT_CPUS,
    val pidsLimit: Int = DockerConstants.DEFAULT_PIDS_LIMIT,
    val stopTimeoutSeconds: Int = DockerConstants.DEFAULT_STOP_TIMEOUT_SECONDS,
    /** The `--max-turns` of the CLI command (see [CliCommand]). The only value of the command that a caller chooses. */
    val maxTurns: Int = CliCommand.DEFAULT_MAX_TURNS,
    /** The `--model` of the CLI. It must be in [DockerSettings.allowedModels]. */
    val model: String = CliCommand.DEFAULT_MODEL,
    /** The `--max-budget-usd` of the CLI: a dollar amount in the fixed two-decimal form, from 0.01 to 20.00 (see [DockerPatterns]). */
    val budgetUsd: String = CliCommand.DEFAULT_BUDGET_USD,
) {
    /** `kivra-run-<runId>-<attemptId>`. Validated in [init], so it is safe as a docker name. */
    val containerName: String = "${DockerConstants.CONTAINER_PREFIX}$runId-$attemptId"

    init {
        requireId("runId", runId)
        requireId("attemptId", attemptId)
        if (image.length > 255 || !DockerPatterns.IMAGE.matches(image)) throw DockerSpecException("image is invalid")
        if (!DockerPatterns.NETWORK.matches(network) || network in DockerPatterns.RESERVED_NETWORKS) {
            throw DockerSpecException("network is invalid")
        }
        if (!DockerPatterns.PROXY_URL.matches(proxyUrl)) throw DockerSpecException("proxyUrl must be http://host:port")
        WorkDirPolicy.checkSyntax(workDir)
        if (timeout < MIN_TIMEOUT || timeout > MAX_TIMEOUT) throw DockerSpecException("timeout is out of range")
        if (!DockerPatterns.MEMORY.matches(memory)) throw DockerSpecException("memory is invalid")
        if (!DockerPatterns.CPUS.matches(cpus)) throw DockerSpecException("cpus is invalid")
        if (pidsLimit !in 1..MAX_PIDS_LIMIT) throw DockerSpecException("pidsLimit is out of range")
        if (stopTimeoutSeconds !in 1..MAX_STOP_TIMEOUT_SECONDS) throw DockerSpecException("stopTimeoutSeconds is out of range")
        if (maxTurns !in 1..CliCommand.MAX_MAX_TURNS) throw DockerSpecException("maxTurns is out of range")
        if (!DockerPatterns.MODEL.matches(model)) throw DockerSpecException("model is invalid")
        if (!DockerPatterns.isBudgetInRange(budgetUsd)) throw DockerSpecException("budgetUsd is out of range")
    }

    override fun toString(): String = "DockerRunSpec(runId=$runId, attemptId=$attemptId)"

    private companion object {
        val MIN_TIMEOUT: Duration = Duration.ofSeconds(1)
        val MAX_TIMEOUT: Duration = Duration.ofHours(24)
        const val MAX_PIDS_LIMIT = 4096
        const val MAX_STOP_TIMEOUT_SECONDS = 120

        fun requireId(field: String, value: String) {
            if (!DockerPatterns.ID.matches(value)) throw DockerSpecException("$field is invalid")
        }
    }
}
