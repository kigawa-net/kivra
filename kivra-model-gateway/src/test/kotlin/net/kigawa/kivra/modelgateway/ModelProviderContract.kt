package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

enum class Scenario { TEXT, TOOL_CALL, RATE_LIMITED, SERVER_ERROR, UNAUTHORIZED, BAD_REQUEST, NO_CREDENTIAL, CONNECTION_RESET, NO_USAGE }

/**
 * Adapter conformance suite (acceptance criterion: shared contract tests). Every adapter test class extends this
 * and maps each [Scenario] to provider-specific mock traffic.
 */
abstract class ModelProviderContract {
    protected val target: ResolvedTarget = ResolvedTarget(
        provider("contract", confirmed = true),
        model("contract-model", "contract", caps = setOf(Capability.TEXT, Capability.TOOL_CALLING, Capability.STREAMING, Capability.SYSTEM_PROMPT)),
    )
    protected val ctx = CallContext("req-1", "req-1-a1", "decision-1")
    protected val tool = ToolSpec("get_weather", "weather", emptySchema)

    abstract fun providerFor(scenario: Scenario): ModelProvider

    private fun req(withTools: Boolean = false) = request(tools = if (withTools) listOf(tool) else emptyList())
        .copy(messages = listOf(Message(Role.SYSTEM, "be brief"), Message(Role.USER, "hi")))

    private suspend fun errorKind(s: Scenario): ErrorKind =
        assertFailsWith<GatewayException> { providerFor(s).invoke(req(), target, ctx) }.error.kind

    @Test fun textResponseCarriesUsageAndIdentity() = runTest {
        val r = providerFor(Scenario.TEXT).invoke(req(), target, ctx)
        assertEquals(ctx.requestId, r.requestId); assertEquals(ctx.attemptId, r.attemptId)
        assertEquals(target.model.ref, r.selected)
        assertEquals(FinishReason.STOP, r.finishReason)
        assertTrue(r.usage.inputTokens > 0 && r.usage.outputTokens > 0)
        assertTrue(r.message.parts.filterIsInstance<Part.Text>().isNotEmpty())
    }

    @Test fun toolCallIsReturnedNotExecuted() = runTest {
        val r = providerFor(Scenario.TOOL_CALL).invoke(req(withTools = true), target, ctx)
        assertEquals(FinishReason.TOOL_CALL, r.finishReason)
        assertTrue(r.message.parts.filterIsInstance<Part.ToolCall>().single().name == tool.name)
    }

    @Test fun streamingYieldsDeltasThenFinished() = runTest {
        val chunks = providerFor(Scenario.TEXT).stream(req(), target, ctx).toList()
        assertTrue(chunks.any { it is StreamChunk.Text })
        assertIs<StreamChunk.Finished>(chunks.last())
    }

    @Test fun rateLimitIsClassified() = runTest { assertEquals(ErrorKind.RATE_LIMITED, errorKind(Scenario.RATE_LIMITED)) }
    @Test fun serverErrorIsTransient() = runTest { assertEquals(ErrorKind.TRANSIENT, errorKind(Scenario.SERVER_ERROR)) }
    @Test fun unauthorizedIsAuth() = runTest { assertEquals(ErrorKind.AUTH, errorKind(Scenario.UNAUTHORIZED)) }
    @Test fun badRequestIsInvalidRequest() = runTest { assertEquals(ErrorKind.INVALID_REQUEST, errorKind(Scenario.BAD_REQUEST)) }
    @Test fun missingCredentialIsAuthWithoutNetworkCall() = runTest { assertEquals(ErrorKind.AUTH, errorKind(Scenario.NO_CREDENTIAL)) }
    @Test fun connectionFailureIsTransient() = runTest { assertEquals(ErrorKind.TRANSIENT, errorKind(Scenario.CONNECTION_RESET)) }

    @Test fun streamErrorsAreClassifiedToo() = runTest {
        val e = assertFailsWith<GatewayException> { providerFor(Scenario.RATE_LIMITED).stream(req(), target, ctx).toList() }
        assertEquals(ErrorKind.RATE_LIMITED, e.error.kind)
    }
}
