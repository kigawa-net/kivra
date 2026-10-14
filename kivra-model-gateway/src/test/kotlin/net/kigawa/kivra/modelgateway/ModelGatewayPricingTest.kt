package net.kigawa.kivra.modelgateway

import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The gateway books the actual cost in the JPY budget through the converter (design 13 B2). */
class ModelGatewayPricingTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ext = provider("ext")

    private fun converter(rate: String?) = CostConverter(
        FxConfig(rate?.let { BigDecimal(it) }, Instant.parse("2026-10-08T00:00:00Z")), clock,
    )

    private fun gateway(m: ModelEntry, fake: FakeProvider, b: BudgetPort, c: CostConverter?) = ModelGateway(
        registryOf(listOf(ext), listOf(m)), mapOf("ext" to fake), b, sleep = { }, devAllowInMemoryLedger = true,
        clock = clock, costConverter = c,
    )

    @Test fun actualCostIsBookedInYenWithFxAndTaxHeadroom() = runTest {
        val m = model("m", "ext", price = Price(BigDecimal("3"), BigDecimal("15")))
        val fake = FakeProvider("ext") { okResponse(m.ref, Usage(1000, 500)) }
        val b = BudgetController(mapOf("task" to BudgetLimit(BigDecimal("10"), currency = "JPY")), clock)
        val r = gateway(m, fake, b, converter("150")).invoke(request(id = "r1"))
        // Actual: 1000 x 3 + 500 x 15 per Mtok = 0.0105 USD -> 0.0105 x 150 x 1.1 x 1.1 = 1.90575 yen
        assertEquals(0, BigDecimal("1.90575").compareTo(b.usage("task").committed))
        assertEquals(0L, b.usage("task").unpricedCalls)
        // The response keeps the native (USD) actual cost; only the budget is in yen.
        assertEquals("USD", r.cost.currency)
        assertEquals(0, BigDecimal("0.0105").compareTo(r.cost.amount))
    }

    @Test fun unconvertibleCostRefusesTheCallBeforeTheProviderIsCalled() = runTest {
        val m = model("m", "ext", price = Price(BigDecimal("3"), BigDecimal("15")))
        val fake = FakeProvider("ext") { okResponse(m.ref, Usage(1000, 500)) }
        val b = BudgetController(mapOf("task" to BudgetLimit(BigDecimal("10"), currency = "JPY")), clock)
        val e = assertFailsWith<GatewayException> { gateway(m, fake, b, converter(null)).invoke(request(id = "r2")) }
        assertEquals("UNKNOWN_COST", e.error.code)
        assertEquals(0, fake.calls)
    }
}
