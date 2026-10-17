package net.kigawa.kivra.agent.docker

/**
 * Builds the argument list of one `docker run` (the arguments after the `docker` binary). The list is an allowlist shape: a fixed set
 * of options in a fixed order, each as one `--option=value` token, one work mount, and the image last. No shell is involved.
 *
 * The output is checked again by [DockerArgsGuard] right before it runs. This builder is the first line of that double check.
 * The task text is not an input: [DockerRunSpec] has no field for it, so it cannot reach an argument or an environment value. The
 * command after the image is [CliCommand.argv], a constant shape; the task is read from the work directory ([TaskFile]).
 */
class DockerCommandBuilder(private val settings: DockerSettings) {

    fun build(spec: DockerRunSpec): List<String> {
        if (spec.image !in settings.allowedImages) throw DockerSpecException("image is not allowed")
        if (spec.network !in settings.allowedNetworks) throw DockerSpecException("network is not allowed")
        val workDir = WorkDirPolicy.validate(spec.workDir, settings.workRoot)
        val proxy = spec.proxyUrl
        return listOf(
            "run",
            "--rm",
            "--name=${spec.containerName}",
            "--user=${DockerConstants.USER}",
            "--read-only",
            "--tmpfs=${DockerConstants.TMPFS_TMP}",
            "--tmpfs=${DockerConstants.TMPFS_HOME}",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--pids-limit=${spec.pidsLimit}",
            "--memory=${spec.memory}",
            "--memory-swap=${spec.memory}",
            "--cpus=${spec.cpus}",
            "--network=${spec.network}",
            "--mount=type=bind,source=$workDir,target=${DockerConstants.WORKDIR}",
            "--workdir=${DockerConstants.WORKDIR}",
            "--label=${DockerConstants.LABEL_AGENT_KEY}=${DockerConstants.LABEL_AGENT_VALUE}",
            "--label=${DockerConstants.LABEL_RUN_KEY}=${spec.runId}",
            "--stop-timeout=${spec.stopTimeoutSeconds}",
            "--env=ANTHROPIC_BASE_URL=$proxy${DockerConstants.PROXY_PATH}",
            "--env=ANTHROPIC_API_KEY=${DockerConstants.DUMMY_API_KEY}",
            "--env=HTTPS_PROXY=$proxy",
            "--env=HTTP_PROXY=$proxy",
            "--env=NO_PROXY=${DockerConstants.NO_PROXY}",
            "--env=JAVA_TOOL_OPTIONS=${ProxyEnv.javaToolOptions(proxy)}",
            spec.image,
        ) + CliCommand.argv(spec.maxTurns)
    }
}
