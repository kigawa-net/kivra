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
import net.kigawa.kivra.agent.claude.StreamJsonParser
import net.kigawa.kivra.agent.claude.StreamReport
import net.kigawa.kivra.agent.docker.CliCommand
import net.kigawa.kivra.agent.docker.DockerLauncher
import net.kigawa.kivra.agent.docker.DockerRunResult
import net.kigawa.kivra.agent.docker.DockerRunSpec
import net.kigawa.kivra.agent.docker.DockerRunner
import net.kigawa.kivra.agent.docker.DockerSettings
import net.kigawa.kivra.agent.docker.ProcessDockerLauncher
import net.kigawa.kivra.agent.docker.TaskFile
import net.kigawa.kivra.agent.workdir.AgentResult
import net.kigawa.kivra.agent.workdir.CollectOutcome
import net.kigawa.kivra.agent.workdir.DiffCollector
import net.kigawa.kivra.agent.workdir.DiffRejectReason
import net.kigawa.kivra.agent.workdir.PreparedWorkDir
import net.kigawa.kivra.agent.workdir.PublicGitMirror
import net.kigawa.kivra.agent.workdir.RepoSource
import net.kigawa.kivra.agent.workdir.RunDirCleanup
import net.kigawa.kivra.agent.workdir.TaskInput
import net.kigawa.kivra.agent.workdir.WorkDirPreparer
import org.slf4j.LoggerFactory

/** The settings of [RealAttemptExecutor]. Read only when `KIVRA_AGENT_EXECUTOR=real`. [toString] shows no value. */
class RealExecutorSettings(
    /** The absolute path of the docker CLI (the rootless one of the agent's user). */
    val dockerBinary: Path,
    /** The `DOCKER_HOST` of the rootless socket (`unix:///...`), or null for the docker default. */
    val dockerHost: String?,
    /** The internal network of the run containers (`docker network create --internal`). */
    val network: String,
    /** The run image, for example `kivra-agent-runtime:<tag>`. */
    val image: String,
    /** The egress proxy, `http://host:port`. */
    val proxyUrl: String,
    /** The absolute path of git, used only on the host to read the public base commit. */
    val gitBinary: Path,
    /** The repository the base commit is read from. The config only builds a public GitHub source; tests use a local one. */
    val repo: RepoSource,
    /** The `--model` of the CLI (`KIVRA_AGENT_MODEL`). The default is Haiku 5.5, the only model kivra's policy allows. */
    val model: String = CliCommand.DEFAULT_MODEL,
    /** The `--max-budget-usd` of the CLI (`KIVRA_AGENT_MAX_BUDGET_USD`), two decimals. */
    val budgetUsd: String = CliCommand.DEFAULT_BUDGET_USD,
    /** Keeps the debug output of a failed attempt (`KIVRA_AGENT_DEBUG_KEEP=1`). Off by default. See [AttemptDebugKeep]. */
    val debugKeep: Boolean = false,
) {
    override fun toString(): String = "RealExecutorSettings(***)"
}

/**
 * The real attempt executor (ADR 0010, runbook 6.5 and 6.10). For one claimed attempt it:
 *
 * 1. reads the public base commit into a host-side mirror and extracts it into a new work directory ([WorkDirPreparer]);
 * 2. runs the CLI in one container ([DockerRunner]). The container is stopped on the time limit, on a cancel request from kivra
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
    private val launcher: DockerLauncher = ProcessDockerLauncher,
    private val pollMillis: Long = DockerRunner.DEFAULT_POLL_MILLIS,
) : AttemptExecutor {
    private val logger = LoggerFactory.getLogger(RealAttemptExecutor::class.java)
    private val dockerSettings = DockerSettings(
        dockerBinary = settings.dockerBinary,
        dockerHost = settings.dockerHost,
        workRoot = workRoot,
        allowedNetworks = setOf(settings.network),
        allowedImages = setOf(settings.image),
        allowedModels = setOf(settings.model),
    )
    private val mirror = PublicGitMirror(settings.gitBinary, workRoot.resolve(MIRROR_DIR_NAME))
    private val preparer = WorkDirPreparer(workRoot)
    private val collector = DiffCollector(workRoot)
    private val debugKeep: AttemptDebugKeep? = if (settings.debugKeep) AttemptDebugKeep(workRoot).also { it.prune() } else null

    override suspend fun execute(attempt: ClaimedAttempt): AttemptOutcome {
        val state = AttemptState()
        var outcome: AttemptOutcome? = null
        try {
            val result = attemptOnce(attempt, state)
            outcome = result
            return result
        } finally {
            // The debug output is taken before the work directory is removed. Nothing is kept for a success (outcome == ResultSubmitted),
            // and a thrown exception or a cancel leaves outcome null, which is kept as EXCEPTION.
            debugKeep?.let { keep ->
                if (outcome !is AttemptOutcome.ResultSubmitted) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        keep.keep(
                            runId = attempt.runId,
                            attemptId = attempt.attemptId,
                            outcome = outcomeText(outcome, state.reasons),
                            stdout = state.run?.result?.stdout,
                            taskFile = state.prepared?.runDir?.resolve(TaskFile.RELATIVE_PATH),
                        )
                    }
                }
            }
            state.prepared?.let { work ->
                val clean = withContext(Dispatchers.IO + NonCancellable) { RunDirCleanup.delete(work.runDir, work.manifest) }
                if (!clean) logger.warn("work directory was not fully removed: runId={} attemptId={}", attempt.runId, attempt.attemptId)
            }
            debugKeep?.let { keep -> withContext(Dispatchers.IO + NonCancellable) { keep.prune() } }
        }
    }

    private suspend fun attemptOnce(attempt: ClaimedAttempt, state: AttemptState): AttemptOutcome {
        val job = currentCoroutineContext().job
        // The blocking work runs to its end even when the coroutine is cancelled: the container is stopped by the cancel check
        // below, and the work directory is removed afterwards. Nothing is left running on a cancel.
        val run = withContext(Dispatchers.IO + NonCancellable) {
            mirror.sync(settings.repo)
            val work = preparer.prepare(
                attempt.runId,
                attempt.attemptId,
                mirror.openArchive(attempt.baseSha),
                TaskInput(title = attempt.task.value, body = "", truncated = attempt.task.truncated),
            )
            state.prepared = work
            val spec = DockerRunSpec(
                runId = attempt.runId,
                attemptId = attempt.attemptId,
                image = settings.image,
                workDir = work.runDir,
                network = settings.network,
                proxyUrl = settings.proxyUrl,
                timeout = Duration.ofSeconds(attempt.limits.maxRuntimeSeconds),
                maxTurns = attempt.limits.maxTurns,
                model = settings.model,
                budgetUsd = settings.budgetUsd,
            )
            val runner = DockerRunner(
                dockerSettings,
                launcher = launcher,
                pollMillis = pollMillis,
                outputLimitBytes = minOf(attempt.limits.maxOutputBytes, DockerRunner.DEFAULT_OUTPUT_LIMIT_BYTES.toLong()).toInt(),
            )
            runner.cleanupOrphans(attempt.runId)
            val result = runner.run(spec) { !job.isActive || Files.exists(stopFile) }
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
        if (run.outcome != AgentRunOutcome.SUCCEEDED) {
            return AttemptOutcome.Failed(run.outcome.errorCode ?: AgentErrorCode.NEEDS_HUMAN)
        }

        val work = checkNotNull(state.prepared) { "the work directory is missing after a run" }
        val collected = withContext(Dispatchers.IO + NonCancellable) {
            collector.collect(
                runDir = work.runDir,
                manifestPath = work.manifest,
                summary = run.report.result?.resultText.orEmpty(),
                exitStatus = checkNotNull(run.result.exitCode),
                sessionId = run.report.result?.sessionId,
            )
        }
        return when (collected) {
            is CollectOutcome.Rejected -> {
                state.reasons = collected.reasons
                val code = rejectionCode(collected.reasons)
                logger.warn(
                    "change set rejected: runId={} attemptId={} reasons={} code={} numTurns={} permissionDenials={}",
                    attempt.runId,
                    attempt.attemptId,
                    collected.reasons.joinToString(",") { it.name },
                    code,
                    run.report.result?.numTurns,
                    run.report.result?.permissionDenials,
                )
                AttemptOutcome.Failed(code)
            }
            is CollectOutcome.Accepted -> submit(attempt, collected.result)
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
    private suspend fun submit(attempt: ClaimedAttempt, result: AgentResult): AttemptOutcome {
        currentCoroutineContext().ensureActive()
        client.heartbeat(attempt.attemptId, attempt.generation, ProgressPhase.FINALIZING)
        if (client.status(attempt.attemptId).cancelRequested) return AttemptOutcome.Failed(AgentErrorCode.CANCELLED)
        return when (client.submitResult(attempt.attemptId, attempt.generation, result)) {
            is ResultReply.Accepted -> {
                logger.info("result sent: runId={} attemptId={} changes={}", attempt.runId, attempt.attemptId, result.changes.size)
                AttemptOutcome.ResultSubmitted
            }
            ResultReply.Rejected -> {
                logger.warn("result refused by kivra: runId={} attemptId={}", attempt.runId, attempt.attemptId)
                AttemptOutcome.Failed(AgentErrorCode.BAD_OUTPUT)
            }
        }
    }

    private class RunState(val result: DockerRunResult, val report: StreamReport, val outcome: AgentRunOutcome)

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
         * is treated as a failure (CLI_NONZERO), never as success. Every other refusal is a bad output (BAD_OUTPUT).
         */
        internal fun rejectionCode(reasons: List<DiffRejectReason>): AgentErrorCode =
            if (reasons.all { it == DiffRejectReason.EMPTY_PATCH }) AgentErrorCode.CLI_NONZERO else AgentErrorCode.BAD_OUTPUT
    }
}
