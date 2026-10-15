package net.kigawa.kivra.db

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.modelgateway.BudgetController
import net.kigawa.kivra.modelgateway.BudgetLimit
import net.kigawa.kivra.modelgateway.CallContext
import net.kigawa.kivra.modelgateway.Capability
import net.kigawa.kivra.modelgateway.DataClass
import net.kigawa.kivra.modelgateway.DataHandling
import net.kigawa.kivra.modelgateway.DataHandlingStatus
import net.kigawa.kivra.modelgateway.FinishReason
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
import net.kigawa.kivra.modelgateway.Purpose
import net.kigawa.kivra.modelgateway.Role
import net.kigawa.kivra.modelgateway.StreamChunk
import net.kigawa.kivra.modelgateway.TrustZone
import net.kigawa.kivra.modelgateway.Usage
import net.kigawa.kivra.modelgateway.Cost
import net.kigawa.kivra.modelgateway.CostBasis
import org.jetbrains.exposed.sql.Database

/** A provider that counts its calls and answers every request with a fixed reply for the requestId it was given. */
private class CountingProvider(override val providerId: String) : ModelProvider {
    var calls = 0
    private val ref = ModelRef(providerId, "m1", "provider-m1")

    override suspend fun health(target: ResolvedTarget) = ProviderHealth(ProviderHealthState.UP)
    override suspend fun listModels(target: ResolvedTarget) = emptyList<ProviderModelInfo>()
    override suspend fun invoke(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): ModelResponse {
        calls++
        return ModelResponse(
            requestId = req.requestId, attemptId = ctx.attemptId, selected = ref,
            message = Message(Role.ASSISTANT, "answer $calls"), structured = null, finishReason = FinishReason.STOP,
            usage = Usage(1000, 500), cost = Cost(BigDecimal("0.01"), "USD", CostBasis.ESTIMATED),
            latencyMs = 5, routingDecisionId = ctx.routingDecisionId, usageComplete = true,
        )
    }
    override fun stream(req: ModelRequest, target: ResolvedTarget, ctx: CallContext): Flow<StreamChunk> =
        throw UnsupportedOperationException("not used by this test")
}

/** The Gateway over a durable ledger: a repeated requestId is answered from the store, and a restarted Gateway too. */
class DbRequestLedgerGatewayTest {
    private val providerEntry = ProviderEntry(
        "ext", "fake", TrustZone.EXTERNAL,
        DataHandling(DataHandlingStatus.CONFIRMED, "tester", LocalDate.of(2026, 10, 1), "https://example.test/terms"),
        true, "https://ext.example.test", "EXT_KEY",
    )
    private val modelEntry = ModelEntry(
        "m1", "ext", "m1-name", Lifecycle.ACTIVE, null,
        setOf(Capability.TEXT, Capability.STREAMING, Capability.SYSTEM_PROMPT, Capability.TOOL_CALLING),
        100_000, 4096, listOf(Price(BigDecimal("3"), BigDecimal("15"))), QualityTier.A,
    )

    private fun gateway(database: Database, provider: ModelProvider) = ModelGateway(
        ModelRegistry("test-v1", listOf(providerEntry), listOf(modelEntry)),
        mapOf("ext" to provider),
        BudgetController(mapOf("task" to BudgetLimit(BigDecimal("100")))),
        ledger = DbRequestLedger(database),
    )

    private fun request(id: String) = ModelRequest(
        requestId = id, principal = "tester", purpose = Purpose.CODING,
        messages = listOf(Message(Role.USER, "hello")), dataClass = DataClass.PUBLIC, budgetScopes = listOf("task"),
    )

    @Test
    fun repeatedRequestIdIsAnsweredFromTheLedgerWithoutAProviderCall() = TestDb().use { db ->
        val provider = CountingProvider("ext")
        val g = gateway(db.database, provider)
        val first = runBlocking { g.invoke(request("gw-1")) }
        val second = runBlocking { g.invoke(request("gw-1")) }
        assertEquals(first, second)
        assertEquals(1, provider.calls)
    }

    @Test
    fun aRestartedGatewayReplaysTheStoredResponseWithoutCallingTheProviderAgain() = TestDb().use { db ->
        val firstProvider = CountingProvider("ext")
        val first = runBlocking { gateway(db.database, firstProvider).invoke(request("gw-2")) }
        assertEquals(1, firstProvider.calls)

        // A new Gateway, a new ledger instance over a separate pool, and a provider that must not be called.
        val ds = db.replicaDataSource()
        try {
            val restartedProvider = CountingProvider("ext")
            val replayed = runBlocking { gateway(connectDatabase(ds), restartedProvider).invoke(request("gw-2")) }
            assertEquals(first, replayed)
            assertEquals(0, restartedProvider.calls)
            assertTrue(replayed.message.parts.isNotEmpty())
        } finally {
            closeDataSource(ds)
        }
    }
}
