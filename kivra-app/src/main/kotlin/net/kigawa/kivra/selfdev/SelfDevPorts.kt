package net.kigawa.kivra.selfdev

import java.time.Instant
import java.time.ZoneOffset
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.RepoRef

/**
 * The run store the runner depends on. [DbDevelopmentRunStore] adapts the existing [DevelopmentRunRepository]. Every write takes a
 * [Lease] and an expected version, so a stale worker is refused by the database (fencing).
 */
interface DevelopmentRunStore {
    fun find(runId: RunId): DevelopmentRun?

    /** Fenced transition. Throws the same exceptions as [DevelopmentRunRepository.transition]. */
    fun transition(
        lease: Lease,
        expectedVersion: Long,
        to: DevelopmentRunState,
        expectedFrom: DevelopmentRunState,
        reasonCode: String?,
    ): DevelopmentRun

    /** PULL_REQUEST -> CANCELLED with the reason code PR_ABANDONED. Pull requests are never touched. */
    fun closeAbandonedPullRequest(lease: Lease, expectedVersion: Long): DevelopmentRun

    /** The pull request binding the run records, or null when it has none (see [DevelopmentRunRepository.loadPullRequestBinding]). */
    fun loadBinding(runId: RunId): PullRequestBinding?

    /** Records the binding in PULL_REQUEST. Fenced like every write. A second bind overwrites the binding, so the runner binds once. */
    fun bindPullRequest(lease: Lease, expectedVersion: Long, binding: PullRequestBinding): DevelopmentRun

    /**
     * PULL_REQUEST -> COMPLETED after the merge Authority verified the merge. Throws the same exceptions as
     * [DevelopmentRunRepository.completePrOnlyRun]: [net.kigawa.kivra.db.PrMergeNotVerifiedException] for a refusal,
     * [net.kigawa.kivra.core.PrMergeAuthorityUnavailableException] when the Authority cannot answer.
     */
    suspend fun completePrOnlyRun(lease: Lease, expectedVersion: Long): DevelopmentRun
}

class DbDevelopmentRunStore(
    private val runs: DevelopmentRunRepository,
    private val actor: String = ACTOR,
) : DevelopmentRunStore {
    override fun find(runId: RunId): DevelopmentRun? = runs.get(runId)

    override fun transition(
        lease: Lease,
        expectedVersion: Long,
        to: DevelopmentRunState,
        expectedFrom: DevelopmentRunState,
        reasonCode: String?,
    ): DevelopmentRun = runs.transition(
        lease, expectedVersion, to, actor,
        reason = reasonCode, expectedFrom = expectedFrom, auditReasonCode = reasonCode,
    )

    override fun closeAbandonedPullRequest(lease: Lease, expectedVersion: Long): DevelopmentRun =
        runs.closeAbandonedPullRequest(lease, expectedVersion, actor)

    override fun loadBinding(runId: RunId): PullRequestBinding? = runs.loadPullRequestBinding(runId)

    override fun bindPullRequest(lease: Lease, expectedVersion: Long, binding: PullRequestBinding): DevelopmentRun =
        runs.bindPullRequest(lease, expectedVersion, binding, actor)

    override suspend fun completePrOnlyRun(lease: Lease, expectedVersion: Long): DevelopmentRun =
        runs.completePrOnlyRun(lease, expectedVersion, actor)

    private companion object {
        const val ACTOR = "selfdev-runner"
    }
}

/**
 * One path of the repository tree with its size in bytes. [readable] is false for a symlink or a submodule: such a path is listed (so it
 * counts as existing, and is never overwritten as a new file) but its content is never read or shown to the LLM.
 */
data class RepoEntry(val path: String, val sizeBytes: Long, val readable: Boolean = true)

/**
 * Read-only access to the repository at one commit. Contract: [listFiles] returns the complete tree. A truncated or partial listing
 * must be a failure, never a short list: a file missing from the list is treated as new, and new files may be written.
 *
 * [readFile] returns `Success(null)` for a file that is excluded by design: binary, not UTF-8, or over the reader's size limit. Such a
 * file is left out of the LLM context. It still exists in the listing, so it is never overwritten (UNSEEN_OVERWRITE). Real failures
 * (network, server error, a missing path after the listing) stay failures.
 */
interface RepoReader {
    suspend fun listFiles(repo: RepoRef, commitSha: String): GitHubResult<List<RepoEntry>>

    suspend fun readFile(repo: RepoRef, commitSha: String, path: String): GitHubResult<String?>
}

/** The task the Developer works on. Untrusted text, written by the task author. Never logged, never put in a reason code. */
data class TaskText(val title: String, val description: String) {
    override fun toString(): String = "TaskText(titleChars=${title.length}, descriptionChars=${description.length})"
}

/** Supplies the task text for a run. Returns null when the text is not available (the run then does not advance). */
fun interface TaskTextSource {
    fun taskText(run: DevelopmentRun): TaskText?
}

/** The instant of the run's last update. The database stores UTC wall-clock time without an offset. */
internal fun DevelopmentRun.updatedInstant(): Instant = updatedAt.toInstant(ZoneOffset.UTC)
