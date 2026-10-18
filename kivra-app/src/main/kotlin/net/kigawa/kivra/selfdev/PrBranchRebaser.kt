package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.delay
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PrBranchPort
import net.kigawa.kivra.github.PullRequest
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RepoRef
import net.kigawa.kivra.github.RepoTree
import net.kigawa.kivra.github.TreeEntryType
import org.slf4j.LoggerFactory

/** Limits of the pull request branch update (issue #276). Per pull request: [maxPerWindow] per [window], [maxTotal] in all. */
data class PrRebaseSettings(
    val maxPerWindow: Int = 1,
    val window: Duration = Duration.ofHours(1),
    val maxTotal: Int = 3,
    /** How often, and how many times, the pull request is read after a successful update request, to see the new head. */
    val observePolls: Int = 5,
    val observeInterval: Duration = Duration.ofSeconds(2),
)

/**
 * Keeps a waiting self-development pull request on top of `main` (issue #276). It is called only from the pull request step, while the
 * run waits for a person, and only when the feature is switched on (`KIVRA_SELFDEV_PR_REBASE_ENABLED`, off by default).
 *
 * Two paths:
 * - **Behind, no conflict, the head is still the recorded one**: the update is requested with `expected_head_sha` (after the audit row
 *   of the attempt is written). The new head is then verified and, only if it passes, recorded.
 * - **The head differs from the recorded one** (an update that landed later, or a push by a person): the new head is verified in the same
 *   way, and only a passing head is recorded. A head that does not pass stays, and the run waits for a person.
 *
 * Verification of a new head `N` against the recorded head `O` and the current `main` head `M` (all must hold):
 * (a) `N` is a merge commit whose two parents are exactly `O` and `M`.
 * (b) The pull request's own change set is the same before and after: the files that differ between `merge-base(O, M)` and `O` (path, mode
 *     and blob hash, sorted) equal the files that differ between `M` and `N`. A change set that differs, or that cannot be read in full,
 *     is refused. Because the change set is the same, the policy result is the same, so the policy is not run again.
 *
 * A refusal is never a failure: the run stays in PULL_REQUEST with a fixed reason code. Logs and audit rows carry the run id, the first 12
 * characters of heads, and fixed codes only: never a title, body, path, file content, or model output.
 */
class PrBranchRebaser(
    private val repo: RepoRef,
    private val baseBranch: String,
    /** The App bot's user id. A pull request opened by anyone else is never touched. */
    private val botUserId: Long,
    private val port: PrBranchPort,
    private val github: GitHubPort,
    private val runs: DevelopmentRunStore,
    private val settings: PrRebaseSettings = PrRebaseSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val pause: suspend (Duration) -> Unit = { delay(it.toMillis()) },
) {
    /** What one look did. [Held] carries a fixed reason code. [Rebound] means the recorded head was moved to a verified head. */
    sealed interface Outcome {
        data object NoAction : Outcome
        data class Held(val code: String) : Outcome
        data object Rebound : Outcome
    }

    /**
     * One look at a pull request that waits for a person. [renew] renews the lease before a provider write (it throws when the lease is
     * lost), and [version] reads the run's current version for a fenced write. Lease loss and version conflicts are not caught here: the
     * runner maps them to a skipped call.
     */
    suspend fun look(
        lease: Lease,
        run: DevelopmentRun,
        binding: PullRequestBinding,
        pullRequest: PullRequest,
        renew: () -> Unit,
        version: () -> Long,
    ): Outcome {
        if (pullRequest.state != PullRequestState.OPEN || pullRequest.merged) return Outcome.NoAction
        if (pullRequest.authorId != botUserId) return Outcome.NoAction
        if (pullRequest.headSha == binding.headSha) return updateIfBehind(lease, run, binding, pullRequest, renew, version)
        // The head differs from the recorded one: a landed update or a push by a person. Only a verified head is recorded.
        val verdict = verify(binding.headSha, pullRequest.headSha)
        if (verdict != null) return held(run, verdict)
        return rebind(lease, run, binding.headSha, pullRequest.headSha, renew, version)
    }

    private suspend fun updateIfBehind(
        lease: Lease,
        run: DevelopmentRun,
        binding: PullRequestBinding,
        pullRequest: PullRequest,
        renew: () -> Unit,
        version: () -> Long,
    ): Outcome {
        val view = when (val r = port.getPrRebaseView(repo, pullRequest.number)) {
            is GitHubResult.Success -> r.value
            else -> return Outcome.NoAction
        }
        // "behind" means the head is behind the base with no conflict. A conflict ("dirty"), an unknown state or a clean head is not touched.
        if (view.mergeableState != MERGEABLE_BEHIND || view.pullRequest.headSha != binding.headSha) return Outcome.NoAction

        val now = clock.instant()
        val (total, recent) = runs.countRebaseAttempts(run.runId, now.minus(settings.window))
        if (total >= settings.maxTotal) return held(run, LIMIT_TOTAL)
        if (recent >= settings.maxPerWindow) return Outcome.NoAction

        // The attempt is written before the request, so a crash after the request still counts against the limit.
        renew()
        runs.recordRebaseAttempt(lease, binding.headSha)
        renew()
        // A refused request (409, 422) is recorded with its fixed code. An unknown outcome may still have landed: the head is read below.
        val updateCode = when (val update = port.updatePrBranch(repo, pullRequest.number, binding.headSha)) {
            is GitHubResult.Success -> null
            is GitHubResult.Failure -> update.kind.code
            is GitHubResult.Unknown -> UPDATE_UNKNOWN
        }
        val newHead = observeNewHead(pullRequest.number, binding.headSha)
        if (newHead == null) return holdAfterAttempt(lease, run, binding.headSha, updateCode ?: NOT_OBSERVED)
        val verdict = verify(binding.headSha, newHead)
        if (verdict != null) return holdAfterAttempt(lease, run, binding.headSha, verdict)
        return rebind(lease, run, binding.headSha, newHead, renew, version)
    }

    /** Reads the pull request until its head differs from [oldHead], at most [PrRebaseSettings.observePolls] times. */
    private suspend fun observeNewHead(number: Int, oldHead: String): String? {
        repeat(settings.observePolls) {
            pause(settings.observeInterval)
            val head = (github.getPullRequest(repo, number) as? GitHubResult.Success)?.value?.headSha
            if (head != null && head != oldHead) return head
        }
        return null
    }

    /**
     * Verifies [newHead] against [oldHead] and the current base head. Returns null when it passes, else the fixed reason code. Every read
     * is a read-only GET, and a failed or truncated read is a refusal, never a pass.
     */
    private suspend fun verify(oldHead: String, newHead: String): String? {
        val main = when (val r = github.getBranch(repo, baseBranch)) {
            is GitHubResult.Success -> r.value?.headSha ?: return VERIFY_FAILED
            else -> return VERIFY_FAILED
        }
        if (main == oldHead || newHead == oldHead) return PARENTS_MISMATCH
        val parents = (port.getCommitParents(repo, newHead) as? GitHubResult.Success)?.value ?: return VERIFY_FAILED
        // (a) A merge of exactly the recorded head and the base head.
        if (parents.size != 2 || parents.toSet() != setOf(oldHead, main)) return PARENTS_MISMATCH

        val base = (port.mergeBaseOf(repo, oldHead, main) as? GitHubResult.Success)?.value ?: return VERIFY_FAILED
        val baseTree = treeOf(base) ?: return VERIFY_FAILED
        val oldTree = treeOf(oldHead) ?: return VERIFY_FAILED
        val mainTree = treeOf(main) ?: return VERIFY_FAILED
        val newTree = treeOf(newHead) ?: return VERIFY_FAILED
        // (b) The pull request's own change set is identical before and after.
        val before = changedFiles(baseTree, oldTree) ?: return VERIFY_FAILED
        val after = changedFiles(mainTree, newTree) ?: return VERIFY_FAILED
        return if (before == after) null else DIFF_CHANGED
    }

    private suspend fun treeOf(commitSha: String): RepoTree? =
        (port.listTreeAt(repo, commitSha) as? GitHubResult.Success)?.value

    /** One file of a change set: its path, mode and blob hash after the change. An absent side is empty. */
    private data class ChangedFile(val path: String, val mode: String, val blobSha: String)

    /** Blob-like entries of the tree by path. Null when an entry has no object id (the listing cannot prove its content). */
    private fun blobsOf(tree: RepoTree): Map<String, Pair<String, String>>? {
        val out = HashMap<String, Pair<String, String>>()
        for (e in tree.entries) {
            if (e.type == TreeEntryType.TREE) continue
            val sha = e.sha ?: return null
            out[e.path] = e.mode to sha
        }
        return out
    }

    /** The files that differ from [from] to [to], sorted by path. Null when either side cannot be read in full. */
    private fun changedFiles(from: RepoTree, to: RepoTree): List<ChangedFile>? {
        val a = blobsOf(from) ?: return null
        val b = blobsOf(to) ?: return null
        return (a.keys + b.keys).sorted().mapNotNull { path ->
            val x = a[path]
            val y = b[path]
            if (x == y) null else ChangedFile(path, y?.first.orEmpty(), y?.second.orEmpty())
        }
    }

    private fun rebind(lease: Lease, run: DevelopmentRun, fromHead: String, toHead: String, renew: () -> Unit, version: () -> Long): Outcome {
        renew()
        return try {
            runs.rebindPullRequestHead(lease, version(), fromHead, toHead, VERIFIED)
            logger.info("pull request head rebound: runId={} code={}", run.runId.value, VERIFIED)
            Outcome.Rebound
        } catch (e: LeaseLostException) {
            throw e
        } catch (e: VersionConflictException) {
            throw e
        } catch (e: InvalidTransitionException) {
            throw e
        } catch (e: RuntimeException) {
            // Rolled back as a whole by the store: the recorded head is still [fromHead].
            logger.warn("pull request head rebind failed: runId={} code={}", run.runId.value, REBIND_FAILED)
            Outcome.Held(REBIND_FAILED)
        }
    }

    /** A hold that follows an attempt: it is audited, so the run's trail shows why the branch was left. */
    private fun holdAfterAttempt(lease: Lease, run: DevelopmentRun, fromHead: String, code: String): Outcome {
        runs.recordRebaseHold(lease, fromHead, code)
        return held(run, code)
    }

    private fun held(run: DevelopmentRun, code: String): Outcome {
        logger.info("pull request branch left for a person: runId={} code={}", run.runId.value, code)
        return Outcome.Held(code)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PrBranchRebaser::class.java)

        /** GitHub's `mergeable_state` when the head is behind the base and has no conflict. */
        const val MERGEABLE_BEHIND = "behind"

        const val VERIFIED = "PR_REBASE_VERIFIED"
        const val LIMIT_TOTAL = "PR_REBASE_LIMIT_TOTAL"
        const val UPDATE_UNKNOWN = "PR_REBASE_UPDATE_UNKNOWN"
        const val NOT_OBSERVED = "PR_REBASE_NOT_OBSERVED"
        const val PARENTS_MISMATCH = "PR_REBASE_PARENTS_MISMATCH"
        const val DIFF_CHANGED = "PR_REBASE_DIFF_CHANGED"
        const val VERIFY_FAILED = "PR_REBASE_VERIFY_FAILED"
        const val REBIND_FAILED = "PR_REBASE_REBIND_FAILED"
    }
}
