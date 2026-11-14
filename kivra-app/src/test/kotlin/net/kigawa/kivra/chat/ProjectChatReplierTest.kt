package net.kigawa.kivra.chat

import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.db.ProjectChatRepository
import net.kigawa.kivra.db.ProjectRepository
import net.kigawa.kivra.db.TestDb
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.DifficultyRouter
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.ProviderHealth
import net.kigawa.kivra.modelgateway.ProviderHealthState
import net.kigawa.kivra.modelgateway.ProviderModelInfo
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.Usage
import net.kigawa.kivra.modelgateway.InMemoryRequestLedger
import net.kigawa.kivra.modelgateway.Lifecycle
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelEntry
import net.kigawa.kivra.modelgateway.ModelEvent
import net.kigawa.kivra.modelgateway.ModelGateway
import net.kigawa.kivra.modelgateway.ModelRegistry
import net.kigawa.kivra.modelgateway.Price
import net.kigawa.kivra.modelgateway.ProviderEntry
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.Purpose
import net.kigawa.kivra.modelgateway.QualityTier
import net.kigawa.kivra.modelgateway.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The reply's request (history window, policy fields) and the event flow, on H2 with the real chat repository and a scripted provider. */
class ProjectChatReplierTest {

    private fun TestDb.session(chats: ProjectChatRepository): String {
        val projectId = ProjectRepository(database, Clock.systemUTC()).create("Reply", null).projectId
        chats.createSession(projectId, "Chat")
        return projectId
    }

    @Test
    fun `the history is the newest messages, starting at a user message, and the newest is the user's`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats)
        // 21 alternating messages, user first: the newest 20 start with an assistant message, which the history drops.
        for (i in 1..21) chats.appendMessage(sessionId, if (i % 2 == 1) "user" else "assistant", "m$i")
        val replier = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE))

        val request = replier.request(sessionId)

        val history = request.messages.drop(1)
        assertEquals(Role.SYSTEM, request.messages.first().role)
        assertEquals((3..21).map { "m$it" }, history.map { (it.parts.single() as Part.Text).text })
        assertEquals(Role.USER, history.first().role)
        assertEquals(Role.USER, history.last().role)
        assertTrue(request.tools.isEmpty(), "the reply has no tools, so the model can take no action")
    }

    @Test
    fun `the request carries the chat policy (a fixed principal, INTERNAL data, the tenant budget and the output limit)`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats)
        chats.appendMessage(sessionId, "user", "hello")
        val replier = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE))

        val request = replier.request(sessionId)

        assertTrue(request.requestId.startsWith("chat-"))
        assertEquals(ProjectChatReplier.CHAT_PRINCIPAL, request.principal)
        assertEquals(Purpose.OTHER, request.purpose)
        assertEquals(DataClass.INTERNAL, request.dataClass)
        assertEquals(listOf(TEST_BUDGET_SCOPE), request.budgetScopes)
        assertEquals(ProjectChatReplier.MAX_OUTPUT_TOKENS, request.generation.maxOutputTokens)
        assertEquals(setOf(Capability.TEXT), request.requirements.requiredCapabilities)
    }

    @Test
    fun `each reply has its own requestId`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats)
        chats.appendMessage(sessionId, "user", "hello")
        val replier = ProjectChatReplier(chatTestGateway(ScriptedChatProvider()), chats, listOf(TEST_BUDGET_SCOPE))

        assertTrue(replier.request(sessionId).requestId != replier.request(sessionId).requestId)
    }

    @Test
    fun `the reply streams its text deltas and completes with the joined text`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats)
        chats.appendMessage(sessionId, "user", "hello")
        val provider = ScriptedChatProvider(listOf("Hel", "lo"))
        val replier = ProjectChatReplier(chatTestGateway(provider), chats, listOf(TEST_BUDGET_SCOPE))

        val events = runBlocking { replier.reply(sessionId).toList() }

        val deltas = events.filterIsInstance<ModelEvent.TextDelta>().map { it.text }
        assertEquals(listOf("Hel", "lo"), deltas)
        val completed = assertIs<ModelEvent.Completed>(events.last())
        assertEquals("Hello", ProjectChatReplier.replyText(completed.response))
        assertEquals(1, provider.requests.size)
    }

    /** Two providers: the hosted one (`anthropic`, priced) and an on-premises one (`local`, free). Both are scripted; the local one answers a classification. */
    private fun gatewayWithLocal(hosted: ScriptedChatProvider, local: ModelProvider): Pair<ModelGateway, ModelRegistry> {
        val confirmed = DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms")
        val registry = ModelRegistry(
            "test-v1",
            listOf(
                ProviderEntry("anthropic", "anthropic", TrustZone.EXTERNAL, confirmed, true, "https://anthropic.example.test", "ANTHROPIC_API_KEY"),
                ProviderEntry("local", "openai", TrustZone.LOCAL, DataHandling(DataHandlingStatus.UNKNOWN), true, "http://llm.example.internal:8000", "none"),
            ),
            listOf(
                ModelEntry("chat-model", "anthropic", "chat-model-name", Lifecycle.ACTIVE, null,
                    setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT), 100_000, 4_096,
                    listOf(Price(BigDecimal("3"), BigDecimal("15"))), QualityTier.A),
                ModelEntry("local-chat", "local", "local-name", Lifecycle.ACTIVE, null,
                    setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT), 8_192, 1_024,
                    listOf(Price(BigDecimal.ZERO, BigDecimal.ZERO)), QualityTier.C),
            ),
        )
        val gateway = ModelGateway(
            registry = registry,
            providers = mapOf("anthropic" to hosted, "local" to local),
            budget = BudgetController(mapOf(TEST_BUDGET_SCOPE to BudgetLimit(BigDecimal("100")))),
            ledger = InMemoryRequestLedger(),
        )
        return gateway to registry
    }

    /** Answers a classification (the non-streamed call) with [label], and streams [reply]. [calls] counts every call. */
    private fun labelledLocal(label: String, reply: String = "local") = LabelledProvider("local", label, reply)

    private fun routerFor(gateway: ModelGateway, registry: ModelRegistry, enabled: Boolean = true) =
        DifficultyRouter(gateway, registry, "local", "anthropic", enabled)

    @Test
    fun `an EASY message is answered on-premises, and a HARD one by the hosted provider only`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats)
        chats.appendMessage(sessionId, "user", "what time is it")
        val hosted = ScriptedChatProvider(listOf("hi"))
        val (gw, reg) = gatewayWithLocal(hosted, labelledLocal("EASY", "local-answer"))
        val replier = ProjectChatReplier(gw, chats, listOf(TEST_BUDGET_SCOPE), router = routerFor(gw, reg))

        val completed = assertIs<ModelEvent.Completed>(runBlocking { replier.reply(sessionId).toList() }.last())
        assertEquals("local", completed.response.selected.providerId)
        assertEquals("local-answer", ProjectChatReplier.replyText(completed.response))
        assertEquals(0, hosted.requests.size)

        chats.appendMessage(sessionId, "user", "design a migration plan")
        val hosted2 = ScriptedChatProvider(listOf("hosted"))
        val (gw2, reg2) = gatewayWithLocal(hosted2, labelledLocal("HARD"))
        val replier2 = ProjectChatReplier(gw2, chats, listOf(TEST_BUDGET_SCOPE), router = routerFor(gw2, reg2))
        val completed2 = assertIs<ModelEvent.Completed>(runBlocking { replier2.reply(sessionId).toList() }.last())
        assertEquals("anthropic", completed2.response.selected.providerId)
        assertEquals(1, hosted2.requests.size)
    }

    @Test
    fun `without a router every reply is hosted, and the local model is never called`() = TestDb().use { db ->
        val chats = ProjectChatRepository(db.database)
        val sessionId = db.session(chats)
        chats.appendMessage(sessionId, "user", "hello")
        val hosted = ScriptedChatProvider(listOf("hi"))
        val local = labelledLocal("EASY")
        val (gw, _) = gatewayWithLocal(hosted, local)
        val replier = ProjectChatReplier(gw, chats, listOf(TEST_BUDGET_SCOPE))

        runBlocking { replier.reply(sessionId).toList() }

        assertEquals(1, hosted.requests.size)
        assertEquals(0, local.calls)
    }
}

/** A provider that answers the classification with [label] (an invoke) and streams [reply]. [calls] counts every call. */
private class LabelledProvider(override val providerId: String, private val label: String, private val reply: String) : ModelProvider {
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
