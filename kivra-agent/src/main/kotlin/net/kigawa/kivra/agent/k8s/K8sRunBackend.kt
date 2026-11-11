package net.kigawa.kivra.agent.k8s

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import net.kigawa.kivra.agent.backend.RunBackend
import net.kigawa.kivra.agent.backend.RunOutcome
import net.kigawa.kivra.agent.backend.RunResult
import net.kigawa.kivra.agent.backend.RunSpec
import net.kigawa.kivra.agent.backend.StdoutLine
import net.kigawa.kivra.agent.docker.CliOutputLimits
import net.kigawa.kivra.agent.k8s.pod.PodInput
import net.kigawa.kivra.agent.k8s.pod.PodPaths
import net.kigawa.kivra.agent.k8s.pod.PodProtocolException
import net.kigawa.kivra.agent.k8s.pod.PodReadout
import net.kigawa.kivra.agent.k8s.pod.PodResultCodec

/** Where the repository, the commit and the task text of a run come from (the executor gives them; PR 5 wires it). */
fun interface PodInputSource {
    fun inputFor(spec: RunSpec): PodInput
}

/** A run could not be carried out (not a result of the run). The message is a fixed code. */
class K8sRunException(val code: String) : RuntimeException(code)

/**
 * The k8s implementation of [RunBackend] (ADR 0010 追補 7, PR 4). One Job per run:
 *
 * 1. The manifest is built by [K8sJobBuilder] and checked by [K8sJobGuard] right before it is sent. Nothing else reaches the cluster:
 *    this class has no other way to create a Job (the [manifestOf] hook only replaces the builder, never the guard).
 * 2. When the Pod runs, the agent sends the input file by exec ([PodPaths.INPUT]) and follows the container's log. The log is the
 *    CLI's standard output, read with the same limits as the docker side (`outputLimitBytes`, [CliOutputLimits.MAX_LINE_BYTES]).
 * 3. The in-Pod entrypoint runs the CLI, collects the change set and writes [PodPaths.RESULT]; this class reads it with an exec
 *    ("exec readout"; the change set does not depend on the log) and ends the run.
 * 4. The Job is deleted at the end of every run, whatever happened (result, timeout, cancel, output limit, error).
 *
 * The run ends with: [RunOutcome.EXITED] (the result file was read, or the Pod ended), [RunOutcome.TIMEOUT] (the spec's time limit, or the
 * Job's own deadline), [RunOutcome.CANCELLED], or [RunOutcome.OUTPUT_LIMIT]. The Pod's file is untrusted: [RunResult.podReadout] must be
 * checked by [PodReadout.Collected.finish]. [RunSpec.workDir] and [RunSpec.network] are docker values and are not used here.
 *
 * Nothing is logged here, and no error text carries a value.
 */
class K8sRunBackend(
    private val settings: K8sSettings,
    private val api: KubeApi,
    private val inputs: PodInputSource,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    /** How long the log may be quiet before it is taken as complete after the result is read. */
    private val drainIdleMillis: Long = DEFAULT_DRAIN_IDLE_MILLIS,
    private val drainMaxMillis: Long = DEFAULT_DRAIN_MAX_MILLIS,
    private val manifestOf: (K8sJobSpec) -> JsonObject = K8sJobBuilder(settings)::build,
) : RunBackend {
    private val guard = K8sJobGuard(settings)

    override fun validate(spec: RunSpec) {
        manifestFor(toJobSpec(spec))
    }

    override fun run(spec: RunSpec, onStdoutLine: ((StdoutLine) -> Unit)?, cancelRequested: () -> Boolean): RunResult {
        val jobSpec = toJobSpec(spec)
        val manifest = manifestFor(jobSpec)
        val input = inputs.inputFor(spec)
        if (input.runId != spec.runId || input.attemptId != spec.attemptId) throw K8sRunException("INPUT_MISMATCH")
        val inputBytes = PodInput.encode(input)
        if (inputBytes.size > PodInput.MAX_FILE_BYTES) throw K8sRunException("INPUT_TOO_LARGE")

        val deadline = System.nanoTime() + spec.timeout.toNanos()
        api.createJob(settings.namespace, manifest)
        var pump: LogPump? = null
        try {
            return supervise(jobSpec, spec, inputBytes, deadline, onStdoutLine, cancelRequested) { pump = it }
        } finally {
            pump?.stop()
            try {
                api.deleteJob(settings.namespace, jobSpec.jobName)
            } catch (e: KubeApiException) {
                // The Job's own deadline and ttl remove it later; the next run's cleanup removes it too.
            }
        }
    }

    override fun cleanupOrphans(activeRunId: String?): Int? {
        val jobs = try {
            api.listJobs(settings.namespace, "${K8sConstants.LABEL_AGENT_KEY}=${K8sConstants.LABEL_AGENT_VALUE}")
        } catch (e: KubeApiException) {
            return null
        }
        var removed = 0
        for (job in jobs) {
            if (activeRunId != null && job.runId == activeRunId) continue
            try {
                api.deleteJob(settings.namespace, job.name)
                removed++
            } catch (e: KubeApiException) {
                // Counted only when removed; the next cleanup tries again.
            }
        }
        return removed
    }

    private fun supervise(
        jobSpec: K8sJobSpec,
        spec: RunSpec,
        inputBytes: ByteArray,
        deadline: Long,
        onStdoutLine: ((StdoutLine) -> Unit)?,
        cancelRequested: () -> Boolean,
        setPump: (LogPump) -> Unit,
    ): RunResult {
        val ns = settings.namespace
        var pump: LogPump? = null
        var inputSent = false
        var failures = 0
        while (true) {
            if (cancelRequested()) return finish(RunOutcome.CANCELLED, null, null, pump)
            if (System.nanoTime() - deadline > 0) return finish(RunOutcome.TIMEOUT, null, null, pump)
            if (pump?.exceeded == true) return finish(RunOutcome.OUTPUT_LIMIT, null, null, pump)
            try {
                val pod = api.podOf(ns, jobSpec.jobName)
                if (pod != null && pod.terminated) {
                    val state = api.jobState(ns, jobSpec.jobName)
                    val outcome = if (state?.deadlineExceeded == true) RunOutcome.TIMEOUT else RunOutcome.EXITED
                    return finish(outcome, pod.exitCode.takeIf { outcome == RunOutcome.EXITED }, null, pump, drain = true)
                }
                if (pod != null && pod.running) {
                    if (pump == null) {
                        pump = LogPump(api.openLog(ns, pod.name, K8sConstants.CONTAINER_NAME), spec.outputLimitBytes, onStdoutLine)
                        setPump(pump)
                    }
                    if (!inputSent) {
                        sendInput(pod.name, inputBytes)
                        inputSent = true
                    } else {
                        val readout = readResult(pod.name)
                        if (readout != null) {
                            val exit = (readout as? PodReadout.Collected)?.exitStatus
                            return finish(if (pump.exceeded) RunOutcome.OUTPUT_LIMIT else RunOutcome.EXITED, exit, readout, pump, drain = true)
                        }
                    }
                } else if (pod == null && api.jobState(ns, jobSpec.jobName)?.let { it.failed || it.succeeded } == true) {
                    return finish(RunOutcome.EXITED, null, null, pump)
                }
                failures = 0
            } catch (e: KubeApiException) {
                // A call can fail for a moment (the API server restarts). The run is lost only if it keeps failing.
                if (++failures >= MAX_CONSECUTIVE_FAILURES) throw K8sRunException("API_UNAVAILABLE")
            }
            Thread.sleep(pollMillis)
        }
    }

    private fun sendInput(pod: String, bytes: ByteArray) {
        val tmp = "${PodPaths.KIVRA_DIR}/input.json.tmp"
        // `head -c N` stops after N bytes, so no end-of-input signal is needed. N is a number made here; nothing else is interpolated.
        val script = "umask 077; mkdir -p ${PodPaths.KIVRA_DIR} && head -c ${bytes.size} > $tmp && mv $tmp ${PodPaths.INPUT}"
        val result = api.exec(settings.namespace, pod, K8sConstants.CONTAINER_NAME, listOf("sh", "-c", script), bytes, 0, EXEC_TIMEOUT_MILLIS)
        if (result.exitCode != 0) throw K8sRunException("INPUT_NOT_DELIVERED")
    }

    /** The result file as the Pod wrote it, or null when it is not there yet. */
    private fun readResult(pod: String): PodReadout? {
        val result = api.exec(
            settings.namespace,
            pod,
            K8sConstants.CONTAINER_NAME,
            listOf("cat", PodPaths.RESULT),
            null,
            PodResultCodec.MAX_FILE_BYTES,
            EXEC_TIMEOUT_MILLIS,
        )
        if (result.exitCode != 0) return null
        if (result.truncated) return PodReadout.Error("RESULT_TOO_LARGE")
        return try {
            PodResultCodec.decode(result.stdout)
        } catch (e: PodProtocolException) {
            PodReadout.Error(e.code.take(MAX_CODE_CHARS).filter { it in 'A'..'Z' || it == '_' }.ifEmpty { "RESULT_MALFORMED" })
        }
    }

    private fun finish(outcome: RunOutcome, exitCode: Int?, readout: PodReadout?, pump: LogPump?, drain: Boolean = false): RunResult {
        if (pump != null) {
            if (drain) pump.awaitQuiet(drainIdleMillis, drainMaxMillis)
            pump.stop()
        }
        val truncated = pump?.exceeded == true
        return RunResult(
            outcome = if (outcome == RunOutcome.EXITED && truncated) RunOutcome.OUTPUT_LIMIT else outcome,
            exitCode = exitCode,
            stdout = pump?.text().orEmpty(),
            // The log merges the two streams (the Pod has one log), so there is no separate standard error.
            stderr = "",
            outputTruncated = truncated,
            podReadout = readout,
        )
    }

    private fun manifestFor(jobSpec: K8sJobSpec): JsonObject {
        val manifest = manifestOf(jobSpec)
        // The guard is the last step before anything is sent. A refusal throws K8sJobRejectedException with the reason only.
        guard.check(manifest)
        return manifest
    }

    private fun toJobSpec(spec: RunSpec): K8sJobSpec = K8sJobSpec(
        runId = spec.runId,
        attemptId = spec.attemptId,
        namespace = settings.namespace,
        image = spec.image,
        proxyUrl = spec.proxyUrl,
        timeout = spec.timeout,
        maxTurns = spec.maxTurns,
        model = spec.model,
        budgetUsd = spec.budgetUsd,
    )

    /**
     * Reads the container's log on its own thread: the bytes are kept up to [limit], the lines (at most
     * [CliOutputLimits.MAX_LINE_BYTES] each) go to [listener], and past the limit the rest is dropped and [exceeded] is set.
     * A listener that throws is ignored (the log keeps being read).
     */
    private class LogPump(private val input: InputStream, private val limit: Int, private val listener: ((StdoutLine) -> Unit)?) {
        private val kept = ByteArrayOutputStream()
        private val line = ByteArrayOutputStream()
        private var oversized = false
        private var total = 0L

        @Volatile private var lastActivity = System.nanoTime()
        private val stopped = AtomicBoolean(false)

        @Volatile var exceeded = false
            private set

        private val reader = thread(isDaemon = true, name = "k8s-log") {
            val chunk = ByteArray(CHUNK)
            try {
                while (!exceeded) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    lastActivity = System.nanoTime()
                    accept(chunk, n)
                }
            } catch (e: IOException) {
                // The stream was closed or broke; what was read stays.
            }
            if (!exceeded && (line.size() > 0 || oversized)) emit()
        }

        private fun accept(chunk: ByteArray, n: Int) {
            val room = (limit - total).coerceAtLeast(0).toInt()
            val take = minOf(n, room)
            synchronized(kept) { kept.write(chunk, 0, take) }
            total += take
            for (i in 0 until take) feed(chunk[i].toInt())
            if (n > room) exceeded = true
        }

        private fun feed(b: Int) {
            if (b == '\n'.code) {
                emit()
            } else if (!oversized) {
                if (line.size() >= CliOutputLimits.MAX_LINE_BYTES) {
                    oversized = true
                    line.reset()
                } else {
                    line.write(b)
                }
            }
        }

        private fun emit() {
            val item: StdoutLine = if (oversized) StdoutLine.Oversized else StdoutLine.Text(line.toString(Charsets.UTF_8))
            line.reset()
            oversized = false
            try {
                listener?.invoke(item)
            } catch (e: Exception) {
                // A failing listener must not stop the run.
            }
        }

        fun text(): String = synchronized(kept) { kept.toString(Charsets.UTF_8) }

        /** Waits until nothing has been read for [idleMillis], or the stream ended, or [maxMillis] passed. */
        fun awaitQuiet(idleMillis: Long, maxMillis: Long) {
            val end = System.nanoTime() + maxMillis * NANOS_PER_MILLI
            while (reader.isAlive && System.nanoTime() < end) {
                if ((System.nanoTime() - lastActivity) / NANOS_PER_MILLI >= idleMillis) return
                Thread.sleep(QUIET_POLL_MILLIS)
            }
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            try {
                input.close()
            } catch (e: IOException) {
                // Closing a broken stream.
            }
            reader.join(JOIN_MILLIS)
        }

        private companion object {
            const val CHUNK = 8192
            const val JOIN_MILLIS = 2_000L
            const val QUIET_POLL_MILLIS = 20L
            const val NANOS_PER_MILLI = 1_000_000L
        }
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 2_000L
        const val DEFAULT_DRAIN_IDLE_MILLIS = 1_000L
        const val DEFAULT_DRAIN_MAX_MILLIS = 10_000L
        private const val EXEC_TIMEOUT_MILLIS = 30_000L
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val MAX_CODE_CHARS = 40
    }
}
