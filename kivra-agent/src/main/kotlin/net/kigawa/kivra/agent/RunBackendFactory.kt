package net.kigawa.kivra.agent

import java.io.IOException
import java.nio.file.Path
import java.security.GeneralSecurityException
import net.kigawa.kivra.agent.backend.RunBackend
import net.kigawa.kivra.agent.docker.DockerLauncher
import net.kigawa.kivra.agent.docker.DockerRunBackend
import net.kigawa.kivra.agent.docker.DockerRunner
import net.kigawa.kivra.agent.docker.DockerSettings
import net.kigawa.kivra.agent.docker.DockerSpecException
import net.kigawa.kivra.agent.docker.ProcessDockerLauncher
import net.kigawa.kivra.agent.k8s.HttpKubeApi
import net.kigawa.kivra.agent.k8s.K8sRunBackend
import net.kigawa.kivra.agent.k8s.K8sSettings
import net.kigawa.kivra.agent.k8s.K8sSpecException
import net.kigawa.kivra.agent.k8s.KubeApi
import net.kigawa.kivra.agent.k8s.KubeApiException

/**
 * The run backend for `KIVRA_AGENT_BACKEND` (ADR 0010 追補 7, PR 2 and PR 5c). docker is the default. k8s runs one Job per run in the
 * namespace of [RealExecutorSettings.k8s].
 *
 * The settings of the chosen backend are checked here, before the executor is built. An invalid setting throws (a [DockerSpecException]
 * for docker, a [ConfigException] for k8s), and `Main` exits with 2, so no attempt is claimed. For k8s the agent must run in the cluster:
 * when the ServiceAccount is not there, the start fails the same way (see [HttpKubeApi.inCluster]). No message holds a value.
 *
 * [podInputs] must be the instance that the executor is given too (see `Main.executorFor`). The k8s backend reads each attempt's input
 * from it; a different instance would fail every run with "no input is registered".
 */
internal fun createRunBackend(
    backend: AgentConfig.Backend,
    settings: RealExecutorSettings,
    workRoot: Path,
    podInputs: AttemptPodInputs = AttemptPodInputs(),
    launcher: DockerLauncher = ProcessDockerLauncher,
    /** The poll interval of the backend in use. Null is that backend's default. */
    pollMillis: Long? = null,
    kubeApi: () -> KubeApi = { HttpKubeApi.inCluster() },
): RunBackend = when (backend) {
    AgentConfig.Backend.DOCKER -> DockerRunBackend(
        DockerSettings(
            dockerBinary = settings.dockerBinary ?: throw DockerSpecException("dockerBinary is required"),
            dockerHost = settings.dockerHost,
            workRoot = workRoot,
            allowedNetworks = setOf(settings.network ?: throw DockerSpecException("network is required")),
            allowedImages = setOf(settings.image),
            allowedModels = setOf(settings.model),
        ),
        launcher = launcher,
        pollMillis = pollMillis ?: DockerRunner.DEFAULT_POLL_MILLIS,
    )
    AgentConfig.Backend.K8S -> {
        val k8s = settings.k8s ?: throw ConfigException("${AgentConfig.ENV_K8S_NAMESPACE} is required for the k8s backend")
        val k8sSettings = try {
            K8sSettings(k8s.namespace, k8s.ownerLabel, setOf(settings.image), setOf(settings.model))
        } catch (e: K8sSpecException) {
            throw ConfigException("the k8s settings are invalid (${AgentConfig.ENV_K8S_NAMESPACE}, ${AgentConfig.ENV_K8S_IMAGE}, ${AgentConfig.ENV_MODEL})")
        }
        val api = try {
            kubeApi()
        } catch (e: KubeApiException) {
            throw ConfigException("the k8s backend must run in the cluster (the ServiceAccount is not available)")
        } catch (e: IOException) {
            throw ConfigException("the k8s backend must run in the cluster (the ServiceAccount is not readable)")
        } catch (e: GeneralSecurityException) {
            throw ConfigException("the k8s backend must run in the cluster (the cluster CA is not readable)")
        }
        K8sRunBackend(k8sSettings, api, podInputs, pollMillis = pollMillis ?: K8sRunBackend.DEFAULT_POLL_MILLIS)
    }
}
