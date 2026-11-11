package net.kigawa.kivra.agent.k8s.pod

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.system.exitProcess
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffCollector
import net.kigawa.kivra.agent.workdir.DiffLimits
import net.kigawa.kivra.agent.workdir.PublicGitMirror
import net.kigawa.kivra.agent.workdir.TaskInput
import net.kigawa.kivra.agent.workdir.WorkDirException
import net.kigawa.kivra.agent.workdir.WorkDirPreparer

/** Gives the archive of the base commit (the output of `git archive`). The production one fetches the commit over https. */
fun interface BaseFetcher {
    fun open(input: PodInput): InputStream
}

/** Runs the CLI in [workDir] and returns its exit status. The standard output of the CLI is the container's standard output. */
fun interface CliProcess {
    fun run(argv: List<String>, workDir: Path): Int
}

/**
 * The first process of the Pod (ADR 0010 追補 7 PR 4). The Job's command is this entrypoint and then the CLI argv. It does, in order:
 *
 * 1. waits for `input.json` (the agent writes it with an exec; see [PodProtocol]);
 * 2. fetches the one base commit from public GitHub and extracts it, writes `.kivra/task.json` and the base manifest
 *    ([WorkDirPreparer], the same code as the host side);
 * 3. runs the CLI argv in the work directory, with its output passing through to the container's standard output;
 * 4. collects the change set from the work directory ([DiffCollector], the same code and rules as the host side, with the built-in path
 *    rules; the claim's patterns are applied again by the agent) and writes `result.json` atomically;
 * 5. stays alive ([hold]) until the agent has read the result and deletes the Job (or the Job's deadline kills it).
 *
 * The summary is not known here (it is in the CLI's output, which the agent parses), so the collection uses a fixed non-empty
 * placeholder, and the agent supplies the real one in [PodReadout.Collected.finish].
 *
 * A failure of any step writes an error result with a fixed code, so the agent always reads something. Nothing here logs.
 */
class PodEntrypoint(
    private val workRoot: Path,
    private val kivraDir: Path,
    private val fetcher: BaseFetcher,
    private val cli: CliProcess,
    private val limits: DiffLimits = DiffLimits(),
    private val inputWait: Duration = Duration.ofMinutes(10),
    private val pollMillis: Long = 200,
) {
    private val inputFile: Path = kivraDir.resolve(INPUT_NAME)
    private val resultFile: Path = kivraDir.resolve(RESULT_NAME)

    /** Does steps 1 to 4. The result file exists when this returns. */
    fun run(argv: List<String>) {
        try {
            writeResult(execute(argv))
        } catch (e: PodProtocolException) {
            writeResult(PodResultCodec.encodeError(e.code))
        } catch (e: WorkDirException) {
            writeResult(PodResultCodec.encodeError("WORKDIR_FAILED"))
        } catch (e: Exception) {
            writeResult(PodResultCodec.encodeError("ENTRYPOINT_FAILED"))
        }
    }

    private fun execute(argv: List<String>): ByteArray {
        val input = PodInput.decode(awaitInput())
        val prepared = WorkDirPreparer(workRoot).prepare(
            input.runId,
            input.attemptId,
            fetcher.open(input),
            TaskInput(input.title, input.body, input.truncated),
        )
        val exitStatus = cli.run(argv, prepared.runDir)
        val outcome: CollectOutcome = DiffCollector(workRoot, limits).collect(
            runDir = prepared.runDir,
            manifestPath = prepared.manifest,
            summary = SUMMARY_PLACEHOLDER,
            exitStatus = exitStatus,
            sessionId = null,
        )
        return PodResultCodec.encode(exitStatus, outcome)
    }

    private fun awaitInput(): ByteArray {
        val deadline = System.nanoTime() + inputWait.toNanos()
        while (!Files.isRegularFile(inputFile)) {
            if (System.nanoTime() > deadline) throw PodProtocolException("INPUT_TIMEOUT")
            Thread.sleep(pollMillis)
        }
        val size = Files.size(inputFile)
        if (size > PodInput.MAX_FILE_BYTES) throw PodProtocolException("INPUT_TOO_LARGE")
        return Files.readAllBytes(inputFile)
    }

    /** Writes through a temporary file and a rename, so a reader never sees half a result. */
    private fun writeResult(bytes: ByteArray): Int {
        Files.createDirectories(kivraDir)
        val tmp = kivraDir.resolve("$RESULT_NAME.tmp")
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        Files.move(tmp, resultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return bytes.size
    }

    companion object {
        const val INPUT_NAME = "input.json"
        const val RESULT_NAME = "result.json"

        /** The summary used for the collection in the Pod. It is replaced by the agent. */
        const val SUMMARY_PLACEHOLDER = "collected in the pod"

        /** Waits until the process is ended from outside. */
        fun hold() {
            while (true) Thread.sleep(HOLD_MILLIS)
        }

        private const val HOLD_MILLIS = 60_000L
    }
}

/** Runs the real CLI. No shell; stdin is empty; stdout and stderr go to the container's. */
internal object ProcessCliProcess : CliProcess {
    override fun run(argv: List<String>, workDir: Path): Int {
        val builder = ProcessBuilder(argv)
        builder.directory(workDir.toFile())
        builder.redirectInput(File("/dev/null"))
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return START_FAILED
        }
        return process.waitFor()
    }

    private const val START_FAILED = 127
}

/**
 * `main` of the Pod. The runtime image's launcher (`/usr/local/bin/kivra-pod-entrypoint`) runs this class with the CLI argv. The proxy
 * variables of the Pod are passed to git (which otherwise gets an empty environment); there is no other credential.
 */
fun main(args: Array<String>) {
    val env = System.getenv()
    val gitEnv = listOf("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY").mapNotNull { name -> env[name]?.let { name to it } }.toMap()
    val mirror = PublicGitMirror(Path.of(PodPaths.GIT_BINARY), Path.of(PodPaths.MIRROR_DIR), extraEnv = gitEnv)
    val fetcher = BaseFetcher { input ->
        mirror.syncCommit(input.source(), input.commit)
        mirror.openArchive(input.commit)
    }
    val entrypoint = PodEntrypoint(Path.of(PodPaths.WORK_ROOT), Path.of(PodPaths.KIVRA_DIR), fetcher, ProcessCliProcess)
    if (args.isEmpty()) exitProcess(2)
    entrypoint.run(args.toList())
    PodEntrypoint.hold()
}
