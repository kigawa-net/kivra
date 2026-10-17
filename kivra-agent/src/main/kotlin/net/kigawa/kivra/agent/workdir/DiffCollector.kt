package net.kigawa.kivra.agent.workdir

import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.EnumSet
import net.kigawa.kivra.agent.docker.DockerSpecException
import net.kigawa.kivra.agent.docker.TaskFile
import net.kigawa.kivra.agent.docker.WorkDirPolicy

/*
 * Turns the work directory after a run into a change set (PR 8; runbook 6.8). The base is the manifest that [WorkDirPreparer] wrote,
 * so no git runs here.
 *
 * Rules (each one is a reason; any reason refuses the whole change set):
 * - `.kivra/` at the top is excluded and never read. Nothing else is excluded.
 * - A symbolic link, a hard link (link count above 1), or a non-regular entry (device, FIFO, socket) is refused, anywhere in the tree.
 * - An executable bit that differs from the base (new or changed) is refused.
 * - A file is read only when it is a change. The content must be UTF-8, must not contain NUL, and must keep its lines and secrets
 *   within the limits of [ChangeRules].
 * - A changed path must pass [ChangeRules.pathReason]: the shape, the denylist and the allowlist of the PatchPolicy. The path must be NFC.
 * - The counts and sizes are the PatchPolicy limits ([DiffLimits]), so a change that the server would refuse is refused here first.
 *
 * Reasons never include paths, names or content. The walk does not follow symbolic links.
 */
class DiffCollector(workRoot: Path, private val limits: DiffLimits = DiffLimits()) {
    private val workRoot: Path = workRoot.toRealPath()

    /**
     * Compares [runDir] with the base manifest at [manifestPath]. [summary] is the text of the result (checked here, as the server does),
     * [exitStatus] and [sessionId] describe the CLI run. The caller holds the work root lock.
     */
    fun collect(runDir: Path, manifestPath: Path, summary: String, exitStatus: Int, sessionId: String?): CollectOutcome {
        val base = try {
            BaseManifest.read(manifestPath)
        } catch (e: WorkDirException) {
            return CollectOutcome.Rejected(listOf(DiffRejectReason.MANIFEST_INVALID))
        }
        val root = try {
            WorkDirPolicy.validate(runDir, workRoot)
        } catch (e: DockerSpecException) {
            return CollectOutcome.Rejected(listOf(DiffRejectReason.RUN_DIR_INVALID))
        }

        val scan = Scan(root, base)
        Files.walkFileTree(root, scan.visitor())
        scan.finishDeletes()
        scan.checkTotals()
        checkSummary(summary, scan.reasons)
        if (exitStatus != 0) scan.reasons.add(DiffRejectReason.CLI_EXIT_NONZERO)
        if (sessionId != null && !SESSION_ID.matches(sessionId)) scan.reasons.add(DiffRejectReason.RESULT_FIELD_INVALID)

        if (scan.reasons.isNotEmpty()) return CollectOutcome.Rejected(scan.reasons.toList())
        val changes = scan.changes.sortedBy { it.path }
        return CollectOutcome.Accepted(AgentResult(summary, changes, exitStatus, sessionId))
    }

    private fun checkSummary(summary: String, reasons: MutableSet<DiffRejectReason>) {
        if (summary.isBlank()) reasons.add(DiffRejectReason.SUMMARY_EMPTY)
        if (summary.length > limits.maxSummaryChars) reasons.add(DiffRejectReason.SUMMARY_TOO_LONG)
        if (!isWellFormed(summary)) reasons.add(DiffRejectReason.INVALID_UTF8)
        if (ChangeRules.containsSecretLike(summary)) reasons.add(DiffRejectReason.SECRET_LIKE_CONTENT)
    }

    /** The state of one walk. Each field is a count or a set of codes. */
    private inner class Scan(private val root: Path, private val base: Map<String, BaseEntry>) {
        val reasons: MutableSet<DiffRejectReason> = EnumSet.noneOf(DiffRejectReason::class.java)
        val changes = ArrayList<AgentChange>()
        private val seen = HashSet<String>()
        private var walked = 0
        private var writeBytes = 0L
        private var deletes = 0
        private var stopped = false

        fun visitor(): FileVisitor<Path> = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == root) return FileVisitResult.CONTINUE
                val rel = relative(dir)
                if (isExcluded(rel)) return FileVisitResult.SKIP_SUBTREE
                return if (count()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = relative(file)
                if (isExcluded(rel)) return FileVisitResult.CONTINUE
                if (!count()) return FileVisitResult.TERMINATE
                seen.add(rel)
                when {
                    attrs.isSymbolicLink -> reasons.add(DiffRejectReason.SYMLINK)
                    attrs.isRegularFile -> inspectRegular(file, rel, attrs.size())
                    else -> reasons.add(DiffRejectReason.SPECIAL_FILE)
                }
                return if (stopped) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                reasons.add(DiffRejectReason.WALK_FAILED)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) reasons.add(DiffRejectReason.WALK_FAILED)
                return FileVisitResult.CONTINUE
            }
        }

        /** Counts one walked entry. Returns false (and stops the walk) past the limit. */
        private fun count(): Boolean {
            walked++
            if (walked > limits.maxWalkEntries) {
                reasons.add(DiffRejectReason.TOO_MANY_ENTRIES)
                stopped = true
                return false
            }
            return true
        }

        private fun inspectRegular(file: Path, rel: String, size: Long) {
            val nlink = try {
                (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
            } catch (e: Exception) {
                reasons.add(DiffRejectReason.READ_FAILED)
                return
            }
            if (nlink > 1) {
                reasons.add(DiffRejectReason.HARDLINK)
                return
            }
            val executable = try {
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).any { it in EXECUTABLE }
            } catch (e: IOException) {
                reasons.add(DiffRejectReason.READ_FAILED)
                return
            }
            val entry = base[rel]
            if (entry != null && entry.size == size) {
                // The size equals the base size, which is already bounded by the extraction limit, so the digest is cheap.
                val digest = try {
                    digestOf(file)
                } catch (e: IOException) {
                    reasons.add(DiffRejectReason.READ_FAILED)
                    return
                }
                if (digest == entry.sha256) {
                    if (executable != entry.executable) reasons.add(DiffRejectReason.EXECUTABLE_BIT)
                    return
                }
            }
            if (executable != (entry?.executable ?: false)) reasons.add(DiffRejectReason.EXECUTABLE_BIT)
            ChangeRules.pathReason(rel, limits.maxPathChars)?.let {
                reasons.add(it)
                return
            }
            if (size > limits.maxWriteFileBytes) {
                reasons.add(DiffRejectReason.FILE_TOO_LARGE)
                return
            }
            val bytes = try {
                readBounded(file, limits.maxWriteFileBytes)
            } catch (e: IOException) {
                reasons.add(DiffRejectReason.READ_FAILED)
                return
            }
            if (bytes == null) {
                reasons.add(DiffRejectReason.FILE_TOO_LARGE)
                return
            }
            val content = ChangeRules.contentOf(bytes, limits.maxLineChars)
            if (content.reasons.isNotEmpty()) {
                reasons.addAll(content.reasons)
                return
            }
            writeBytes += bytes.size
            changes.add(AgentChange(rel, AgentChangeKind.WRITE, content.text))
        }

        /** Every base file that the walk did not see is a deletion. */
        fun finishDeletes() {
            if (stopped) return
            for (rel in base.keys) {
                if (rel in seen) continue
                val refused = ChangeRules.pathReason(rel, limits.maxPathChars)
                if (refused != null) {
                    reasons.add(refused)
                    continue
                }
                deletes++
                changes.add(AgentChange(rel, AgentChangeKind.DELETE, null))
            }
        }

        fun checkTotals() {
            if (changes.isEmpty() && reasons.isEmpty()) reasons.add(DiffRejectReason.EMPTY_PATCH)
            if (changes.size > limits.maxChangedFiles) reasons.add(DiffRejectReason.TOO_MANY_CHANGES)
            if (deletes > limits.maxDeletes) reasons.add(DiffRejectReason.TOO_MANY_DELETES)
            if (writeBytes > limits.maxTotalWriteBytes) reasons.add(DiffRejectReason.TOTAL_TOO_LARGE)
            val keys = HashMap<String, String>()
            for (change in changes) {
                val previous = keys.put(ChangeRules.collisionKey(change.path), change.path)
                if (previous != null && previous != change.path) reasons.add(DiffRejectReason.CASE_COLLISION)
            }
        }

        private fun relative(path: Path): String = root.relativize(path).joinToString("/") { it.toString() }

        private fun isExcluded(rel: String): Boolean = rel == TaskFile.DIRECTORY || rel.startsWith(TaskFile.DIRECTORY + "/")
    }

    private companion object {
        val EXECUTABLE: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun digestOf(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                DigestInputStream(Channels.newInputStream(channel), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Reads at most [max] bytes. Returns null when the file is longer. */
        fun readBounded(file: Path, max: Long): ByteArray? {
            Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val bytes = Channels.newInputStream(channel).readNBytes((max + 1).toInt())
                return if (bytes.size > max) null else bytes
            }
        }

        fun isWellFormed(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    Character.isHighSurrogate(c) -> {
                        if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                            i += 2
                            continue
                        }
                        return false
                    }

                    Character.isLowSurrogate(c) -> return false
                }
                i++
            }
            return true
        }
    }
}
