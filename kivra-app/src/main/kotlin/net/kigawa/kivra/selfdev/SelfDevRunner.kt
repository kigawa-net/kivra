package net.kigawa.kivra.selfdev

import java.time.Clock
import java.time.Duration
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.PatchVerdict
import net.kigawa.kivra.core.patch.RepoSnapshot
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.DeveloperRequest
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.RepoFile
import net.kigawa.kivra.github.BranchInfo
import net.kigawa.kivra.github.CommitFilesRequest
import net.kigawa.kivra.github.CreatePullRequest
import net.kigawa.kivra.github.FileWrite
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RunBranch
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Failed
import net.kigawa.kivra.selfdev.AdvanceResult.Skipped
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting

/**
 * Self-development runner (ADR 0008 part 4, Issue #56). It moves one PR_ONLY run forward, one [advance] call at a time:
 *
 * | state at call          | what the call does                                                                 | persisted                          |
 * |------------------------|-------------------------------------------------------------------------------------|------------------------------------|
 * | CREATED                | nothing else                                                                        | -> DEVELOPING                      |
 * | DEVELOPING             | propose (LLM), PatchPolicy, commit, pull request                                    | -> PATCH_READY -> PULL_REQUEST     |
 * | PATCH_READY (resume)   | propose again, PatchPolicy, commit, pull request                                    | -> PULL_REQUEST (or a failure)     |
 * | PULL_REQUEST           | read the pull request: wait, abandon (closed or timed out), or wait for verification | -> CANCELLED / UNKNOWN, or nothing |
 * | UNKNOWN                | nothing (waits for an operator)                                                     | nothing                            |
 *
 * A call may persist two transitions (DEVELOPING -> PATCH_READY -> PULL_REQUEST). PATCH_READY is committed before the first GitHub
 * write, so a crash after it leaves a PATCH_READY run.
 *
 * Limits, by design (ADR 0008):
 * - **The change set is not stored.** The schema has nowhere to keep a PatchSet, so a resume from PATCH_READY asks the Developer
 *   again. The gateway ledger refuses a second call for the same run (requestId `developer-<runId>-1`), so in production a resume
 *   normally ends in UNKNOWN, which an operator releases. A resume never commits without a fresh, policy-checked proposal.
 * - **Double commit guard.** A resumed run whose branch is not at the current base head is treated as already committed, and goes to
 *   UNKNOWN. This can also fire when the base moved, which is safe (fail closed) but needs an operator.
 * - **Merge is not completed.** Completion must go through `completePrOnlyRun`, which needs a PullRequestBinding with a policy revision
 *   and a review manifest digest. This flow has neither, so a merged pull request keeps the run in PULL_REQUEST with
 *   [WaitReason.AWAITING_MERGE_VERIFICATION]. kivra never merges and never closes a pull request.
 *
 * Safety layers: the kill switch ([SelfDevConfig.enabled]); the fenced lease (a stale worker's writes are refused by the database,
 * and the lease is renewed before each GitHub write); [PatchPolicy] again on the runner side (the Developer is not trusted); the
 * UNSEEN_OVERWRITE check (the snapshot knows which files the LLM saw); and fixed reason codes only.
 *
 * Logging and results: nothing here logs. Results and reason codes never contain task text, model output, patch content, tokens or
 * paths. Errors from the store and the lease are mapped to [AdvanceResult] codes, not passed on.
 */
class SelfDevRunner(
    private val config: SelfDevConfig,
    private val runs: DevelopmentRunStore,
    private val leases: LeaseStore,
    private val developer: DeveloperPort,
    private val github: GitHubPort,
    private val repoReader: RepoReader,
    private val taskTexts: TaskTextSource,
    private val clock: Clock = Clock.systemUTC(),
    private val policy: PatchPolicy = PatchPolicy(),
) {
    /** Advances [runId] by one call (see the class KDoc). Never throws for a provider or lease condition. */
    suspend fun advance(runId: RunId): AdvanceResult {
        if (!config.enabled) return Skipped(SkipReason.DISABLED)
        val run = runs.find(runId) ?: return Skipped(SkipReason.RUN_NOT_FOUND)
        if (run.completionMode != RunCompletionMode.PR_ONLY) return Skipped(SkipReason.NOT_PR_ONLY)
        if (run.state.isTerminal) return Skipped(SkipReason.TERMINAL)
        if (run.state == DevelopmentRunState.UNKNOWN) return Waiting(WaitReason.AWAITING_OPERATOR)

        val lease = leases.acquire(runId, config.workerId, config.leaseTtl, Duration.ZERO)
            ?: return Skipped(SkipReason.LEASE_NOT_ACQUIRED)
        return try {
            step(lease)
        } catch (e: RunHaltedException) {
            Skipped(SkipReason.HALTED)
        } catch (e: LeaseLostException) {
            Skipped(SkipReason.LEASE_LOST)
        } catch (e: VersionConflictException) {
            Failed(FailureCode.VERSION_CONFLICT)
        } catch (e: InvalidTransitionException) {
            Failed(FailureCode.INVALID_TRANSITION)
        } finally {
            leases.release(lease)
        }
    }

    private suspend fun step(lease: Lease): AdvanceResult {
        val run = runs.find(lease.runId) ?: return Skipped(SkipReason.RUN_NOT_FOUND)
        // A cancel request is acknowledged by moving to CANCELLED, which the lease holder is allowed to do.
        if (run.cancelRequested) return persistAdvance(lease, run, DevelopmentRunState.CANCELLED, CANCEL_REQUESTED)
        return when (run.state) {
            DevelopmentRunState.CREATED -> persistAdvance(lease, run, DevelopmentRunState.DEVELOPING, null)
            DevelopmentRunState.DEVELOPING -> develop(lease, run)
            DevelopmentRunState.PATCH_READY -> resumePatchReady(lease, run)
            DevelopmentRunState.PULL_REQUEST -> pullRequestStep(lease, run)
            DevelopmentRunState.UNKNOWN -> Waiting(WaitReason.AWAITING_OPERATOR)
            else -> Skipped(SkipReason.TERMINAL)
        }
    }

    // ---- DEVELOPING: propose, then commit and open the pull request in the same call ----

    private suspend fun develop(lease: Lease, run: DevelopmentRun): AdvanceResult {
        val base = when (val s = readBase()) {
            is Step.Go -> s.value
            is Step.Done -> return s.result
        }
        val patch = when (val s = propose(lease, run, base, resuming = false)) {
            is Step.Go -> s.value
            is Step.Done -> return s.result
        }
        // PATCH_READY is persisted before the first GitHub write. A crash after this point leaves a PATCH_READY run.
        val patchReady = transitionTo(lease, run.state, DevelopmentRunState.PATCH_READY, null)
        return try {
            when (val end = commitAndOpenPullRequest(lease, patchReady, patch, base)) {
                is Advanced -> Advanced(DevelopmentRunState.DEVELOPING, end.to, end.reasonCode)
                else -> end
            }
        } catch (e: LeaseLostException) {
            // The run is already PATCH_READY. Report that progress, not a skip; the next call resumes from PATCH_READY.
            Advanced(DevelopmentRunState.DEVELOPING, DevelopmentRunState.PATCH_READY)
        }
    }

    // ---- PATCH_READY (resume after a crash): regenerate, then commit and open the pull request ----

    private suspend fun resumePatchReady(lease: Lease, run: DevelopmentRun): AdvanceResult {
        val base = when (val s = readBase()) {
            is Step.Go -> s.value
            is Step.Done -> return s.result
        }
        val patch = when (val s = propose(lease, run, base, resuming = true)) {
            is Step.Go -> s.value
            is Step.Done -> return s.result
        }
        return commitAndOpenPullRequest(lease, run, patch, base)
    }

    /**
     * Proposes a patch for [run]. A rejection, refusal or unknown outcome is persisted here. [resuming] is true for a PATCH_READY run,
     * where a refusal or unknown outcome becomes UNKNOWN instead of FAILED: the run had already promised a proposal.
     */
    private suspend fun propose(lease: Lease, run: DevelopmentRun, base: BranchInfo, resuming: Boolean): Step<PatchSet> {
        val task = taskTexts.taskText(run) ?: return Step.Done(Failed(FailureCode.TASK_TEXT_UNAVAILABLE))
        val view = when (val s = loadView(base.headSha)) {
            is Step.Go -> s.value
            is Step.Done -> return s
        }
        val request = DeveloperRequest(
            runId = run.runId.value,
            taskTitle = task.title,
            taskDescription = task.description,
            repoContext = view.contextFiles,
        )
        return when (val result = developer.propose(request)) {
            is DeveloperResult.Proposed -> when (val verdict = policy.validate(result.patch, view.snapshot)) {
                is PatchVerdict.Accepted -> Step.Go(verdict.patch)
                is PatchVerdict.Rejected -> Step.Done(persistAdvance(lease, run, DevelopmentRunState.FAILED, POLICY_REJECTED))
            }

            is DeveloperResult.Refused -> {
                val to = if (resuming) DevelopmentRunState.UNKNOWN else DevelopmentRunState.FAILED
                Step.Done(persistAdvance(lease, run, to, "$DEVELOPER_PREFIX${result.reason.name}"))
            }

            is DeveloperResult.Unknown ->
                Step.Done(persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, "$DEVELOPER_PREFIX${result.reason.name}"))
        }
    }

    /**
     * Branch, commit and pull request for a PATCH_READY run. Every GitHub write is preceded by a lease renewal, so a worker whose lease
     * has expired stops before it writes. A [LeaseLostException] propagates to the caller.
     */
    private suspend fun commitAndOpenPullRequest(
        lease: Lease,
        run: DevelopmentRun,
        patch: PatchSet,
        base: BranchInfo,
    ): AdvanceResult {
        val runId = run.runId.value
        renewOrThrow(lease)
        val branch = when (val r = github.ensureRunBranch(config.repo, runId, base.headSha)) {
            is GitHubResult.Success -> r.value
            is GitHubResult.Failure -> return persistAdvance(lease, run, DevelopmentRunState.FAILED, r.kind.code)
            is GitHubResult.Unknown -> return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, GITHUB_UNKNOWN)
        }
        // A branch that exists but is not at the base head holds an earlier commit of this run. Committing again would double it.
        if (!branch.created && branch.branch.headSha != base.headSha) {
            return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, BRANCH_ADVANCED_BEFORE_COMMIT)
        }

        val commitRequest = CommitFilesRequest(
            branch = RunBranch.name(runId),
            expectedHeadSha = branch.branch.headSha,
            message = "kivra: self-development run $runId",
            changes = patch.files.map {
                when (it) {
                    is Write -> FileWrite.Write(it.path, it.content)
                    is Delete -> FileWrite.Delete(it.path)
                }
            },
        )
        renewOrThrow(lease)
        var committed = github.commitFiles(config.repo, commitRequest)
        // The adapter already read the ref back. "Not moved" means nothing was written, so one more attempt is safe.
        if (committed is GitHubResult.Unknown && committed.cause == REF_NOT_MOVED) {
            renewOrThrow(lease)
            committed = github.commitFiles(config.repo, commitRequest)
        }
        val commitSha = when (committed) {
            is GitHubResult.Success -> committed.value.commitSha
            is GitHubResult.Failure -> return persistAdvance(lease, run, DevelopmentRunState.FAILED, committed.kind.code)
            is GitHubResult.Unknown -> return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, GITHUB_UNKNOWN)
        }

        renewOrThrow(lease)
        val pullRequest = CreatePullRequest(
            runId = runId,
            base = config.baseBranch,
            title = "Self-development run $runId",
            body = sanitizedSummary(patch.summary),
            headSha = commitSha,
        )
        when (val r = github.ensurePullRequest(config.repo, pullRequest)) {
            is GitHubResult.Success -> Unit
            is GitHubResult.Failure -> return persistAdvance(lease, run, DevelopmentRunState.FAILED, r.kind.code)
            is GitHubResult.Unknown -> return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, GITHUB_UNKNOWN)
        }
        return persistAdvance(lease, run, DevelopmentRunState.PULL_REQUEST, null)
    }

    // ---- PULL_REQUEST: read only, then close the run if the pull request was abandoned ----

    private suspend fun pullRequestStep(lease: Lease, run: DevelopmentRun): AdvanceResult {
        val runId = run.runId.value
        val candidates = when (val r = github.findPullRequestsByHead(config.repo, RunBranch.name(runId))) {
            is GitHubResult.Success -> r.value.filter { it.repo == config.repo && it.hasMarker(runId) }
            else -> return Failed(FailureCode.GITHUB_READ_FAILED)
        }
        val pullRequest = when (candidates.size) {
            0 -> return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, PR_NOT_FOUND)
            1 -> candidates.single()
            else -> return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, PR_AMBIGUOUS)
        }

        if (pullRequest.merged) return Waiting(WaitReason.AWAITING_MERGE_VERIFICATION)
        val abandoned = pullRequest.state == PullRequestState.CLOSED ||
            clock.instant().isAfter(run.updatedInstant().plus(config.prReviewTimeout))
        if (abandoned) {
            val closed = runs.closeAbandonedPullRequest(lease, currentVersion(lease))
            return Advanced(run.state, closed.state, PR_ABANDONED)
        }
        return Waiting(WaitReason.AWAITING_HUMAN_REVIEW)
    }

    // ---- helpers ----

    private suspend fun readBase(): Step<BranchInfo> = when (val r = github.getBranch(config.repo, config.baseBranch)) {
        is GitHubResult.Success -> r.value?.let { Step.Go(it) } ?: Step.Done(Failed(FailureCode.BASE_BRANCH_UNAVAILABLE))
        else -> Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
    }

    private class RepoView(val snapshot: RepoSnapshot, val contextFiles: List<RepoFile>)

    private suspend fun loadView(baseSha: String): Step<RepoView> {
        val entries = when (val r = repoReader.listFiles(config.repo, baseSha)) {
            is GitHubResult.Success -> r.value
            else -> return Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
        }
        val picked = ContextSelection.select(entries, config)
        val files = ArrayList<RepoFile>(picked.size)
        for (entry in picked) {
            when (val r = repoReader.readFile(config.repo, baseSha, entry.path)) {
                is GitHubResult.Success -> files += RepoFile(entry.path, r.value)
                else -> return Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
            }
        }
        val snapshot = SelfDevSnapshot(
            sizes = entries.associate { it.path to it.sizeBytes },
            inContext = picked.mapTo(HashSet()) { it.path },
        )
        return Step.Go(RepoView(snapshot, files))
    }

    /** Writes a fenced transition from [from] to [to] and reports it. [reasonCode] is a fixed code, never free text. */
    private fun persistAdvance(lease: Lease, run: DevelopmentRun, to: DevelopmentRunState, reasonCode: String?): Advanced {
        transitionTo(lease, run.state, to, reasonCode)
        return Advanced(run.state, to, reasonCode)
    }

    /**
     * The fenced transition. The version is read here, not taken from an earlier read: lease acquire and renewal both bump it, so an
     * earlier read goes stale within the same call. [from] is still checked under the row lock (expectedFrom).
     */
    private fun transitionTo(lease: Lease, from: DevelopmentRunState, to: DevelopmentRunState, reasonCode: String?): DevelopmentRun =
        runs.transition(lease, currentVersion(lease), to, from, reasonCode)

    private fun currentVersion(lease: Lease): Long =
        checkNotNull(runs.find(lease.runId)) { "run vanished while its lease was held" }.version

    /** Renews the lease before a GitHub write. A worker that lost its lease must not write. */
    private fun renewOrThrow(lease: Lease) {
        if (!leases.renew(lease, config.leaseTtl)) throw LeaseLostException("lease lost before a write")
    }

    private sealed interface Step<out T> {
        data class Go<out T>(val value: T) : Step<T>
        data class Done(val result: AdvanceResult) : Step<Nothing>
    }

    private companion object {
        /** The adapter's reason when the ref was not moved; the only Unknown from commitFiles that may be retried. */
        const val REF_NOT_MOVED = "ref not moved after reconcile; commitFiles may be run again"
        const val GITHUB_UNKNOWN = "GITHUB_UNKNOWN_OUTCOME"
        const val POLICY_REJECTED = "POLICY_REJECTED"
        const val DEVELOPER_PREFIX = "DEVELOPER_"
        const val BRANCH_ADVANCED_BEFORE_COMMIT = "BRANCH_ADVANCED_BEFORE_COMMIT"
        const val PR_NOT_FOUND = "PR_NOT_FOUND"
        const val PR_AMBIGUOUS = "PR_AMBIGUOUS"
        const val PR_ABANDONED = "PR_ABANDONED"
        const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
        const val MAX_BODY_CHARS = 4_000

        /** Removes control characters (keeps line breaks and tabs) and bounds the length. The text is model output: it is never trusted. */
        fun sanitizedSummary(summary: String): String =
            summary.filter { it == '\n' || it == '\t' || !it.isISOControl() }.take(MAX_BODY_CHARS)
    }
}
