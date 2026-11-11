package net.kigawa.kivra.agent.backend

import java.nio.file.Path
import java.time.Duration

/**
 * Runs the CLI of one attempt in a container, and removes the containers of an earlier run (ADR 0010 追補 7, PR 2). The executor
 * depends only on this interface. The docker implementation is `DockerRunBackend`; the k8s one comes later (PR 4). Nothing here is
 * backend-specific, so the classification in `claude/` uses these types without depending on any backend.
 *
 * Blocking, because a backend waits for a process or a job. The caller runs it on an IO dispatcher.
 */
interface RunBackend {
    /** Checks [spec] and throws if it is refused. Nothing is started or removed. */
    fun validate(spec: RunSpec)

    /**
     * Runs one container and waits for its end. [cancelRequested] is polled while it runs. [onStdoutLine], when given, receives each
     * line of the standard output while the container runs. The result is the same with or without the listener.
     */
    fun run(
        spec: RunSpec,
        onStdoutLine: ((StdoutLine) -> Unit)? = null,
        cancelRequested: () -> Boolean = { false },
    ): RunResult

    /**
     * Removes the containers of this agent that are not the active run's. Pass null at startup: then every container of this agent is an
     * orphan. Returns the number removed, or null if the listing failed.
     */
    fun cleanupOrphans(activeRunId: String?): Int?
}

/**
 * What one attempt's run needs, without any backend detail. The task text is never a field: it goes to the work directory as a file.
 * [toString] shows the ids only.
 */
class RunSpec(
    val runId: String,
    val attemptId: String,
    val image: String,
    /** The host path of the work directory of the attempt. */
    val workDir: Path,
    /** The internal network of the run. */
    val network: String,
    /** The egress proxy, `http://host:port`. */
    val proxyUrl: String,
    /** The run's time limit. The run is stopped when it passes. */
    val timeout: Duration,
    /** The `--max-turns` of the CLI. */
    val maxTurns: Int,
    /** The `--model` of the CLI. */
    val model: String,
    /** The `--max-budget-usd` of the CLI, in the fixed two-decimal form. */
    val budgetUsd: String,
    /** The most standard output bytes kept and read for this run: the smaller of the server's limit and the agent's. */
    val outputLimitBytes: Int,
) {
    override fun toString(): String = "RunSpec(runId=$runId, attemptId=$attemptId)"
}

enum class RunOutcome { EXITED, TIMEOUT, CANCELLED, OUTPUT_LIMIT }

/**
 * One line of the container's standard output, as it is read while the container runs. [Text] holds the line's content (never logged;
 * [toString] shows no value). [Oversized] is a line over the per-line limit, whose content is not kept.
 */
sealed interface StdoutLine {
    class Text(val value: String) : StdoutLine {
        override fun toString(): String = "StdoutLine.Text(***)"
    }

    data object Oversized : StdoutLine
}

/**
 * The result of one run. [stdout] and [stderr] are bounded and may contain anything the container wrote, including secrets or the
 * task. The caller must not log them. [toString] shows sizes only.
 */
class RunResult(
    val outcome: RunOutcome,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean,
    /**
     * The result file that the k8s Pod wrote (PR 4), read through exec. Null for docker, where the host collects the diff, and for a k8s
     * run that ended before the file was written. It is untrusted until [net.kigawa.kivra.agent.k8s.pod.PodReadout.Collected.finish]
     * has checked it.
     */
    val podReadout: net.kigawa.kivra.agent.k8s.pod.PodReadout? = null,
) {
    override fun toString(): String =
        "RunResult(outcome=$outcome, exitCode=$exitCode, stdoutChars=${stdout.length}, stderrChars=${stderr.length}, " +
            "outputTruncated=$outputTruncated)"
}
