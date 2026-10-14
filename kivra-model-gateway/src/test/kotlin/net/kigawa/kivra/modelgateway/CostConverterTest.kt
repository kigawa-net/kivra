package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FX / tax conversion to yen, fail-closed rules, FX movement, and config loading (design 13 B2). */
class CostConverterTest {
    private val asOf = Instant.parse("2026-10-08T00:00:00Z")
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private fun dec(s: String) = BigDecimal(s)
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertEquals(0, dec(expected).compareTo(actual), "expected $expected but was $actual")

    private fun converter(rate: String? = "150", at: Instant? = asOf, fx: String = "0.10", tax: String = "0.10", maxAge: Long = 7, clockAt: Instant = now) =
        CostConverter(
            FxConfig(rate?.let(::dec), at, dec(fx), dec(tax), maxAge),
            Clock.fixed(clockAt, ZoneOffset.UTC),
        )

    // (c) conversion formula: ceil(foreign x rate x (1 + fx) x (1 + tax))

    @Test fun usdIsConvertedWithFxAndTaxHeadroom() {
        // 1 USD x 150 x 1.1 x 1.1 = 181.5
        assertDec("181.5", converter().toYen(dec("1"), "USD")!!.amount)
    }

    @Test fun roundsUpToMicroYen() {
        // 0.0000001 USD x 150 x 1.21 = 0.0000181500 -> ceil at micro-yen = 0.000019
        assertDec("0.000019", converter().toYen(dec("0.0000001"), "USD")!!.amount)
        assertEquals(6, converter().toYen(dec("0.0000001"), "USD")!!.amount.scale())
    }

    @Test fun jpyPriceGetsOnlyTheTaxHeadroom() {
        // No FX for JPY: 100 yen x 1.1 = 110.000000
        val y = converter(rate = null, at = null).toYen(dec("100"), "JPY")!!
        assertDec("110", y.amount)
        assertTrue(y.evidence.contains("fx=none"))
    }

    @Test fun marginsCanBeZeroAndTheRateIsAppliedExactly() {
        assertDec("150", converter(fx = "0", tax = "0").toYen(dec("1"), "USD")!!.amount)
    }

    @Test fun evidenceRecordsRateMarginsAndAsOf() {
        val e = converter().toYen(dec("1"), "USD")!!.evidence
        assertTrue(e.contains("fx=USD/JPY:150"), e)
        assertTrue(e.contains("fxMargin=0.10") && e.contains("taxMargin=0.10"), e)
        assertTrue(e.contains("fxAsOf=2026-10-08T00:00:00Z"), e)
    }

    @Test fun otherForeignCurrenciesAreNotConverted() {
        assertNull(converter().toYen(dec("1"), "EUR"))
    }

    // (d) fail closed

    @Test fun missingRateRefusesConversion() {
        assertNull(converter(rate = null).toYen(dec("1"), "USD"))
    }

    @Test fun outOfRangeRatesRefuseConversion() {
        for (bad in listOf("0", "-150", "0.99", "1000.01", "100000")) assertNull(converter(rate = bad).toYen(dec("1"), "USD"), bad)
        assertNotNull(converter(rate = "1").toYen(dec("1"), "USD"), "1 yen is the lower bound")
        assertNotNull(converter(rate = "1000").toYen(dec("1"), "USD"), "1000 yen is the upper bound")
    }

    @Test fun missingAsOfRefusesConversion() {
        assertNull(converter(at = null).toYen(dec("1"), "USD"))
    }

    @Test fun rateOlderThanTheMaximumAgeRefusesConversion() {
        // 7 days exactly is still fresh; 8 days is refused.
        assertNotNull(converter(at = Instant.parse("2026-10-03T00:00:00Z")).toYen(dec("1"), "USD"))
        assertNull(converter(at = Instant.parse("2026-10-02T00:00:00Z")).toYen(dec("1"), "USD"))
    }

    @Test fun maximumAgeIsConfigurable() {
        assertNull(converter(maxAge = 1).toYen(dec("1"), "USD"))
    }

    @Test fun asOfInTheFutureRefusesConversion() {
        assertNull(converter(at = Instant.parse("2026-10-11T00:00:00Z")).toYen(dec("1"), "USD"))
    }

    // (e) FX movement and month-end

    @Test fun rateMovementRaisesTheSameEstimate() {
        val at150 = converter(rate = "150").toYen(dec("1"), "USD")!!.amount
        val at165 = converter(rate = "165").toYen(dec("1"), "USD")!!.amount
        assertDec("181.5", at150)
        assertDec("199.65", at165)
        assertTrue(at165 > at150)
    }

    @Test fun monthEndRateChangeTakesEffectOnlyWithTheNewConfig() {
        // Oct 31 (JST): the rate of 150 (as of Oct 30) is still fresh.
        val before = converter(rate = "150", at = Instant.parse("2026-10-30T00:00:00Z"), clockAt = Instant.parse("2026-10-31T14:00:00Z"))
            .toYen(dec("1"), "USD")!!.amount
        // Nov 1 (JST) after the config reload with rate 165 as of Nov 1.
        val after = converter(rate = "165", at = Instant.parse("2026-11-01T00:00:00Z"), clockAt = Instant.parse("2026-11-01T00:30:00Z"))
            .toYen(dec("1"), "USD")!!.amount
        assertTrue(after > before)
        // The old rate, left unreloaded, goes stale 7 days later and is refused rather than silently used.
        assertNull(converter(rate = "150", at = Instant.parse("2026-10-30T00:00:00Z"), clockAt = Instant.parse("2026-11-09T00:00:00Z"))
            .toYen(dec("1"), "USD"))
    }

    @Test fun rateMovementCanRejectAReservationThatWasAdmittedBefore() {
        val ext = provider("ext")
        val m = model("m", "ext", price = Price(dec("3"), dec("15")))
        val req = request(scopes = listOf("task"))
        fun route(rate: String): RoutingDecision {
            val c = converter(rate = rate)
            val b = BudgetController(mapOf("task" to BudgetLimit(dec("2.80"), currency = "JPY")), Clock.fixed(now, ZoneOffset.UTC))
            return RoutingEngine(registryOf(listOf(ext), listOf(m)), b, AlwaysHealthy, setOf("ext"), Clock.fixed(now, ZoneOffset.UTC), 3, c).route(req)
        }
        // Estimate: 0.015099 USD. At 150 -> 2.740469 yen (admitted); at 165 -> 3.014515 yen (over the 2.80 limit).
        assertEquals(listOf("m"), route("150").selected.map { it.modelId })
        val denied = route("165")
        assertTrue(denied.selected.isEmpty())
        assertEquals("BUDGET", denied.rejection!!.code)
    }

    // Pricing through Estimator: versions, free local models and unknown costs

    private fun estimate(
        m: ModelEntry, p: ProviderEntry, at: Instant, c: CostConverter? = null,
    ) = Estimator.maxBudgetAmount(request(), m, p, "test-v1", at, c)

    @Test fun priceVersionChangesAtTheJstBoundary() {
        val ext = provider("ext")
        val v1 = Price(dec("3"), dec("15"), effectiveFrom = java.time.LocalDate.of(2026, 1, 1))
        val v2 = Price(dec("6"), dec("15"), effectiveFrom = java.time.LocalDate.of(2026, 11, 1))
        val m = model("m", "ext", price = null).copy(prices = listOf(v1, v2))
        val old = estimate(m, ext, Instant.parse("2026-10-31T14:59:59Z"))!!.amount
        val new = estimate(m, ext, Instant.parse("2026-10-31T15:00:00Z"))!!.amount
        assertTrue(new > old)
        assertNull(estimate(model("m", "ext", price = null).copy(prices = listOf(v2)), ext, Instant.parse("2026-06-01T00:00:00Z")))
    }

    @Test fun localModelWithExplicitZeroPriceIsFreeWithoutAnyRate() {
        val local = provider("loc", TrustZone.LOCAL)
        val m = model("m", "loc", price = Price(dec("0"), dec("0")))
        val noRate = converter(rate = null, at = null)
        val free = estimate(m, local, now, noRate)!!
        assertDec("0", free.amount)
        assertEquals("JPY", free.currency)
        assertTrue(free.priceRevision!!.contains("free-local"))
        // Without a converter the zero is in the price currency (USD budgets keep working as before).
        assertEquals("USD", estimate(m, local, now)!!.currency)
    }

    @Test fun localModelWithoutAPriceIsUnknownNotFree() {
        val local = provider("loc", TrustZone.LOCAL)
        assertNull(estimate(model("m", "loc", price = null), local, now, converter()))
    }

    @Test fun externalZeroPriceStillNeedsAUsableRate() {
        val ext = provider("ext")
        val m = model("m", "ext", price = Price(dec("0"), dec("0")))
        assertNull(estimate(m, ext, now, converter(rate = null, at = null)))
        assertNotNull(estimate(m, ext, now, converter()))
    }

    // (f) the local zero and the unknown price are kept apart in routing

    @Test fun routingAdmitsLocalFreeModelButRejectsUnknownCostForExternalAndLocalUnpriced() {
        val loc = provider("loc", TrustZone.LOCAL)
        val ext = provider("ext")
        val free = model("free", "loc", price = Price(dec("0"), dec("0")))
        val unpriced = model("unpriced", "loc", price = null)
        val extNoRate = model("ext-no-rate", "ext", price = Price(dec("3"), dec("15")))
        val c = converter(rate = null, at = null)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val d = RoutingEngine(registryOf(listOf(loc, ext), listOf(free, unpriced, extNoRate)), BudgetController(mapOf("task" to BudgetLimit(dec("10"), currency = "JPY")), clock),
            AlwaysHealthy, setOf("loc", "ext"), clock, 3, c).route(request())
        assertEquals(listOf("free"), d.selected.map { it.modelId })
        assertEquals(ExclusionReason.UNKNOWN_COST, d.candidates.first { it.model.modelId == "unpriced" }.exclusion)
        assertEquals(ExclusionReason.UNKNOWN_COST, d.candidates.first { it.model.modelId == "ext-no-rate" }.exclusion)
    }

    // (h) config loading

    @Test fun emptyEnvironmentUsesDefaultsAndLeavesTheRateUnset() {
        val c = FxConfig.fromEnv(emptyMap())
        assertNull(c.usdJpyRate)
        assertNull(c.fxAsOf)
        assertDec("0.10", c.fxMarginRatio)
        assertDec("0.10", c.taxMarginRatio)
        assertEquals(7L, c.maxAgeDays)
        assertFalse(FxConfig.fromEnv(mapOf(FxConfig.ENV_USD_JPY to "  ")).usdJpyRate != null, "a blank value counts as unset")
    }

    @Test fun fullEnvironmentIsParsed() {
        val c = FxConfig.fromEnv(
            mapOf(
                FxConfig.ENV_USD_JPY to "150.25", FxConfig.ENV_AS_OF to "2026-10-08T00:00:00Z",
                FxConfig.ENV_FX_MARGIN to "0.05", FxConfig.ENV_TAX_MARGIN to "0.2", FxConfig.ENV_MAX_AGE_DAYS to "3",
            ),
        )
        assertDec("150.25", c.usdJpyRate)
        assertEquals(asOf, c.fxAsOf)
        assertDec("0.05", c.fxMarginRatio)
        assertDec("0.2", c.taxMarginRatio)
        assertEquals(3L, c.maxAgeDays)
    }

    @Test fun dateOnlyAsOfIsReadAsUtcMidnight() {
        assertEquals(asOf, FxConfig.fromEnv(mapOf(FxConfig.ENV_AS_OF to "2026-10-08")).fxAsOf)
    }

    @Test fun malformedValuesAreRejectedWithoutEchoingThem() {
        val bad = listOf(
            FxConfig.ENV_USD_JPY to "abc",
            FxConfig.ENV_AS_OF to "yesterday",
            FxConfig.ENV_FX_MARGIN to "1.5",
            FxConfig.ENV_FX_MARGIN to "-0.1",
            FxConfig.ENV_TAX_MARGIN to "x",
            FxConfig.ENV_MAX_AGE_DAYS to "0",
            FxConfig.ENV_MAX_AGE_DAYS to "seven",
        )
        for ((name, value) in bad) {
            val e = assertFailsWith<FxConfigException>(name) { FxConfig.fromEnv(mapOf(name to value)) }
            assertTrue(e.message!!.contains(name), e.message)
            assertFalse(e.message!!.contains(value) && value.length > 3, "message must not echo the value: ${e.message}")
        }
    }

    @Test fun extremeRateLoadsButIsRefusedAtConversion() {
        // A parseable but out-of-range rate is a conversion-time refusal, not a startup failure.
        val c = FxConfig.fromEnv(mapOf(FxConfig.ENV_USD_JPY to "5000", FxConfig.ENV_AS_OF to "2026-10-08"))
        assertNull(CostConverter(c, Clock.fixed(now, ZoneOffset.UTC)).toYen(dec("1"), "USD"))
    }
}
