package net.kigawa.kivra.agent.docker

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory

/** One running process of the docker CLI. The streams are read by the runner, and the process is always ended by it. */
interface DockerProcess {
    val stdout: InputStream
    val stderr: InputStream

    /** Waits up to [millis] milliseconds. Returns the exit code, or null if the process is still running. */
    fun waitFor(millis: Long): Int?

    fun destroy()
}

/** Starts the docker CLI. The production implementation is [ProcessDockerLauncher]; tests use a fake. */
fun interface DockerLauncher {
    fun start(argv: List<String>, env: Map<String, String>): DockerProcess
}

/**
 * Starts a process from an argument list. There is no shell. The environment is cleared first, so only [env] reaches the docker
 * CLI (the `DOCKER_HOST` of the rootless socket, and nothing else from the agent's own environment).
 */
object ProcessDockerLauncher : DockerLauncher {
    override fun start(argv: List<String>, env: Map<String, String>): DockerProcess {
        val builder = ProcessBuilder(argv)
        builder.environment().clear()
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        return object : DockerProcess {
            override val stdout: InputStream = process.inputStream
            override val stderr: InputStream = process.errorStream

            override fun waitFor(millis: Long): Int? =
                if (process.waitFor(millis, TimeUnit.MILLISECONDS)) process.exitValue() else null

            override fun destroy() {
                process.destroyForcibly()
            }
        }
    }
}

enum class DockerRunOutcome { EXITED, TIMEOUT, CANCELLED, OUTPUT_LIMIT }

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
class DockerRunResult(
    val outcome: DockerRunOutcome,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val outputTruncated: Boolean,
) {
    override fun toString(): String =
        "DockerRunResult(outcome=$outcome, exitCode=$exitCode, stdoutChars=${stdout.length}, stderrChars=${stderr.length}, " +
            "outputTruncated=$outputTruncated)"
}

/**
 * Starts, watches and stops the Run containers (design #187 section 3.3, PR 6).
 *
 * - The arguments come from [DockerCommandBuilder] and are checked by [DockerArgsGuard] right before the start.
 * - A time limit, a cancel request and an output limit each end the container with `docker kill <name>` (the name is validated).
 * - Before a new work, [cleanupOrphans] removes only the containers that carry the `kivra-agent=1` label and do not belong to the
 *   active run. A container with another label is never touched.
 * - Output is read through a bounded buffer. The rest is drained and discarded, so the container cannot block on a full pipe.
 * - Nothing is logged except fixed outcome codes and the run and attempt ids. Arguments, environment, output and the task are never
 *   logged.
 *
 * Blocking, because the docker CLI is a process. The caller runs it on an IO dispatcher.
 */
class DockerRunner(
    private val settings: DockerSettings,
    private val launcher: DockerLauncher = ProcessDockerLauncher,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pollMillis: Long = DEFAULT_POLL_MILLIS,
    private val outputLimitBytes: Int = DEFAULT_OUTPUT_LIMIT_BYTES,
) {
    private val logger = LoggerFactory.getLogger(DockerRunner::class.java)
    private val builder = DockerCommandBuilder(settings)
    private val guard = DockerArgsGuard(settings)

    /**
     * Runs one container and waits for its end. [cancelRequested] is polled every [pollMillis]. Throws [DockerSpecException] or
     * [DockerArgsRejectedException] when the spec or the arguments are refused (nothing is started).
     *
     * [onStdoutLine], when given, receives each line of the standard output while the container runs. It runs on its own thread, behind
     * a bounded queue: a slow or failing listener never delays the reading, the limits or the kill. A line the queue cannot take is
     * dropped. The returned [DockerRunResult.stdout] is the same as without the listener.
     */
    fun run(
        spec: DockerRunSpec,
        onStdoutLine: ((StdoutLine) -> Unit)? = null,
        cancelRequested: () -> Boolean = { false },
    ): DockerRunResult {
        val args = builder.build(spec)
        guard.check(args)
        val process = launcher.start(dockerArgv(args), dockerEnv())
        val stdout = BoundedCapture(process.stdout, outputLimitBytes, onStdoutLine)
        val stderr = BoundedCapture(process.stderr, outputLimitBytes)
        val deadline = nanoTime() + spec.timeout.toNanos()

        var exit: Int? = null
        var stopReason: DockerRunOutcome? = null
        while (true) {
            exit = process.waitFor(pollMillis)
            if (exit != null) break
            val reason = when {
                stdout.exceeded || stderr.exceeded -> DockerRunOutcome.OUTPUT_LIMIT
                cancelRequested() -> DockerRunOutcome.CANCELLED
                nanoTime() - deadline >= 0 -> DockerRunOutcome.TIMEOUT
                else -> null
            }
            if (reason != null) {
                stopReason = reason
                kill(spec.containerName)
                exit = stop(process)
                break
            }
        }
        stdout.join()
        stderr.join()
        val outcome = stopReason ?: if (stdout.exceeded || stderr.exceeded) DockerRunOutcome.OUTPUT_LIMIT else DockerRunOutcome.EXITED
        logger.info(
            "docker run finished: runId={} attemptId={} outcome={} exitCode={}",
            spec.runId,
            spec.attemptId,
            outcome,
            exit,
        )
        return DockerRunResult(
            outcome = outcome,
            exitCode = exit,
            stdout = stdout.text(),
            stderr = stderr.text(),
            outputTruncated = stdout.exceeded || stderr.exceeded,
        )
    }

    /**
     * Removes the containers of this agent (label `kivra-agent=1`) that are not the active run's. Pass null at startup: then every
     * labelled container is an orphan of an earlier process. Returns the number removed, or null if the listing failed.
     */
    fun cleanupOrphans(activeRunId: String?): Int? {
        val listing = command(listOf("ps", "-a", "--no-trunc", "--filter", "label=kivra-agent=1", "--format", "{{.ID}}\t{{.Label \"kivra-run\"}}"))
            ?: return null
        if (listing.exitCode != 0) return null
        var removed = 0
        for (line in listing.stdout.lines()) {
            val columns = line.split('\t')
            if (columns.size != 2) continue
            val id = columns[0]
            if (!DockerPatterns.CONTAINER_ID.matches(id)) continue
            if (activeRunId != null && columns[1] == activeRunId) continue
            val rm = command(listOf("rm", "-f", id))
            if (rm?.exitCode == 0) removed++
        }
        logger.info("orphan containers removed: count={}", removed)
        return removed
    }

    /** `docker kill <name>`. The name has been validated; a failure is not fatal, because the container has `--rm` and the next start cleans it. */
    private fun kill(containerName: String) {
        if (!DockerPatterns.CONTAINER_NAME.matches(containerName)) return
        val result = command(listOf("kill", containerName), timeoutMillis = KILL_WAIT_MILLIS)
        if (result?.exitCode != 0) logger.warn("docker kill did not succeed: name-prefix={}", DockerConstants.CONTAINER_PREFIX)
    }

    /** Waits briefly for the CLI to end after the kill, then forces it. Returns the exit code, or null if it was forced. */
    private fun stop(process: DockerProcess): Int? {
        process.waitFor(STOP_WAIT_MILLIS)?.let { return it }
        process.destroy()
        return process.waitFor(STOP_WAIT_MILLIS)
    }

    private class CommandResult(val exitCode: Int?, val stdout: String)

    /** A short docker command whose output is needed (`ps`), or whose result is only a success flag (`kill`, `rm`). */
    private fun command(args: List<String>, timeoutMillis: Long = COMMAND_WAIT_MILLIS): CommandResult? {
        val process = try {
            launcher.start(dockerArgv(args), dockerEnv())
        } catch (e: IOException) {
            logger.warn("docker command could not start")
            return null
        }
        val out = BoundedCapture(process.stdout, COMMAND_OUTPUT_LIMIT_BYTES)
        val err = BoundedCapture(process.stderr, COMMAND_OUTPUT_LIMIT_BYTES)
        val exit = process.waitFor(timeoutMillis) ?: stop(process)
        out.join()
        err.join()
        return CommandResult(exit, out.text())
    }

    private fun dockerArgv(args: List<String>): List<String> = listOf(settings.dockerBinary.toString()) + args

    private fun dockerEnv(): Map<String, String> = buildMap {
        settings.dockerHost?.let { put("DOCKER_HOST", it) }
    }

    /** Keeps the first [limit] bytes and reads the rest to the end. [exceeded] tells the runner to stop the container. */
    private class BoundedCapture(input: InputStream, private val limit: Int, lineListener: ((StdoutLine) -> Unit)? = null) {
        private val kept = ByteArrayOutputStream()
        private val lines = lineListener?.let { LineForwarder(it) }

        @Volatile
        private var total = 0L

        private val reader = thread(isDaemon = true, name = "docker-capture") {
            try {
                input.use { stream ->
                    val chunk = ByteArray(CHUNK)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        total += n
                        lines?.feed(chunk, n)
                        synchronized(kept) {
                            val room = limit - kept.size()
                            if (room > 0) kept.write(chunk, 0, minOf(room, n))
                        }
                    }
                }
            } catch (e: IOException) {
                // The process was destroyed; what was read so far is kept.
            } finally {
                lines?.close()
            }
        }

        val exceeded: Boolean get() = total > limit

        fun join() {
            reader.join(JOIN_MILLIS)
        }

        fun text(): String = synchronized(kept) { kept.toString(Charsets.UTF_8) }

        private companion object {
            const val CHUNK = 8192
            const val JOIN_MILLIS = 5_000L
        }
    }

    /**
     * Splits the bytes of the standard output into lines, on the reader's thread, and hands them to [listener] on another thread through
     * a bounded queue. Nothing here blocks the reader: a full queue drops the line, and a listener that throws or blocks only holds its
     * own thread. A listener failure is logged once, with the exception class only.
     */
    private class LineForwarder(private val listener: (StdoutLine) -> Unit) {
        private val logger = LoggerFactory.getLogger(DockerRunner::class.java)
        private val queue = LinkedBlockingQueue<Any>(LINE_QUEUE_CAPACITY)
        private val buffer = ByteArrayOutputStream()
        private var oversized = false
        private var listenerFailed = false

        private val consumer = thread(isDaemon = true, name = "docker-stdout-lines") {
            while (true) {
                val item = queue.take()
                if (item === END) break
                try {
                    listener(item as StdoutLine)
                } catch (e: Exception) {
                    if (!listenerFailed) {
                        listenerFailed = true
                        logger.warn("stdout line listener failed: class={}", e.javaClass.simpleName)
                    }
                }
            }
        }

        fun feed(chunk: ByteArray, n: Int) {
            for (i in 0 until n) {
                val b = chunk[i].toInt()
                if (b == '\n'.code) {
                    emit()
                } else if (!oversized) {
                    if (buffer.size() >= MAX_LINE_BYTES) {
                        oversized = true
                        buffer.reset()
                    } else {
                        buffer.write(b)
                    }
                }
            }
        }

        /** Emits a last line that has no newline (a cut output), then ends the listener's thread. Waits briefly for it. */
        fun close() {
            if (buffer.size() > 0 || oversized) emit()
            queue.offer(END)
            consumer.join(LINE_JOIN_MILLIS)
        }

        private fun emit() {
            val item: StdoutLine = if (oversized) StdoutLine.Oversized else StdoutLine.Text(buffer.toString(Charsets.UTF_8))
            buffer.reset()
            oversized = false
            queue.offer(item)
        }
    }

    companion object {
        const val DEFAULT_POLL_MILLIS = 200L
        const val DEFAULT_OUTPUT_LIMIT_BYTES = 1024 * 1024
        const val COMMAND_OUTPUT_LIMIT_BYTES = 64 * 1024
        const val KILL_WAIT_MILLIS = 10_000L
        const val COMMAND_WAIT_MILLIS = 30_000L
        const val STOP_WAIT_MILLIS = 5_000L

        /** The per-line limit of the monitor's stream. The same as the parser's limit (ADR 0010), so a line is oversized in both. */
        const val MAX_LINE_BYTES = 1024 * 1024
        private const val LINE_QUEUE_CAPACITY = 1_000
        private const val LINE_JOIN_MILLIS = 500L
        private val END = Any()
    }
}
