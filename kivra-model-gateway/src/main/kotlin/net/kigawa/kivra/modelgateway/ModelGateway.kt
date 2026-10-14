package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

data class GatewayConfig(
    val maxRetriesPerModel: Int = 2,
    val backoffBaseMs: Long = 500,
    val backoffMaxMs: Long = 20_000,
)

/**
 * Model Gateway entry point (design 05). Routes under hard constraints, reserves budget, calls the
 * provider with a shared deadline, retries / falls back per the error taxonomy, and emits audit and
 * metrics hooks. Tool calls are returned, never executed.
 *
 * requestId idempotency lives in [ledger]. Retry and fallback are only performed for failures proven to occur
 * before transmission ([SendState.NOT_SENT]); once a provider call may have been made, the reservation is
 * settled at the estimate, the requestId is sealed as outcome-unknown, and the caller must issue a new requestId.
 */
class ModelGateway(
    private val registry: ModelRegistry,
    private val providers: Map<String, ModelProvider>,
    private val budget: BudgetPort,
    private val health: HealthTracker = AlwaysHealthy,
    private val audit: AuditSink = AuditSink { },
    private val metrics: GatewayMetrics = GatewayMetrics.NOOP,
    private val config: GatewayConfig = GatewayConfig(),
    private val clock: Clock = Clock.systemUTC(),
    maxAttempts: Int = 3,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    /** Durable requestId ledger. Required in production. */
    ledger: RequestLedger? = null,
    /** Explicit opt-in for the process-local [InMemoryRequestLedger] (dev/test only). Without it, no ledger means fail-fast. */
    devAllowInMemoryLedger: Boolean = false,
    /** Converts USD prices into the JPY budget (design 13 B2). Null keeps the native currency. */
    private val costConverter: CostConverter? = null,
) {
    private val ledger: RequestLedger = ledger ?: if (devAllowInMemoryLedger) InMemoryRequestLedger() else throw IllegalStateException(
        "ModelGateway requires a durable RequestLedger; InMemoryRequestLedger is dev/test only (pass devAllowInMemoryLedger = true to opt in)",
    )

    private val router = RoutingEngine(registry, budget, health, providers.keys, clock, maxAttempts, costConverter)

    fun route(req: ModelRequest, streaming: Boolean = false): RoutingDecision = router.route(req, streaming)

    /**
     * Process-local seal for requestIds whose provider response was received but could not be recorded
     * (see [unrecorded]). It holds even when the [ledger] refuses the terminal write, so this process never re-sends.
     */
    private val sealedRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Per-invocation settlement state. [mayHaveSent] means a provider call may have left the Gateway.
     * [responseReceived] means a provider response (success) was obtained: the call is billed and the requestId must
     * never be released, whatever happens while recording it.
     */
    private class Flight {
        var mayHaveSent = false
        var responseReceived = false
    }

    /** Claims [req].requestId. Returns the stored response for a replayable completed call, or null when claimed. */
    private fun claim(req: ModelRequest, replayable: Boolean): ModelResponse? {
        if (req.requestId in sealedRequestIds) throw denied("DUPLICATE_REQUEST", "requestId was already completed or its outcome is unknown")
        return when (val c = ledger.begin(req.requestId, RequestDigest.of(req))) {
            LedgerClaim.Claimed -> null
            is LedgerClaim.Completed -> c.response?.takeIf { replayable }
                ?: throw denied("DUPLICATE_REQUEST", "requestId was already completed")
            is LedgerClaim.Rejected -> throw denied(c.code, c.message)
        }
    }

    /**
     * Settles a failed invocation. A requestId is released only when no provider request can have been transmitted.
     * Once a response was received, the terminal record is owned by [unrecorded] / the completion path and is never released.
     */
    private fun settle(req: ModelRequest, flight: Flight) {
        if (flight.responseReceived) return
        if (flight.mayHaveSent) ledger.markOutcomeUnknown(req.requestId) else ledger.releaseUnsent(req.requestId)
    }

    /**
     * A provider response was received but could not be recorded (budget, ledger or audit write failed). Fail closed:
     * the requestId is sealed in-process, the ledger is moved to outcome-unknown on a best-effort basis, and the caller
     * gets OUTCOME_UNKNOWN / RECONCILIATION_REQUIRED instead of the response. The response is deliberately not returned:
     * its completion is not durable, so a later retry under the same requestId could bill the provider twice. The caller
     * must reconcile the request result before issuing a new requestId. Budget has already been charged at the estimate
     * (or the actual cost when that commit succeeded) and is never released.
     */
    private fun unrecorded(req: ModelRequest, cause: Throwable): GatewayException {
        sealedRequestIds.add(req.requestId)
        try {
            ledger.markOutcomeUnknown(req.requestId)
        } catch (e: Throwable) {
            cause.addSuppressed(e)
        }
        return GatewayException(
            GatewayError(
                ErrorKind.OUTCOME_UNKNOWN,
                "provider responded but the call could not be recorded; reconcile before issuing a new requestId",
                code = "RECONCILIATION_REQUIRED",
            ),
            cause,
        )
    }

    private fun denied(code: String, msg: String) = GatewayException(GatewayError(ErrorKind.POLICY_DENIED, msg, code = code))

    /** Settles a possibly-billed failure: estimate is charged, the error is no longer retryable or fallback-able. */
    private fun outcomeUnknown(raw: GatewayError) = GatewayError(
        ErrorKind.OUTCOME_UNKNOWN,
        "provider outcome unknown after ${raw.kind}: ${raw.message}",
        code = raw.code ?: "PROVIDER_OUTCOME_UNKNOWN",
        providerStatus = raw.providerStatus,
    )

    suspend fun invoke(req: ModelRequest): ModelResponse {
        val decision = decide(req, streaming = false)
        claim(req, replayable = true)?.let { return it }
        val flight = Flight()
        val resp = try {
            withTimeout(req.timeout) { attemptLoop(req, decision, flight) }
        } catch (e: TimeoutCancellationException) {
            settle(req, flight)
            throw GatewayException(GatewayError(ErrorKind.TIMEOUT, "request deadline exceeded"), e)
        } catch (e: Throwable) {
            settle(req, flight)
            throw e
        }
        // The provider responded and the call is billed. A ledger failure here must not release the requestId.
        try {
            ledger.complete(req.requestId, resp)
        } catch (e: Throwable) {
            throw unrecorded(req, e)
        }
        return resp
    }

    /** Streaming call. Failures before the first event may fall back; after that a Failed event ends the stream. */
    fun stream(req: ModelRequest): Flow<ModelEvent> = flow {
        val decision = decide(req, streaming = true)
        claim(req, replayable = false)
        val flight = Flight()
        try { streamClaimed(req, decision, flight) } catch (e: Throwable) { settle(req, flight); throw e }
        settle(req, flight)
    }

    private suspend fun FlowCollector<ModelEvent>.streamClaimed(req: ModelRequest, decision: RoutingDecision, flight: Flight) {
        var seq = 0L
        var attemptNo = 0
        var lastError: GatewayError? = null
        var prev: ModelRef? = null
        try {
            withTimeout(req.timeout) {
                for (ref in decision.selected) {
                    prev?.let { metrics.fallback(it, ref, lastError!!.kind) }
                    prev = ref
                    val target = registry.resolve(ref)
                    var retries = 0
                    while (true) {
                        val attemptId = "${req.requestId}-a${++attemptNo}"
                        val key = "${req.requestId}/$attemptId"
                        val denied = budget.reserve(key, req.budgetScopes, reservation(req, target))
                        if (denied is ReserveResult.Denied) {
                            lastError = GatewayError(ErrorKind.POLICY_DENIED, "budget: ${denied.reason}", code = denied.code)
                            auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, 0, null, lastError)
                            break
                        }
                        val t0 = System.nanoTime()
                        var emitted = false
                        var finished = false
                        var usage = Usage.ZERO
                        val ctx = CallContext(req.requestId, attemptId, decision.decisionId)
                        try {
                            ledger.markSending(req.requestId)
                            providers.getValue(ref.providerId).stream(req, target, ctx).collect { chunk ->
                                if (finished) return@collect // exactly one terminal event: anything after Finished is dropped
                                if (!emitted) {
                                    emitted = true
                                    emit(ModelEvent.Started(req.requestId, attemptId, seq++, ref, decision.decisionId))
                                }
                                when (chunk) {
                                    is StreamChunk.Text -> emit(ModelEvent.TextDelta(req.requestId, attemptId, seq++, chunk.text))
                                    is StreamChunk.ToolCallDelta -> emit(ModelEvent.ToolCallDelta(req.requestId, attemptId, seq++, chunk.callId, chunk.name, chunk.argsJsonDelta))
                                    is StreamChunk.ToolCallCompleted -> emit(ModelEvent.ToolCallCompleted(req.requestId, attemptId, seq++, chunk.callId, chunk.name, chunk.args))
                                    is StreamChunk.UsageUpdate -> { usage = chunk.usage; emit(ModelEvent.UsageUpdate(req.requestId, attemptId, seq++, chunk.usage)) }
                                    is StreamChunk.Finished -> {
                                        finished = true
                                        flight.responseReceived = true
                                        val s = seq++
                                        // Booking failures become a terminal Failed(OUTCOME_UNKNOWN); the requestId is never released.
                                        val terminal = try {
                                            val cost = book(key, req, target, chunk.response.usage, chunk.response.usageComplete)
                                            val resp = chunk.response.copy(cost = cost)
                                            ledger.complete(req.requestId, null)
                                            health.recordSuccess(ref.providerId)
                                            auditAttempt(req, attemptId, ref, decision, resp.usage, cost, resp.latencyMs, resp.finishReason, null)
                                            ModelEvent.Completed(req.requestId, attemptId, s, resp)
                                        } catch (e: Throwable) {
                                            runCatching { budget.commit(key, null) }
                                            val failure = unrecorded(req, e)
                                            runCatching { auditAttempt(req, attemptId, ref, decision, chunk.response.usage, null, chunk.response.latencyMs, chunk.response.finishReason, failure.error) }
                                            ModelEvent.Failed(req.requestId, attemptId, s, failure.error)
                                        }
                                        emit(terminal)
                                    }
                                }
                            }
                            if (!finished) {
                                // Provider ended the stream without a terminal event. It may have been billed, so settle
                                // conservatively at the reserved estimate and never fall back after an ambiguous send.
                                flight.mayHaveSent = true
                                val err = GatewayError(ErrorKind.OUTCOME_UNKNOWN, "stream ended without a terminal event", code = "TRUNCATED_STREAM")
                                budget.commit(key, null)
                                health.recordFailure(ref.providerId, ErrorKind.TRANSIENT)
                                auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), null, err)
                                emit(ModelEvent.Failed(req.requestId, attemptId, seq++, err))
                            }
                            return@withTimeout
                        } catch (e: CancellationException) {
                            flight.mayHaveSent = true
                            withContext(NonCancellable) {
                                budget.commit(key, null)
                                val kind = if (e is TimeoutCancellationException) ErrorKind.TIMEOUT else ErrorKind.CANCELLED
                                auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), FinishReason.CANCELLED, GatewayError(kind, "cancelled"))
                            }
                            throw e
                        } catch (e: Exception) {
                            if (finished) return@withTimeout // error after the terminal event: already settled, stay silent
                            val raw = ErrorClassifier.fromThrowable(e)
                            health.recordFailure(ref.providerId, raw.kind)
                            if (raw.sendState == SendState.NOT_SENT && !emitted) {
                                budget.release(key)
                                auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), null, raw)
                                lastError = raw
                                if (raw.kind.retrySameModel && retries < config.maxRetriesPerModel) {
                                    sleep(backoff(retries++, raw)); continue
                                }
                                if (!raw.kind.fallback) { emit(ModelEvent.Failed(req.requestId, attemptId, seq++, raw)); return@withTimeout }
                                break
                            }
                            // The request may have reached the provider: bill the estimate and stop. No retry, no fallback.
                            flight.mayHaveSent = true
                            val err = outcomeUnknown(raw)
                            budget.commit(key, null)
                            auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), null, err)
                            lastError = err
                            emit(ModelEvent.Failed(req.requestId, attemptId, seq++, err))
                            return@withTimeout
                        }
                    }
                }
                emit(ModelEvent.Failed(req.requestId, "${req.requestId}-a$attemptNo", seq++, lastError ?: GatewayError(ErrorKind.TRANSIENT, "all candidates failed")))
            }
        } catch (e: TimeoutCancellationException) {
            emit(ModelEvent.Failed(req.requestId, "${req.requestId}-a$attemptNo", seq++, GatewayError(ErrorKind.TIMEOUT, "request deadline exceeded")))
        }
    }

    // ---- internals ----------------------------------------------------------------------------------

    private fun decide(req: ModelRequest, streaming: Boolean): RoutingDecision {
        val d = router.route(req, streaming)
        d.rejection?.let { err ->
            metrics.routeRejected(err.code ?: err.kind.name)
            audit.record(record(req, null, null, d, Usage.ZERO, null, 0, null, err))
            throw GatewayException(err)
        }
        return d
    }

    private suspend fun attemptLoop(req: ModelRequest, decision: RoutingDecision, flight: Flight): ModelResponse {
        var attemptNo = 0
        var last: GatewayError? = null
        var prev: ModelRef? = null
        for (ref in decision.selected) {
            prev?.let { metrics.fallback(it, ref, last!!.kind) }
            prev = ref
            val target = registry.resolve(ref)
            var retries = 0
            while (true) {
                val attemptId = "${req.requestId}-a${++attemptNo}"
                val key = "${req.requestId}/$attemptId"
                val denied = budget.reserve(key, req.budgetScopes, reservation(req, target))
                if (denied is ReserveResult.Denied) {
                    last = GatewayError(ErrorKind.POLICY_DENIED, "budget: ${denied.reason}", code = denied.code)
                    auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, 0, null, last)
                    break
                }
                val t0 = System.nanoTime()
                ledger.markSending(req.requestId)
                val resp = try {
                    providers.getValue(ref.providerId).invoke(req, target, CallContext(req.requestId, attemptId, decision.decisionId))
                } catch (e: CancellationException) {
                    flight.mayHaveSent = true
                    withContext(NonCancellable) {
                        budget.commit(key, null)
                        val kind = if (e is TimeoutCancellationException) ErrorKind.TIMEOUT else ErrorKind.CANCELLED
                        auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, elapsed(t0), FinishReason.CANCELLED, GatewayError(kind, "cancelled"))
                    }
                    throw e
                } catch (e: Exception) {
                    val raw = ErrorClassifier.fromThrowable(e)
                    health.recordFailure(ref.providerId, raw.kind)
                    if (raw.sendState == SendState.NOT_SENT) {
                        // Proven not transmitted: release the reservation and allow retry / fallback under this requestId.
                        budget.release(key)
                        auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, elapsed(t0), null, raw)
                        last = raw
                        if (raw.kind.retrySameModel && retries < config.maxRetriesPerModel) {
                            sleep(backoff(retries++, raw)); continue
                        }
                        if (!raw.kind.fallback) throw GatewayException(raw)
                        break
                    }
                    // The request may have reached the provider (5xx, transport loss, unparseable body, ...).
                    // The reservation is kept at the estimate and this requestId is sealed: no retry, no fallback.
                    flight.mayHaveSent = true
                    val err = outcomeUnknown(raw)
                    budget.commit(key, null)
                    auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, elapsed(t0), null, err)
                    throw GatewayException(err, e)
                }
                // A response was received: the call is billed. Booking failures are fail-closed (see [unrecorded]).
                flight.responseReceived = true
                val cost = try {
                    val c = book(key, req, target, resp.usage, resp.usageComplete)
                    health.recordSuccess(ref.providerId)
                    auditAttempt(req, attemptId, ref, decision, resp.usage, c, resp.latencyMs, resp.finishReason, null)
                    c
                } catch (e: Throwable) {
                    runCatching { budget.commit(key, null) }
                    throw unrecorded(req, e)
                }
                return resp.copy(cost = cost)
            }
        }
        throw GatewayException(last ?: GatewayError(ErrorKind.TRANSIENT, "all candidates failed"))
    }

    /**
     * Budget amount to reserve for a call (upper bound). An unpriced model is refused, never reserved as zero, so the
     * provider is not called. Routing already excludes unpriced models; this keeps the invariant local to the reservation.
     */
    private fun reservation(req: ModelRequest, target: ResolvedTarget): BudgetAmount =
        Estimator.maxBudgetAmount(req, target.model, target.provider, registry.registryVersion, clock.instant(), costConverter)
            ?: throw denied("UNKNOWN_COST", "model ${target.model.id} has no price in force or cost cannot be converted; provider not called")

    /**
     * Books a completed provider call. With complete usage, the actual cost is committed and bound to its price revision,
     * model and requestId. Without it (missing usage, partial stream), the estimate is committed conservatively and the
     * call is recorded as unpriced, so the next admission on these scopes is UNKNOWN_COST.
     */
    private fun book(key: String, req: ModelRequest, target: ResolvedTarget, usage: Usage, usageComplete: Boolean): Cost {
        val rev = registry.registryVersion
        if (!usageComplete) {
            val est = reservation(req, target)
            budget.commit(key, null)
            return Cost(est.amount, est.currency, CostBasis.ESTIMATED, est.unit, rev, target.model.ref, req.requestId)
        }
        val now = clock.instant()
        val price = target.model.priceAt(now) ?: throw denied("UNKNOWN_COST", "model ${target.model.id} has no price in force")
        // Tier is chosen from the actual input size (input + cached), so the actual cost is exact for the tier that applied.
        val rate = price.rateFor(usage.inputTokens + usage.cachedInputTokens)
        val native = CostCalculator.cost(usage, rate, price.currency, CostBasis.ACTUAL)
        val amount = Estimator.toBudget(native.amount, price, target.model.ref, target.provider, rev, now, costConverter)
            ?: throw denied("UNKNOWN_COST", "model ${target.model.id} cost cannot be converted to the budget currency")
        budget.commit(key, amount)
        return native.copy(priceRevision = rev, model = target.model.ref, requestId = req.requestId)
    }

    /** Exponential backoff with full jitter, never shorter than a provider-supplied Retry-After. */
    private fun backoff(retry: Int, err: GatewayError): Long {
        val cap = min(config.backoffMaxMs, config.backoffBaseMs shl min(retry, 20))
        val jittered = if (cap <= 0) 0 else random.nextLong(0, cap + 1)
        return maxOf(jittered, min(err.retryAfterMs ?: 0, config.backoffMaxMs))
    }

    private fun elapsed(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun auditAttempt(req: ModelRequest, attemptId: String, ref: ModelRef, d: RoutingDecision, usage: Usage,
                             cost: Cost?, latencyMs: Long, finish: FinishReason?, err: GatewayError?) {
        audit.record(record(req, attemptId, ref, d, usage, cost, latencyMs, finish, err))
        metrics.requestCompleted(ref.providerId, ref.modelId, req.purpose, if (err == null) "success" else "error", err?.kind, usage, cost, latencyMs)
    }

    private fun record(req: ModelRequest, attemptId: String?, ref: ModelRef?, d: RoutingDecision, usage: Usage,
                       cost: Cost?, latencyMs: Long, finish: FinishReason?, err: GatewayError?) =
        AuditRecord(clock.instant(), req.requestId, attemptId, req.taskId, req.executionId, req.principal, req.purpose,
            req.dataClass, ref, d.decisionId, usage, cost, latencyMs, finish, err?.kind, err?.code)
}
