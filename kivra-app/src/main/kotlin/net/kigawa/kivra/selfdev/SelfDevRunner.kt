package net.kigawa.kivra.selfdev

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import net.kigawa.kivra.coding.TaskBodyText
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.InvalidTransitionException
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.LeaseLostException
import net.kigawa.kivra.core.LeaseStore
import net.kigawa.kivra.core.PrMergeAuthorityUnavailableException
import net.kigawa.kivra.core.PullRequestBinding
import net.kigawa.kivra.core.RunCompletionMode
import net.kigawa.kivra.core.RunHaltedException
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.VersionConflictException
import net.kigawa.kivra.core.patch.Delete
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.core.patch.RejectReason
import net.kigawa.kivra.core.patch.PatchVerdict
import net.kigawa.kivra.core.patch.RepoSnapshot
import net.kigawa.kivra.core.patch.Write
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.PrMergeNotVerifiedException
import net.kigawa.kivra.developer.DeveloperPort
import net.kigawa.kivra.developer.DeveloperRefusal
import net.kigawa.kivra.developer.DeveloperRequest
import net.kigawa.kivra.developer.DeveloperResult
import net.kigawa.kivra.developer.RepoFile
import net.kigawa.kivra.github.BranchInfo
import net.kigawa.kivra.github.CommitFilesRequest
import net.kigawa.kivra.github.CreatePullRequest
import net.kigawa.kivra.github.FileWrite
import net.kigawa.kivra.github.GitHubFailureKind
import net.kigawa.kivra.github.GitHubPort
import net.kigawa.kivra.github.GitHubResult
import net.kigawa.kivra.github.PullRequest
import net.kigawa.kivra.github.PullRequestState
import net.kigawa.kivra.github.RunBranch
import net.kigawa.kivra.selfdev.AdvanceResult.Advanced
import net.kigawa.kivra.selfdev.AdvanceResult.Failed
import net.kigawa.kivra.selfdev.AdvanceResult.Skipped
import net.kigawa.kivra.selfdev.AdvanceResult.Waiting
import org.slf4j.LoggerFactory

/**
 * Self-development runner (ADR 0008 part 4, Issue #56). It moves one PR_ONLY run forward, one [advance] call at a time:
 *
 * | state at call          | what the call does                                                                        | persisted                              |
 * |------------------------|--------------------------------------------------------------------------------------------|----------------------------------------|
 * | CREATED                | nothing else                                                                               | -> DEVELOPING                          |
 * | DEVELOPING             | propose (LLM), PatchPolicy, commit, pull request, binding                                  | -> PATCH_READY -> PULL_REQUEST         |
 * | PATCH_READY (resume)   | propose again, PatchPolicy, commit, pull request, binding                                  | -> PULL_REQUEST (or a failure)         |
 * | PULL_REQUEST           | read the pull request: wait, abandon (closed or timed out), or complete (merged and verified) | -> CANCELLED / COMPLETED, or nothing   |
 * | UNKNOWN                | nothing (waits for an operator)                                                            | nothing                                |
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
 * - **Merge completes only through the Authority.** The pull request binding (repository, repository id, PR number, node id, base,
 *   head SHA, policy revision, review manifest digest) is recorded once, right after the pull request is opened. A merged pull request
 *   completes the run only via `completePrOnlyRun`, which asks the merge Authority. A refusal keeps the run in PULL_REQUEST with
 *   [WaitReason.MERGE_NOT_VERIFIED]; an unavailable Authority keeps it with [WaitReason.AWAITING_MERGE_VERIFICATION]. A PULL_REQUEST run
 *   without a binding is never completed: it waits with [WaitReason.BINDING_MISSING]. kivra never merges and never closes a pull request.
 *
 * Safety layers: the kill switch ([SelfDevConfig.enabled]); the fenced lease (a stale worker's writes are refused by the database,
 * and the lease is renewed before each GitHub write); [PatchPolicy] again on the runner side (the Developer is not trusted); the
 * UNSEEN_OVERWRITE check (the snapshot knows which files the LLM saw); and fixed reason codes only.
 *
 * CLI runtime (ADR 0010, `KIVRA_SELFDEV_RUNTIME=cli`): the DEVELOPING step takes its change set from a coding attempt instead of the
 * model ([CodingPatchSource]). A pending attempt waits ([WaitReason.AWAITING_CODING_AGENT]). A ready one goes through the same PatchPolicy
 * (here with the base snapshot), PATCH_READY and the commit path. The "not stored" limit above is the API path's; a CLI resume reads the
 * stored result. Before it reads the attempt, it marks overdue attempts EXPIRED or ORPHANED ([CodingPatchSource.settleExpired]); the Run then
 * moves to UNKNOWN in this same advance, under its lease. The CLI path is enabled only with the agent API (`KIVRA_AGENT_API_ENABLED=true`,
 * see SelfDevSettings).
 *
 * Logging and results: the only log line is the WARN for a rejected patch, with its fixed reason names and a count. Results and reason
 * codes never contain task text, model output, patch content, tokens or paths. Errors from the store and the lease are mapped to
 * [AdvanceResult] codes, not passed on.
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
    /**
     * The change-set source of the CLI runtime (ADR 0010). Required when [SelfDevConfig.runtime] is CLI, unused for API. The runner refuses
     * CLI without it, so a missing wiring cannot fall back to anything else.
     */
    private val coding: CodingPatchSource? = null,
    /**
     * Resolves a task title that is a reference (Issue #229, addendum). Empty by default: then every title is used as it is. Runs once per
     * run, before any attempt is queued; a failure ends the run with TASK_REFERENCE_UNRESOLVED.
     */
    private val references: TaskReferenceResolvers = TaskReferenceResolvers(emptyList()),
    /**
     * Re-checks the change set of an auto-approved task before it is committed (ADR 0011 section 6.2). Used on the CLI path only, which is
     * the only one the autopilot runs on. Null (the default) checks nothing.
     */
    private val autoScope: AutoScopeGuard? = null,
    /**
     * Keeps a waiting pull request on top of the base (issue #276). Null (the default, and the production value while
     * `KIVRA_SELFDEV_PR_REBASE_ENABLED` is off) means the waiting step does exactly what it did before.
     */
    private val prRebaser: PrBranchRebaser? = null,
) {
    init {
        require(config.runtime == SelfDevRuntime.API || coding != null) { "the CLI runtime needs a coding patch source" }
    }

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
        // Before any attempt is queued (or any Developer call): the reference is resolved once and stored with the run.
        val current = when (val resolved = resolveTaskReference(lease, run)) {
            is Step.Go -> resolved.value
            is Step.Done -> return Step.Done(resolved.result)
        }
        if (config.runtime == SelfDevRuntime.CLI) return proposeFromAgent(lease, current, base)
        val task = taskTexts.taskText(current) ?: return Step.Done(Failed(FailureCode.TASK_TEXT_UNAVAILABLE))
        val view = when (val s = loadView(base.headSha, TitlePaths.extract("${task.title}\n${task.description}"))) {
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
                is PatchVerdict.Rejected -> {
                    logPatchRejected(run.runId.value, verdict.reasons)
                    Step.Done(persistAdvance(lease, run, DevelopmentRunState.FAILED, POLICY_REJECTED))
                }
            }

            is DeveloperResult.Refused -> {
                if (result.reason == DeveloperRefusal.POLICY_REJECTED) logPatchRejected(run.runId.value, result.policyReasons)
                val to = if (resuming) DevelopmentRunState.UNKNOWN else DevelopmentRunState.FAILED
                Step.Done(persistAdvance(lease, run, to, "$DEVELOPER_PREFIX${result.reason.name}"))
            }

            is DeveloperResult.Unknown ->
                Step.Done(persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, "$DEVELOPER_PREFIX${result.reason.name}"))
        }
    }

    /**
     * Resolves the task title when it is a reference, and stores the body and the source with the run (V17). Returns [Step.Go] with the
     * run to use next (the same run when nothing was resolved, the stored one after a save), or [Step.Done] when the run failed here. A run
     * that already has a source is never resolved again. Logs the run id, a fixed code, a count and `owner/repo#n` only, never the title or
     * the body.
     */
    private suspend fun resolveTaskReference(lease: Lease, run: DevelopmentRun): Step<DevelopmentRun> {
        if (run.taskSource != null) return Step.Go(run)
        val title = run.taskTitle?.takeIf { it.isNotBlank() } ?: return Step.Go(run)
        // The read can take a while (bounded retries, timeouts): keep the lease alive first, as before a GitHub write.
        renewOrThrow(lease)
        return when (val result = references.resolve(title)) {
            is TaskReferenceResult.NotApplicable -> Step.Go(run)
            is TaskReferenceResult.Failed -> {
                logger.warn("task reference unresolved: runId={} code={}", run.runId.value, result.code.code)
                Step.Done(persistAdvance(lease, run, DevelopmentRunState.FAILED, TASK_REFERENCE_UNRESOLVED, auditReasonCode = result.code.code))
            }
            is TaskReferenceResult.Resolved -> {
                val body = TaskBodyText.compose(run.taskBody, result.text)
                if (body == null) {
                    val code = TaskReferenceFailure.NO_ROOM_FOR_REFERENCE.code
                    logger.warn("task reference unresolved: runId={} code={}", run.runId.value, code)
                    return Step.Done(persistAdvance(lease, run, DevelopmentRunState.FAILED, TASK_REFERENCE_UNRESOLVED, auditReasonCode = code))
                }
                val saved = runs.saveTaskReference(lease, currentVersion(lease), body, result.source)
                logger.info(
                    "task reference resolved: runId={} source={} chars={} truncated={}",
                    run.runId.value, result.source, TaskBodyText.codePoints(body), TaskBodyText.isTruncated(body),
                )
                Step.Go(saved)
            }
        }
    }

    /**
     * CLI runtime (ADR 0010): the change set comes from a coding attempt instead of the model. It is checked exactly like an API proposal,
     * with the same PatchPolicy and the base snapshot. The snapshot marks every file of the base as seen: the agent works on the whole
     * tree at the base commit, so UNSEEN_OVERWRITE (which guards the LLM's limited context) does not apply to it.
     */
    private suspend fun proposeFromAgent(lease: Lease, run: DevelopmentRun, base: BranchInfo): Step<PatchSet> {
        val source = checkNotNull(coding) { "the CLI runtime has no coding patch source" }
        source.settleExpired()
        val entries = when (val r = repoReader.listFiles(config.repo, base.headSha)) {
            is GitHubResult.Success -> r.value
            else -> return Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
        }
        val sizes = entries.associate { it.path to it.sizeBytes }
        val snapshot = SelfDevSnapshot(
            sizes = sizes,
            inContext = entries.mapTo(HashSet()) { it.path },
        )
        return when (val proposal = source.proposal(run, base.headSha)) {
            is CodingProposal.Pending -> Step.Done(Waiting(WaitReason.AWAITING_CODING_AGENT))
            is CodingProposal.Failed -> Step.Done(persistAdvance(lease, run, proposal.to, proposal.reasonCode))
            is CodingProposal.Ready -> when (val verdict = policy.validate(proposal.patch, snapshot)) {
                is PatchVerdict.Accepted -> {
                    // ADR 0011 section 6.2: the actual diff of an auto-approved task is checked again before anything is committed. An
                    // out-of-scope change ends the run as a policy rejection, and the task waits for the operator (not counted as a failure).
                    val guard = autoScope
                    val scope = guard?.check(run.runId, verdict.patch) { sizes[it] }
                    if (guard != null && scope is AutoScopeVerdict.OutOfScope) {
                        guard.settle(run.runId)
                        Step.Done(persistAdvance(lease, run, DevelopmentRunState.FAILED, POLICY_REJECTED))
                    } else {
                        Step.Go(verdict.patch)
                    }
                }
                is PatchVerdict.Rejected -> {
                    logPatchRejected(run.runId.value, verdict.reasons)
                    Step.Done(persistAdvance(lease, run, DevelopmentRunState.FAILED, POLICY_REJECTED))
                }
            }
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
        // NOT_APPLIED is a confirmed read-back: nothing was written, so the same request may be sent once more. Nothing else is retried.
        if (committed is GitHubResult.Failure && committed.kind == GitHubFailureKind.NOT_APPLIED) {
            renewOrThrow(lease)
            committed = github.commitFiles(config.repo, commitRequest)
            if (committed is GitHubResult.Failure && committed.kind == GitHubFailureKind.NOT_APPLIED) {
                return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, COMMIT_NOT_APPLIED_TWICE)
            }
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
        val ensured = when (val r = github.ensurePullRequest(config.repo, pullRequest)) {
            is GitHubResult.Success -> r.value
            is GitHubResult.Failure -> return persistAdvance(lease, run, DevelopmentRunState.FAILED, r.kind.code)
            is GitHubResult.Unknown -> return persistAdvance(lease, run, DevelopmentRunState.UNKNOWN, GITHUB_UNKNOWN)
        }
        // bindPullRequest is allowed only in PULL_REQUEST, so the binding is recorded right after the transition, with the new version.
        val pullRequestRun = transitionTo(lease, run.state, DevelopmentRunState.PULL_REQUEST, null)
        bindOpenedPullRequest(lease, pullRequestRun, ensured.pullRequest, commitSha)
        return Advanced(run.state, DevelopmentRunState.PULL_REQUEST, null)
    }

    /**
     * Records the binding of the pull request this run just opened. It is recorded once: a run that already has a binding is left
     * alone. Nothing is recorded when the provider's answer does not match what kivra committed (repository, base, head) or lacks the
     * identity fields; the run then waits with [WaitReason.BINDING_MISSING] instead of being completed later on a guess.
     */
    private fun bindOpenedPullRequest(lease: Lease, run: DevelopmentRun, pullRequest: PullRequest, headSha: String) {
        if (runs.loadBinding(run.runId) != null) return
        if (pullRequest.repo != config.repo || pullRequest.headSha != headSha || pullRequest.baseRef != config.baseBranch) return
        val repositoryId = pullRequest.baseRepositoryId ?: return
        val nodeId = pullRequest.nodeId ?: return
        runs.bindPullRequest(
            lease,
            currentVersion(lease),
            PullRequestBinding(
                runId = run.runId,
                repository = config.repo.toString(),
                repositoryId = repositoryId,
                prNumber = pullRequest.number.toLong(),
                prNodeId = nodeId,
                baseRef = config.baseBranch,
                headSha = headSha,
                policyRevision = POLICY_REVISION,
                reviewManifestDigest = manifestDigest(run.runId.value, headSha),
            ),
        )
    }

    // ---- PULL_REQUEST: read the pull request; complete it when merged and verified, abandon it when closed or timed out ----

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

        if (pullRequest.merged) {
            reconcileMergedHead(lease, run, pullRequest)
            return completeMergedPullRequest(lease, run)
        }
        val abandoned = pullRequest.state == PullRequestState.CLOSED ||
            clock.instant().isAfter(run.updatedInstant().plus(config.prReviewTimeout))
        if (abandoned) {
            val closed = runs.closeAbandonedPullRequest(lease, currentVersion(lease))
            return Advanced(run.state, closed.state, PR_ABANDONED)
        }
        val rebaser = prRebaser ?: return Waiting(WaitReason.AWAITING_HUMAN_REVIEW)
        val binding = runs.loadBinding(run.runId) ?: return Waiting(WaitReason.AWAITING_HUMAN_REVIEW)
        return when (val o = rebaser.look(lease, run, binding, pullRequest, { renewOrThrow(lease) }, { currentVersion(lease) })) {
            PrBranchRebaser.Outcome.NoAction -> Waiting(WaitReason.AWAITING_HUMAN_REVIEW)
            is PrBranchRebaser.Outcome.Held -> Waiting(WaitReason.AWAITING_HUMAN_REVIEW, o.code)
            PrBranchRebaser.Outcome.Rebound -> Waiting(WaitReason.AWAITING_HUMAN_REVIEW, REBASE_REBOUND)
        }
    }

    /**
     * A merged pull request whose head is not the recorded one (issue #276): the recorded head is moved only when the rebaser verifies the
     * new head. Nothing is written otherwise, and [completeMergedPullRequest] then refuses the head mismatch as before. Only with the
     * feature on; without the rebaser this does nothing.
     */
    private suspend fun reconcileMergedHead(lease: Lease, run: DevelopmentRun, pullRequest: PullRequest) {
        val rebaser = prRebaser ?: return
        val binding = runs.loadBinding(run.runId) ?: return
        if (binding.headSha == pullRequest.headSha) return
        rebaser.reconcileMergedHead(lease, run, binding, pullRequest, { renewOrThrow(lease) }, { currentVersion(lease) })
    }

    /**
     * A merged pull request completes the run only through the merge Authority (`completePrOnlyRun`, which also checks the author and
     * the binding). Without a binding nothing is asked and nothing is completed. A refusal and an unavailable Authority both leave the
     * run in PULL_REQUEST, with a wait reason. A second completion (another worker finished the run first) is reported as skipped.
     */
    private suspend fun completeMergedPullRequest(lease: Lease, run: DevelopmentRun): AdvanceResult {
        if (runs.loadBinding(run.runId) == null) return Waiting(WaitReason.BINDING_MISSING)
        return try {
            val completed = runs.completePrOnlyRun(lease, currentVersion(lease))
            Advanced(run.state, completed.state, PR_MERGED)
        } catch (e: PrMergeNotVerifiedException) {
            Waiting(WaitReason.MERGE_NOT_VERIFIED, e.reason)
        } catch (e: PrMergeAuthorityUnavailableException) {
            Waiting(WaitReason.AWAITING_MERGE_VERIFICATION)
        } catch (e: InvalidTransitionException) {
            if (runs.find(run.runId)?.state == DevelopmentRunState.COMPLETED) Skipped(SkipReason.TERMINAL) else throw e
        }
    }

    // ---- helpers ----

    private suspend fun readBase(): Step<BranchInfo> = when (val r = github.getBranch(config.repo, config.baseBranch)) {
        is GitHubResult.Success -> r.value?.let { Step.Go(it) } ?: Step.Done(Failed(FailureCode.BASE_BRANCH_UNAVAILABLE))
        else -> Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
    }

    private class RepoView(val snapshot: RepoSnapshot, val contextFiles: List<RepoFile>)

    private suspend fun loadView(baseSha: String, titlePaths: List<String>): Step<RepoView> {
        val entries = when (val r = repoReader.listFiles(config.repo, baseSha)) {
            is GitHubResult.Success -> r.value
            else -> return Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
        }
        val picked = ContextSelection.select(entries, config, titlePaths, policy)
        val files = ArrayList<RepoFile>(picked.size)
        for (entry in picked) {
            when (val r = repoReader.readFile(config.repo, baseSha, entry.path)) {
                // Excluded by design (binary, not UTF-8, over the limit): not shown, so not in the snapshot's context either.
                is GitHubResult.Success -> r.value?.let { files += RepoFile(entry.path, it) }
                else -> return Step.Done(Failed(FailureCode.GITHUB_READ_FAILED))
            }
        }
        val snapshot = SelfDevSnapshot(
            sizes = entries.associate { it.path to it.sizeBytes },
            inContext = files.mapTo(HashSet()) { it.path },
        )
        return Step.Go(RepoView(snapshot, files))
    }

    /**
     * One WARN line with the fixed reason names (at most [MAX_LOGGED_REASONS]) and the total count. Never a path, a file content,
     * a diff, model output or task text: a reason name says what kind of rule failed, not which file.
     */
    private fun logPatchRejected(runId: String, reasons: List<RejectReason>) {
        logger.warn(
            "self-development patch rejected: runId={} reasons={} count={}",
            runId, reasons.take(MAX_LOGGED_REASONS).joinToString(",") { it.name }, reasons.size,
        )
    }

    /** Writes a fenced transition from [from] to [to] and reports it. [reasonCode] is a fixed code, never free text. */
    private fun persistAdvance(
        lease: Lease,
        run: DevelopmentRun,
        to: DevelopmentRunState,
        reasonCode: String?,
        auditReasonCode: String? = reasonCode,
    ): Advanced {
        transitionTo(lease, run.state, to, reasonCode, auditReasonCode)
        return Advanced(run.state, to, reasonCode)
    }

    /**
     * The fenced transition. The version is read here, not taken from an earlier read: lease acquire and renewal both bump it, so an
     * earlier read goes stale within the same call. [from] is still checked under the row lock (expectedFrom).
     */
    private fun transitionTo(
        lease: Lease,
        from: DevelopmentRunState,
        to: DevelopmentRunState,
        reasonCode: String?,
        auditReasonCode: String? = reasonCode,
    ): DevelopmentRun = runs.transition(lease, currentVersion(lease), to, from, reasonCode, auditReasonCode)

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
        val logger = LoggerFactory.getLogger(SelfDevRunner::class.java)
        const val MAX_LOGGED_REASONS = 10
        const val COMMIT_NOT_APPLIED_TWICE = "COMMIT_NOT_APPLIED_TWICE"
        const val GITHUB_UNKNOWN = "GITHUB_UNKNOWN_OUTCOME"
        const val POLICY_REJECTED = "POLICY_REJECTED"
        const val DEVELOPER_PREFIX = "DEVELOPER_"
        const val BRANCH_ADVANCED_BEFORE_COMMIT = "BRANCH_ADVANCED_BEFORE_COMMIT"
        const val PR_NOT_FOUND = "PR_NOT_FOUND"
        const val PR_AMBIGUOUS = "PR_AMBIGUOUS"
        const val PR_ABANDONED = "PR_ABANDONED"
        const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
        const val PR_MERGED = "PR_MERGED"

        /** The waiting reason code after a verified head was recorded (issue #276). */
        const val REBASE_REBOUND = "PR_REBASE_VERIFIED"
        const val MAX_BODY_CHARS = 4_000

        /** The run's state reason when its task reference cannot be used (Issue #229 addendum). The sub reason is the audit code. */
        const val TASK_REFERENCE_UNRESOLVED = "TASK_REFERENCE_UNRESOLVED"

        /** Recorded in the binding. Not checked against any provider fact yet (ADR 0011 / #49, #50 own the real policy revision). */
        const val POLICY_REVISION = "patchpolicy-v1"

        /** Placeholder review manifest digest, derived from the run and the head. Not checked yet (#49 owns the real manifest). */
        fun manifestDigest(runId: String, headSha: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("manifest-$runId-$headSha".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Removes control characters (keeps line breaks and tabs) and bounds the length. The text is model output: it is never trusted. */
        fun sanitizedSummary(summary: String): String =
            summary.filter { it == '\n' || it == '\t' || !it.isISOControl() }.take(MAX_BODY_CHARS)
    }
}
