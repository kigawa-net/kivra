package net.kigawa.kivra.chat

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.ErrorKind
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.GatewayError
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.InMemoryRequestLedger
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelEntry
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
import net.kigawa.kivra.modelgateway.QualityTier
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.SendState
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage

/** The provider id of the test registry. */
internal const val TEST_PROVIDER_ID = "anthropic"

/** The budget scope of the test gateway. */
internal const val TEST_BUDGET_SCOPE = "tenant"

/**
 * A provider that streams [pieces] as text and then completes with their join, or that fails with [failure] before any text (the call may have
 * been sent, so the gateway reports the failure as an event). With [tail] it streams the pieces and then throws it instead of completing
 * (a mid-reply failure, or a CancellationException for a call that was cancelled). Every request it gets is kept in [requests].
 */
internal class ScriptedChatProvider(
    private val pieces: List<String> = listOf("Hel", "lo"),
    private val failure: ErrorKind? = null,
    private val tail: Throwable? = null,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override val providerId: String = TEST_PROVIDER_ID

    override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)

    override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()

    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse =
        error("the chat reply streams; invoke is not used")

    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = flow {
        requests += req
        failure?.let { throw GatewayException(GatewayError(it, "scripted failure", sendState = SendState.MAYBE_SENT)) }
        pieces.forEach { emit(StreamChunk.Text(it)) }
        tail?.let { throw it }
        val response = ModelResponse(
            requestId = ctx.requestId,
            attemptId = ctx.attemptId,
            selected = ModelRef(target.provider.id, target.model.id, target.model.providerModelName),
            message = Message(Role.ASSISTANT, pieces.joinToString("")),
            structured = null,
            finishReason = FinishReason.STOP,
            usage = Usage(inputTokens = 100, outputTokens = 20),
            cost = Cost.zero(),
            latencyMs = 1,
            routingDecisionId = ctx.routingDecisionId,
            usageComplete = true,
        )
        emit(StreamChunk.Finished(response))
    }
}

/** A gateway over [provider] with one active streaming text model, a tenant budget and the in-memory ledger (tests only). */
internal fun chatTestGateway(provider: ModelProvider): ModelGateway {
    val registry = ModelRegistry(
        "test-v1",
        listOf(
            ProviderEntry(
                id = TEST_PROVIDER_ID,
                adapter = "fake",
                trustZone = TrustZone.EXTERNAL,
                dataHandling = DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms"),
                enabled = true,
                endpoint = "https://anthropic.example.test",
                credentialRef = "ANTHROPIC_API_KEY",
            ),
        ),
        listOf(
            ModelEntry(
                id = "chat-model",
                providerId = TEST_PROVIDER_ID,
                providerModelName = "chat-model-name",
                lifecycle = Lifecycle.ACTIVE,
                retireAt = null,
                capabilities = setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT),
                contextWindowTokens = 100_000,
                maxOutputTokens = 4_096,
                prices = listOf(Price(BigDecimal("3"), BigDecimal("15"))),
                qualityTier = QualityTier.A,
            ),
        ),
    )
    return ModelGateway(
        registry = registry,
        providers = mapOf(TEST_PROVIDER_ID to provider),
        budget = BudgetController(mapOf(TEST_BUDGET_SCOPE to BudgetLimit(BigDecimal("100")))),
        ledger = InMemoryRequestLedger(),
    )
}
