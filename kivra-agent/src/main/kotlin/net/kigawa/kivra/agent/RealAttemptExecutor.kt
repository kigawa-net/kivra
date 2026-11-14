package net.kigawa.kivra.agent

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import net.kigawa.kivra.agent.claude.AgentRunOutcome
import net.kigawa.kivra.agent.claude.CliOutcomeClassifier
import net.kigawa.kivra.agent.claude.CliResultLog
import net.kigawa.kivra.agent.claude.MonitorEvent
import net.kigawa.kivra.agent.claude.ReportedUsage
import net.kigawa.kivra.agent.claude.StreamJsonParser
import net.kigawa.kivra.agent.claude.StreamReport
import net.kigawa.kivra.agent.backend.RunBackend
import net.kigawa.kivra.agent.backend.RunResult
import net.kigawa.kivra.agent.backend.RunSpec
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.CliOutputLimits
import net.kigawa.kivra.agent.docker.TaskFile
import net.kigawa.kivra.agent.k8s.PodInputSource
import net.kigawa.kivra.agent.k8s.pod.PodInput
import net.kigawa.kivra.agent.k8s.pod.PodReadout
import net.kigawa.kivra.agent.workdir.AgentResult
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffCollector
import net.kigawa.kivra.agent.workdir.DiffRejectReason
import net.kigawa.kivra.agent.workdir.PathPatternException
import net.kigawa.kivra.agent.workdir.PathRules
import net.kigawa.kivra.agent.workdir.PreparedWorkDir
import net.kigawa.kivra.agent.workdir.PublicGitMirror
import net.kigawa.kivra.agent.workdir.RepoSource
import net.kigawa.kivra.agent.workdir.RunDirCleanup
import net.kigawa.kivra.agent.workdir.TaskInput
import net.kigawa.kivra.agent.workdir.WorkDirPreparer
import org.slf4j.LoggerFactory

/**
 * The k8s settings of [RealExecutorSettings] (ADR 0010 追補 7, PR 5c). Set only for the k8s backend. [toString] shows no value.
 */
class K8sExecutorSettings(
    /** The namespace the Jobs are created in. */
    val namespace: String,
    /** The owner label of this agent's Jobs (see [net.kigawa.kivra.agent.k8s.K8sNames.ownerLabel]). */
    val ownerLabel: String,
) {
    override fun toString(): String = "K8sExecutorSettings(***)"
}

/**
 * The settings of [RealAttemptExecutor]. Read only when `KIVRA_AGENT_EXECUTOR=real`. [toString] shows no value.
 *
 * The docker fields are set for the docker backend, and [k8s] for the k8s backend. [AgentConfig] sets exactly the ones of its backend. [image]
 * and [proxyUrl] are used by both: the run image and the egress proxy of the backend.
 */
class RealExecutorSettings(
    /** The absolute path of the docker CLI (the rootless one of the agent's user). Docker only. */
    val dockerBinary: Path? = null,
    /** The `DOCKER_HOST` of the rootless socket (`unix:///...`), or null for the docker default. Docker only. */
    val dockerHost: String? = null,
    /** The internal network of the run containers (`docker network create --internal`). Docker only. */
    val network: String? = null,
    /** The run image, for example `kivra-agent-runtime:<tag>`. */
    val image: String,
    /** The egress proxy, `http://host:port`. */
    val proxyUrl: String,
    /** The absolute path of git, used only on the host to read the public base commit (docker only; the Pod fetches its own). */
    val gitBinary: Path? = null,
    /** The repository the base commit is read from. The config only builds a public GitHub source; tests use a local one. */
    val repo: RepoSource,
    /** The `--model` of the CLI (`KIVRA_AGENT_MODEL`). The default is Haiku 5.5, the only model kivra's policy allows. */
    val model: String = CliCommand.DEFAULT_MODEL,
    /** The `--max-budget-usd` of the CLI (`KIVRA_AGENT_MAX_BUDGET_USD`), two decimals. */
    val budgetUsd: String = CliCommand.DEFAULT_BUDGET_USD,
    /** Keeps the debug output of a failed attempt (`KIVRA_AGENT_DEBUG_KEEP=1`). Off by default. See [AttemptDebugKeep]. */
    val debugKeep: Boolean = false,
    /** Sends EMPTY_PATCH, BUDGET_EXCEEDED and OVERLOADED instead of the older codes (`KIVRA_AGENT_NEW_ERROR_CODES=1`). Off by default. */
    val newErrorCodes: Boolean = false,
    /** Sends the run's monitor events to kivra (`KIVRA_AGENT_MONITOR_EVENTS=1`). Off by default. See [MonitorSession] and ADR 0012. */
    val monitorEvents: Boolean = false,
    /** The namespace and owner label of the k8s backend (`KIVRA_AGENT_BACKEND=k8s`). Null for docker. */
    val k8s: K8sExecutorSettings? = null,
) {
    override fun toString(): String = "RealExecutorSettings(***)"
}

/**
 * The real attempt executor (ADR 0010, runbook 6.5 and 6.10). For one claimed attempt it:
 *
 * 1. reads the public base commit into a host-side mirror and extracts it into a new work directory ([WorkDirPreparer]). A backend
 *    without a host work directory (k8s, [RunBackend.usesHostWorkDir]) skips this: its Pod does it;
 * 2. runs the CLI in one container ([RunBackend]). The container is stopped on the time limit, on a cancel request from kivra
 *    (`cancelRequested`), on the STOP file, and when the coroutine is cancelled;
 * 3. after the container has stopped, classifies the stream ([StreamJsonParser], [CliOutcomeClassifier]) and collects the change set
 *    from the work directory ([DiffCollector]);
 * 4. checks that the lease is still ours (heartbeat, then status), and sends the result. A 409 or 404 throws, so the loop discards the
 *    result without a fail.
 *
 * The work directory is removed in every case (see [RunDirCleanup]). The work root lock is held by the caller (`Main`), so it is not
 * taken here. Nothing here logs a path, the task, the output, a change or a token: only ids, fixed codes and counts.
 *
 * Errors that are not a classification (a bad setting, an unexpected I/O error) propagate as exceptions. [AgentLoop] reports them as
 * NEEDS_HUMAN, so an operator decides and nothing is retried.
 */
class RealAttemptExecutor(
    private val client: KivraAgentClient,
    workRoot: Path,
    private val stopFile: Path,
    private val settings: RealExecutorSettings,
    /** Runs the containers and removes the orphans. Built by [createRunBackend] from the settings, so this class names no backend. */
    private val backend: RunBackend,
    /**
     * Where a k8s backend reads the input of the run it is running (the repository, the base commit and the task). The executor registers
     * each attempt here before the run and removes it after. The docker backend does not read it.
     */
    private val podInputs: AttemptPodInputs = AttemptPodInputs(),
) : AttemptExecutor {
    private val logger = LoggerFactory.getLogger(RealAttemptExecutor::class.java)

    /** The host mirror, used only when the backend has a host work directory. A k8s Pod fetches the commit itself. */
    private val mirror: PublicGitMirror? = if (backend.usesHostWorkDir) {
        val git = settings.gitBinary ?: throw ConfigException("${AgentConfig.ENV_GIT_BIN} is required")
        PublicGitMirror(git, workRoot.resolve(MIRROR_DIR_NAME))
    } else {
        null
    }
    private val preparer = WorkDirPreparer(workRoot)
    private val changeRoot: Path = workRoot
    private val debugKeep: AttemptDebugKeep? = if (settings.debugKeep) AttemptDebugKeep(workRoot).also { it.prune() } else null

    override suspend fun execute(attempt: ClaimedAttempt): AttemptOutcome {
        // The claim's patterns are checked before anything runs. Invalid patterns are not guessed at: the attempt is failed as NEEDS_HUMAN.
        val pathRules = try {
            PathRules.fromServer(attempt.allowedPathPatterns, attempt.deniedPathPatterns)
        } catch (e: PathPatternException) {
            logger.warn(
                "claim path patterns refused: runId={} attemptId={} allowed={} denied={} reason={}",
                attempt.runId,
                attempt.attemptId,
                attempt.allowedPathPatterns.size,
                attempt.deniedPathPatterns.size,
                e.message,
            )
            return AttemptOutcome.Failed(AgentErrorCode.NEEDS_HUMAN)
        }
        val state = AttemptState()
        var outcome: AttemptOutcome? = null
        // The monitor is best effort (ADR 0012 U-11): nothing it does changes the outcome below. It is stopped here, after its last events
        // are sent (bounded), before the result or the fail is reported.
        val monitor = if (settings.monitorEvents) {
            MonitorSession.start(client, attempt.attemptId, attempt.generation)
        } else {
            MonitorSession.DISABLED
        }
        try {
            val result = attemptOnce(attempt, state, pathRules, monitor)
            outcome = result
            return result
        } finally {
            // One NonCancellable block for everything here. A withContext that returns after the coroutine was cancelled throws, so a
            // second withContext would skip the rest of this block (the work directory would be left behind).
            withContext(Dispatchers.IO + NonCancellable) {
                // The monitor's last events go out first (bounded by its own timeout). It has no effect on the outcome.
                monitor.finish()
                // The debug output is taken before the work directory is removed. Nothing is kept for a success (outcome == ResultSubmitted),
                // and a thrown exception or a cancel leaves outcome null, which is kept as EXCEPTION.
                debugKeep?.let { keep ->
                    if (outcome !is AttemptOutcome.ResultSubmitted) {
                        keep.keep(
                            runId = attempt.runId,
                            attemptId = attempt.attemptId,
                            outcome = outcomeText(outcome, state.reasons),
                            stdout = state.run?.result?.stdout,
                            taskFile = state.prepared?.runDir?.resolve(TaskFile.RELATIVE_PATH),
                        )
                    }
                }
                state.prepared?.let { work ->
                    val clean = RunDirCleanup.delete(work.runDir, work.manifest)
                    if (!clean) logger.warn("work directory was not fully removed: runId={} attemptId={}", attempt.runId, attempt.attemptId)
                }
                debugKeep?.prune()
            }
        }
    }

    private suspend fun attemptOnce(attempt: ClaimedAttempt, state: AttemptState, pathRules: PathRules, monitor: MonitorSession): AttemptOutcome {
        val job = currentCoroutineContext().job
        // The blocking work runs to its end even when the coroutine is cancelled: the container is stopped by the cancel check
        // below, and the work directory is removed afterwards. Nothing is left running on a cancel.
        val run = withContext(Dispatchers.IO + NonCancellable) {
            // The host work directory exists only for a backend that uses one (docker). For k8s the Pod does this itself.
            val work = if (backend.usesHostWorkDir) prepareHostWorkDir(attempt) else null
            state.prepared = work
            val spec = RunSpec(
                runId = attempt.runId,
                attemptId = attempt.attemptId,
                image = settings.image,
                workDir = work?.runDir,
                network = settings.network,
                proxyUrl = settings.proxyUrl,
                timeout = Duration.ofSeconds(attempt.limits.maxRuntimeSeconds),
                maxTurns = attempt.limits.maxTurns,
                model = settings.model,
                budgetUsd = settings.budgetUsd,
                outputLimitBytes = minOf(attempt.limits.maxOutputBytes, CliOutputLimits.MAX_TOTAL_BYTES).toInt(),
            )
            // A refused spec throws here, before the orphans are removed or anything starts (as before the RunBackend split).
            backend.validate(spec)
            backend.cleanupOrphans(attempt.runId)
            monitor.record(MonitorEvent.AttemptStarted)
            val result = try {
                podInputs.register(attempt, settings.repo)
                backend.run(
                    spec,
                    onStdoutLine = monitor.stdoutListener(),
                    cancelRequested = { !job.isActive || Files.exists(stopFile) },
                )
            } finally {
                podInputs.unregister(attempt)
            }
            monitor.record(MonitorEvent.ProcessExitConfirmed(result.outcome))
            val report = StreamJsonParser().parse(result.stdout)
            RunState(result, report, CliOutcomeClassifier.classify(result, report))
        }
        state.run = run
        // Counts, enums and numbers only (see CliResultLog). The result text, the tool input and the paths are never logged.
        logger.info(
            "cli run ended: runId={} attemptId={} outcome={} {}",
            attempt.runId,
            attempt.attemptId,
            run.outcome,
            CliResultLog.describe(run.report.result),
        )
        // The CLI ran and gave a result, so its cost is reported with the failure (null when there is no result).
        val usage = run.report.result?.reportedUsage
        if (run.outcome != AgentRunOutcome.SUCCEEDED) {
            return AttemptOutcome.Failed(run.outcome.errorCode(settings.newErrorCodes) ?: AgentErrorCode.NEEDS_HUMAN, usage)
        }

        val collected = withContext(Dispatchers.IO + NonCancellable) {
            collect(attempt, run, state.prepared, pathRules)
        }
        return when (collected) {
            is CollectOutcome.Rejected -> {
                state.reasons = collected.reasons
                val code = rejectionCode(collected.reasons, settings.newErrorCodes)
                logger.warn(
                    "change set rejected: runId={} attemptId={} reasons={} code={} numTurns={} permissionDenials={}",
                    attempt.runId,
                    attempt.attemptId,
                    collected.reasons.joinToString(",") { it.name },
                    code,
                    run.report.result?.numTurns,
                    run.report.result?.permissionDenials,
                )
                AttemptOutcome.Failed(code, usage)
            }
            is CollectOutcome.Accepted -> submit(attempt, collected.result, usage, monitor)
        }
    }

    /** Reads the base commit into the host mirror and extracts it into a new work directory (docker only; see [usesHostWorkDir]). */
    private fun prepareHostWorkDir(attempt: ClaimedAttempt): PreparedWorkDir {
        val host = checkNotNull(mirror) { "the host mirror is not used by this backend" }
        host.sync(settings.repo)
        return preparer.prepare(
            attempt.runId,
            attempt.attemptId,
            host.openArchive(attempt.baseSha),
            TaskInput(
                title = attempt.task.value,
                body = attempt.task.body,
                truncated = attempt.task.truncated || attempt.task.bodyTruncated,
            ),
        )
    }

    /**
     * Collects the change set. A run that gives a Pod readout (k8s) is not collected on the host: the Pod's claim is checked again with
     * the real limits and path rules of the attempt, and the summary and the session id come from the CLI's output here
     * ([PodReadout.Collected.finish]). Without a readout (docker) the host collects from the work directory, as it always did.
     */
    private fun collect(attempt: ClaimedAttempt, run: RunState, work: PreparedWorkDir?, pathRules: PathRules): CollectOutcome {
        val limits = attempt.limits.toDiffLimits()
        val summary = run.report.result?.resultText.orEmpty()
        val sessionId = run.report.result?.sessionId
        return when (val readout = run.result.podReadout) {
            null -> {
                val prepared = checkNotNull(work) { "the work directory is missing after a run" }
                DiffCollector(changeRoot, limits).collect(
                    runDir = prepared.runDir,
                    manifestPath = prepared.manifest,
                    summary = summary,
                    exitStatus = checkNotNull(run.result.exitCode),
                    sessionId = sessionId,
                    pathRules = pathRules,
                )
            }
            is PodReadout.Error -> CollectOutcome.Rejected(listOf(DiffRejectReason.RESULT_FIELD_INVALID))
            is PodReadout.Collected -> readout.finish(summary, sessionId, limits, pathRules)
        }
    }

    /** The debug outcome: the failure code and, for a refused change set, the reasons. Codes only. EXCEPTION means no result came back. */
    private fun outcomeText(outcome: AttemptOutcome?, reasons: List<DiffRejectReason>): String {
        val code = (outcome as? AttemptOutcome.Failed)?.code?.name ?: "EXCEPTION"
        val lines = mutableListOf("code=$code")
        if (reasons.isNotEmpty()) lines += "reasons=" + reasons.joinToString(",") { it.name }
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * Sends the result if the lease is still ours. A stale lease (409) or a missing attempt (404) throws, and the result is discarded.
     * A cancel request seen just before the send means the attempt is reported as CANCELLED, and nothing is sent.
     */
    private suspend fun submit(attempt: ClaimedAttempt, result: AgentResult, usage: ReportedUsage?, monitor: MonitorSession): AttemptOutcome {
        currentCoroutineContext().ensureActive()
        client.heartbeat(attempt.attemptId, attempt.generation, ProgressPhase.FINALIZING)
        if (client.status(attempt.attemptId).cancelRequested) return AttemptOutcome.Failed(AgentErrorCode.CANCELLED, usage)
        // The monitor's events go out before the result, so that the result is the last thing of the attempt (ADR 0012 §2.1).
        monitor.record(MonitorEvent.Phase(ProgressPhase.FINALIZING))
        withContext(Dispatchers.IO + NonCancellable) { monitor.finish() }
        return when (client.submitResult(attempt.attemptId, attempt.generation, result, usage)) {
            is ResultReply.Accepted -> {
                logger.info("result sent: runId={} attemptId={} changes={}", attempt.runId, attempt.attemptId, result.changes.size)
                AttemptOutcome.ResultSubmitted
            }
            ResultReply.Rejected -> {
                logger.warn("result refused by kivra: runId={} attemptId={}", attempt.runId, attempt.attemptId)
                AttemptOutcome.Failed(AgentErrorCode.BAD_OUTPUT, usage)
            }
        }
    }

    private class RunState(val result: RunResult, val report: StreamReport, val outcome: AgentRunOutcome)

    /** What one attempt has produced so far, for the debug output. Written by [attemptOnce], read by [execute]. */
    private class AttemptState {
        var prepared: PreparedWorkDir? = null
        var run: RunState? = null
        var reasons: List<DiffRejectReason> = emptyList()
    }

    companion object {
        /** The host-side mirror lives in the work root. A run directory name starts with an id character, so it cannot clash. */
        const val MIRROR_DIR_NAME = ".mirror.git"

        /**
         * The code for a refused change set. A change set with no change is an empty patch: the CLI finished without a change, which
         * is treated as a failure, never as success. It is CLI_NONZERO, or EMPTY_PATCH with [newErrorCodes]. Every other refusal is a
         * bad output (BAD_OUTPUT).
         */
        internal fun rejectionCode(reasons: List<DiffRejectReason>, newErrorCodes: Boolean = false): AgentErrorCode =
            when {
                reasons.all { it == DiffRejectReason.EMPTY_PATCH } -> if (newErrorCodes) AgentErrorCode.EMPTY_PATCH else AgentErrorCode.CLI_NONZERO
                else -> AgentErrorCode.BAD_OUTPUT
            }
    }
}

/**
 * The input of the runs in flight, for the k8s backend ([PodInputSource]). [RealAttemptExecutor] registers an attempt before its run and
 * removes it after. The repository must be a public GitHub one: the Pod fetches the base commit itself, with no credential.
 */
class AttemptPodInputs : PodInputSource {
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Pair<ClaimedAttempt, RepoSource>>()

    internal fun register(attempt: ClaimedAttempt, repo: RepoSource) {
        attempts[key(attempt.runId, attempt.attemptId)] = attempt to repo
    }

    internal fun unregister(attempt: ClaimedAttempt) {
        attempts.remove(key(attempt.runId, attempt.attemptId))
    }

    internal fun isRegistered(spec: RunSpec): Boolean = attempts.containsKey(key(spec.runId, spec.attemptId))

    override fun inputFor(spec: RunSpec): PodInput {
        val (attempt, repo) = attempts[key(spec.runId, spec.attemptId)] ?: error("no input is registered for the run")
        val github = repo as? RepoSource.PublicGithub ?: error("the repository is not a public GitHub one")
        return PodInput(
            runId = attempt.runId,
            attemptId = attempt.attemptId,
            owner = github.owner,
            repo = github.repo,
            commit = attempt.baseSha,
            title = attempt.task.value,
            body = attempt.task.body,
            truncated = attempt.task.truncated || attempt.task.bodyTruncated,
        )
    }

    private fun key(runId: String, attemptId: String) = "$runId/$attemptId"
}
