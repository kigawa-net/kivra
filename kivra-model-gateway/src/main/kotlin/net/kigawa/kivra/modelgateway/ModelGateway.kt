package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    suspend fun invoke(req: ModelRequest): ModelResponse {
        val decision = decide(req, streaming = false)
        try {
            return withTimeout(req.timeout) { attemptLoop(req, decision) }
        } catch (e: TimeoutCancellationException) {
            throw GatewayException(GatewayError(ErrorKind.TIMEOUT, "request deadline exceeded"), e)
        }
    }

    /** Streaming call. Failures before the first event may fall back; after that a Failed event ends the stream. */
    fun stream(req: ModelRequest): Flow<ModelEvent> = flow {
        val decision = decide(req, streaming = true)
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
                        var usage = Usage.ZERO
                        val ctx = CallContext(req.requestId, attemptId, decision.decisionId)
                        try {
                            providers.getValue(ref.providerId).stream(req, target, ctx).collect { chunk ->
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
                                        val cost = CostCalculator.cost(chunk.response.usage, target.model.price!!, CostBasis.ACTUAL)
                                        val resp = chunk.response.copy(cost = cost)
                                        budget.commit(key, cost.amount)
                                        health.recordSuccess(ref.providerId)
                                        auditAttempt(req, attemptId, ref, decision, resp.usage, cost, resp.latencyMs, resp.finishReason, null)
                                        emit(ModelEvent.Completed(req.requestId, attemptId, seq++, resp))
                                    }
                                }
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

    private suspend fun attemptLoop(req: ModelRequest, decision: RoutingDecision): ModelResponse {
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
