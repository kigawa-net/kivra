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
    private val budget: BudgetController,
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
) {
    private val ledger: RequestLedger = ledger ?: if (devAllowInMemoryLedger) InMemoryRequestLedger() else throw IllegalStateException(
        "ModelGateway requires a durable RequestLedger; InMemoryRequestLedger is dev/test only (pass devAllowInMemoryLedger = true to opt in)",
    )

    private val router = RoutingEngine(registry, budget, health, providers.keys, clock, maxAttempts)

    fun route(req: ModelRequest, streaming: Boolean = false): RoutingDecision = router.route(req, streaming)

    /** Per-invocation settlement state: whether a provider call may have left the Gateway. */
    private class Flight { var mayHaveSent = false }

    /** Claims [req].requestId. Returns the stored response for a replayable completed call, or null when claimed. */
    private fun claim(req: ModelRequest, replayable: Boolean): ModelResponse? =
        when (val c = ledger.begin(req.requestId, RequestDigest.of(req))) {
            LedgerClaim.Claimed -> null
            is LedgerClaim.Completed -> c.response?.takeIf { replayable }
                ?: throw denied("DUPLICATE_REQUEST", "requestId was already completed")
            is LedgerClaim.Rejected -> throw denied(c.code, c.message)
        }

    private fun settle(req: ModelRequest, flight: Flight) {
        if (flight.mayHaveSent) ledger.markOutcomeUnknown(req.requestId) else ledger.releaseUnsent(req.requestId)
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
        try {
            val resp = withTimeout(req.timeout) { attemptLoop(req, decision, flight) }
            ledger.complete(req.requestId, resp)
            return resp
        } catch (e: TimeoutCancellationException) {
            settle(req, flight)
            throw GatewayException(GatewayError(ErrorKind.TIMEOUT, "request deadline exceeded"), e)
        } catch (e: Throwable) {
            settle(req, flight)
            throw e
        }
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
                        val est = Estimator.maxCost(req, target.model)!!
                        val denied = budget.reserve(key, req.budgetScopes, est)
                        if (denied is ReserveResult.Denied) {
                            lastError = GatewayError(ErrorKind.POLICY_DENIED, "budget: ${denied.reason}", code = "BUDGET")
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
                                        val cost = CostCalculator.cost(chunk.response.usage, target.model.price!!, CostBasis.ACTUAL)
                                        val resp = chunk.response.copy(cost = cost)
                                        budget.commit(key, cost.amount)
                                        ledger.complete(req.requestId, null)
                                        health.recordSuccess(ref.providerId)
                                        auditAttempt(req, attemptId, ref, decision, resp.usage, cost, resp.latencyMs, resp.finishReason, null)
                                        emit(ModelEvent.Completed(req.requestId, attemptId, seq++, resp))
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
                val denied = budget.reserve(key, req.budgetScopes, Estimator.maxCost(req, target.model)!!)
                if (denied is ReserveResult.Denied) {
                    last = GatewayError(ErrorKind.POLICY_DENIED, "budget: ${denied.reason}", code = "BUDGET")
                    auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, 0, null, last)
                    break
                }
                val t0 = System.nanoTime()
                ledger.markSending(req.requestId)
                try {
                    val resp = providers.getValue(ref.providerId).invoke(req, target, CallContext(req.requestId, attemptId, decision.decisionId))
                    val cost = CostCalculator.cost(resp.usage, target.model.price!!, CostBasis.ACTUAL)
                    budget.commit(key, cost.amount)
                    health.recordSuccess(ref.providerId)
                    auditAttempt(req, attemptId, ref, decision, resp.usage, cost, resp.latencyMs, resp.finishReason, null)
                    return resp.copy(cost = cost)
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
            }
        }
        throw GatewayException(last ?: GatewayError(ErrorKind.TRANSIENT, "all candidates failed"))
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
