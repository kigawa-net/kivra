package net.kigawa.kivra.chat

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.db.ProjectChatMessageRow
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.FinishReason
import net.kigawa.kivra.modelgateway.GatewayException
import net.kigawa.kivra.modelgateway.Message
import net.kigawa.kivra.modelgateway.ModelProvider
import net.kigawa.kivra.modelgateway.Part
import net.kigawa.kivra.modelgateway.ModelRef
import net.kigawa.kivra.modelgateway.ModelRequest
import net.kigawa.kivra.modelgateway.ModelResponse
import net.kigawa.kivra.modelgateway.ProviderHealth
import net.kigawa.kivra.modelgateway.ProviderHealthState
import net.kigawa.kivra.modelgateway.ProviderModelInfo
import net.kigawa.kivra.modelgateway.Purpose
import net.kigawa.kivra.modelgateway.ResolvedTarget
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.ThinkingMode
import net.kigawa.kivra.modelgateway.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The summarizer's gateway call: its policy fields, its output checks, and a gateway refusal propagates (the replier falls back). */
class GatewayChatSummarizerTest {

    // Shorter than the secret scanner's 32-character candidates, so the nonce itself is never read as a secret. Production nonces are 128-bit.
    private val nonce = "5d1c8e0a9f7b4236"

    private fun row(id: Long, role: String, content: String) = ProjectChatMessageRow(id, "s", role, content, Instant.EPOCH)

    /** A provider that answers the one-shot call with [text] and [finish]; every request is kept. */
    private class InvokeProvider(private val text: String, private val finish: FinishReason = FinishReason.STOP) : ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        override val providerId: String = TEST_PROVIDER_ID
        override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
        override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
        override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
            requests += req
            return ModelResponse(
                requestId = ctx.requestId, attemptId = ctx.attemptId,
                selected = ModelRef(target.provider.id, target.model.id, target.model.providerModelName),
                message = Message(Role.ASSISTANT, text), structured = null, finishReason = finish,
                usage = Usage(inputTokens = 100, outputTokens = 20), cost = Cost.zero(), latencyMs = 1,
                routingDecisionId = ctx.routingDecisionId, usageComplete = true,
            )
        }

        override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> = flow {
            error("the summary is one-shot; stream is not used")
        }
    }

    private fun summarizer(provider: ModelProvider, limits: ChatSummaryLimits = ChatSummaryLimits()) = GatewayChatSummarizer(
        gateway = chatTestGateway(provider),
        budgetScopes = listOf(TEST_BUDGET_SCOPE),
        limits = limits,
        // The test registry has one model; the production default names the Planner's model, which the test registry does not have.
        modelId = "chat-model",
        nonceSource = { nonce },
    )

    @Test
    fun `the call is a one-shot summary under the chat's principal, data class and budget, with thinking off`() = runBlocking {
        val provider = InvokeProvider("要約です")
        val input = ChatSummaryInput(listOf(row(1, "user", "最初の依頼"), row(2, "assistant", "計画")), omitted = 3)

        val summary = summarizer(provider).summarize(input)

        assertEquals(ChatSummary("要約です", omitted = 3), summary)
        val request = provider.requests.single()
        assertTrue(request.requestId.startsWith("chat-summary-"))
        assertEquals(ProjectChatReplier.CHAT_PRINCIPAL, request.principal)
        assertEquals(Purpose.SUMMARIZE, request.purpose)
        assertEquals(DataClass.INTERNAL, request.dataClass)
        assertEquals(listOf(TEST_BUDGET_SCOPE), request.budgetScopes)
        assertEquals(ThinkingMode.DISABLED, request.generation.thinking)
        assertEquals(ChatSummaryLimits().maxOutputChars * 2, request.generation.maxOutputTokens)
        assertEquals(Role.SYSTEM, request.messages.first().role)
        val user = (request.messages.last().parts.single() as Part.Text).text
        assertTrue(user.contains("<chat-history nonce=\"$nonce\">"))
        assertTrue(user.contains("それより前の 3 件は含まれていません"))
    }

    @Test
    fun `a summary cut at the output limit is refused`() = runBlocking {
        val failure = assertFailsWith<ChatSummaryFailure> {
            summarizer(InvokeProvider("途中で", FinishReason.LENGTH)).summarize(ChatSummaryInput(listOf(row(1, "user", "a")), 0))
        }
        assertEquals("OUTPUT_LIMIT", failure.code)
    }

    @Test
    fun `an empty summary is returned as empty, for the replier to refuse`() = runBlocking {
        val summary = summarizer(InvokeProvider("   ")).summarize(ChatSummaryInput(listOf(row(1, "user", "a")), 0))

        assertEquals("", summary.text)
    }

    @Test
    fun `a history that holds a secret is refused by the gateway before any call, and the error propagates`() = runBlocking {
        val provider = InvokeProvider("never")
        val input = ChatSummaryInput(listOf(row(1, "user", "key is sk-abcdefghijklmnop1234")), 0)

        val error = assertFailsWith<GatewayException> { summarizer(provider).summarize(input) }

        assertEquals("SECRET_DETECTED", error.error.code)
        assertEquals(0, provider.requests.size, "the provider is never called")
    }

    @Test
    fun `a nonce that occurs in the history is redrawn, and when no nonce is usable the summary fails`() = runBlocking {
        val provider = InvokeProvider("要約")
        val input = ChatSummaryInput(listOf(row(1, "user", "contains $nonce")), 0)

        val failure = assertFailsWith<ChatSummaryFailure> { summarizer(provider).summarize(input) }

        assertEquals("NONCE", failure.code)
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun `the default model is the Planner's pinned model`() {
        assertEquals("claude-haiku-5-5", CHAT_SUMMARY_MODEL_ID)
    }
}
