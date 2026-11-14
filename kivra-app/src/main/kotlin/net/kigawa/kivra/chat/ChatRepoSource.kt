package net.kigawa.kivra.chat

import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.selfdev.RepoReader

/**
 * The repository reads of the project chat's AI (ADR 0023 追補 7), built only while self-development is on (it reuses its GitHub reader). Read
 * only: the tree and the text of a file at the head of [baseBranch]. The paths the AI may read are the self-development context rules:
 * [prefixes] (`KIVRA_SELFDEV_CONTEXT_PREFIXES`; an entry ending in `/` is a directory prefix, any other entry an exact path) and
 * [maxFileBytes] (`KIVRA_SELFDEV_MAX_CONTEXT_FILE_BYTES`).
 *
 * [defaultRepo] is the self-development target, read when the project has no repository mapped. The AI never names a repository the server
 * does not allow: the project's mapped repositories, or this default.
 */
class ChatRepoSource(
    val reader: RepoReader,
    /** The head commit of a branch, or null when the branch is missing. */
    private val branchHead: suspend (RepoRef, String) -> GitHubResult<String?>,
    val defaultRepo: RepoRef,
    val baseBranch: String,
    val prefixes: List<String>,
    val maxFileBytes: Long,
) {
    suspend fun head(repo: RepoRef): GitHubResult<String?> = branchHead(repo, baseBranch)

    /** Whether [path] is inside the context prefixes (the same rule as the self-development context selection). */
    fun allows(path: String): Boolean = prefixes.any { p -> if (p.endsWith("/")) path.startsWith(p) else path == p }

    companion object {
        /** The longest path the AI may give. */
        const val PATH_MAX = 300

        /**
         * A repository path as the AI may give it, or null: no leading `/` or `\`, no backslash, no control character, and no empty, `.`,
         * `..` or `.git*` segment. Nothing is resolved: a path that is not exactly in the tree is not found.
         */
        fun normalizedPath(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty() || value.length > PATH_MAX) return null
            if (value.startsWith("/") || value.contains('\\') || value.any { it.isISOControl() }) return null
            val segments = value.trimEnd('/').split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".git") }) return null
            return value
        }
    }
}
