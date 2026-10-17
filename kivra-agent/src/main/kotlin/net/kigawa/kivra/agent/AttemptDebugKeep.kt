package net.kigawa.kivra.agent

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import net.kigawa.kivra.agent.docker.DockerPatterns
import org.slf4j.LoggerFactory

/**
 * Debug retention for a failed attempt (`KIVRA_AGENT_DEBUG_KEEP=1`, runbook §6.12). Off by default: with it off, nothing here runs.
 *
 * When an attempt ends without a success, [keep] writes `<workRoot>/debug/<runId>-<attemptId>/` with
 * - `stream.jsonl`: the CLI's stdout (stream-json), cut to [STREAM_LIMIT_BYTES] from the start;
 * - `task.json`: the `.kivra/task.json` that the CLI received (it shows the title that was passed);
 * - `outcome.txt`: the failure code (and the rejection reasons, when the change set was refused).
 *
 * The content holds the task text and the repository content, so it must not be shared. Directories are 0700, files 0600. Nothing
 * is logged except IDs and facts. Every method is best-effort: a failure here is logged as a warning and never changes the outcome of
 * the attempt. [prune] removes entries older than [MAX_AGE] and keeps at most [MAX_ENTRIES] (the newest).
 */
class AttemptDebugKeep(workRoot: Path, private val clock: Clock = Clock.systemUTC()) {
    private val logger = LoggerFactory.getLogger(AttemptDebugKeep::class.java)
    private val root: Path = workRoot.resolve(DIR_NAME)

    /** Writes the debug files of one failed attempt. [stdout] and [taskFile] are optional: null when that part never existed. */
    fun keep(runId: String, attemptId: String, outcome: String, stdout: String?, taskFile: Path?) {
        if (!DockerPatterns.ID.matches(runId) || !DockerPatterns.ID.matches(attemptId)) {
            logger.warn("debug output not kept: invalid id runId={} attemptId={}", runId, attemptId)
            return
        }
        val dir = root.resolve("$runId-$attemptId")
        try {
            ensureRoot()
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIR_PERMISSIONS))
            try {
                writeNew(dir.resolve(OUTCOME_FILE), outcome.toByteArray(StandardCharsets.UTF_8))
                if (stdout != null) {
                    val bytes = stdout.toByteArray(StandardCharsets.UTF_8)
                    writeNew(dir.resolve(STREAM_FILE), bytes.copyOf(minOf(bytes.size, STREAM_LIMIT_BYTES)))
                }
                if (taskFile != null) {
                    val task = readBounded(taskFile, TASK_LIMIT_BYTES)
                    if (task != null) writeNew(dir.resolve(TASK_FILE), task)
                }
            } catch (e: Exception) {
                deleteTree(dir)
                throw e
            }
            logger.info("debug output kept: runId={} attemptId={}", runId, attemptId)
        } catch (e: Exception) {
            logger.warn("debug output not kept: runId={} attemptId={} class={}", runId, attemptId, e.javaClass.simpleName)
        }
    }

    /** Removes the entries older than [MAX_AGE], then the oldest entries beyond [MAX_ENTRIES]. Only directories in the debug root count. */
    fun prune() {
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
            val now = clock.instant()
            val entries = Files.list(root).use { stream ->
                stream.toList().filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.map { it to modified(it) }
            }
            val (expired, fresh) = entries.partition { Duration.between(it.second, now) > MAX_AGE }
            val excess = fresh.sortedBy { it.second }.take(maxOf(0, fresh.size - MAX_ENTRIES)).map { it.first }
            val victims = expired.map { it.first } + excess
            var failed = 0
            for (victim in victims) {
                if (!deleteTree(victim)) failed++
            }
            if (victims.isNotEmpty()) logger.info("debug output pruned: removed={} failed={}", victims.size - failed, failed)
        } catch (e: IOException) {
            logger.warn("debug output prune skipped: class={}", e.javaClass.simpleName)
        }
    }

    private fun modified(path: Path): Instant = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        .lastModifiedTime().toInstant()

    private fun ensureRoot() {
        if (Files.isSymbolicLink(root)) throw IOException("debug root is a symbolic link")
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(root, DIR_PERMISSIONS)
        } else {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("debug root is not a directory")
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(DIR_PERMISSIONS))
        }
    }

    private fun writeNew(path: Path, bytes: ByteArray) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        Files.write(path, bytes)
    }

    /** Reads at most [limit] bytes of a regular file. Null when the file is missing or is not a regular file. */
    private fun readBounded(path: Path, limit: Int): ByteArray? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit) }
    }

    /** Removes a tree without following links. Returns false when something is left. */
    private fun deleteTree(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        var clean = true
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!tryDelete(file)) clean = false
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                clean = false
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (!tryDelete(dir)) clean = false
                return FileVisitResult.CONTINUE
            }
        })
        return clean
    }

    private fun tryDelete(path: Path): Boolean = try {
        Files.deleteIfExists(path)
        true
    } catch (e: IOException) {
        false
    }

    companion object {
        /** The environment variable that turns the retention on. Read only for `KIVRA_AGENT_EXECUTOR=real`. */
        const val ENV_DEBUG_KEEP = "KIVRA_AGENT_DEBUG_KEEP"

        /** The directory under the work root. */
        const val DIR_NAME = "debug"
        const val STREAM_FILE = "stream.jsonl"
        const val TASK_FILE = "task.json"
        const val OUTCOME_FILE = "outcome.txt"

        const val STREAM_LIMIT_BYTES = 8 * 1024 * 1024
        const val TASK_LIMIT_BYTES = 256 * 1024
        val MAX_AGE: Duration = Duration.ofHours(24)
        const val MAX_ENTRIES = 20

        private val DIR_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
    }
}
