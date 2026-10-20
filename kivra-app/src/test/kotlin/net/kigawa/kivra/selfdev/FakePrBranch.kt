package net.kigawa.kivra.selfdev

import net.kigawa.kivra.github.ComparedFile
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PrBranchPort
import net.kigawa.kivra.github.PrRebaseView
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RepoTree
import net.kigawa.kivra.github.TreeEntry
import net.kigawa.kivra.github.TreeEntryType

/**
 * A small git object graph for the branch-update tests: commits with parents and a flat tree (path to mode and blob id). The tests
 * register every commit they need, so the verification sees exactly the parents and trees the test describes.
 */
class FakeGitObjects {
    class Commit(val parents: List<String>, val tree: Map<String, Pair<String, String>>)

    private val commits = HashMap<String, Commit>()

    fun commit(sha: String, parents: List<String>, tree: Map<String, Pair<String, String>>) {
        commits[sha] = Commit(parents, tree)
    }

    fun parentsOf(sha: String): List<String>? = commits[sha]?.parents

    /** The commits [base] has that [head] does not have, as the compare API counts them (`behind_by`). Null when a commit is unknown. */
    fun behindCount(base: String, head: String): Int? {
        if (base !in commits || head !in commits) return null
        return (ancestors(base) - ancestors(head)).size
    }

    /** The commits [head] has that [base] does not have, as the compare API counts them (`ahead_by`). Null when a commit is unknown. */
    fun aheadCount(base: String, head: String): Int? {
        if (base !in commits || head !in commits) return null
        return (ancestors(head) - ancestors(base)).size
    }

    fun treeOf(sha: String): Map<String, Pair<String, String>>? = commits[sha]?.tree

    /** The merge base of [a] and [b]: a common ancestor that is not an ancestor of another common ancestor. */
    fun mergeBase(a: String, b: String): String? {
        val common = ancestors(a) intersect ancestors(b)
        return common.firstOrNull { c -> common.none { d -> d != c && c in ancestors(d) } }
    }

    private fun ancestors(sha: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(sha))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            commits[next]?.parents?.let { queue.addAll(it) }
        }
        return seen
    }
}

/**
 * In-memory [PrBranchPort] over [FakeGitHub] (pull requests, branches) and [FakeGitObjects] (commits). Every call is counted.
 * [onUpdate] runs when an update request is accepted: a test uses it to make the update land (move the pull request head) or not.
 */
class FakePrBranch(private val github: FakeGitHub, private val git: FakeGitObjects) : PrBranchPort {
    /** GitHub's `mergeable_state` reported for the pull request. A real repository without the up-to-date protection reports "unstable". */
    var mergeableState: String? = "unstable"

    /** GitHub's `mergeable` reported for the pull request: null while computing, false on a conflict. */
    var mergeable: Boolean? = true

    /** When set, the compare request answers with this result instead of the distance computed from the object graph. */
    var compareResult: GitHubResult<Int>? = null

    var compareCalls = 0
        private set

    /** When set, the ahead-by compare answers with this result instead of the count computed from the object graph. */
    var aheadResult: GitHubResult<Int>? = null

    /** When set, the update request answers with this result (and does not run [onUpdate]). */
    var updateResult: GitHubResult<Unit>? = null

    /** Runs first in every update request, before the answer: a test moves the head here to model a push that races the request. */
    var beforeUpdate: (Int) -> Unit = { _ -> }

    /** Called with the pull request number and the expected head when an update request is accepted. */
    var onUpdate: (Int, String) -> Unit = { _, _ -> }

    var updateCalls = 0
        private set
    var lastExpectedHead: String? = null
        private set

    /** When set, the merge base read answers with this failure instead of the merge base computed from the object graph. */
    var mergeBaseFailure: GitHubFailureKind? = null

    /** Commits whose tree read fails with the given kind (for example a truncated tree). */
    val failingTrees = HashMap<String, GitHubFailureKind>()

    var mergeBaseCalls = 0
        private set
    var treeCalls = 0
        private set

    /** Every call of the port, reads included. */
    var totalCalls = 0
        private set

    override suspend fun getPrRebaseView(repo: RepoRef, number: Int): GitHubResult<PrRebaseView> {
        totalCalls++
        return when (val r = github.getPullRequest(repo, number)) {
            is GitHubResult.Success -> GitHubResult.Success(PrRebaseView(r.value, mergeableState, mergeable))
            is GitHubResult.Failure -> r
            is GitHubResult.Unknown -> r
        }
    }

    override suspend fun getBehindBy(repo: RepoRef, base: String, headSha: String): GitHubResult<Int> {
        totalCalls++
        compareCalls++
        compareResult?.let { return it }
        // The compare API takes a branch name for the base: it is resolved to the branch's head, as GitHub does.
        val baseSha = (github.getBranch(repo, base) as? GitHubResult.Success)?.value?.headSha ?: return notFound()
        return git.behindCount(baseSha, headSha)?.let { GitHubResult.Success(it) } ?: notFound()
    }

    override suspend fun getAheadBy(repo: RepoRef, baseSha: String, headSha: String): GitHubResult<Int> {
        totalCalls++
        aheadResult?.let { return it }
        return git.aheadCount(baseSha, headSha)?.let { GitHubResult.Success(it) } ?: notFound()
    }

    /** The compare answer per (base, head) commit pair. A pair that is not registered is not found. */
    val compareFiles = HashMap<Pair<String, String>, List<ComparedFile>>()

    /** When set, every compare-files read fails with this kind. */
    var compareFilesFailure: GitHubFailureKind? = null

    var compareFilesCalls = 0
        private set

    override suspend fun getCompareFiles(repo: RepoRef, base: String, head: String): GitHubResult<List<ComparedFile>> {
        totalCalls++
        compareFilesCalls++
        compareFilesFailure?.let { return GitHubResult.Failure(it, it.code) }
        return compareFiles[base to head]?.let { GitHubResult.Success(it) } ?: notFound()
    }

    override suspend fun updatePrBranch(repo: RepoRef, number: Int, expectedHeadSha: String): GitHubResult<Unit> {
        totalCalls++
        updateCalls++
        lastExpectedHead = expectedHeadSha
        beforeUpdate(number)
        updateResult?.let { return it }
        onUpdate(number, expectedHeadSha)
        return GitHubResult.Success(Unit)
    }

    override suspend fun getCommitParents(repo: RepoRef, sha: String): GitHubResult<List<String>> {
        totalCalls++
        return git.parentsOf(sha)?.let { GitHubResult.Success(it) } ?: notFound()
    }

    override suspend fun mergeBaseOf(repo: RepoRef, a: String, b: String): GitHubResult<String> {
        totalCalls++
        mergeBaseCalls++
        mergeBaseFailure?.let { return GitHubResult.Failure(it, it.code) }
        return git.mergeBase(a, b)?.let { GitHubResult.Success(it) } ?: notFound()
    }

    override suspend fun listTreeAt(repo: RepoRef, commitSha: String): GitHubResult<RepoTree> {
        totalCalls++
        treeCalls++
        failingTrees[commitSha]?.let { return GitHubResult.Failure(it, it.code) }
        val tree = git.treeOf(commitSha) ?: return notFound()
        val entries = tree.map { (path, modeAndBlob) ->
            TreeEntry(path, TreeEntryType.BLOB, modeAndBlob.first, 1L, modeAndBlob.second)
        }
        return GitHubResult.Success(RepoTree(commitSha, entries, truncated = false))
    }

    private fun <T> notFound(): GitHubResult<T> = GitHubResult.Failure(GitHubFailureKind.NOT_FOUND, GitHubFailureKind.NOT_FOUND.code)
}
