package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Issue #56: the dev self-development run was refused with UNKNOWN_COST. This uses the dev configuration as it is deployed
 * (Haiku 5.5 registry with tiered prices, KIVRA_FX_USD_JPY=165, KIVRA_FX_AS_OF=2026-10-16, JPY 10000 monthly limit), the
 * developer's request shape (INTERNAL, text, 8000 max output tokens, repository context of a few hundred KB) and the
 * usage that Anthropic actually reported for Haiku 5.5.
 */
class UnknownCostHaikuTest {
    private val registryYaml = """
        registryVersion: "2026-10-16-dev"
        providers:
          - id: anthropic
            adapter: anthropic
            trustZone: EXTERNAL
            dataHandling: { status: CONFIRMED, confirmedBy: tester, confirmedAt: 2026-10-01, sourceUrl: 'https://example.test/terms' }
            enabled: true
            endpoint: https://api.anthropic.com
            credentialRef: ANTHROPIC_API_KEY
        models:
          - id: claude-haiku-5-5
            providerId: anthropic
            providerModelName: claude-haiku-5-5
            lifecycle: ACTIVE
            capabilities: [TEXT, STRUCTURED_OUTPUT_NATIVE, TOOL_CALLING, STREAMING, SYSTEM_PROMPT, PROMPT_CACHING]
            contextWindowTokens: 200000
            maxOutputTokens: 64000
            qualityTier: A
            price:
              inputPerMtok: "0.50"
              outputPerMtok: "2.50"
              cachedInputPerMtok: "0.05"
              cacheWrite5mPerMtok: "0.625"
              cacheWrite1hPerMtok: "1.00"
              currency: USD
              tiers:
                - inputTokensUpTo: 100000
                  inputPerMtok: "0.10"
                  outputPerMtok: "0.50"
                  cachedInputPerMtok: "0.01"
                  cacheWrite5mPerMtok: "0.125"
                  cacheWrite1hPerMtok: "0.20"
    """.trimIndent()

    private val registry = RegistryLoader.fromYaml(registryYaml)
    private val haiku = registry.models.single()

    /** The dev FX settings, parsed by the same code the app uses. */
    private val fx = FxConfig.fromEnv(mapOf("KIVRA_FX_USD_JPY" to "165", "KIVRA_FX_AS_OF" to "2026-10-16"))

    /** Repository context of a few hundred KB, as the developer sends it. */
    private val repoText = "fun main() = println(\"hello\")\n".repeat(12_000)

    /** Usage as reported by the Anthropic Messages API for Haiku 5.5 (see issue #56). */
    private val reportedUsage = Usage(inputTokens = 18, outputTokens = 4)

    /** Dev request at 09:30 JST on 2026-10-16 (after the as-of date has begun in JST). */
    @Test fun devRequestAfterJstMidnightIsAdmittedAndBookedInYen() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-16T00:30:00Z"), ZoneOffset.UTC)
        val fake = FakeProvider("anthropic") { okResponse(haiku.ref, reportedUsage) }
        val budget = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), clock)
        val gateway = ModelGateway(
            registry, mapOf("anthropic" to fake), budget, sleep = { }, devAllowInMemoryLedger = true,
            clock = clock, costConverter = CostConverter(fx, clock),
        )
        val req = request(
            dataClass = DataClass.INTERNAL, id = "dev-1", scopes = listOf("tenant"), text = repoText, maxOut = 8000,
            reqs = Requirements(requiredCapabilities = setOf(Capability.TEXT, Capability.STRUCTURED_OUTPUT_NATIVE), allowedProviders = setOf("anthropic")),
        )
        gateway.invoke(req)
        assertEquals(1, fake.calls)
        assertEquals(0L, budget.usage("tenant").unpricedCalls)
    }

    /**
     * Dev request at 05:00 JST on 2026-10-16. KIVRA_FX_AS_OF=2026-10-16 is read as UTC midnight (design 13, runbook), which is
     * 09:00 JST, so the rate is still in the future here and the cost cannot be converted. Fail closed, with the reason code.
     * Whether a date-only as-of should mean JST midnight is a design decision (see the issue report), so this pins the current rule.
     */
    @Test fun devRequestBeforeUtcMidnightOfTheAsOfDateIsRefusedWithFxReason() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-15T20:00:00Z"), ZoneOffset.UTC)
        val fake = FakeProvider("anthropic") { okResponse(haiku.ref, reportedUsage) }
        val budget = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), clock)
        val gateway = ModelGateway(
            registry, mapOf("anthropic" to fake), budget, sleep = { }, devAllowInMemoryLedger = true,
            clock = clock, costConverter = CostConverter(fx, clock),
        )
        val req = request(
            dataClass = DataClass.INTERNAL, id = "dev-2", scopes = listOf("tenant"), text = repoText, maxOut = 8000,
            reqs = Requirements(requiredCapabilities = setOf(Capability.TEXT, Capability.STRUCTURED_OUTPUT_NATIVE), allowedProviders = setOf("anthropic")),
        )
        val e = assertFailsWith<GatewayException> { gateway.invoke(req) }
        assertEquals("UNKNOWN_COST", e.error.code)
        assertEquals(UnknownCostReason.FX_UNUSABLE, e.error.costReason)
        assertEquals(0, fake.calls)
        assertEquals(0L, budget.usage("tenant").unpricedCalls)
    }

    @Test fun estimateOfTheDevRequestFitsTheMonthlyLimit() {
        val clock = Clock.fixed(Instant.parse("2026-10-16T00:30:00Z"), ZoneOffset.UTC)
        val req = request(dataClass = DataClass.INTERNAL, text = repoText, maxOut = 8000)
        val amount = Estimator.maxBudgetAmount(req, haiku, registry.providers.getValue("anthropic"), registry.registryVersion, clock.instant(), CostConverter(fx, clock))
        assertEquals("JPY", amount?.currency)
        // Worst case for ~ 0.5 MB of input at the top input rate plus 8000 output tokens: well under 1 yen per 100 yen of the limit.
        kotlin.test.assertTrue(amount != null && amount.amount < BigDecimal("100"), "estimate was ${amount?.amount}")
    }

    @Test fun unpricedUsageFromAnEarlierCallBlocksLaterDevRequests() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-16T00:30:00Z"), ZoneOffset.UTC)
        val budget = BudgetController(mapOf("tenant" to BudgetLimit(BigDecimal("10000"), currency = "JPY")), clock)
        // A response that is billed but whose cost cannot be established (for example, the call failed after it was sent).
        val failing = FakeProvider("anthropic") { fail(ErrorKind.TRANSIENT, "BAD_RESPONSE") }
        val gw = ModelGateway(
            registry, mapOf("anthropic" to failing), budget, sleep = { }, devAllowInMemoryLedger = true,
            clock = clock, costConverter = CostConverter(fx, clock),
        )
        val req = request(
            dataClass = DataClass.INTERNAL, id = "dev-3", scopes = listOf("tenant"), text = "hi", maxOut = 8000,
            reqs = Requirements(requiredCapabilities = setOf(Capability.TEXT, Capability.STRUCTURED_OUTPUT_NATIVE), allowedProviders = setOf("anthropic")),
        )
        assertFailsWith<GatewayException> { gw.invoke(req) }
        assertEquals(1L, budget.usage("tenant").unpricedCalls)
        val next = assertFailsWith<GatewayException> {
            gw.invoke(req.copy(requestId = "dev-4"))
        }
        assertEquals("UNKNOWN_COST", next.error.code)
        assertEquals(UnknownCostReason.UNPRICED_IN_SCOPE, next.error.costReason)
    }
}
