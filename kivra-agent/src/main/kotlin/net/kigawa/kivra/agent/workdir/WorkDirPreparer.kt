package net.kigawa.kivra.agent.workdir

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.TreeMap
import net.kigawa.kivra.agent.docker.DockerPatterns
import net.kigawa.kivra.agent.docker.DockerSpecException
import net.kigawa.kivra.agent.docker.TaskFile
import net.kigawa.kivra.agent.docker.WorkDirPolicy

/*
 * Creates the work directory of one attempt from the base commit (ADR 0010 decision 4; runbook 6.8).
 *
 * The base commit comes from a host-side bare mirror that is fetched without any credential (see [PublicGitMirror]). The tree is read
 * with `git archive` and extracted here. The host never runs git inside the work directory, and the archive is refused if it holds a
 * symbolic link, a hard link, a device, an absolute or `..` path, the reserved `.kivra` directory, or more than the limits allow.
 *
 * Next to the work directory the host writes a base manifest (`<runId>-<attemptId>.base`, outside the mounted directory, mode 0600):
 * the SHA-256, size and executable flag of every base file. [DiffCollector] compares the work directory with it, so the host never
 * needs the base files again and never runs git on the work directory.
 *
 * Ownership (unconfirmed on rootless docker, see runbook 6.8): the container runs as uid 10001, which under rootless docker is a
 * subordinate uid of the host user, not the host user. So the host cannot hand the files to it. Instead the run directory and every
 * directory in it are mode 0777, and the files 0666 (0777 when executable). Only the work root (mode 0700, owned by the agent's OS
 * user) makes that reachable, so no other host user can get in. The sticky-free 0777 directories also let the container create entries.
 */

/** The result of a successful extraction, with the base files and their digests. */
internal class BaseEntry(val sha256: String, val size: Long, val executable: Boolean)

/** Reads and writes the base manifest. One line per base file: `<sha256>\t<size>\t<0|1>\t<path>`. */
internal object BaseManifest {
    private val HEX = Regex("[0-9a-f]{64}")

    fun write(path: Path, entries: Map<String, BaseEntry>) {
        val text = buildString {
            for ((rel, e) in entries) {
                append(e.sha256).append('\t').append(e.size).append('\t').append(if (e.executable) '1' else '0')
                append('\t').append(rel).append('\n')
            }
        }
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(text.toByteArray(StandardCharsets.UTF_8))
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }

    fun read(path: Path): Map<String, BaseEntry> {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw WorkDirException(WorkDirReason.MANIFEST_INVALID)
        val result = HashMap<String, BaseEntry>()
        val lines = try {
            Files.readAllLines(path, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw WorkDirException(WorkDirReason.MANIFEST_INVALID)
        }
        for (line in lines) {
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4 || !HEX.matches(parts[0]) || parts[3].isEmpty()) throw WorkDirException(WorkDirReason.MANIFEST_INVALID)
            val size = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: throw WorkDirException(WorkDirReason.MANIFEST_INVALID)
            val exec = when (parts[2]) {
                "0" -> false
                "1" -> true
                else -> throw WorkDirException(WorkDirReason.MANIFEST_INVALID)
            }
            if (result.put(parts[3], BaseEntry(parts[0], size, exec)) != null) {
                throw WorkDirException(WorkDirReason.MANIFEST_INVALID)
            }
        }
        return result
    }
}

/** Creates work directories under [workRoot]. The caller holds the [net.kigawa.kivra.agent.WorkDirLock] of the work root. */
class WorkDirPreparer(workRoot: Path, private val limits: ExtractLimits = ExtractLimits()) {
    private val workRoot: Path = realWorkRoot(workRoot)

    /**
     * Extracts the archive [source] (the output of `git archive`) into `<workRoot>/<runId>-<attemptId>`, writes `.kivra/task.json`
     * and the base manifest. Any failure removes what was made. [source] is always closed here; a failing `git archive` surfaces on
     * close, and then the run directory is removed as well.
     */
    fun prepare(runId: String, attemptId: String, source: InputStream, task: TaskInput): PreparedWorkDir {
        val prepared = try {
            prepareUnclosed(runId, attemptId, source, task)
        } catch (e: Throwable) {
            try {
                source.close()
            } catch (ignored: Exception) {
                // The original failure is the one to report.
            }
            throw e
        }
        try {
            source.close()
        } catch (e: WorkDirException) {
            // The stream closes after git has exited, so a failed git shows here, after the tree was written.
            RunDirCleanup.delete(prepared.runDir, prepared.manifest)
            throw e
        }
        return prepared
    }

    private fun prepareUnclosed(runId: String, attemptId: String, source: InputStream, task: TaskInput): PreparedWorkDir {
        if (!DockerPatterns.ID.matches(runId) || !DockerPatterns.ID.matches(attemptId)) {
            throw WorkDirException(WorkDirReason.RUN_ID_INVALID)
        }
        val name = "$runId-$attemptId"
        val runDir = workRoot.resolve(name)
        val manifest = workRoot.resolve("$name.base")
        if (Files.exists(runDir, LinkOption.NOFOLLOW_LINKS) || Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkDirException(WorkDirReason.WORKDIR_EXISTS)
        }
        // Created outside the try: a failure here must not remove a directory that another process made.
        try {
            Files.createDirectory(runDir)
        } catch (e: FileAlreadyExistsException) {
            throw WorkDirException(WorkDirReason.WORKDIR_EXISTS)
        } catch (e: IOException) {
            throw WorkDirException(WorkDirReason.WORKROOT_INVALID)
        }
        try {
            setMode(runDir, DIRECTORY_MODE)
            val entries = extract(source, runDir)
            val real = WorkDirPolicy.validate(runDir, workRoot)
            ensureDirectory(real, TaskFile.DIRECTORY)
            try {
                TaskFile.write(real, runId, attemptId, task.title, task.body, task.truncated)
            } catch (e: DockerSpecException) {
                throw WorkDirException(WorkDirReason.TASK_INVALID)
            }
            BaseManifest.write(manifest, entries)
            return PreparedWorkDir(real, manifest)
        } catch (e: WorkDirException) {
            RunDirCleanup.delete(runDir, manifest)
            throw e
        } catch (e: IOException) {
            RunDirCleanup.delete(runDir, manifest)
            throw WorkDirException(WorkDirReason.ARCHIVE_WRITE_FAILED)
        } catch (e: RuntimeException) {
            RunDirCleanup.delete(runDir, manifest)
            throw e
        }
    }

    private fun extract(source: InputStream, runDir: Path): Map<String, BaseEntry> {
        val entries = TreeMap<String, BaseEntry>()
        val explicit = HashMap<String, String>()
        var total = 0L
        TarReader(source, limits.maxEntries).forEachEntry { entry, data ->
            val rel = ArchivePath.check(entry.name, limits.maxPathChars, entry.kind == TarKind.DIRECTORY)
            val key = ChangeRules.collisionKey(rel)
            val previous = explicit.put(key, rel)
            if (previous != null) throw WorkDirException(WorkDirReason.ARCHIVE_DUPLICATE)
            when (entry.kind) {
                TarKind.DIRECTORY -> ensureDirectory(runDir, rel)
                TarKind.REGULAR -> {
                    if (entry.size > limits.maxFileBytes) throw WorkDirException(WorkDirReason.ARCHIVE_FILE_TOO_LARGE)
                    total += entry.size
                    if (total > limits.maxTotalBytes) throw WorkDirException(WorkDirReason.ARCHIVE_TOO_LARGE)
                    val slash = rel.lastIndexOf('/')
                    if (slash > 0) ensureDirectory(runDir, rel.substring(0, slash))
                    val target = runDir.resolve(rel)
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw WorkDirException(WorkDirReason.ARCHIVE_DUPLICATE)
                    val executable = entry.mode and EXEC_BITS != 0
                    val digest = writeFile(target, data, entry.size, executable)
                    entries[rel] = BaseEntry(digest, entry.size, executable)
                }
            }
        }
        return entries
    }

    /** Creates each missing directory of [relative] under [base] (mode 0777). An existing non-directory is refused. */
    private fun ensureDirectory(base: Path, relative: String) {
        var current = base
        for (segment in relative.split('/')) {
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw WorkDirException(WorkDirReason.ARCHIVE_PATH)
            } else {
                Files.createDirectory(current)
                setMode(current, DIRECTORY_MODE)
            }
        }
    }

    /** Writes the entry data with the size checked on the way. Returns the SHA-256 hex. */
    private fun writeFile(target: Path, data: InputStream, size: Long, executable: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            DigestOutputStream(out, digest).use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = data.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    copied += n
                }
            }
        }
        if (copied != size) throw WorkDirException(WorkDirReason.ARCHIVE_TRUNCATED)
        setMode(target, if (executable) FILE_EXEC_MODE else FILE_MODE)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun setMode(path: Path, mode: String) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }

    private fun realWorkRoot(root: Path): Path {
        val real = try {
            root.toRealPath()
        } catch (e: IOException) {
            throw WorkDirException(WorkDirReason.WORKROOT_INVALID)
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw WorkDirException(WorkDirReason.WORKROOT_INVALID)
        val perms = try {
            Files.getPosixFilePermissions(real, LinkOption.NOFOLLOW_LINKS)
        } catch (e: UnsupportedOperationException) {
            throw WorkDirException(WorkDirReason.WORKROOT_INVALID)
        }
        // The work root is the confinement for the 0777 directories inside it: no group and no other access is allowed.
        if (perms.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
            throw WorkDirException(WorkDirReason.WORKROOT_INVALID)
        }
        return real
    }

    private companion object {
        const val DIRECTORY_MODE = "rwxrwxrwx"
        const val FILE_MODE = "rw-rw-rw-"
        const val FILE_EXEC_MODE = "rwxrwxrwx"
        const val EXEC_BITS = 0b001_001_001
    }
}

/** Removes a run directory and its manifest. Symbolic links are removed, never followed. Returns false when something is left. */
object RunDirCleanup {
    fun delete(runDir: Path, manifest: Path): Boolean {
        val removedManifest = try {
            Files.deleteIfExists(manifest)
            true
        } catch (e: IOException) {
            false
        }
        if (!Files.exists(runDir, LinkOption.NOFOLLOW_LINKS)) return removedManifest
        var clean = true
        Files.walkFileTree(runDir, object : SimpleFileVisitor<Path>() {
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
        return clean && removedManifest
    }

    private fun tryDelete(path: Path): Boolean = try {
        Files.deleteIfExists(path)
        true
    } catch (e: IOException) {
        false
    }
}

/** Shape rules for base paths. They are looser than the change rules (base files are not written) but refuse anything unsafe. */
internal object ArchivePath {
    private val forbidden = Regex("[\\u0000-\\u001F\\u007F\\\\\\uFFFD]")

    fun check(raw: String, maxChars: Int, directory: Boolean): String {
        val name = if (directory && raw.endsWith('/')) raw.dropLast(1) else raw
        if (name.isEmpty() || name.length > maxChars) throw WorkDirException(WorkDirReason.ARCHIVE_PATH)
        if (name.startsWith('/') || forbidden.containsMatchIn(name)) throw WorkDirException(WorkDirReason.ARCHIVE_PATH)
        val segments = name.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) throw WorkDirException(WorkDirReason.ARCHIVE_PATH)
        if (segments[0] == TaskFile.DIRECTORY) throw WorkDirException(WorkDirReason.ARCHIVE_RESERVED_PATH)
        return name
    }
}
