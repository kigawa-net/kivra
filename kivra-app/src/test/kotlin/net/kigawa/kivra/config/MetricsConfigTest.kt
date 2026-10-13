package net.kigawa.kivra.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MetricsConfigTest {
    @Test
    fun `metrics is disabled and has no token by default`() {
        val config = MetricsConfig.from(MapApplicationConfig())
        assertFalse(config.enabled)
        assertNull(config.bearerToken)
    }

    @Test
    fun `enabling metrics without a token fails at startup`() {
        val cfg = MapApplicationConfig("kivra.metrics.enabled" to "true", "kivra.metrics.bearerToken" to "  ")
        assertFailsWith<IllegalArgumentException> { MetricsConfig.from(cfg) }
    }

    @Test
    fun `enabling metrics with a token is accepted`() {
        val cfg = MapApplicationConfig("kivra.metrics.enabled" to "true", "kivra.metrics.bearerToken" to "t0ken")
        val config = MetricsConfig.from(cfg)
        assertEquals(true, config.enabled)
        assertEquals("t0ken", config.bearerToken)
    }
}
