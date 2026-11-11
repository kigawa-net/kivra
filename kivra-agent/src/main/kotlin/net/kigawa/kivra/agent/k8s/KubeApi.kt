package net.kigawa.kivra.agent.k8s

import java.io.InputStream
import kotlinx.serialization.json.JsonObject

/**
 * The part of the Kubernetes API that the k8s backend uses (ADR 0010 追補 7 PR 4). [K8sRunBackend] depends on this interface only, so the
 * unit tests use a fake and never reach a cluster. The production implementation is [HttpKubeApi].
 *
 * The RBAC that the agent's ServiceAccount needs for exactly these calls, in the Job namespace (the manifests are PR 5):
 * - `batch/jobs`: `create`, `get`, `list`, `delete`
 * - `pods`: `get`, `list`
 * - `pods/log`: `get`
 * - `pods/exec`: `create` (and `get`, which the WebSocket upgrade of an exec needs)
 * Nothing else: no `secrets`, no `configmaps`, no `deletecollection`, no cluster-wide role.
 *
 * Every method blocks. A failure throws [KubeApiException], whose message is a fixed code (no URL, body or token).
 */
interface KubeApi {
    fun createJob(namespace: String, manifest: JsonObject)

    /** The state of the Job, or null when it does not exist. */
    fun jobState(namespace: String, name: String): JobState?

    /** The Jobs that match the label selector, or throws. */
    fun listJobs(namespace: String, labelSelector: String): List<JobRef>

    /** Deletes the Job and its Pods (background propagation). A missing Job is not an error. */
    fun deleteJob(namespace: String, name: String)

    /** The Pod of the Job, or null when there is none yet. */
    fun podOf(namespace: String, jobName: String): PodState?

    /** The log of the container, followed (the stream ends when the container ends or the stream is closed). */
    fun openLog(namespace: String, pod: String, container: String): InputStream

    /**
     * Runs [command] in the container and waits for its end. [stdin] is sent first, when given. The standard output is kept up to
     * [maxStdoutBytes] (more sets [ExecResult.truncated]). The result has no exit code when the exec did not finish within [timeoutMillis].
     */
    fun exec(namespace: String, pod: String, container: String, command: List<String>, stdin: ByteArray?, maxStdoutBytes: Int, timeoutMillis: Long): ExecResult
}

class KubeApiException(val code: String) : RuntimeException(code)

class JobRef(val name: String, val runId: String?)

class JobState(val succeeded: Boolean, val failed: Boolean, val deadlineExceeded: Boolean)

/** The one container of a Job's Pod. [exitCode] is set when the container has terminated. */
class PodState(val name: String, val running: Boolean, val terminated: Boolean, val exitCode: Int?)

class ExecResult(val exitCode: Int?, val stdout: ByteArray, val truncated: Boolean)
