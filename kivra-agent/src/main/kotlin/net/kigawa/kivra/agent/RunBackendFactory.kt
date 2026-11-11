package net.kigawa.kivra.agent

import java.nio.file.Path
import net.kigawa.kivra.agent.backend.RunBackend
import net.kigawa.kivra.agent.docker.DockerLauncher
import net.kigawa.kivra.agent.docker.DockerRunBackend
import net.kigawa.kivra.agent.docker.DockerRunner
import net.kigawa.kivra.agent.docker.DockerSettings
import net.kigawa.kivra.agent.docker.ProcessDockerLauncher

/**
 * The run backend for `KIVRA_AGENT_BACKEND` (ADR 0010 追補 7, PR 2). docker is the default and the only one that runs. `k8s` is refused at
 * start by [AgentConfig], and it fails here too, so it is never taken for docker.
 *
 * The docker settings are checked here, before the executor is built. An invalid setting throws [net.kigawa.kivra.agent.docker.DockerSpecException]
 * (exit 2 in `Main`), as it did in the executor's constructor before.
 */
internal fun createRunBackend(
    backend: AgentConfig.Backend,
    settings: RealExecutorSettings,
    workRoot: Path,
    launcher: DockerLauncher = ProcessDockerLauncher,
    pollMillis: Long = DockerRunner.DEFAULT_POLL_MILLIS,
): RunBackend = when (backend) {
    AgentConfig.Backend.DOCKER -> DockerRunBackend(
        DockerSettings(
            dockerBinary = settings.dockerBinary,
            dockerHost = settings.dockerHost,
            workRoot = workRoot,
            allowedNetworks = setOf(settings.network),
            allowedImages = setOf(settings.image),
            allowedModels = setOf(settings.model),
        ),
        launcher = launcher,
        pollMillis = pollMillis,
    )
    AgentConfig.Backend.K8S -> throw ConfigException("${AgentConfig.ENV_BACKEND} names a backend that is not implemented yet")
}
