package net.kigawa.kivra.selfdev

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
 * Chooses the files the LLM is shown: the entries matching [SelfDevConfig.contextPathPrefixes], in path order, within the file
 * and byte limits. Deterministic: the same tree always gives the same selection. Oversize files are skipped, not truncated.
 */
internal object ContextSelection {
    fun select(entries: List<RepoEntry>, config: SelfDevConfig): List<RepoEntry> {
        val picked = ArrayList<RepoEntry>()
        var total = 0L
        for (entry in entries.sortedBy { it.path }) {
            if (picked.size >= config.maxContextFiles) break
            if (!entry.readable) continue
            if (config.contextPathPrefixes.none { matches(entry.path, it) }) continue
            if (entry.sizeBytes > config.maxContextFileBytes) continue
            if (total + entry.sizeBytes > config.maxContextTotalBytes) continue
            picked += entry
            total += entry.sizeBytes
        }
        return picked
    }

    /** A prefix ending in `/` is a directory prefix; any other prefix is an exact path. */
    private fun matches(path: String, prefix: String): Boolean =
        if (prefix.endsWith("/")) path.startsWith(prefix) else path == prefix
}
