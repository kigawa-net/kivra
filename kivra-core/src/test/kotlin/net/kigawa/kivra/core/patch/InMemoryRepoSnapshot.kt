package net.kigawa.kivra.core.patch

/** Test double for [RepoSnapshot], backed by a map of path to content. */
class InMemoryRepoSnapshot(files: Map<String, String>) : RepoSnapshot {
    private val files: Map<String, String> = files.toMap()

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun size(path: String): Long? = files[path]?.let { utf8Size(it) }
}
