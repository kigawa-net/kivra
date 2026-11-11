package net.kigawa.kivra.agent.k8s

import net.kigawa.kivra.agent.docker.DockerConstants

/**
 * The fixed values of the Job manifest (ADR 0010 追補 7 §4). The builder writes them and the guard compares with them. A value here
 * that also exists on the docker side is written as the docker constant (for example the uid, the env names, the label keys), so that
 * a change to one side is a compile-time change to both. `K8sDockerCorrespondenceTest` checks the values that differ in form (memory
 * units, tmpfs sizes, the stop timeout).
 */
internal object K8sConstants {
    const val API_VERSION = "batch/v1"
    const val KIND = "Job"

    /** The one container of the Pod. */
    const val CONTAINER_NAME = "agent"

    /**
     * The docker side never pulls at run time (`--pull` is a forbidden option). The k8s side has no such option, so the value is fixed:
     * `IfNotPresent` pulls only when the node does not have the image, which is the docker default (`--pull=missing`). `Always` would
     * change the image of a run without a change to the allowlist, and `Never` would fail every run on a node that has not got it yet.
     */
    const val IMAGE_PULL_POLICY = "IfNotPresent"

    /**
     * The in-Pod entrypoint (ADR 0010 追補 7, PR 4). It is the first token of the container command; the CLI argv follows. It is a launcher
     * of `net.kigawa.kivra.agent.k8s.pod.PodEntrypointKt` that the runtime image must provide (the image change is not part of PR 4).
     */
    const val POD_ENTRYPOINT = "/usr/local/bin/kivra-pod-entrypoint"

    /** The working directory of the container (the emptyDir `work`), as [DockerConstants.WORKDIR]. */
    const val WORKDIR = DockerConstants.WORKDIR

    /** Kept after the Job finished (the agent deletes the Job itself after it has read the logs and the result file; this is the backstop). An operator choice, not a docker value. */
    const val TTL_SECONDS_AFTER_FINISHED = 3600

    /** The most `activeDeadlineSeconds` a Job may have: 24 hours, as the docker side's time limit. */
    const val MAX_ACTIVE_DEADLINE_SECONDS = 86_400L

    /** The most memory a limit may have: 8 Gi. The docker side has no upper bound; this is the k8s side's own bound. */
    const val MAX_MEMORY_MI = 8192L

    /** The most cpu a limit may have. */
    const val MAX_CPU = 8

    /** Requests, which are the smaller of the limit and these. They are the scheduling hint; the limits are the bound. */
    const val REQUEST_MEMORY_MI = 2048L
    const val REQUEST_CPU = "1"

    /** The emptyDir volumes, in the order of the manifest: name, mount path, size limit. There are no other volumes. */
    const val VOLUME_WORK = "work"
    const val VOLUME_TMP = "tmp"
    const val VOLUME_HOME = "home"
    const val PATH_TMP = "/tmp"
    const val PATH_HOME = "/home/agent"

    /** The work volume has no docker counterpart with a limit (a bind mount of the host). The k8s side needs a bound, so it is set here. */
    const val SIZE_WORK = "10Gi"

    /** The docker tmpfs `size=512m` and `size=2g` (see [DockerConstants.TMPFS_TMP] and [DockerConstants.TMPFS_HOME]). */
    const val SIZE_TMP = "512Mi"
    const val SIZE_HOME = "2Gi"

    /** The labels of the Job and the Pod template. The keys of the first and the third are the docker label keys. */
    const val LABEL_AGENT_KEY = DockerConstants.LABEL_AGENT_KEY
    const val LABEL_AGENT_VALUE = DockerConstants.LABEL_AGENT_VALUE
    const val LABEL_RUN_KEY = DockerConstants.LABEL_RUN_KEY
    const val LABEL_ATTEMPT_KEY = "kivra-attempt"

    /** The seccomp profile of the Pod. */
    const val SECCOMP_TYPE = "RuntimeDefault"

    /** The grace period before the kill, as the docker `--stop-timeout`. */
    const val TERMINATION_GRACE_SECONDS = DockerConstants.DEFAULT_STOP_TIMEOUT_SECONDS
}
