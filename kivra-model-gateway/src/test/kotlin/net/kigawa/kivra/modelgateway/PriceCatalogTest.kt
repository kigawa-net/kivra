package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Price versions (effectiveFrom) and token tiers (design 13 B2). */
class PriceCatalogTest {
    private fun at(s: String) = Instant.parse(s)
    private fun dec(s: String) = BigDecimal(s)
    private fun assertDec(expected: String, actual: BigDecimal) = assertEquals(0, dec(expected).compareTo(actual), "expected $expected but was $actual")

    private val v1 = Price(dec("3"), dec("15"), effectiveFrom = LocalDate.of(2026, 1, 1))
    private val v2 = Price(dec("6"), dec("15"), effectiveFrom = LocalDate.of(2026, 11, 1))
    private fun versioned(vararg prices: Price) = model("m", "ext", price = null).copy(prices = prices.toList())

    // (a) version selection

    @Test fun latestEffectiveVersionIsChosenAtTheJstMonthBoundary() {
        val m = versioned(v1, v2)
        assertEquals(v1, m.priceAt(at("2026-10-31T14:59:59Z")), "JST 2026-10-31 23:59:59 still uses the old version")
        assertEquals(v2, m.priceAt(at("2026-10-31T15:00:00Z")), "JST 2026-11-01 00:00 uses the new version")
    }

    @Test fun futureVersionIsNotUsedBeforeItsDate() {
        assertEquals(v1, versioned(v1, v2).priceAt(at("2026-06-01T00:00:00Z")))
    }

    @Test fun noVersionInForceMeansNoPrice() {
        assertNull(versioned(v2).priceAt(at("2026-06-01T00:00:00Z")))
        assertNull(versioned().priceAt(at("2026-06-01T00:00:00Z")))
    }

    @Test fun unversionedPriceIsAlwaysInForceAndYieldsToDatedVersions() {
        val always = Price(dec("1"), dec("1"))
        assertEquals(always, versioned(always).priceAt(at("2000-01-01T00:00:00Z")))
        assertEquals(always, versioned(always, v1).priceAt(at("2025-06-01T00:00:00Z")))
        assertEquals(v1, versioned(always, v1).priceAt(at("2026-06-01T00:00:00Z")))
    }

    @Test fun twoVersionsOnTheSameDateAreRejected() {
        assertFailsWith<RegistryException> { versioned(v1, v1.copy(outputPerMtok = dec("1"))) }
        assertFailsWith<RegistryException> { versioned(Price(dec("1"), dec("1")), Price(dec("2"), dec("2"))) }
    }

    // (b) tiers

    private val tiered = Price(
        dec("6"), dec("30"), cachedInputPerMtok = dec("0.6"),
        tiers = listOf(PriceTier(100, dec("3"), dec("15"), dec("0.3"))),
    )

    @Test fun tierBoundaryIsInclusiveOfUpTo() {
        assertDec("3", tiered.rateFor(100).inputPerMtok)
        assertDec("6", tiered.rateFor(101).inputPerMtok)
        assertDec("0.3", tiered.rateFor(0).cachedInputPerMtok)
    }

    @Test fun unknownTierUsesTheHighestRatePerComponent() {
        val r = tiered.rateFor(null)
        assertDec("6", r.inputPerMtok)
        assertDec("30", r.outputPerMtok)
        assertDec("0.6", r.cachedInputPerMtok)
    }

    @Test fun unknownTierTakesTheHighestCachedRateAcrossTiers() {
        // The estimate charges input-side tokens at the higher of the input and cached rates.
        val p = Price(dec("3"), dec("15"), cachedInputPerMtok = dec("0.3"), tiers = listOf(PriceTier(100, dec("2"), dec("10"), dec("1.5"))))
        assertDec("3", p.rateFor(null).inputPerMtok)
        assertDec("1.5", p.rateFor(null).cachedInputPerMtok)
    }

    @Test fun actualCostIsChargedAtTheTierOfTheActualInputSize() {
        val usage = Usage(inputTokens = 60, outputTokens = 0, cachedInputTokens = 40) // 100 total: still tier 1
        assertDec("0.000192", CostCalculator.cost(usage, tiered.rateFor(100), "USD", CostBasis.ACTUAL).amount)
        val over = Usage(inputTokens = 60, outputTokens = 0, cachedInputTokens = 41) // 101 total: top tier
        assertDec("0.0003846", CostCalculator.cost(over, tiered.rateFor(101), "USD", CostBasis.ACTUAL).amount)
    }

    @Test fun tiersMustBeStrictlyAscendingAndPositive() {
        assertFailsWith<RegistryException> {
            Price(dec("1"), dec("1"), tiers = listOf(PriceTier(200, dec("1"), dec("1")), PriceTier(100, dec("1"), dec("1"))))
        }
        assertFailsWith<RegistryException> { Price(dec("1"), dec("1"), tiers = listOf(PriceTier(0, dec("1"), dec("1")))) }
        assertFailsWith<RegistryException> { Price(dec("-1"), dec("1")) }
    }

    // YAML (a) + (b): versions and tiers are loaded from the registry

    private val base = """
        registryVersion: v1
        providers:
          - id: p
            adapter: anthropic
            trustZone: EXTERNAL
            dataHandling: { status: UNKNOWN }
            enabled: true
            endpoint: https://x.test
            credentialRef: P_KEY
        models:
          - id: m
            providerId: p
            providerModelName: name
            lifecycle: ACTIVE
            capabilities: [TEXT]
            contextWindowTokens: 1000
            maxOutputTokens: 100
    """.trimIndent()

    private fun withPrice(block: String) = base + "\n" + block.trimIndent().prependIndent("    ")

    @Test fun yamlLoadsVersionsWithTiersAndUnquotedDates() {
        val text = withPrice(
            """
            price:
              - effectiveFrom: 2026-01-01
                inputPerMtok: "3"
                outputPerMtok: "15"
                cachedInputPerMtok: "0.3"
                tiers:
                  - inputTokensUpTo: 200000
                    inputPerMtok: "1"
                    outputPerMtok: "5"
              - effectiveFrom: "2026-11-01"
                inputPerMtok: "6"
                outputPerMtok: "30"
            """,
        )
        val m = RegistryLoader.fromYaml(text).models.single()
        assertEquals(2, m.prices.size)
        assertEquals(LocalDate.of(2026, 1, 1), m.prices.mapNotNull { it.effectiveFrom }.min())
        assertEquals(PriceTier(200000, dec("1"), dec("5")), m.prices.single { it.tiers.isNotEmpty() }.tiers.single())
        assertDec("30", m.priceAt(at("2026-12-01T00:00:00Z"))!!.outputPerMtok)
    }

    @Test fun yamlSingleMappingStillMeansOneAlwaysValidVersion() {
        val m = RegistryLoader.fromYaml(withPrice("price: { inputPerMtok: \"3\", outputPerMtok: \"15\" }")).models.single()
        assertEquals(1, m.prices.size)
        assertEquals(null, m.prices.single().effectiveFrom)
    }

    @Test fun yamlRejectsUnknownPlaceholderInsideAVersionList() {
        assertFailsWith<RegistryException> {
            RegistryLoader.fromYaml(withPrice("price:\n  - { inputPerMtok: UNKNOWN, outputPerMtok: UNKNOWN }\n  - { inputPerMtok: \"1\", outputPerMtok: \"1\" }"))
        }
    }

    @Test fun yamlRejectsBadDecimalsUnsortedTiersAndDuplicateDates() {
        assertFailsWith<RegistryException> { RegistryLoader.fromYaml(withPrice("price: { inputPerMtok: abc, outputPerMtok: \"1\" }")) }
        assertFailsWith<RegistryException> {
            RegistryLoader.fromYaml(
                withPrice(
                    """
                    price:
                      inputPerMtok: "1"
                      outputPerMtok: "1"
                      tiers:
                        - { inputTokensUpTo: 200, inputPerMtok: "1", outputPerMtok: "1" }
                        - { inputTokensUpTo: 100, inputPerMtok: "1", outputPerMtok: "1" }
                    """,
                ),
            )
        }
        assertFailsWith<RegistryException> {
            RegistryLoader.fromYaml(
                withPrice(
                    """
                    price:
                      - { effectiveFrom: 2026-01-01, inputPerMtok: "1", outputPerMtok: "1" }
                      - { effectiveFrom: 2026-01-01, inputPerMtok: "2", outputPerMtok: "2" }
                    """,
                ),
            )
        }
    }
}
