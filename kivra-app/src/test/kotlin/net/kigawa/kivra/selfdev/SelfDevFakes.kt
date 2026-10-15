package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.DeveloperUsage
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.modelgateway.Usage

/** Serves the files of one commit. Any other commit is NOT_FOUND. Sizes are UTF-8 bytes, as the GitHub tree reports them. */
class InMemoryRepoReader(private val files: Map<String, String>, private val commitSha: String) : RepoReader {
    var listCalls = 0
        private set

    override suspend fun listFiles(repo: RepoRef, commitSha: String): GitHubResult<List<RepoEntry>> {
        listCalls++
        if (commitSha != this.commitSha) return GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, GitHubFailureKind.NOT_FOUND.code)
        return GitHubResult.Success(files.map { (path, text) -> RepoEntry(path, text.toByteArray(Charsets.UTF_8).size.toLong()) }.sortedBy { it.path })
    }

    override suspend fun readFile(repo: RepoRef, commitSha: String, path: String): GitHubResult<String> {
        val text = files[path]
        if (commitSha != this.commitSha || text == null) return GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, GitHubFailureKind.NOT_FOUND.code)
        return GitHubResult.Success(text)
    }
}

/** Task text by task id. The runs in these tests all come from one task unless the test says otherwise. */
class FixedTaskTextSource(private val text: TaskText?) : TaskTextSource {
    override fun taskText(run: DevelopmentRun): TaskText? = text
}

object DevFixtures {
    val usage = DeveloperUsage(attempts = 1, usage = Usage(inputTokens = 10, outputTokens = 5), costs = emptyList())

    /** A proposal that writes one docs file. */
    fun proposed(vararg writes: Pair<String, String>, summary: String = "docs を更新する"): DeveloperResult.Proposed =
        DeveloperResult.Proposed(PatchSet(writes.map { (path, content) -> Write(path, content) }, summary), usage)

    val taskText = TaskText(title = "README を直す", description = "TASK-SECRET-MARKER 本文")
}
