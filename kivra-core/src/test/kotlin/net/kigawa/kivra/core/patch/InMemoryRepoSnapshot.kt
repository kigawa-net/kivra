package net.kigawa.kivra.core.patch

/**
 * Test double for [RepoSnapshot], backed by a map of path to content. [inContext] is the set of paths the LLM was shown;
 * null means every file is in context (the default, matching [RepoSnapshot.isInContext]).
 */
class InMemoryRepoSnapshot(files: Map<String, String>, inContext: Set<String>? = null) : RepoSnapshot {
    private val files: Map<String, String> = files.toMap()
    private val inContext: Set<String>? = inContext?.toSet()

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun size(path: String): Long? = files[path]?.let { utf8Size(it) }

    override fun isInContext(path: String): Boolean = inContext?.contains(path) ?: true
}
