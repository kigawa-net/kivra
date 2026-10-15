package net.kigawa.kivra.developer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.FinishReason
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
import net.kigawa.kivra.modelgateway.Requirements
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** Scripted provider: [script] receives the 1-based call number and the request, and returns or throws. */
internal class ScriptedProvider(
    private val script: suspend (call: Int, req: ModelRequest) -> ModelResponse,
) : ModelProvider {
    override val providerId: String = "fake"
    val calls = CopyOnWriteArrayList<ModelRequest>()

    override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
    override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()

    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
        calls.add(req)
        return script(calls.size, req)
    }

    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = emptyFlow()
}

internal val TEST_MODEL_REF = ModelRef("fake", "m1", "m1-name")

internal fun reply(text: String) = ModelResponse(
    requestId = "r", attemptId = "a", selected = TEST_MODEL_REF, message = Message(Role.ASSISTANT, text), structured = null,
    finishReason = FinishReason.STOP, usage = Usage(1000, 500), cost = Cost.zero(), latencyMs = 5, routingDecisionId = "d",
    usageComplete = true,
)

internal const val VALID_PATCH_JSON =
    """{"files":[{"op":"write","path":"kivra-developer/src/main/kotlin/Hello.kt","content":"fun hello() = 1\n"}],"summary":"add hello"}"""

/** A gateway over one scripted provider, an in-memory ledger, and a budget of [budgetHard] USD. */
internal class Harness(
    budgetHard: String = "100",
    price: Price? = Price(BigDecimal("3"), BigDecimal("15")),
    script: suspend (call: Int, req: ModelRequest) -> ModelResponse = { _, _ -> reply(VALID_PATCH_JSON) },
) {
    val provider = ScriptedProvider(script)
    private val providerEntry = ProviderEntry(
        "fake", "fake", TrustZone.EXTERNAL,
        DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms"),
        true, "https://fake.example.test", "FAKE_KEY",
    )
    private val modelEntry = ModelEntry(
        "m1", "fake", "m1-name", Lifecycle.ACTIVE, null,
        setOf(net.kigawa.kivra.modelgateway.Capability.TEXT, net.kigawa.kivra.modelgateway.Capability.SYSTEM_PROMPT),
        100_000, 4096, listOfNotNull(price), QualityTier.A,
    )
    val gateway = ModelGateway(
        registry = ModelRegistry("test-v1", listOf(providerEntry), listOf(modelEntry)),
        providers = mapOf("fake" to provider),
        budget = BudgetController(mapOf("task" to BudgetLimit(BigDecimal(budgetHard)))),
        ledger = InMemoryRequestLedger(),
        sleep = { },
    )

    fun developer(
        nonceSource: () -> String = { "n0nce-fixed-for-tests" },
        maxContextBytes: Long = DeveloperConfig.DEFAULT_MAX_CONTEXT_BYTES,
    ) = LlmDeveloper(
        gateway,
        DeveloperConfig(
            principal = "kivra-developer-test",
            dataClass = DataClass.PUBLIC,
            budgetScopes = listOf("task"),
            requirements = Requirements(),
            maxOutputTokens = 1000,
            maxContextBytes = maxContextBytes,
        ),
        nonceSource,
    )
}

internal fun taskRequest(
    runId: String = "run-1",
    title: String = "Add hello",
    description: String = "Add a hello function.",
    files: List<RepoFile> = listOf(RepoFile("kivra-developer/src/main/kotlin/Hello.kt", "fun hello() = 0\n")),
) = DeveloperRequest(runId, title, description, files)

/** The user message text of a recorded request. */
internal fun userText(req: ModelRequest): String = req.messages.filter { it.role == Role.USER }
    .flatMap { it.parts }.filterIsInstance<net.kigawa.kivra.modelgateway.Part.Text>().joinToString("\n") { it.text }

internal fun systemText(req: ModelRequest): String = req.messages.filter { it.role == Role.SYSTEM }
    .flatMap { it.parts }.filterIsInstance<net.kigawa.kivra.modelgateway.Part.Text>().joinToString("\n") { it.text }
