package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.RepoSnapshot

/**
 * The repository as the runner saw it at one commit: every file's size (for the existence and shrink checks) and the subset the
 * LLM was shown (for UNSEEN_OVERWRITE). The listing is the whole tree; a file missing from it counts as new.
 */
internal class SelfDevSnapshot(
    private val sizes: Map<String, Long>,
    private val inContext: Set<String>,
) : RepoSnapshot {
    override fun exists(path: String): Boolean = sizes.containsKey(path)

    override fun size(path: String): Long? = sizes[path]

    override fun isInContext(path: String): Boolean = path in inContext
}

/**
 * Finds path-like strings in the task text (for example `docs/selfdev-hello.md` or `README.md`). The text is untrusted input, so the
 * extraction is strict: a candidate must contain `/` or end in a known extension, and must not be absolute, end in `/`, or have an
 * empty, `.`, `..` or `.git*` segment. The result is only a set of hints: the caller still checks that each path exists in the tree.
 * At most [MAX_PATHS] distinct candidates are returned, in order of appearance. Paths are never logged.
 */
internal object TitlePaths {
    const val MAX_PATHS = 5

    private val candidate = Regex("[A-Za-z0-9_./-]+")
    private val knownExtensions = setOf(
        "md", "txt", "kt", "kts", "gradle", "java", "yml", "yaml", "json", "toml", "properties", "sql", "sh",
        "ts", "tsx", "js", "jsx", "html", "css", "xml", "go", "py",
    )

    fun extract(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (match in candidate.findAll(text)) {
            if (out.size >= MAX_PATHS) break
            val value = match.value.trimEnd('.')
            if (isPathShaped(value)) out += value
        }
        return out.toList()
    }

    private fun isPathShaped(value: String): Boolean {
        if (value.isEmpty() || value.startsWith('/') || value.endsWith('/')) return false
        val segments = value.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".git") }) return false
        return segments.size > 1 || value.substringAfterLast('.', "") in knownExtensions
    }
}

/**
 * Chooses the files the LLM is shown. Files named in the task text come first, in the order they appear there, when they exist in the
 * tree, are readable, are allowed by the path rules ([PatchPolicy.allowsPath]) and fit the file and byte limits. The rest are the entries
 * matching [SelfDevConfig.contextPathPrefixes], in path order, within the same limits. Both share [SelfDevConfig.maxContextFiles] and
 * [SelfDevConfig.maxContextTotalBytes]. Deterministic: the same tree and text always give the same selection. Oversize files are
 * skipped, not truncated.
 */
internal object ContextSelection {
    fun select(
        entries: List<RepoEntry>,
        config: SelfDevConfig,
        titlePaths: List<String> = emptyList(),
        policy: PatchPolicy = PatchPolicy(),
    ): List<RepoEntry> {
        val picked = ArrayList<RepoEntry>()
        val pickedPaths = HashSet<String>()
        var total = 0L

        fun take(entry: RepoEntry): Boolean {
            if (picked.size >= config.maxContextFiles) return false
            if (!entry.readable) return false
            if (entry.sizeBytes > config.maxContextFileBytes) return false
            if (total + entry.sizeBytes > config.maxContextTotalBytes) return false
            picked += entry
            pickedPaths += entry.path
            total += entry.sizeBytes
            return true
        }

        val byPath = entries.associateBy { it.path }
        for (path in titlePaths) {
            val entry = byPath[path] ?: continue
            if (!policy.allowsPath(path)) continue
            take(entry)
        }
        for (entry in entries.sortedBy { it.path }) {
            if (picked.size >= config.maxContextFiles) break
            if (entry.path in pickedPaths) continue
            if (config.contextPathPrefixes.none { matches(entry.path, it) }) continue
            take(entry)
        }
        return picked
    }

    /** A prefix ending in `/` is a directory prefix; any other prefix is an exact path. */
    private fun matches(path: String, prefix: String): Boolean =
        if (prefix.endsWith("/")) path.startsWith(prefix) else path == prefix
}
