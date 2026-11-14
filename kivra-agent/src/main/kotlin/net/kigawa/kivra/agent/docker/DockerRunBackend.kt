package net.kigawa.kivra.agent.docker

import net.kigawa.kivra.agent.backend.RunBackend
import net.kigawa.kivra.agent.backend.RunResult
import net.kigawa.kivra.agent.backend.RunSpec
import net.kigawa.kivra.agent.backend.StdoutLine

/**
 * The docker implementation of [RunBackend] (ADR 0010 追補 7, PR 2). It maps a [RunSpec] to a [DockerRunSpec] (the docker-only values keep
 * their defaults) and runs it with [DockerRunner]. A runner is made for each call, and a run gets its own output limit, as the executor
 * did before this interface existed. Nothing about the container changes.
 */
class DockerRunBackend(
    private val settings: DockerSettings,
    private val launcher: DockerLauncher = ProcessDockerLauncher,
    private val pollMillis: Long = DockerRunner.DEFAULT_POLL_MILLIS,
) : RunBackend {
    override fun validate(spec: RunSpec) {
        toDockerSpec(spec)
    }

    override fun run(spec: RunSpec, onStdoutLine: ((StdoutLine) -> Unit)?, cancelRequested: () -> Boolean): RunResult {
        val runner = DockerRunner(settings, launcher = launcher, pollMillis = pollMillis, outputLimitBytes = spec.outputLimitBytes)
        return runner.run(toDockerSpec(spec), onStdoutLine, cancelRequested)
    }

    override fun cleanupOrphans(activeRunId: String?): Int? =
        DockerRunner(settings, launcher = launcher, pollMillis = pollMillis).cleanupOrphans(activeRunId)

    private fun toDockerSpec(spec: RunSpec): DockerRunSpec = DockerRunSpec(
        runId = spec.runId,
        attemptId = spec.attemptId,
        image = spec.image,
        workDir = spec.workDir ?: throw DockerSpecException("workDir is required"),
        network = spec.network ?: throw DockerSpecException("network is required"),
        proxyUrl = spec.proxyUrl,
        timeout = spec.timeout,
        maxTurns = spec.maxTurns,
        model = spec.model,
        budgetUsd = spec.budgetUsd,
    )
}
