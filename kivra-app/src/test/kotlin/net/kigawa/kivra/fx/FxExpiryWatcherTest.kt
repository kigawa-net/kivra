package net.kigawa.kivra.fx

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.modelgateway.FxConfig
import net.kigawa.kivra.modelgateway.FxExpiryLevel

class FxExpiryWatcherTest {
    private val asOf = "2026-10-08"
    private fun watcherAt(now: String, env: Map<String, String> = mapOf(FxConfig.ENV_USD_JPY to "150", FxConfig.ENV_AS_OF to asOf)) =
        FxExpiryWatcher(
            fxSettingsForWarning(env)!!,
            Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
        )

    @Test
    fun `check returns the warning when two days or fewer are left`() {
        val alert = assertNotNull(watcherAt("2026-10-13T00:00:00Z").check())
        assertEquals(FxExpiryLevel.WARN, alert.level)
        assertEquals("fx rate expires soon: asOf=2026-10-08 daysLeft=2", alert.message)
    }

    @Test
    fun `check returns an error once expired`() {
        val alert = assertNotNull(watcherAt("2026-10-15T00:00:01Z").check())
        assertEquals(FxExpiryLevel.ERROR, alert.level)
        assertEquals("fx rate expired: asOf=2026-10-08", alert.message)
    }

    @Test
    fun `check logs nothing with ample time left`() {
        assertNull(watcherAt("2026-10-09T00:00:00Z").check())
    }

    @Test
    fun `no settings when USD is not configured (no rate, or no as-of)`() {
        assertNull(fxSettingsForWarning(emptyMap()))
        assertNull(fxSettingsForWarning(mapOf(FxConfig.ENV_USD_JPY to "150")))
        assertNull(fxSettingsForWarning(mapOf(FxConfig.ENV_AS_OF to asOf)))
    }

    @Test
    fun `invalid FX settings give no watcher and do not throw`() {
        assertNull(fxSettingsForWarning(mapOf(FxConfig.ENV_USD_JPY to "150", FxConfig.ENV_AS_OF to "not-a-date")))
        assertNull(fxSettingsForWarning(mapOf(FxConfig.ENV_USD_JPY to "150", FxConfig.ENV_AS_OF to asOf, FxConfig.ENV_MAX_AGE_DAYS to "0")))
    }
}
