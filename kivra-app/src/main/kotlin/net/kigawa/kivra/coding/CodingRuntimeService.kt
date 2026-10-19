package net.kigawa.kivra.coding

import java.time.Duration
import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.core.patch.PatchPolicy
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.PatchSet
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptResult
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.CodingRefusal
import net.kigawa.kivra.db.EnqueueResult
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.ExpireResult
import org.slf4j.LoggerFactory

/** Task text for a Run. Returns the title of the Run, or null when it is not available (the attempt is then not claimed). */
fun interface CodingTaskSource {
    fun title(run: DevelopmentRun): String?
}

/**
 * Timing and size settings of the coding runtime. [leaseTtl] must be longer than the agent's heartbeat interval: a lease that runs
 * out makes the attempt ORPHANED. [queueTtl] is how long a QUEUED attempt waits for an agent before it is EXPIRED.
 */
data class CodingRuntimeConfig(
    val leaseTtl: Duration = Duration.ofMinutes(2),
    val queueTtl: Duration = Duration.ofHours(24),
    val maxRuntime: Duration = Duration.ofMinutes(30),
    val maxTurns: Int = 30,
    /** The agent's total stdout limit (see [CodingLimits.maxOutputBytes]). */
    val maxOutputBytes: Long = CODING_MAX_OUTPUT_BYTES,
    /** How many queued attempts one claim looks at before it gives up. */
    val maxClaimCandidates: Int = 10,
    /** How many rows one reconcile pass settles. */
    val reconcileLimit: Int = 100,
) {
    init {
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "leaseTtl must be positive" }
        require(!queueTtl.isNegative && !queueTtl.isZero) { "queueTtl must be positive" }
        require(!maxRuntime.isNegative && !maxRuntime.isZero) { "maxRuntime must be positive" }
        require(maxTurns > 0 && maxOutputBytes > 0) { "maxTurns and maxOutputBytes must be positive" }
        require(maxClaimCandidates > 0 && reconcileLimit > 0) { "candidate and reconcile limits must be positive" }
    }
}

/**
 * The server side of the coding runtime (design: Issue #187 section 2; ADR 0010). It owns the attempt transitions that the local agent
 * drives, and it checks everything the agent sends. No HTTP here: the agent API (`routes/AgentApiRoutes.kt`) is a thin layer on top of this class.
 *
 * - [enqueue] queues one attempt for a DEVELOPING Run, with the base sha fixed. It is idempotent while an attempt is active, and never
 *   queues again after a terminal attempt (no retry loop).
 * - [claim] hands the oldest claimable attempt to one agent. The claim is a CAS in the repository, so two agents never get one attempt.
 * - [heartbeat], [submitResult] and [fail] are accepted only for the current lease generation (fencing).
 * - [submitResult] checks the result with [CodingResultValidator] (shape, then the PatchPolicy) before it stores anything, and stores
 *   the change set before it marks the attempt RESULT_RECEIVED.
 * - [reconcile] moves expired attempts on, and puts a Run whose attempt was orphaned or expired on hold as UNKNOWN for an operator.
 *   A new attempt is not queued for such a Run: the agent may still be running the old one, and a second run would double the cost and
 *   the side effects. The operator decides (ADR 0009 style release). It is not run by the periodic worker: the hold needs the Run lease,
 *   which the runner holds (ADR 0010 addendum 6). The worker calls [expireOverdue] instead.
 *
 * Logs carry only identifiers, fixed codes and counts. Never a title, a path, a summary, a change or a secret.
 */
class CodingRuntimeService(
    private val attempts: CodingAttemptRepository,
    private val runs: CodingRunPort,
    private val results: CodingResultStore,
    private val tasks: CodingTaskSource,
    /** The cost ledger (ADR 0010 addendum 4). Required: a claim is refused when the monthly budget cannot hold one reserve. */
    private val cost: CodingCostPort,
    private val policyConfig: PatchPolicyConfig = PatchPolicyConfig(),
    private val config: CodingRuntimeConfig = CodingRuntimeConfig(),
) {
    private val policy = PatchPolicy(policyConfig)

    /** The limits sent to the agent. The file limits are the PatchPolicy's own numbers, so the two cannot drift apart. */
    val limits: CodingLimits = CodingLimits(
        maxRuntime = config.maxRuntime,
        maxTurns = config.maxTurns,
        maxOutputBytes = config.maxOutputBytes,
        maxChangedFiles = policyConfig.maxFiles,
        maxFileBytes = policyConfig.maxFileBytes,
        maxTotalBytes = policyConfig.maxTotalBytes,
        maxDeletes = policyConfig.maxDeletes,
    )

    private val validator = CodingResultValidator(policy, limits)

    /** Queues an attempt for [runId] at [baseSha], unless the Run is not DEVELOPING, is held, or has a cancel request. */
    fun enqueue(runId: String, baseSha: String): CodingEnqueueOutcome {
        if (!BASE_SHA.matches(baseSha)) return CodingEnqueueOutcome.InvalidInput
        val run = runs.find(RunId(runId)) ?: return CodingEnqueueOutcome.RunNotFound
        if (run.state != DevelopmentRunState.DEVELOPING || run.cancelRequested || run.holdReason != null) {
            return CodingEnqueueOutcome.RunNotDeveloping
        }
        return when (val r = attempts.enqueue(runId, baseSha, config.queueTtl, allowAfterSettled = false)) {
            is EnqueueResult.Enqueued -> CodingEnqueueOutcome.Queued(r.attempt)
            is EnqueueResult.Existing -> CodingEnqueueOutcome.Active(r.attempt)
            is EnqueueResult.Settled -> CodingEnqueueOutcome.Settled(r.latest)
            EnqueueResult.RunNotFound -> CodingEnqueueOutcome.RunNotFound
        }
    }

    /**
     * Claims the oldest queued attempt that can run, for [agentId]. Queued attempts of a Run that is no longer DEVELOPING, or that has a
     * cancel request, are cancelled instead of being handed out. A held Run keeps its queued attempt (it expires if the hold lasts).
     * The expiry check uses the DB clock, so the claim does not take a time argument.
     *
     * Admission first: when the monthly budget cannot hold one attempt's reserve, nothing is handed out (the same answer as an empty queue).
     * The Run keeps its queued attempt and waits; the denial code is logged, never the Run's content.
     */
    fun claim(agentId: String): CodingClaimOutcome {
        val candidates = attempts.listClaimable(config.maxClaimCandidates)
        if (candidates.isEmpty() || cost.claimDenial() != null) return CodingClaimOutcome.NothingToClaim
        for (attemptId in candidates) {
            val attempt = attempts.get(attemptId) ?: continue
            val run = runs.find(RunId(attempt.runId)) ?: continue
            if (run.holdReason != null) continue
            if (run.state != DevelopmentRunState.DEVELOPING || run.cancelRequested) {
                attempts.cancelQueued(attemptId)
                continue
            }
            val title = tasks.title(run)?.takeIf { it.isNotBlank() } ?: continue
            val claimed = attempts.claim(attemptId, agentId, config.leaseTtl)
            if (claimed is CodingAttemptResult.Ok) return CodingClaimOutcome.Claimed(inputFor(claimed.attempt, title, run.taskBody))
        }
        return CodingClaimOutcome.NothingToClaim
    }

    /**
     * Extends the lease of the current generation. [agentId] must be the holder that claimed the attempt; another agent gets NOT_FOUND.
     * [progress] is a fixed phase; it is recorded only while the event cap allows.
     */
    fun heartbeat(attemptId: String, agentId: String, generation: Long, progress: ProgressPhase? = null): CodingAttemptResult =
        attempts.heartbeat(attemptId, agentId, generation, config.leaseTtl, progress?.let { """{"phase":"${it.name}"}""" })

    /**
     * Terminal failure with a fixed code, by the holder only. The state follows the code ([CodingErrorCode.state]). The cost is booked
     * first, while the holder still holds the attempt: the reported [usage], or the per-attempt reserve when there is none. When the
     * ledger cannot record it, nothing changes and [CodingCostUnavailableException] is thrown (the route answers 503, so the holder
     * may send the fail again).
     */
    fun fail(attemptId: String, agentId: String, generation: Long, code: CodingErrorCode, usage: CliUsage? = null): CodingAttemptResult {
        val current = attempts.get(attemptId, agentId)
        if (current != null && current.leaseGeneration == generation && current.state.active) {
            val booked = if (usage != null) cost.recordReported(attemptId, generation, usage) else cost.recordEstimated(attemptId, generation)
            if (!booked) throw CodingCostUnavailableException()
        }
        return attempts.fail(attemptId, agentId, generation, code.name, code.state)
    }

    /**
     * What the holder of [attemptId] may learn about it: the state, and whether the Run has a cancel request. Null for an attempt that
     * is missing or held by another agent (the caller answers 404 for both).
     */
    fun statusOf(attemptId: String, agentId: String): AgentAttemptStatus? {
        val attempt = attempts.get(attemptId, agentId) ?: return null
        val run = runs.find(RunId(attempt.runId)) ?: return null
        return AgentAttemptStatus(attempt.state, run.cancelRequested)
    }

    /**
     * Accepts a result from the current holder. Order: the generation and state pre-check (nothing is read or stored for a stale holder),
     * then the shape and PatchPolicy checks (a refusal stores nothing), then the store, then the transition. A store that fails leaves
     * the attempt unchanged, so the agent may send the result again.
     *
     * The cost follows the holder's check: a reported [usage] is booked right after the holder check (a refused result keeps it, since the
     * CLI cost is spent either way). With no usage, the reserve is booked before the transition to RESULT_RECEIVED. A ledger failure
     * returns [CodingSubmitOutcome.StoreFailed] with nothing changed.
     */
    fun submitResult(attemptId: String, agentId: String, generation: Long, result: CodingResult, usage: CliUsage? = null): CodingSubmitOutcome {
        val current = attempts.get(attemptId, agentId) ?: return CodingSubmitOutcome.Refused(CodingRefusal.NOT_FOUND)
        if (current.state != CodingAttemptState.CLAIMED && current.state != CodingAttemptState.RUNNING) {
            return CodingSubmitOutcome.Refused(CodingRefusal.NOT_ACTIVE)
        }
        if (current.leaseGeneration != generation) return CodingSubmitOutcome.Refused(CodingRefusal.STALE_GENERATION)
        if (usage != null && !cost.recordReported(attemptId, generation, usage)) return CodingSubmitOutcome.StoreFailed

        return when (val verdict = validator.check(result)) {
            is CodingResultValidator.Verdict.Rejected -> {
                val codes = (verdict.reasons.map { it.name } + verdict.policyReasons.map { it.name }).joinToString(",")
                logger.warn("coding result rejected: attemptId={} reasons={}", attemptId, codes.take(MAX_LOGGED_CHARS))
                attempts.recordResultRejected(attemptId, agentId, codes)
                CodingSubmitOutcome.Rejected(verdict.reasons, verdict.policyReasons)
            }
            is CodingResultValidator.Verdict.Accepted ->
                store(attemptId, agentId, generation, result, verdict.patch, verdict.digest, reported = usage != null)
        }
    }

    private fun store(
        attemptId: String,
        agentId: String,
        generation: Long,
        result: CodingResult,
        patch: PatchSet,
        digest: String,
        reported: Boolean,
    ): CodingSubmitOutcome {
        try {
            results.save(attemptId, digest, patch)
        } catch (_: Exception) {
            // The exception is not logged: its message could carry the content that failed to store.
            logger.error("coding result store failed: attemptId={}", attemptId)
            return CodingSubmitOutcome.StoreFailed
        }
        // No usage was reported with this result: the reserve is booked before the attempt is marked received (fail closed).
        if (!reported && !cost.recordEstimated(attemptId, generation)) return CodingSubmitOutcome.StoreFailed
        return when (val done = attempts.complete(attemptId, agentId, generation, digest, result.exitStatus, result.sessionId)) {
            is CodingAttemptResult.Ok -> {
                logger.info("coding result accepted: attemptId={} changes={}", attemptId, patch.files.size)
                CodingSubmitOutcome.Accepted(digest)
            }
            is CodingAttemptResult.Refused -> CodingSubmitOutcome.Refused(done.reason)
        }
    }

    /**
     * Requests cancellation of [runId]. The Run is flagged (its owner moves it to CANCELLED at the next advance). A QUEUED attempt is
     * cancelled at once. A CLAIMED or RUNNING attempt is only flagged; see [CodingCancelOutcome.CancelRequested].
     */
    fun cancel(runId: String): CodingCancelOutcome {
        val id = RunId(runId)
        val run = runs.find(id) ?: return CodingCancelOutcome.RunNotFound
        if (run.state.isTerminal || !runs.requestCancel(id)) return CodingCancelOutcome.RunNotCancellable
        val active = attempts.findActive(runId)
        if (active != null && active.state == CodingAttemptState.QUEUED) {
            if (attempts.cancelQueued(active.attemptId) is CodingAttemptResult.Ok) return CodingCancelOutcome.QueuedCancelled
        }
        return CodingCancelOutcome.CancelRequested
    }

    /**
     * Settles the attempts that ran out of time: QUEUED past the queue expiry becomes EXPIRED, CLAIMED or RUNNING past the lease becomes
     * ORPHANED. Each such attempt whose Run is still DEVELOPING (and has no other active attempt) moves that Run to UNKNOWN with a fixed
     * reason, so an operator decides. Safe to call again: a Run already moved on is left alone.
     */
    fun reconcile(): CodingReconcileReport {
        val expired = expireAndBookEstimates()
        var held = 0
        var skipped = 0
        for (attempt in attempts.unsettledOfDevelopingRuns(config.reconcileLimit)) {
            val runId = RunId(attempt.runId)
            if (runs.find(runId) == null) {
                skipped++
                continue
            }
            if (attempts.findActive(attempt.runId) != null) continue
            val reason = if (attempt.state == CodingAttemptState.ORPHANED) REASON_ORPHANED else REASON_QUEUE_EXPIRED
            when (runs.holdForOperator(runId, reason)) {
                HoldOutcome.MOVED_TO_UNKNOWN -> held++
                HoldOutcome.SKIPPED -> skipped++
            }
        }
        val report = CodingReconcileReport(expired.expiredQueued.size, expired.orphaned.size, held, skipped)
        logger.info(
            "coding reconcile: queuedExpired={} orphaned={} held={} skipped={}",
            report.queuedExpired, report.orphaned, report.runsHeldForOperator, report.runsSkipped,
        )
        return report
    }

    /**
     * Marks the overdue attempts EXPIRED (QUEUED past its queue time) or ORPHANED (lease ran out), and books their reserves. It holds no
     * Run: the Run's own advance moves it to UNKNOWN with the reason its attempt now carries. Called by the runner (under the Run's
     * lease) and by [net.kigawa.kivra.coding.CodingReconcileWorker] (no lease). Both are safe to repeat: each attempt moves once, and
     * each reserve is booked once under its key.
     */
    fun expireOverdue(): ExpireResult = expireAndBookEstimates()

    /**
     * Expires the overdue attempts, then books the per-attempt reserve for each one: an EXPIRED attempt (generation 0, never claimed) and an
     * ORPHANED one (its lease generation). Each attempt moves once, so each is booked once; a booking that fails is logged by id and is not
     * retried here (a known gap, see the runbook).
     */
    private fun expireAndBookEstimates(): ExpireResult {
        val expired = attempts.expire(config.reconcileLimit)
        for (id in expired.expiredQueued) {
            if (!cost.recordEstimated(id, 0)) logger.error("coding reserve not booked: attemptId={} code=LEDGER_UNAVAILABLE", id)
        }
        for (id in expired.orphaned) {
            val generation = attempts.get(id)?.leaseGeneration ?: continue
            if (!cost.recordEstimated(id, generation)) logger.error("coding reserve not booked: attemptId={} code=LEDGER_UNAVAILABLE", id)
        }
        return expired
    }

    /** The newest attempt of [runId], active or terminal. */
    fun latest(runId: String): CodingAttempt? = attempts.latestAttempt(runId)

    /** The change set accepted for a RESULT_RECEIVED attempt, or null when the attempt has none or it cannot be read. */
    fun loadResult(attempt: CodingAttempt): PatchSet? {
        val digest = attempt.resultDigest ?: return null
        return try {
            results.load(attempt.attemptId, digest)
        } catch (_: Exception) {
            logger.error("coding result load failed: attemptId={}", attempt.attemptId)
            null
        }
    }

    private fun inputFor(attempt: CodingAttempt, title: String, body: String?): CodingInput = CodingInput(
        runId = attempt.runId,
        attemptId = attempt.attemptId,
        leaseGeneration = attempt.leaseGeneration,
        baseSha = attempt.baseSha,
        task = UntrustedText(
            origin = "task_title",
            value = title.take(MAX_TASK_CHARS),
            trust = Trust.UNTRUSTED,
            truncated = title.length > MAX_TASK_CHARS,
        ),
        // The stored body is already within its limit (TaskBodyText); the truncation is recorded by its mark, never by a second cut here.
        taskBody = UntrustedText(
            origin = "task_body",
            value = body.orEmpty(),
            trust = Trust.UNTRUSTED,
            truncated = body != null && TaskBodyText.isTruncated(body),
        ),
        limits = limits,
        allowedPathPatterns = policyConfig.allowedPathPatterns,
        deniedPathPatterns = policyConfig.deniedPathPatterns,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(CodingRuntimeService::class.java)
        private val BASE_SHA = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
        const val MAX_TASK_CHARS = 2_000
        const val REASON_ORPHANED = "CODING_ORPHANED"
        const val REASON_QUEUE_EXPIRED = "CODING_QUEUE_EXPIRED"
        private const val MAX_LOGGED_CHARS = 200
    }
}
