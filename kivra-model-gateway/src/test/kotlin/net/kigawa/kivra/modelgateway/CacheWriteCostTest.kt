package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Cache write (prompt cache creation) pricing: the cost is included, and an unpriced cache write is never booked as zero. */
class CacheWriteCostTest {
    private fun dec(s: String) = BigDecimal(s)
    private fun assertDec(expected: String, actual: BigDecimal?) =
        assertEquals(0, dec(expected).compareTo(actual), "expected $expected but was $actual")

    private val withWrites = Price(dec("3"), dec("15"), cacheWrite5mPerMtok = dec("3.75"), cacheWrite1hPerMtok = dec("6"))
    private val withoutWrites = Price(dec("3"), dec("15"))

    private fun cost(usage: Usage, price: Price): BigDecimal? =
        CostCalculator.cost(usage, price.rateFor(usage.inputTokens + usage.cachedInputTokens + usage.cacheCreationInputTokens), "USD", CostBasis.ACTUAL)?.amount

    @Test fun cacheWriteTokensAddTheirOwnCostAtTheTtlRate() {
        // 1000 x 3 + 500 x 15 = 0.0105 USD; 1M 5-minute writes at 3.75 = 3.75; 1M 1-hour writes at 6 = 6.
        val usage = Usage(1000, 500, 0, cacheCreationInputTokens = 2_000_000, cacheCreation1hInputTokens = 1_000_000)
        assertDec("9.7605", cost(usage, withWrites))
    }

    @Test fun zeroCacheWriteCostsTheSameAsBefore() {
        val before = cost(Usage(1000, 500, 40), withoutWrites)!!
        assertDec("0.01062", before)
        assertDec(before.toPlainString(), cost(Usage(1000, 500, 40), withWrites))
        assertDec(before.toPlainString(), cost(Usage(1000, 500, 40, 0, 0), withWrites))
    }

    @Test fun cacheWriteWithoutItsRateIsUnknownNotZero() {
        assertNull(cost(Usage(1000, 500, 0, cacheCreationInputTokens = 100), withoutWrites), "no 5m/1h rate: cost unknown")
        val only5m = Price(dec("3"), dec("15"), cacheWrite5mPerMtok = dec("3.75"))
        assertNull(cost(Usage(1000, 500, 0, cacheCreationInputTokens = 100, cacheCreation1hInputTokens = 100), only5m), "1h write without a 1h rate")
        val only1h = Price(dec("3"), dec("15"), cacheWrite1hPerMtok = dec("6"))
        assertNull(cost(Usage(1000, 500, 0, cacheCreationInputTokens = 100, cacheCreation1hInputTokens = 0), only1h), "5m write without a 5m rate")
    }

    @Test fun estimateCoversCacheWriteAtTheHighestInputSideRate() {
        // The reservation charges input tokens at the highest input-side rate, so a later cache write cannot exceed it.
        assertDec("6", withWrites.rateFor(null).inputPerMtok)
        assertNull(withoutWrites.rateFor(null).cacheWrite5mPerMtok)
    }

    @Test fun tierCacheWriteRatesApplyByTheTotalInputSize() {
        val tiered = Price(
            dec("3"), dec("15"), cacheWrite5mPerMtok = dec("3.75"), cacheWrite1hPerMtok = dec("6"),
            tiers = listOf(PriceTier(100, dec("1"), dec("5"), cacheWrite5mPerMtok = dec("1.25"), cacheWrite1hPerMtok = dec("2"))),
        )
        assertDec("1.25", tiered.rateFor(100).cacheWrite5mPerMtok)
        assertDec("3.75", tiered.rateFor(101).cacheWrite5mPerMtok)
        assertDec("2", tiered.rateFor(100).cacheWrite1hPerMtok)
    }

    @Test fun negativeCacheWriteRateIsRejected() {
        assertFailsWith<RegistryException> { Price(dec("3"), dec("15"), cacheWrite5mPerMtok = dec("-1")) }
    }

    // YAML: the new keys are optional, and registries without them still load.

    private val header = """
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
        """.trimIndent() + "\n"

    @Test fun yamlWithoutCacheWriteRatesStillLoadsAndLeavesThemUnset() {
        val text = header + "    price: { inputPerMtok: \"3\", outputPerMtok: \"15\" }\n"
        val p = RegistryLoader.fromYaml(text).models.single().prices.single()
        assertNull(p.cacheWrite5mPerMtok)
        assertNull(p.cacheWrite1hPerMtok)
    }

    @Test fun yamlLoadsCacheWriteRatesAtTopLevelAndInTiers() {
        val text = header + """
            price:
              inputPerMtok: "3"
              outputPerMtok: "15"
              cacheWrite5mPerMtok: "3.75"
              cacheWrite1hPerMtok: "6"
              tiers:
                - { inputTokensUpTo: 200000, inputPerMtok: "1", outputPerMtok: "5", cacheWrite5mPerMtok: "1.25", cacheWrite1hPerMtok: "2" }
        """.trimIndent().prependIndent("    ") + "\n"
        val p = RegistryLoader.fromYaml(text).models.single().prices.single()
        assertDec("3.75", p.cacheWrite5mPerMtok)
        assertDec("6", p.cacheWrite1hPerMtok)
        assertDec("1.25", p.tiers.single().cacheWrite5mPerMtok)
        assertDec("2", p.tiers.single().cacheWrite1hPerMtok)
    }
}
