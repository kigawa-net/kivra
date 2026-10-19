package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.TreeEntry
import net.kigawa.kivra.github.TreeEntryType
import org.slf4j.LoggerFactory

/*
 * The repository file list shown to the autopilot Planner (Issue #293). Read-only: one `listTree` of the base branch, cached for a short
 * time. Only the paths the validator would accept are listed (the allowlist, minus the denylist), so the Planner does not have to guess.
 *
 * The list is data from the repository, not instructions. A path that contains a control or an invisible character (such as a bidi
 * override) is left out: removing such a character would turn it into a different path. Any failure of the read gives no list at all,
 * and the Planner works as it did before. The reasons in the log are fixed codes; no path is logged.
 */
class PlannerRepoFiles(
    private val github: GitHubPort,
    private val repo: RepoRef,
    private val ref: String,
    private val maxBytes: Int = PlannerRepoFileList.MAX_BYTES,
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive" }
    }

    private class Cached(val at: Instant, val list: String?)

    private val mutex = Mutex()
    private var cached: Cached? = null

    /** The list body (without the data element), or null when it cannot be read. Only a successful read is cached. */
    suspend fun list(): String? = mutex.withLock {
        val now = clock.instant()
        cached?.takeIf { Duration.between(it.at, now) < ttl }?.let { return it.list }
        val fresh = read()
        if (fresh != null) cached = Cached(now, fresh)
        fresh
    }

    private suspend fun read(): String? {
        val result = try {
            github.listTree(repo, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("planner repo files unavailable: reason=EXCEPTION exception={}", e.javaClass.simpleName)
            return null
        }
        return when (result) {
            is GitHubResult.Success -> {
                val rendered = PlannerRepoFileList.render(result.value.entries, PlanValidator::allowsPlannedPath, maxBytes)
                if (rendered == null) {
                    logger.info("planner repo files unavailable: reason=NO_PLANNABLE_FILES")
                    null
                } else {
                    logger.info(
                        "planner repo files: files={} summarized={} bytes={} omittedUnsafe={}",
                        rendered.fileCount, rendered.summarized, rendered.text.toByteArray(Charsets.UTF_8).size, rendered.omittedUnsafe,
                    )
                    rendered.text
                }
            }
            is GitHubResult.Failure -> {
                logger.warn("planner repo files unavailable: reason={}", result.kind.name)
                null
            }
            is GitHubResult.Unknown -> {
                logger.warn("planner repo files unavailable: reason=UNKNOWN_OUTCOME")
                null
            }
        }
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
        private val logger = LoggerFactory.getLogger(PlannerRepoFiles::class.java)
    }
}

/** A rendered list. [summarized] is true when it is a directory summary, not the complete paths. */
internal data class RenderedRepoFiles(
    val text: String,
    val summarized: Boolean,
    val fileCount: Int,
    val omittedUnsafe: Int,
)

/** Pure functions: the list, its size limit, and the path checks. No I/O. */
internal object PlannerRepoFileList {
    /**
     * The size limit of the list body (48 KB). A larger list is replaced by a directory summary. The full list of the real `main` is
     * about 37 KB (Issue #293 follow-up), so it fits with room for the repository to grow.
     */
    const val MAX_BYTES = 48 * 1024

    /** Files at or above this size carry their size in KB (20 KB). */
    const val LARGE_FILE_BYTES = 20 * 1024L

    /** The longest path the Planner is shown. The validator's own limit is 200 characters, so a longer path is never accepted. */
    const val MAX_PATH_CHARS = 200

    private val REGULAR_FILE_MODES = setOf("100644", "100755")

    /**
     * The list of the regular files that [isPlannable] accepts, or null when none does. A file whose path has an unsafe character or is
     * too long is left out (and counted in [RenderedRepoFiles.omittedUnsafe]). Symlinks, submodules and directories are not listed.
     */
    fun render(entries: List<TreeEntry>, isPlannable: (String) -> Boolean, maxBytes: Int = MAX_BYTES): RenderedRepoFiles? {
        val files = ArrayList<Pair<String, Long>>()
        var omitted = 0
        for (entry in entries) {
            if (entry.type != TreeEntryType.BLOB || entry.mode !in REGULAR_FILE_MODES) continue
            if (!isPlannable(entry.path)) continue
            if (!isSafePath(entry.path)) {
                omitted++
                continue
            }
            files += entry.path to (entry.sizeBytes ?: 0L)
        }
        if (files.isEmpty()) return null
        files.sortBy { it.first }

        val full = buildString {
            appendLine(FULL_HEADER)
            for ((path, size) in files) {
                append(path)
                if (size >= LARGE_FILE_BYTES) append(" (").append(Math.round(size / 1024.0)).append(" KB)")
                appendLine()
            }
        }.trimEnd('\n')
        if (utf8Size(full) <= maxBytes) return RenderedRepoFiles(full, summarized = false, fileCount = files.size, omittedUnsafe = omitted)

        // Too large: one line per directory with its file count. The depth is reduced until the summary fits.
        val maxDepth = files.maxOf { dirSegments(it.first).size }
        for (depth in maxDepth downTo 1) {
            val text = summary(files, depth)
            if (utf8Size(text) <= maxBytes) return RenderedRepoFiles(text, summarized = true, fileCount = files.size, omittedUnsafe = omitted)
        }
        // Even the top-level summary is too large: cut it at a line boundary and say so.
        val lines = summary(files, 1).lines()
        val kept = ArrayList<String>()
        var used = 0
        val tail = "... (more directories omitted)"
        for (line in lines) {
            val next = used + utf8Size(line) + 1
            if (next + utf8Size(tail) + 1 > maxBytes) break
            kept += line
            used = next
        }
        return RenderedRepoFiles((kept + tail).joinToString("\n"), summarized = true, fileCount = files.size, omittedUnsafe = omitted)
    }

    /** True when the path has no control, format (bidi and zero-width), private-use, unassigned, separator or non-ASCII space character, and is short enough. */
    fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && path.codePointCount(0, path.length) <= MAX_PATH_CHARS && path.codePoints().allMatch { isSafeCodePoint(it) }

    /** [path] with the unsafe characters removed and cut to [maxChars] code points. Used only for the Planner's own output in a retry note. */
    fun stripForNote(path: String, maxChars: Int = MAX_PATH_CHARS): String {
        val kept = buildString {
            path.codePoints().forEach { cp -> if (isSafeCodePoint(cp)) appendCodePoint(cp) }
        }
        val count = kept.codePointCount(0, kept.length)
        return if (count <= maxChars) kept else kept.substring(0, kept.offsetByCodePoints(0, maxChars)) + "..."
    }

    private fun isSafeCodePoint(cp: Int): Boolean {
        if (cp == ' '.code) return true
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            -> false
            else -> true
        }
    }

    private fun summary(files: List<Pair<String, Long>>, depth: Int): String {
        val counts = java.util.TreeMap<String, Int>()
        for ((path, _) in files) {
            val key = dirSegments(path).take(depth).joinToString("/")
            val dir = if (key.isEmpty()) "(top level)" else "$key/"
            counts[dir] = (counts[dir] ?: 0) + 1
        }
        return buildString {
            appendLine(SUMMARY_HEADER)
            for ((dir, count) in counts) appendLine("$dir $count files")
        }.trimEnd('\n')
    }

    /** The directory segments of [path] (the file name excluded). */
    private fun dirSegments(path: String): List<String> = path.split('/').dropLast(1)

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private const val FULL_HEADER =
        "Repository files that the planner may name in targetPaths (complete paths from the repository root). " +
            "Sizes are given in KB for files of 20 KB or more."
    private const val SUMMARY_HEADER =
        "Repository files, SUMMARIZED by directory because the full list is larger than ${MAX_BYTES / 1024} KB. Each line is a directory and its " +
            "file count. The files themselves are not listed."
}
