package net.kigawa.kivra.selfdev

import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RepoTree
import net.kigawa.kivra.github.TreeEntryType

/**
 * The GitHub implementation of [RepoReader]. Read-only: it calls [GitHubPort.listTree] and [GitHubPort.readFile] and nothing else.
 *
 * [listFiles] lists every blob, symlink and submodule, but not directories. Symlinks (`120000`) and submodules (`160000`) are not
 * regular files and are never read, but their paths are listed so that [SelfDevSnapshot.exists] sees them. A path that exists
 * therefore counts as existing (not overwritable as new), whatever kind of object it is. A blob with any other mode is a
 * protocol failure.
 *
 * [readFile] returns a failure, never a silent skip, for: a file that is not text (binary or not UTF-8), a file over
 * [maxFileBytes], and a path that [listFiles] listed but GitHub now reports as missing. Any failure makes the runner fail the run
 * (`GITHUB_READ_FAILED`), so a file the LLM was supposed to see is never silently dropped.
 *
 * Failures of [GitHubPort] are passed through unchanged. A truncated tree is `TREE_TRUNCATED`, so no partial listing is returned.
 */
class GitHubRepoReader(
    private val github: GitHubPort,
    private val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
) : RepoReader {
    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
    }

    override suspend fun listFiles(repo: RepoRef, commitSha: String): GitHubResult<List<RepoEntry>> =
        when (val r = github.listTree(repo, commitSha)) {
            is GitHubResult.Success -> toEntries(r.value)
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }

    override suspend fun readFile(repo: RepoRef, commitSha: String, path: String): GitHubResult<String> =
        when (val r = github.readFile(repo, commitSha, path, maxFileBytes)) {
            is GitHubResult.Success -> when (val content = r.value) {
                null -> GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, GitHubFailureKind.NOT_FOUND.code)
                else -> content.text?.let { GitHubResult.Success(it) }
                    ?: GitHubResult.Failure(GitHubFailureKind.VALIDATION, "GITHUB_VALIDATION:not text")
            }
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }

    private fun toEntries(tree: RepoTree): GitHubResult<List<RepoEntry>> {
        val out = ArrayList<RepoEntry>(tree.entries.size)
        for (entry in tree.entries) {
            when (entry.type) {
                TreeEntryType.TREE -> {}
                TreeEntryType.COMMIT -> out += RepoEntry(entry.path, 0L)
                TreeEntryType.BLOB -> {
                    if (entry.mode !in BLOB_MODES) return GitHubResult.Failure(GitHubFailureKind.PROTOCOL, GitHubFailureKind.PROTOCOL.code)
                    out += RepoEntry(entry.path, entry.sizeBytes ?: 0L)
                }
            }
        }
        return GitHubResult.Success(out)
    }

    companion object {
        /** The same ceiling as [net.kigawa.kivra.github.CommitFilesLimits.maxFileBytes]. */
        const val DEFAULT_MAX_FILE_BYTES: Int = 1_048_576

        /** Regular files, executable files and symlinks. */
        private val BLOB_MODES = setOf("100644", "100755", "120000")
    }
}
