package net.kigawa.kivra.modelgateway

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Days left and expiry of the FX rate (FxConfig.expiry), the warning policy, and the fixed log format. */
class FxExpiryTest {
    // as-of is a UTC midnight, so the rate expires (7 days later) at 2026-10-15T00:00:00Z.
    private val asOf = Instant.parse("2026-10-08T00:00:00Z")
    private val expiresAt = Instant.parse("2026-10-15T00:00:00Z")
    private val config = FxConfig(usdJpyRate = BigDecimal("150"), fxAsOf = asOf)

    private fun expiryAt(now: Instant, cfg: FxConfig = config): FxExpiry? =
        cfg.expiry(Clock.fixed(now, ZoneOffset.UTC))

    private fun alertAt(now: Instant, cfg: FxConfig = config): FxExpiryAlert? = FxExpiryPolicy.alertFor(expiryAt(now, cfg))

    // --- days left (boundaries) ---

    @Test
    fun `at the as-of the rate has the full 7 days left`() {
        val e = assertNotNull(expiryAt(asOf))
        assertEquals(7L, e.daysLeft)
        assertFalse(e.expired)
        assertEquals(expiresAt, e.expiresAt)
    }

    @Test
    fun `one day after the as-of there are 6 days left`() {
        val e = assertNotNull(expiryAt(asOf.plusSeconds(86_400)))
        assertEquals(6L, e.daysLeft)
        assertFalse(e.expired)
    }

    @Test
    fun `five days after the as-of there are 2 days left`() {
        assertEquals(2L, assertNotNull(expiryAt(asOf.plusSeconds(5 * 86_400L))).daysLeft)
    }

    @Test
    fun `a partial day left is rounded up`() {
        // 1 day and 1 second before expiry: one whole day (rounded up) is left.
        val e = assertNotNull(expiryAt(expiresAt.minusSeconds(86_401)))
        assertEquals(2L, e.daysLeft)
        assertEquals(1L, assertNotNull(expiryAt(expiresAt.minusSeconds(86_399))).daysLeft)
    }

    @Test
    fun `exactly at the expiry instant it is 0 days left and not yet expired (the converter still accepts)`() {
        val e = assertNotNull(expiryAt(expiresAt))
        assertEquals(0L, e.daysLeft)
        assertFalse(e.expired)
    }

    @Test
    fun `one second after the expiry instant it is expired with 0 or fewer days left`() {
        val e = assertNotNull(expiryAt(expiresAt.plusSeconds(1)))
        assertTrue(e.expired)
        assertTrue(e.daysLeft <= 0)
    }

    @Test
    fun `the expiry agrees with the converter - accepted at the boundary, refused just after`() {
        val rate = FxConfig(usdJpyRate = BigDecimal("150"), fxAsOf = asOf)
        val atBoundary = CostConverter(rate, Clock.fixed(expiresAt, ZoneOffset.UTC)).toYen(BigDecimal.ONE, "USD")
        val after = CostConverter(rate, Clock.fixed(expiresAt.plusSeconds(1), ZoneOffset.UTC)).toYen(BigDecimal.ONE, "USD")
        assertNotNull(atBoundary)
        assertNull(after)
        assertFalse(assertNotNull(expiryAt(expiresAt)).expired)
        assertTrue(assertNotNull(expiryAt(expiresAt.plusSeconds(1))).expired)
    }

    @Test
    fun `the days left follow maxAgeDays`() {
        val longer = config.copy(maxAgeDays = 10)
        assertEquals(10L, assertNotNull(expiryAt(asOf, longer)).daysLeft)
    }

    @Test
    fun `an unconfigured rate or as-of has no expiry`() {
        assertNull(expiryAt(asOf, FxConfig(usdJpyRate = null, fxAsOf = asOf)))
        assertNull(expiryAt(asOf, FxConfig(usdJpyRate = BigDecimal("150"), fxAsOf = null)))
        assertNull(expiryAt(asOf, FxConfig()))
    }

    // --- warning policy ---

    @Test
    fun `no warning with more than 2 days left`() {
        assertNull(alertAt(asOf))
        assertNull(alertAt(asOf.plusSeconds(4 * 86_400L))) // 3 days left
    }

    @Test
    fun `a warning at exactly 2 days left and below`() {
        assertEquals(
            FxExpiryAlert(FxExpiryLevel.WARN, "fx rate expires soon: asOf=2026-10-08 daysLeft=2"),
            alertAt(asOf.plusSeconds(5 * 86_400L)),
        )
        assertEquals(FxExpiryLevel.WARN, alertAt(asOf.plusSeconds(6 * 86_400L))?.level) // 1 day left
        assertEquals(FxExpiryLevel.WARN, alertAt(expiresAt)?.level) // 0 days left, not expired
    }

    @Test
    fun `an error once expired, with the fixed format`() {
        assertEquals(
            FxExpiryAlert(FxExpiryLevel.ERROR, "fx rate expired: asOf=2026-10-08"),
            alertAt(expiresAt.plusSeconds(1)),
        )
    }

    @Test
    fun `no warning when the rate is not configured`() {
        assertNull(alertAt(asOf, FxConfig()))
    }

    @Test
    fun `the log line holds only the date and the day count`() {
        val soon = alertAt(asOf.plusSeconds(5 * 86_400L))!!.message
        val expired = alertAt(expiresAt.plusSeconds(1))!!.message
        val rateOnly = FxConfig(usdJpyRate = BigDecimal("987.654321"), fxAsOf = asOf)
        val withRate = FxExpiryPolicy.alertFor(expiryAt(asOf.plusSeconds(5 * 86_400L), rateOnly))!!.message
        assertEquals("fx rate expires soon: asOf=2026-10-08 daysLeft=2", soon)
        assertEquals("fx rate expired: asOf=2026-10-08", expired)
        assertFalse(withRate.contains("987"))
        assertFalse(soon.contains("150"))
    }

    @Test
    fun `mutation check - the threshold is 2 days, so 3 days left must not warn`() {
        assertEquals(2L, FxExpiryPolicy.WARN_DAYS_LEFT)
        assertNull(alertAt(asOf.plusSeconds(4 * 86_400L)))
    }
}
