package net.kigawa.kivra.agent.docker

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val TEST_IMAGE = "kivra-agent-runtime:local"
internal const val TEST_NETWORK = "kivra-agent-internal"
internal const val TEST_PROXY = "http://kivra-agent-proxy:8080"
internal const val TEST_RUN_ID = "01M545K02Q6YB4M5ZHAM5792CS"
internal const val TEST_ATTEMPT_ID = "att-1"
internal const val TEST_DOCKER_HOST = "unix:///run/user/1001/docker.sock"

/** Upper bound for the fake's wait on its endless stream. A stream that is never read ends the wait, and the test then fails. */
private const val READ_WAIT_SECONDS = 5L

/** A work root with one run directory, and the settings that accept them. The root is a real temp directory. */
internal class DockerFixture {
    val root: Path = Files.createTempDirectory("kivra-docker-root").toRealPath()
    val workDir: Path = Files.createDirectories(root.resolve("run-1").resolve("work"))
    val settings: DockerSettings = settings(root)

    fun spec(
        workDir: Path = this.workDir,
        runId: String = TEST_RUN_ID,
        attemptId: String = TEST_ATTEMPT_ID,
        image: String = TEST_IMAGE,
        network: String = TEST_NETWORK,
        proxy: String = TEST_PROXY,
        timeout: Duration = Duration.ofMinutes(30),
    ): DockerRunSpec = DockerRunSpec(runId, attemptId, image, workDir, network, proxy, timeout)

    /** The builder's output for the standard spec. Used as the base that the guard tests mutate. */
    fun golden(): List<String> = DockerCommandBuilder(settings).build(spec())

    companion object {
        fun settings(root: Path, dockerHost: String? = TEST_DOCKER_HOST): DockerSettings = DockerSettings(
            dockerBinary = Path.of("/usr/bin/docker"),
            dockerHost = dockerHost,
            workRoot = root,
            allowedNetworks = setOf(TEST_NETWORK),
            allowedImages = setOf(TEST_IMAGE),
        )
    }
}

/** The index of the image: the first argument after `run` that does not start with `-`. The command follows it. */
internal fun List<String>.imageIndex(): Int = (1 until size).first { !this[it].startsWith("-") }

/** Replaces every option (before the image) that starts with [prefix] by [replacement] (none, when [replacement] is empty). */
internal fun List<String>.replacing(prefix: String, vararg replacement: String): List<String> {
    val image = imageIndex()
    val head = take(image).filterNot { it.startsWith(prefix) }
    return head + replacement + drop(image)
}

/** Inserts [extra] before the image, after the options. */
internal fun List<String>.withExtra(vararg extra: String): List<String> {
    val image = imageIndex()
    return take(image) + extra + drop(image)
}

/** A fake docker CLI for the runner tests. It records every call, and the containers it "runs" are controlled by the test. */
internal class FakeDocker : DockerLauncher {
    class Container(val id: String, val name: String, val labels: Map<String, String>)

    val calls = mutableListOf<List<String>>()
    val envs = mutableListOf<Map<String, String>>()
    val containers = mutableListOf<Container>()
    val killedNames = mutableListOf<String>()
    var clockNanos = 0L
    private val runs = mutableListOf<RunProcess>()

    /** When true, the `run` process never ends until it is killed. */
    var runBlocks = false

    /** The stdout of `run`. [endlessStdout] makes it an endless stream that ends only when the process is killed. */
    var runStdout: ByteArray = ByteArray(0)
    var endlessStdout = false
    var runExitCode = 0

    /**
     * Counts the reads of the endless stream. The fake clock advances only by polls, so without this a test could reach its
     * virtual deadline before the reader thread has read anything, and the run would end as TIMEOUT. A poll therefore waits
     * until the stream has been read twice: the first chunk is then already counted by the runner (the reader updates its
     * total before the next read), so the output limit is seen on the first poll.
     */
    private val endlessReads = CountDownLatch(2)

    override fun start(argv: List<String>, env: Map<String, String>): DockerProcess {
        calls += argv
        envs += env
        val args = argv.drop(1)
        return when (args.firstOrNull()) {
            "run" -> RunProcess().also { runs += it }
            "kill" -> {
                killedNames += args[1]
                runs.forEach { it.markKilled() }
                Immediate(0, "")
            }
            "ps" -> {
                val filtered = if ("label=kivra-agent=1" in args) {
                    containers.filter { it.labels["kivra-agent"] == "1" }
                } else {
                    containers
                }
                Immediate(0, filtered.joinToString("") { "${it.id}\t${it.labels["kivra-run"].orEmpty()}\n" })
            }
            "rm" -> {
                containers.removeAll { it.id == args[2] }
                Immediate(0, "")
            }
            else -> Immediate(1, "")
        }
    }

    private inner class RunProcess : DockerProcess {
        @Volatile
        private var killed = false
        private val endless = object : InputStream() {
            override fun read(): Int = if (killed) -1 else 'x'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                endlessReads.countDown()
                if (killed) return -1
                java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte())
                return len
            }
        }

        override val stdout: InputStream = if (endlessStdout) endless else ByteArrayInputStream(runStdout)
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(millis: Long): Int? {
            if (killed) return 137
            if (!runBlocks) return runExitCode
            if (endlessStdout) endlessReads.await(READ_WAIT_SECONDS, TimeUnit.SECONDS)
            clockNanos += millis * 1_000_000
            return null
        }

        override fun destroy() {
            killed = true
        }

        /** Called by `kill` of the fake: the container stops, so the attached `run` process ends. */
        fun markKilled() {
            killed = true
        }
    }

    private class Immediate(private val code: Int, out: String) : DockerProcess {
        override val stdout: InputStream = ByteArrayInputStream(out.toByteArray())
        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(millis: Long): Int? = code
        override fun destroy() {}
    }
}
