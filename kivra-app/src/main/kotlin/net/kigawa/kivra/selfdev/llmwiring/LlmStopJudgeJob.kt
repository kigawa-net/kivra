package net.kigawa.kivra.selfdev.llmwiring

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.core.autopilot.OrderActor
import net.kigawa.kivra.core.autopilot.OrderState
import net.kigawa.kivra.db.SelfDevOrder
import net.kigawa.kivra.db.SelfDevOrderStopRequest
import net.kigawa.kivra.db.StopRequestState
import net.kigawa.kivra.db.utcNow
import net.kigawa.kivra.selfdev.llm.JudgeDecision
import net.kigawa.kivra.selfdev.llm.JudgeDiagnostics
import net.kigawa.kivra.selfdev.llm.JudgeInput
import net.kigawa.kivra.selfdev.llm.JudgeResult
import net.kigawa.kivra.selfdev.llm.JudgeReasonCode
import net.kigawa.kivra.selfdev.llm.JudgeUsage
import net.kigawa.kivra.selfdev.llm.LimitCode
import net.kigawa.kivra.selfdev.llm.LimitDecision
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalMode
import net.kigawa.kivra.selfdev.llm.LlmStopApprovalSettings
import net.kigawa.kivra.selfdev.llm.RequesterKind
import net.kigawa.kivra.selfdev.llm.StopJudgeClient
import net.kigawa.kivra.selfdev.llm.StopJudgeInputBuilder
import net.kigawa.kivra.selfdev.llm.StopJudgeLimiter
import net.kigawa.kivra.selfdev.llm.StopRequestFacts
import net.kigawa.kivra.selfdev.llm.StopRequestFreeText
import org.slf4j.LoggerFactory

/**
 * What the job may read and write of the orders. In shadow mode only the reads are used. In active mode [approveStopByLlm] performs the
 * approval (ADR 0021 L5).
 */
interface StopJudgeOrders {
    fun stopRequest(requestId: String): SelfDevOrderStopRequest?
    fun order(orderId: String): SelfDevOrder?
    /** Approves a stop request by the LLM. Used only in active mode. */
    fun approveStopByLlm(requestId: String, approverKey: String, digest: String): Boolean
}

/** The fixed codes of a SKIPPED row (nothing was asked of the model). */
enum class SkipCode { FLAG_OFF, ROLES_NOT_ENFORCE, NO_ACTIVE_AI_APPROVER, REQUEST_CHANGED, ORDER_MISSING }

/** The fixed codes of an ERROR row that are not a [net.kigawa.kivra.selfdev.llm.JudgeErrorCode]. */
enum class JudgeFailureCode { CALL_TIMEOUT, JUDGE_INTERNAL_ERROR }

/**
 * The judgement job of the LLM stop approval (ADR 0021 L4/L5). It asks the model about a PENDING stop request and records the answer.
 * In shadow mode it never approves: a shadow APPROVE is recorded as WOULD_APPROVE. In active mode it calls [StopJudgeOrders.approveStopByLlm]
 * to approve the request (ADR 0021 L5).
 *
 * It starts from a nudge after a request is created (non blocking; the request path is never delayed or failed) and from a sweep that picks
 * up requests nobody claimed (a missed nudge, a restart). A claim row (one per request and mode) makes a judgement happen once across Pods.
 * The prerequisites (the flag, the roles mode, an ACTIVE AI_APPROVER) are read again on every judgement and never cached (P5).
 *
 * A request that a human has already decided, cancelled or let expire is left alone. Logs and records hold fixed codes, ids, the model
 * name, the input hash and numbers; never a title, a policy, a request text, the model output or an owner key.
 */
class LlmStopJudgeJob(
    private val settings: LlmStopApprovalSettings,
    private val rolesMode: PermissionMode,
    private val orders: StopJudgeOrders,
    private val decisions: LlmStopDecisionStore,
    private val limiter: StopJudgeLimiter,
    private val client: StopJudgeClient,
    /** True when at least one AI_APPROVER assignee is ACTIVE. Called on every judgement. */
    private val hasActiveApprover: () -> Boolean,
    /** Returns the AI_APPROVER owner key for active approvals, or null. Called only in active mode. */
    private val aiApproverKey: () -> String?,
    private val clock: Clock = Clock.systemUTC(),
    private val callTimeout: Duration = Duration.ofSeconds(60),
    private val sweepInterval: Duration = Duration.ofSeconds(60),
    private val sweepBatch: Int = 20,
) {
    private val log = LoggerFactory.getLogger(LlmStopJudgeJob::class.java)
    private val model: String = checkNotNull(settings.model) { "the model is required" }

    /** The mode comes from settings: shadow records without approving, active approves (ADR 0021 L5). */
    private val mode: JudgeMode = when (settings.mode) {
        LlmStopApprovalMode.ACTIVE -> JudgeMode.ACTIVE
        LlmStopApprovalMode.SHADOW -> JudgeMode.SHADOW
    }

    private val nudges = Channel<String>(capacity = NUDGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Tells the job that a request was created. Never blocks, never throws; a dropped nudge is caught by the sweep. */
    fun nudge(requestId: String) {
        nudges.trySend(requestId)
    }

    fun start(scope: CoroutineScope): List<Job> = listOf(
        scope.launch(Dispatchers.IO) {
            for (id in nudges) {
                if (!isActive) break
                safely { judgeRequest(id) }
            }
        },
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                safely { sweep() }
                delay(sweepInterval.toMillis())
            }
        },
    )

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only the class: a message may carry an SQL value.
            log.warn("llm stop judge: failed code={} exception={}", JudgeFailureCode.JUDGE_INTERNAL_ERROR.name, e.javaClass.simpleName)
        }
    }

    /** Judges the requests that nobody has claimed. */
    suspend fun sweep() {
        for (id in withContext(Dispatchers.IO) { decisions.unclaimedPending(mode, sweepBatch) }) {
            safely { judgeRequest(id) }
        }
    }

    /** Judges one request, once. Does nothing for a request that is not PENDING and unexpired, or that another job has claimed. */
    suspend fun judgeRequest(requestId: String) {
        val first = orders.stopRequest(requestId) ?: return
        if (!isOpen(first)) return
        if (!decisions.claim(requestId, mode)) return
        try {
            judgeClaimed(requestId, first.orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(requestId, first.orderId, DecisionOutcome.ERROR, JudgeFailureCode.JUDGE_INTERNAL_ERROR.name, NO_HASH)
            log.warn("llm stop judge: failed code={} requestId={} exception={}", JudgeFailureCode.JUDGE_INTERNAL_ERROR.name, requestId, e.javaClass.simpleName)
        }
    }

    private fun isOpen(request: SelfDevOrderStopRequest): Boolean =
        request.state == StopRequestState.PENDING && request.expiresAt.isAfter(utcNow(clock))

    private suspend fun judgeClaimed(requestId: String, orderId: String) {
        // Prerequisites, read again every time.
        val skip = when {
            !settings.enabled -> SkipCode.FLAG_OFF
            rolesMode != PermissionMode.ENFORCE -> SkipCode.ROLES_NOT_ENFORCE
            !hasActiveApprover() -> SkipCode.NO_ACTIVE_AI_APPROVER
            else -> null
        }
        if (skip != null) return record(requestId, orderId, DecisionOutcome.SKIPPED, skip.name, NO_HASH)

        // The request again, after the claim: it may have been decided meanwhile, or the order may have moved.
        val request = orders.stopRequest(requestId)
        val order = orders.order(orderId)
        if (request == null || order == null) return record(requestId, orderId, DecisionOutcome.SKIPPED, SkipCode.ORDER_MISSING.name, NO_HASH)
        if (!isOpen(request) || order.state != OrderState.STOP_REQUESTED || order.version != request.orderVersion) {
            return record(requestId, orderId, DecisionOutcome.SKIPPED, SkipCode.REQUEST_CHANGED.name, NO_HASH)
        }

        val input = StopJudgeInputBuilder.build(factsOf(order, request), StopRequestFreeText(order.title, order.policy, humanReason = null))

        val denied = limitDenial(input)
        if (denied != null) return record(requestId, orderId, DecisionOutcome.ABSTAINED, denied, input.inputHash)

        val result = withTimeoutOrNull(callTimeout.toMillis()) { client.judge(input) }
        when (result) {
            null -> record(requestId, orderId, DecisionOutcome.ERROR, JudgeFailureCode.CALL_TIMEOUT.name, input.inputHash)
            is JudgeResult.Error -> record(requestId, orderId, DecisionOutcome.ERROR, result.code.name, input.inputHash)
            is JudgeResult.Judged -> recordJudged(requestId, orderId, input, result)
        }
    }

    /** The code of the limit that refuses the call, or null. Takes one call of the hourly count only when it returns null. */
    private fun limitDenial(input: JudgeInput): String? {
        // An APPROVE could not be used without an hourly approval, so the model is not asked (it would only cost). Read only, before a call is taken.
        if (limiter.approvalsExhausted()) return LimitCode.LIMIT_APPROVALS.name
        val reserved = limiter.reserveCall(input.estimatedInputTokens)
        if (reserved is LimitDecision.Denied) return reserved.code.name
        return null
    }

    private fun recordJudged(requestId: String, orderId: String, input: JudgeInput, result: JudgeResult.Judged) {
        val cost = settings.pricing.costMilliYen(result.usage)
        // A call that cost more than the month had left closes the month, so the next call is refused (LIMIT_COST).
        if (limiter.recordCost(cost) is LimitDecision.Denied) limiter.closeMonth()
        var outcome = DecisionOutcome.ABSTAINED
        var code = result.judgment.reason.name
        if (result.judgment.decision == JudgeDecision.APPROVE && result.judgment.reason == JudgeReasonCode.CLEAR_STOP_REQUEST) {
            // An hourly approval is needed whether shadow or active.
            val approval = limiter.reserveApproval()
            if (approval is LimitDecision.Denied) {
                code = approval.code.name
            } else if (mode == JudgeMode.ACTIVE) {
                // Active mode: attempt the real approval (ADR 0021 L5).
                val (resultOutcome, resultCode) = tryApprove(requestId)
                outcome = resultOutcome
                code = if (outcome == DecisionOutcome.APPROVED) result.judgment.reason.name else resultCode
            } else {
                outcome = DecisionOutcome.WOULD_APPROVE
            }
        }
        record(requestId, orderId, outcome, code, input.inputHash, result.usage.inputTokens, result.usage.outputTokens, cost, result.diagnostics)
    }

    /**
     * Attempts to approve the stop request in active mode (ADR 0021 L5).
     * Returns a pair of (outcome, code): APPROVED on success, LOST_RACE with a fixed code on failure.
     */
    private fun tryApprove(requestId: String): Pair<DecisionOutcome, String> {
        val approverKey = aiApproverKey()
        if (approverKey == null) {
            log.warn("llm stop judge: active approval refused code=NO_ACTIVE_AI_APPROVER requestId={}", requestId)
            return DecisionOutcome.LOST_RACE to "NO_ACTIVE_AI_APPROVER"
        }
        // Re-read the request to obtain the current digest.
        val request = orders.stopRequest(requestId) ?: return DecisionOutcome.LOST_RACE to "REQUEST_CHANGED"
        if (request.state != StopRequestState.PENDING || !request.expiresAt.isAfter(utcNow(clock))) {
            return DecisionOutcome.LOST_RACE to "REQUEST_CHANGED"
        }
        val approved = orders.approveStopByLlm(requestId, approverKey, request.digest)
        return if (approved) DecisionOutcome.APPROVED to "APPROVED" else DecisionOutcome.LOST_RACE to "LOST_RACE"
    }

    private fun record(
        requestId: String,
        orderId: String,
        outcome: DecisionOutcome,
        code: String,
        hash: String,
        tokensIn: Long = 0,
        tokensOut: Long = 0,
        costMilliYen: Long = 0,
        diagnostics: JudgeDiagnostics? = null,
    ) {
        decisions.append(DecisionRecord(requestId, orderId, outcome, code, model, hash, mode, tokensIn, tokensOut, costMilliYen))
        log.info(
            "llm stop judge: mode={} outcome={} code={} requestId={} stop={} shape={}",
            mode.column, outcome.name, code, requestId, diagnostics?.stopReason?.name ?: "-", diagnostics?.shape?.name ?: "-",
        )
    }

    private fun factsOf(order: SelfDevOrder, request: SelfDevOrderStopRequest): StopRequestFacts {
        val now: LocalDateTime = utcNow(clock)
        return StopRequestFacts(
            orderIsRoot = order.parentOrderId == null,
            orderStateCode = order.state.name,
            budgetJpy = order.orderBudgetJpy,
            spentJpy = order.spentJpy,
            cycleCount = order.cyclesStarted,
            consecutiveFailures = order.consecutiveFailedCycles,
            reasonCode = request.reasonCode.name,
            elapsedSeconds = Duration.between(request.createdAt, now).seconds.coerceAtLeast(0),
            requesterKind = if (request.requesterKind == OrderActor.HUMAN) RequesterKind.HUMAN else RequesterKind.AI,
        )
    }

    private companion object {
        const val NUDGE_BUFFER = 256

        /** The input hash of a row written before an input exists (the column is a 64 character hash). */
        val NO_HASH = "0".repeat(64)
    }
}
