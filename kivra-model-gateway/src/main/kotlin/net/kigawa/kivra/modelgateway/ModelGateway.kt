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
) {
    private val router = RoutingEngine(registry, budget, health, providers.keys, clock, maxAttempts)

    fun route(req: ModelRequest, streaming: Boolean = false): RoutingDecision = router.route(req, streaming)

    /** Per-requestId operation record: the gateway, not the caller, decides whether a requestId may reach a provider. */
    private class Operation(val fingerprint: Int) {
        var providerCalled = false
        var response: ModelResponse? = null
    }

    private val operations = LinkedHashMap<String, Operation>()

    /**
     * Claims [req].requestId. A completed non-streaming operation with an identical request returns its
     * stored response (so the provider is never called and nothing is charged twice); any other repeat
     * (in flight, failed after a provider call, streaming, or different payload) is rejected authoritatively.
     */
    private fun claim(req: ModelRequest, replayable: Boolean): Pair<Operation?, ModelResponse?> = synchronized(operations) {
        val existing = operations[req.requestId]
        if (existing == null) {
            val op = Operation(req.hashCode())
            operations[req.requestId] = op
            if (operations.size > MAX_OPERATIONS) {
                val it = operations.entries.iterator()
                while (operations.size > MAX_OPERATIONS && it.hasNext()) { if (it.next().value.response != null) it.remove() }
            }
            return op to null
        }
        if (existing.fingerprint != req.hashCode()) throw denied("REQUEST_ID_REUSED", "requestId was already used for a different request")
        existing.response?.takeIf { replayable }?.let { return null to it }
        throw denied("DUPLICATE_REQUEST", "requestId is already in flight or was already sent to a provider")
    }

    private fun unclaimIfNeverSent(req: ModelRequest, op: Operation) = synchronized(operations) {
        if (!op.providerCalled && operations[req.requestId] === op) operations.remove(req.requestId)
    }

    private fun denied(code: String, msg: String) = GatewayException(GatewayError(ErrorKind.POLICY_DENIED, msg, code = code))

    suspend fun invoke(req: ModelRequest): ModelResponse {
        val decision = decide(req, streaming = false)
        val (op, replay) = claim(req, replayable = true)
        if (replay != null) return replay
        op!!
        try {
            val resp = withTimeout(req.timeout) { attemptLoop(req, decision, op) }
            synchronized(operations) { op.response = resp }
            return resp
        } catch (e: TimeoutCancellationException) {
            unclaimIfNeverSent(req, op)
            throw GatewayException(GatewayError(ErrorKind.TIMEOUT, "request deadline exceeded"), e)
        } catch (e: Throwable) {
            unclaimIfNeverSent(req, op)
            throw e
        }
    }

    /** Streaming call. Failures before the first event may fall back; after that a Failed event ends the stream. */
    fun stream(req: ModelRequest): Flow<ModelEvent> = flow {
        val decision = decide(req, streaming = true)
        val op = claim(req, replayable = false).first!!
        try { streamClaimed(req, decision, op) } catch (e: Throwable) { unclaimIfNeverSent(req, op); throw e }
        if (op.response == null) unclaimIfNeverSent(req, op)
    }

    private suspend fun FlowCollector<ModelEvent>.streamClaimed(req: ModelRequest, decision: RoutingDecision, op: Operation) {
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
                            op.providerCalled = true
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
                                        health.recordSuccess(ref.providerId)
                                        auditAttempt(req, attemptId, ref, decision, resp.usage, cost, resp.latencyMs, resp.finishReason, null)
                                        emit(ModelEvent.Completed(req.requestId, attemptId, seq++, resp))
                                    }
                                }
                            }
                            if (!finished) {
                                // Provider ended the stream without a terminal event. It may have been billed, so settle
                                // conservatively at the reserved estimate and never fall back after an ambiguous send.
                                val err = GatewayError(ErrorKind.TRANSIENT, "stream ended without a terminal event", code = "TRUNCATED_STREAM")
                                budget.commit(key, null)
                                health.recordFailure(ref.providerId, err.kind)
                                auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), null, err)
                                emit(ModelEvent.Failed(req.requestId, attemptId, seq++, err))
                            }
                            return@withTimeout
                        } catch (e: CancellationException) {
                            withContext(NonCancellable) {
                                budget.commit(key, null)
                                val kind = if (e is TimeoutCancellationException) ErrorKind.TIMEOUT else ErrorKind.CANCELLED
                                auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), FinishReason.CANCELLED, GatewayError(kind, "cancelled"))
                            }
                            throw e
                        } catch (e: Exception) {
                            if (finished) return@withTimeout // error after the terminal event: already settled, stay silent
                            val err = ErrorClassifier.fromThrowable(e)
                            if (err.kind == ErrorKind.TIMEOUT) budget.commit(key, null) else budget.release(key)
                            health.recordFailure(ref.providerId, err.kind)
                            auditAttempt(req, attemptId, ref, decision, usage, null, elapsed(t0), null, err)
                            lastError = err
                            if (emitted) {
                                emit(ModelEvent.Failed(req.requestId, attemptId, seq++, err))
                                return@withTimeout
                            }
                            if (err.kind.retrySameModel && retries < config.maxRetriesPerModel) {
                                sleep(backoff(retries++, err)); continue
                            }
                            if (!err.kind.fallback) { emit(ModelEvent.Failed(req.requestId, attemptId, seq++, err)); return@withTimeout }
                            break
                        }
                    }
                }
                emit(ModelEvent.Failed(req.requestId, "${req.requestId}-a$attemptNo", seq++, lastError ?: GatewayError(ErrorKind.TRANSIENT, "all candidates failed")))
            }
        } catch (e: TimeoutCancellationException) {
            emit(ModelEvent.Failed(req.requestId, "${req.requestId}-a$attemptNo", seq++, GatewayError(ErrorKind.TIMEOUT, "request deadline exceeded")))
        }
    }

    private companion object { const val MAX_OPERATIONS = 10_000 }

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

    private suspend fun attemptLoop(req: ModelRequest, decision: RoutingDecision, op: Operation): ModelResponse {
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
                try {
                    op.providerCalled = true
                    val resp = providers.getValue(ref.providerId).invoke(req, target, CallContext(req.requestId, attemptId, decision.decisionId))
                    val cost = CostCalculator.cost(resp.usage, target.model.price!!, CostBasis.ACTUAL)
                    budget.commit(key, cost.amount)
                    health.recordSuccess(ref.providerId)
                    auditAttempt(req, attemptId, ref, decision, resp.usage, cost, resp.latencyMs, resp.finishReason, null)
                    return resp.copy(cost = cost)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        budget.commit(key, null)
                        val kind = if (e is TimeoutCancellationException) ErrorKind.TIMEOUT else ErrorKind.CANCELLED
                        auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, elapsed(t0), FinishReason.CANCELLED, GatewayError(kind, "cancelled"))
                    }
                    throw e
                } catch (e: Exception) {
                    val err = ErrorClassifier.fromThrowable(e)
                    if (err.kind == ErrorKind.TIMEOUT) budget.commit(key, null) else budget.release(key)
                    health.recordFailure(ref.providerId, err.kind)
                    auditAttempt(req, attemptId, ref, decision, Usage.ZERO, null, elapsed(t0), null, err)
                    last = err
                    if (err.kind.retrySameModel && retries < config.maxRetriesPerModel) {
                        sleep(backoff(retries++, err)); continue
                    }
                    if (!err.kind.fallback) throw GatewayException(err)
                    break
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
