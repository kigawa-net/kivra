package net.kigawa.kivra.selfdev

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.DifficultyRouter
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.InMemoryRequestLedger
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelEntry
import net.kigawa.kivra.modelgateway.ModelEvent
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.Price
import net.kigawa.kivra.modelgateway.ProviderEntry
import net.kigawa.kivra.modelgateway.ProviderHealth
import net.kigawa.kivra.modelgateway.ProviderHealthState
import net.kigawa.kivra.modelgateway.ProviderModelInfo
import net.kigawa.kivra.modelgateway.Purpose
import net.kigawa.kivra.modelgateway.QualityTier
import net.kigawa.kivra.modelgateway.Requirements
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The self-development calls reach the on-premises model only through the difficulty router, using the request shape those calls build:
 * the hosted provider pinned, and the hosted model id pinned (planner, review). These tests run that shape through decide, apply, and the gateway.
 */
class SelfDevRoutingTest {
    private val hostedModel = "claude-haiku-5-5"

    /** A provider that answers the classifier (an invoke) with [label] and streams [reply]. [calls] counts every call. */
    private class Labelled(override val providerId: String, private val label: String, private val reply: String) : ModelProvider {
        var calls = 0

        private fun response(ctx: CallContext, target: ResolvedTarget, text: String) = ModelResponse(
            requestId = ctx.requestId, attemptId = ctx.attemptId,
            selected = ModelRef(target.provider.id, target.model.id, target.model.providerModelName),
            message = Message(Role.ASSISTANT, text), structured = null, finishReason = FinishReason.STOP,
            usage = Usage(1, 1), cost = Cost.zero(), latencyMs = 1, routingDecisionId = ctx.routingDecisionId, usageComplete = true,
        )

        override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
        override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
        override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
            calls++
            return response(ctx, target, label)
        }

        override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = flow {
            calls++
            emit(StreamChunk.Text(reply))
            emit(StreamChunk.Finished(response(ctx, target, reply)))
        }
    }

    /** The gateway and the registry it was built from (the router reads the registry to see the on-premises models). */
    private class Built(val gateway: ModelGateway, val registry: ModelRegistry)

    private fun build(local: ModelProvider?, hosted: ModelProvider, withLocalModel: Boolean = true): Built {
        val confirmed = DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms")
        val providers = buildList {
            add(ProviderEntry("anthropic", "anthropic", TrustZone.EXTERNAL, confirmed, true, "https://anthropic.example.test", "ANTHROPIC_API_KEY"))
            if (local != null) {
                add(ProviderEntry("local", "openai", TrustZone.LOCAL, DataHandling(DataHandlingStatus.UNKNOWN), true, "http://llm.example.internal:8000", "none"))
            }
        }
        val models = buildList {
            add(ModelEntry(
                "hm", "anthropic", hostedModel, Lifecycle.ACTIVE, null,
                setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT, Capability.STRUCTURED_OUTPUT_NATIVE),
                100_000, 4_096, listOf(Price(BigDecimal("3"), BigDecimal("15"))), QualityTier.A,
            ))
            if (local != null && withLocalModel) {
                add(ModelEntry(
                    "lm", "local", "local-name", Lifecycle.ACTIVE, null,
                    setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT, Capability.STRUCTURED_OUTPUT_NATIVE, Capability.TOOL_CALLING),
                    8_192, 1_024, listOf(Price(BigDecimal.ZERO, BigDecimal.ZERO)), QualityTier.C,
                ))
            }
        }
        val registry = ModelRegistry("test-v1", providers, models)
        val gw = ModelGateway(
            registry = registry,
            providers = buildMap { put("anthropic", hosted); if (local != null) put("local", local) },
            budget = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("100")))),
            ledger = InMemoryRequestLedger(),
        )
        return Built(gw, registry)
    }

    /** The planner's and the review's request shape: the hosted provider and the hosted model id pinned, TEXT only. */
    private fun pinnedRequest() = ModelRequest(
        requestId = "req-1", principal = "tester", purpose = Purpose.OTHER,
        messages = listOf(Message(Role.SYSTEM, "sys"), Message(Role.USER, "fix a typo")),
        dataClass = DataClass.INTERNAL, budgetScopes = listOf("tenant"),
        requirements = Requirements(
            requiredCapabilities = setOf(Capability.TEXT),
            allowedProviders = setOf("anthropic"),
            allowedModelIds = setOf("hm"),
        ),
    )

    /** Routes [base] with the router for [summary], runs it, and returns the provider that answered. */
    private fun answeredBy(b: Built, r: DifficultyRouter, summary: String): String = runBlocking {
        val base = pinnedRequest()
        val req = r.apply(base, r.decide("selfdev.planner", summary, base))
        assertIs<ModelEvent.Completed>(b.gateway.stream(req).toList().last()).response.selected.providerId
    }

    @Test
    fun `an EASY self-development call is answered on-premises, and a HARD one by the hosted model`() {
        val easy = build(Labelled("local", "EASY", "local-answer"), Labelled("anthropic", "EASY", "hosted-answer"))
        assertEquals("local", answeredBy(easy, DifficultyRouter(easy.gateway, easy.registry, "local", "anthropic", true), "fix a typo in the readme"))

        val hard = build(Labelled("local", "HARD", "local-answer"), Labelled("anthropic", "HARD", "hosted-answer"))
        assertEquals("anthropic", answeredBy(hard, DifficultyRouter(hard.gateway, hard.registry, "local", "anthropic", true), "redesign the scheduler"))
    }

    @Test
    fun `without an on-premises provider the call is unchanged and the classifier is never called`() = runBlocking {
        val hosted = Labelled("anthropic", "EASY", "hosted-answer")
        val b = build(local = null, hosted = hosted)
        val r = DifficultyRouter(b.gateway, b.registry, null, "anthropic", true)
        val base = pinnedRequest()
        assertEquals(base, r.apply(base, r.decide("selfdev.planner", "fix a typo", base)))
        assertEquals(0, hosted.calls)
    }

    @Test
    fun `a local model that does not declare the required capability is never classified`() = runBlocking {
        val local = Labelled("local", "EASY", "local-answer")
        val b = build(local, Labelled("anthropic", "EASY", "hosted-answer"), withLocalModel = false)
        val r = DifficultyRouter(b.gateway, b.registry, "local", "anthropic", true)
        val base = pinnedRequest()
        assertEquals(base, r.apply(base, r.decide("selfdev.planner", "fix a typo", base)))
        assertEquals(0, local.calls)
    }

    @Test
    fun `the switch off keeps every self-development call hosted with no classifier call`() = runBlocking {
        val local = Labelled("local", "EASY", "local-answer")
        val b = build(local, Labelled("anthropic", "EASY", "hosted-answer"))
        val r = DifficultyRouter(b.gateway, b.registry, "local", "anthropic", enabled = false)
        val base = pinnedRequest()
        assertEquals(base, r.apply(base, r.decide("selfdev.review", "review it", base)))
        assertEquals(0, local.calls)
    }
}
