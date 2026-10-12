package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModelGatewayTest {
    private val ext = provider("ext")
    private val ext2 = provider("ext2")
    private val intl = provider("intl", TrustZone.INTERNAL)
    private val local = provider("local", TrustZone.LOCAL)
    private val sleeps = mutableListOf<Long>()
    private val audit = InMemoryAuditSink()

    private fun ok(models: ModelEntry): suspend (Int) -> ModelResponse = { okResponse(models.ref) }

    private fun gateway(
        models: List<ModelEntry>, fakes: Map<String, FakeProvider>, b: BudgetController = budget(),
        providers: List<ProviderEntry> = listOf(ext, ext2, intl, local), health: HealthTracker = AlwaysHealthy,
        metrics: GatewayMetrics = GatewayMetrics.NOOP, ledger: RequestLedger = InMemoryRequestLedger(),
    ) = ModelGateway(registryOf(providers, models), fakes, b, health, audit, metrics, GatewayConfig(), sleep = { sleeps.add(it) }, ledger = ledger)

    @Test fun successReturnsCostedResponseAndAuditsTokensAndCost() = runTest {
        val m = model("m", "ext")
        val g = gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))))
        val r = g.invoke(request())
        // 1000 in * $3/M + 500 out * $15/M = 0.0105
        assertEquals(0, BigDecimal("0.0105").compareTo(r.cost.amount))
        assertEquals(CostBasis.ACTUAL, r.cost.basis)
        val rec = audit.records.single()
        assertEquals(Usage(1000, 500), rec.usage); assertNotNull(rec.cost); assertEquals("m", rec.selected!!.modelId)
        assertEquals(DataClass.PUBLIC, rec.dataClass); assertNotNull(rec.routingDecisionId); assertNull(rec.errorKind)
    }

    @Test fun successCommitsActualCostAndReleasesReservation() = runTest {
        val m = model("m", "ext"); val b = budget()
        gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))), b).invoke(request())
        assertEquals(0, BigDecimal("0.0105").compareTo(b.usage("task").committed))
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").reserved))
    }

    @Test fun budgetExceededIsRejectedBeforeAnyProviderCall() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m))
        val g = gateway(listOf(m), mapOf("ext" to fake), budget(hard = "0.0001"))
        val e = assertFailsWith<GatewayException> { g.invoke(request()) }
        assertEquals(ErrorKind.POLICY_DENIED, e.error.kind); assertEquals("BUDGET", e.error.code)
        assertEquals(0, fake.calls)
        assertEquals(ErrorKind.POLICY_DENIED, audit.records.single().errorKind)
    }

    @Test fun spendAccumulatesUntilBudgetRejects() = runTest {
        val m = model("m", "ext")
        val g = gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))), budget(hard = "0.05"))
        // each call reserves ~ (inputs + 1000 out * 15/M = 0.015); commits 0.0105
        repeat(4) { g.invoke(request(id = "r$it")) }
        assertFailsWith<GatewayException> { g.invoke(request(id = "r-final")) }
    }

    @Test fun capabilityUnsupportedIsExplicit() = runTest {
        val m = model("m", "ext", caps = setOf(Capability.TEXT, Capability.STREAMING))
        val g = gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))))
        val e = assertFailsWith<GatewayException> { g.invoke(request(tools = listOf(ToolSpec("t", "d", emptySchema)))) }
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, e.error.kind)
    }

    @Test fun transientErrorFallsBackToNextModel() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2", tier = QualityTier.A)
        val fa = FakeProvider("ext") { fail(ErrorKind.TRANSIENT, notSent = true) }; val fb = FakeProvider("ext2", ok(b))
        val events = mutableListOf<String>()
        val g = gateway(listOf(a, b), mapOf("ext" to fa, "ext2" to fb), metrics = object : GatewayMetrics {
            override fun fallback(from: ModelRef, to: ModelRef, reason: ErrorKind) { events.add("${from.modelId}->${to.modelId}:$reason") }
        })
        val r = g.invoke(request())
        assertEquals("b", r.selected.modelId)
        assertEquals(3, fa.calls, "1 try + 2 same-model retries before fallback")
        assertEquals(listOf("a->b:TRANSIENT"), events)
        assertEquals(2, sleeps.size)
        assertEquals(3, audit.records.count { it.errorKind == ErrorKind.TRANSIENT })
    }

    @Test fun rateLimitRetriesSameModelHonouringRetryAfter() = runTest {
        val m = model("m", "ext")
        val fake = FakeProvider("ext") { n -> if (n == 1) fail(ErrorKind.RATE_LIMITED, retryAfterMs = 7000, notSent = true) else okResponse(m.ref) }
        gateway(listOf(m), mapOf("ext" to fake)).invoke(request())
        assertEquals(2, fake.calls); assertTrue(sleeps.single() >= 7000)
    }

    @Test fun invalidRequestIsNeitherRetriedNorFallenBack() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val fa = FakeProvider("ext") { fail(ErrorKind.INVALID_REQUEST, notSent = true) }; val fb = FakeProvider("ext2", ok(b))
        val e = assertFailsWith<GatewayException> { gateway(listOf(a, b), mapOf("ext" to fa, "ext2" to fb)).invoke(request()) }
        assertEquals(ErrorKind.INVALID_REQUEST, e.error.kind)
        assertEquals(1, fa.calls); assertEquals(0, fb.calls)
    }

    @Test fun contentFilterDoesNotFallBack() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val fb = FakeProvider("ext2", ok(b))
        assertFailsWith<GatewayException> { gateway(listOf(a, b), mapOf("ext" to FakeProvider("ext") { fail(ErrorKind.CONTENT_FILTER) }, "ext2" to fb)).invoke(request()) }
        assertEquals(0, fb.calls)
    }

    @Test fun authErrorFallsBackToAnotherProviderWithoutSameModelRetry() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val fa = FakeProvider("ext") { fail(ErrorKind.AUTH, notSent = true) }
        val r = gateway(listOf(a, b), mapOf("ext" to fa, "ext2" to FakeProvider("ext2", ok(b)))).invoke(request())
        assertEquals("b", r.selected.modelId); assertEquals(1, fa.calls)
    }

    @Test fun failedAttemptsReleaseReservations() = runTest {
        val m = model("m", "ext"); val b = budget()
        assertFailsWith<GatewayException> { gateway(listOf(m), mapOf("ext" to FakeProvider("ext") { fail(ErrorKind.INVALID_REQUEST, notSent = true) }), b).invoke(request()) }
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").reserved))
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").committed))
    }

    // ---- trust zone is never widened by fallback ----

    @Test fun localPrimaryFailureNeverEscalatesToExternalProvider() = runTest {
        val loc = model("loc", "local", tier = QualityTier.S); val ex = model("ex", "ext")
        val fx = FakeProvider("ext", ok(ex))
        val g = gateway(listOf(loc, ex), mapOf("local" to FakeProvider("local") { fail(ErrorKind.TRANSIENT, notSent = true) }, "ext" to fx))
        assertFailsWith<GatewayException> { g.invoke(request()) }
        assertEquals(0, fx.calls, "external provider must not receive data that was routed to a local model")
    }

    @Test fun restrictedDataNeverReachesExternalEvenWhenLocalFails() = runTest {
        val loc = model("loc", "local"); val ex = model("ex", "ext", tier = QualityTier.S)
        val fx = FakeProvider("ext", ok(ex))
        val g = gateway(listOf(loc, ex), mapOf("local" to FakeProvider("local") { fail(ErrorKind.TRANSIENT, notSent = true) }, "ext" to fx))
        assertFailsWith<GatewayException> { g.invoke(request(DataClass.RESTRICTED)) }
        assertEquals(0, fx.calls)
    }

    @Test fun fallbackMayMoveToMoreInwardZone() = runTest {
        val ex = model("ex", "ext", tier = QualityTier.S); val loc = model("loc", "local")
        val r = gateway(listOf(ex, loc), mapOf("ext" to FakeProvider("ext") { fail(ErrorKind.TRANSIENT, notSent = true) }, "local" to FakeProvider("local", ok(loc)))).invoke(request())
        assertEquals("loc", r.selected.modelId)
    }

    @Test fun fallbackAttemptThatCannotReserveBudgetIsSkipped() = runTest {
        val a = model("a", "ext", tier = QualityTier.S, price = Price(BigDecimal("1"), BigDecimal("1")))
        val b = model("b", "ext2", price = Price(BigDecimal("1"), BigDecimal("1")))
        val fb = FakeProvider("ext2", ok(b))
        val e = assertFailsWith<GatewayException> {
            // Budget fits exactly one reservation; after the first attempt fails and releases, the retry fits again.
            gateway(listOf(a, b), mapOf("ext" to FakeProvider("ext") { fail(ErrorKind.INVALID_REQUEST, notSent = true) }, "ext2" to fb), budget("0.01")).invoke(request())
        }
        assertEquals(ErrorKind.INVALID_REQUEST, e.error.kind)
    }

    // ---- tool calls ----

    @Test fun toolCallIsReturnedAndNeverExecuted() = runTest {
        val m = model("m", "ext")
        val call = Part.ToolCall("c1", "do_thing", buildJsonObject { })
        val fake = FakeProvider("ext") { okResponse(m.ref, finish = FinishReason.TOOL_CALL, message = Message(Role.ASSISTANT, listOf(call))) }
        val r = gateway(listOf(m), mapOf("ext" to fake)).invoke(request(tools = listOf(ToolSpec("do_thing", "d", emptySchema))))
        assertEquals(FinishReason.TOOL_CALL, r.finishReason)
        assertEquals(call, r.message.parts.single())
        assertEquals(1, fake.calls, "gateway makes one call; the caller decides what to do with the tool call")
    }

    // ---- timeout / cancel ----

    @Test fun totalDeadlineProducesTimeoutAndSettlesBudgetAsEstimated() = runTest {
        val m = model("m", "ext"); val b = budget()
        val fake = FakeProvider("ext") { delay(60.seconds * 10); okResponse(m.ref) }
        val g = gateway(listOf(m), mapOf("ext" to fake), b)
        val e = assertFailsWith<GatewayException> { g.invoke(request().copy(timeout = 5.seconds)) }
        assertEquals(ErrorKind.TIMEOUT, e.error.kind)
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").reserved))
        assertTrue(b.usage("task").committed.signum() > 0, "provider may still bill a timed-out call: reserved amount is committed as estimated")
        assertEquals(ErrorKind.TIMEOUT, audit.records.last().errorKind)
    }

    @Test fun callerCancellationSettlesBudgetAndAuditsCancelled() = runTest {
        val m = model("m", "ext"); val b = budget()
        val started = CompletableDeferred<Unit>()
        val fake = FakeProvider("ext") { started.complete(Unit); awaitCancellation() }
        val job = launch { gateway(listOf(m), mapOf("ext" to fake), b).invoke(request()) }
        started.await()
        job.cancelAndJoin()
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").reserved))
        assertEquals(ErrorKind.CANCELLED, audit.records.single().errorKind)
    }

    @Test fun circuitBreakerExcludesProviderAfterRepeatedFailures() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val health = CircuitHealthTracker(threshold = 2)
        val fa = FakeProvider("ext") { fail(ErrorKind.TRANSIENT, notSent = true) }
        val g = gateway(listOf(a, b), mapOf("ext" to fa, "ext2" to FakeProvider("ext2", ok(b))), health = health)
        g.invoke(request(id = "r1"))
        val calls = fa.calls
        val r = g.invoke(request(id = "r2"))
        assertEquals("b", r.selected.modelId); assertEquals(calls, fa.calls, "open breaker: no further calls to the failing provider")
    }

    @Test fun noRouteAuditsRejectionAndReportsMetric() = runTest {
        val rejected = mutableListOf<String>()
        val m = model("m", "ext")
        val g = gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))), metrics = object : GatewayMetrics {
            override fun routeRejected(reason: String) { rejected.add(reason) }
        })
        assertFailsWith<GatewayException> { g.invoke(request(DataClass.SECRET)) }
        assertEquals(listOf("NO_ROUTE"), rejected)
        assertNull(audit.records.single().selected)
    }

    @Test fun auditNeverContainsPromptText() = runTest {
        val m = model("m", "ext")
        gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m)))).invoke(request(text = "TOP-SECRET-PROMPT"))
        assertTrue(!audit.records.toString().contains("TOP-SECRET-PROMPT"))
    }

    // ---- streaming ----

    @Test fun streamEmitsOrderedEventsStartedToCompleted() = runTest {
        val m = model("m", "ext"); val b = budget()
        val events = gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))), b).stream(request()).toList()
        assertIs<ModelEvent.Started>(events.first()); assertIs<ModelEvent.Completed>(events.last())
        assertEquals(events.indices.map { it.toLong() }, events.map { it.seq })
        assertEquals(0, BigDecimal("0.0105").compareTo((events.last() as ModelEvent.Completed).response.cost.amount))
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").reserved))
    }

    @Test fun streamFailureBeforeFirstEventFallsBack() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val g = gateway(listOf(a, b), mapOf("ext" to FakeProvider("ext") { fail(ErrorKind.AUTH, notSent = true) }, "ext2" to FakeProvider("ext2", ok(b))))
        val events = g.stream(request()).toList()
        assertEquals("b", (events.first() as ModelEvent.Started).selected.modelId)
    }

    @Test fun streamFailureAfterOutputDoesNotFallBack() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val partial = object : ModelProvider {
            override val providerId = "ext"
            override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
            override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
            override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse = fail(ErrorKind.TRANSIENT)
            override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext) = kotlinx.coroutines.flow.flow<StreamChunk> {
                emit(StreamChunk.Text("partial")); fail(ErrorKind.TRANSIENT)
            }
        }
        val fb = FakeProvider("ext2", ok(b))
        val events = ModelGateway(registryOf(listOf(ext, ext2), listOf(a, b)), mapOf("ext" to partial, "ext2" to fb), budget(), AlwaysHealthy, audit, sleep = { }, devAllowInMemoryLedger = true)
            .stream(request()).toList()
        assertIs<ModelEvent.Failed>(events.last()); assertEquals(0, fb.calls)
    }

    @Test fun streamRoutingRejectionThrows() = runTest {
        val m = model("m", "ext", caps = setOf(Capability.TEXT))
        val g = gateway(listOf(m), mapOf("ext" to FakeProvider("ext", ok(m))))
        assertEquals(ErrorKind.CAPABILITY_UNSUPPORTED, assertFailsWith<GatewayException> { g.stream(request()).toList() }.error.kind)
    }

    // ---- requestId idempotency / stream terminal contract ----

    @Test fun repeatedRequestIdReplaysResponseWithoutProviderCallOrSecondCharge() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m)); val b = budget()
        val g = gateway(listOf(m), mapOf("ext" to fake), b)
        val first = g.invoke(request())
        repeat(9) { assertEquals(first, g.invoke(request())) }
        assertEquals(1, fake.calls)
        assertEquals(0, BigDecimal("0.0105").compareTo(b.usage("task").committed))
    }

    @Test fun sameRequestIdWithDifferentPayloadIsRejected() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m))
        val g = gateway(listOf(m), mapOf("ext" to fake))
        g.invoke(request())
        val e = assertFailsWith<GatewayException> { g.invoke(request(text = "other")) }
        assertEquals("REQUEST_ID_REUSED", e.error.code); assertEquals(1, fake.calls)
    }

    @Test fun inFlightDuplicateRequestIdIsRejected() = runTest {
        val m = model("m", "ext"); val gate = CompletableDeferred<Unit>()
        val fake = FakeProvider("ext") { gate.await(); okResponse(m.ref) }
        val g = gateway(listOf(m), mapOf("ext" to fake))
        val first = async { g.invoke(request()) }
        runCurrent()
        val e = assertFailsWith<GatewayException> { g.invoke(request()) }
        assertEquals("DUPLICATE_REQUEST", e.error.code)
        gate.complete(Unit); first.await()
        assertEquals(1, fake.calls)
    }

    @Test fun failedAfterProviderCallRequestIdCannotBeResent() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext") { fail(ErrorKind.AUTH) }
        val g = gateway(listOf(m), mapOf("ext" to fake))
        assertFailsWith<GatewayException> { g.invoke(request()) }
        val e = assertFailsWith<GatewayException> { g.invoke(request()) }
        assertEquals("DUPLICATE_REQUEST", e.error.code); assertEquals(1, fake.calls)
    }

    @Test fun budgetDeniedBeforeAnyProviderCallDoesNotBurnRequestId() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m))
        val g = gateway(listOf(m), mapOf("ext" to fake), budget(hard = "0.0001"))
        repeat(2) { assertEquals("BUDGET", assertFailsWith<GatewayException> { g.invoke(request()) }.error.code) }
        assertEquals(0, fake.calls)
    }

    private fun streamOf(m: ModelEntry, chunks: suspend kotlinx.coroutines.flow.FlowCollector<StreamChunk>.() -> Unit) =
        object : ModelProvider by FakeProvider("ext", ok(m)) {
            override val providerId = "ext"
            override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext) = kotlinx.coroutines.flow.flow(chunks)
        }

    @Test fun streamEndingWithoutFinishedFailsTruncatedAndSettlesConservatively() = runTest {
        val m = model("m", "ext"); val b = budget()
        val g = ModelGateway(registryOf(listOf(ext), listOf(m)), mapOf("ext" to streamOf(m) { }), b, AlwaysHealthy, audit, GatewayMetrics.NOOP, GatewayConfig(), sleep = { }, devAllowInMemoryLedger = true)
        val events = g.stream(request()).toList()
        val f = assertIs<ModelEvent.Failed>(events.single())
        assertEquals("TRUNCATED_STREAM", f.error.code)
        assertEquals(0, BigDecimal.ZERO.compareTo(b.usage("task").reserved))
        assertTrue(b.usage("task").committed > BigDecimal.ZERO)
    }

    @Test fun duplicateFinishedAndErrorAfterFinishedYieldExactlyOneTerminal() = runTest {
        val m = model("m", "ext"); val b = budget()
        val p = streamOf(m) {
            emit(StreamChunk.Text("hi")); emit(StreamChunk.Finished(okResponse(m.ref))); emit(StreamChunk.Finished(okResponse(m.ref)))
            fail(ErrorKind.TRANSIENT)
        }
        val g = ModelGateway(registryOf(listOf(ext), listOf(m)), mapOf("ext" to p), b, AlwaysHealthy, audit, GatewayMetrics.NOOP, GatewayConfig(), sleep = { }, devAllowInMemoryLedger = true)
        val events = g.stream(request()).toList()
        assertEquals(1, events.count { it is ModelEvent.Completed || it is ModelEvent.Failed })
        assertIs<ModelEvent.Completed>(events.last())
        assertEquals(0, BigDecimal("0.0105").compareTo(b.usage("task").committed))
    }

    @Test fun repeatedStreamRequestIdIsRejected() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m))
        val g = gateway(listOf(m), mapOf("ext" to fake))
        g.stream(request()).toList()
        assertFailsWith<GatewayException> { g.stream(request()).toList() }
        assertEquals(1, fake.calls)
    }

    // ---- requestId ledger, request fingerprint and send-state settlement (review P1s) ----

    @Test fun sameHashCodeDifferentPayloadIsNotReplayed() = runTest {
        // "Aa" and "BB" share String.hashCode(), so the whole ModelRequest hashCode collides as well.
        assertEquals(request(text = "Aa").hashCode(), request(text = "BB").hashCode())
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m))
        val g = gateway(listOf(m), mapOf("ext" to fake))
        g.invoke(request(text = "Aa"))
        val e = assertFailsWith<GatewayException> { g.invoke(request(text = "BB")) }
        assertEquals("REQUEST_ID_REUSED", e.error.code); assertEquals(1, fake.calls)
    }

    @Test fun completedRequestIdIsNeverEvictedAtCapacity() = runTest {
        val m = model("m", "ext"); val fake = FakeProvider("ext", ok(m))
        val g = gateway(listOf(m), mapOf("ext" to fake), ledger = InMemoryRequestLedger(capacity = 2))
        val first = g.invoke(request(id = "r1"))
        g.invoke(request(id = "r2"))
        val e = assertFailsWith<GatewayException> { g.invoke(request(id = "r3")) }
        assertEquals(ErrorKind.POLICY_DENIED, e.error.kind); assertEquals("LEDGER_FULL", e.error.code)
        assertEquals(2, fake.calls)
        assertEquals(first, g.invoke(request(id = "r1")))
        assertEquals(2, fake.calls, "a completed requestId stays sealed after capacity is reached")
    }

    @Test fun postSendFailureIsNotRetriedOrFallenBackAndChargesEstimate() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val fa = FakeProvider("ext") { fail(ErrorKind.TRANSIENT) }; val fb = FakeProvider("ext2", ok(b))
        val bud = budget()
        val g = gateway(listOf(a, b), mapOf("ext" to fa, "ext2" to fb), bud)
        val e = assertFailsWith<GatewayException> { g.invoke(request()) }
        assertEquals(ErrorKind.OUTCOME_UNKNOWN, e.error.kind)
        assertEquals(1, fa.calls); assertEquals(0, fb.calls); assertTrue(sleeps.isEmpty())
        assertEquals(0, BigDecimal.ZERO.compareTo(bud.usage("task").reserved))
        assertTrue(bud.usage("task").committed > BigDecimal.ZERO, "a possibly billed call is charged at the estimate")
        val again = assertFailsWith<GatewayException> { g.invoke(request()) }
        assertEquals("DUPLICATE_REQUEST", again.error.code); assertEquals(1, fa.calls)
    }

    @Test fun everyPostSendFailureKindStopsRetryAndFallback() = runTest {
        val kinds = listOf(ErrorKind.TRANSIENT, ErrorKind.TIMEOUT, ErrorKind.RATE_LIMITED, ErrorKind.AUTH,
            ErrorKind.OUTPUT_INVALID, ErrorKind.CONTENT_FILTER, ErrorKind.INVALID_REQUEST)
        for (kind in kinds) {
            val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
            val fa = FakeProvider("ext") { fail(kind) }; val fb = FakeProvider("ext2", ok(b))
            val e = assertFailsWith<GatewayException>(kind.name) { gateway(listOf(a, b), mapOf("ext" to fa, "ext2" to fb)).invoke(request()) }
            assertEquals(ErrorKind.OUTCOME_UNKNOWN, e.error.kind, kind.name)
            assertEquals(1, fa.calls, kind.name); assertEquals(0, fb.calls, kind.name)
        }
    }

    @Test fun preSendFailureReleasesReservationAndFallsBackUnderSameRequestId() = runTest {
        val a = model("a", "ext", tier = QualityTier.S); val b = model("b", "ext2")
        val bud = budget(); val fb = FakeProvider("ext2", ok(b))
        val r = gateway(listOf(a, b), mapOf("ext" to FakeProvider("ext") { fail(ErrorKind.TRANSIENT, notSent = true) }, "ext2" to fb), bud).invoke(request())
        assertEquals("b", r.selected.modelId); assertEquals(1, fb.calls)
        assertEquals(0, BigDecimal("0.0105").compareTo(bud.usage("task").committed), "only the billed call is committed")
        assertEquals(0, BigDecimal.ZERO.compareTo(bud.usage("task").reserved))
    }

    @Test fun gatewayWithoutDurableLedgerFailsFastUnlessDevOptIn() {
        val m = model("m", "ext"); val reg = registryOf(listOf(ext), listOf(m))
        val providers = mapOf<String, ModelProvider>("ext" to FakeProvider("ext", ok(m)))
        assertFailsWith<IllegalStateException> { ModelGateway(reg, providers, budget()) }
        assertNotNull(ModelGateway(reg, providers, budget(), devAllowInMemoryLedger = true))
    }
}
