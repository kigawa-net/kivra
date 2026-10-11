package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

fun provider(id: String, zone: TrustZone = TrustZone.EXTERNAL, confirmed: Boolean = true, enabled: Boolean = true) =
    ProviderEntry(
        id, "fake", zone,
        if (confirmed) DataHandling(DataHandlingStatus.CONFIRMED, "tester", java.time.LocalDate.of(2026, 10, 1), "https://example.test/terms")
        else DataHandling(DataHandlingStatus.UNKNOWN),
        enabled, "https://$id.example.test", "${id.uppercase()}_KEY",
    )

fun model(
    id: String, providerId: String, caps: Set<Capability> = setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT, Capability.TOOL_CALLING),
    tier: QualityTier? = QualityTier.A, context: Int = 100_000, maxOut: Int = 4096, price: Price? = Price(BigDecimal("3"), BigDecimal("15")),
    lifecycle: Lifecycle = Lifecycle.ACTIVE, retireAt: java.time.LocalDate? = null,
) = ModelEntry(id, providerId, "$id-name", lifecycle, retireAt, caps, context, maxOut, price, tier)

fun request(
    dataClass: DataClass = DataClass.PUBLIC, id: String = "req-1", scopes: List<String> = listOf("task"),
    reqs: Requirements = Requirements(), tools: List<ToolSpec> = emptyList(), maxOut: Int = 1000, text: String = "hello",
) = ModelRequest(
    requestId = id, principal = "tester", purpose = Purpose.CODING, messages = listOf(Message(Role.USER, text)),
    dataClass = dataClass, budgetScopes = scopes, tools = tools, requirements = reqs, generation = GenerationParams(maxOutputTokens = maxOut),
)

fun budget(hard: String = "100", scope: String = "task") = BudgetController(mapOf(scope to BudgetLimit(BigDecimal(hard))))

fun registryOf(providers: List<ProviderEntry>, models: List<ModelEntry>) = ModelRegistry("test-v1", providers, models)

class FakeProvider(
    override val providerId: String,
    private val script: suspend (attempt: Int) -> ModelResponse,
) : ModelProvider {
    var calls = 0
    override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
    override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse = script(++calls)
    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = flow {
        val r = script(++calls)
        emit(StreamChunk.Text("hi"))
        emit(StreamChunk.Finished(r))
    }
}

fun okResponse(target: ModelRef, usage: Usage = Usage(1000, 500), finish: FinishReason = FinishReason.STOP, message: Message = Message(Role.ASSISTANT, "ok")) =
    ModelResponse("req-1", "a", target, message, null, finish, usage, Cost.zero(), 5, "d")

fun fail(kind: ErrorKind, code: String? = null, retryAfterMs: Long? = null): Nothing =
    throw GatewayException(GatewayError(kind, "fake $kind", code, retryAfterMs))

val emptySchema = JsonObject(emptyMap())
