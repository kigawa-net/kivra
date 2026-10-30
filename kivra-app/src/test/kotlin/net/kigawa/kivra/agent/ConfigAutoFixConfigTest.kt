package net.kigawa.kivra.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Duration

/**
 * ConfigAutoFixConfig validation tests.
 */
class ConfigAutoFixConfigTest {

    @Test
    fun `default config is valid`() {
        val config = ConfigAutoFixConfig()
        assertEquals(Duration.ofMinutes(5), config.interval)
        assertEquals(5, config.maxIssuesPerTick)
        assertEquals(true, config.scanDatabaseConfig)
        assertEquals(true, config.scanEnvVars)
        assertEquals(true, config.scanDependencies)
        assertEquals(true, config.scanNetwork)
    }

    @Test
    fun `rejects negative interval`() {
        try {
            ConfigAutoFixConfig(interval = Duration.ofMinutes(-1))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `rejects zero interval`() {
        try {
            ConfigAutoFixConfig(interval = Duration.ZERO)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `rejects zero maxIssuesPerTick`() {
        try {
            ConfigAutoFixConfig(maxIssuesPerTick = 0)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `accepts custom config`() {
        val config = ConfigAutoFixConfig(
            interval = Duration.ofMinutes(1),
            maxIssuesPerTick = 10,
            scanDatabaseConfig = false,
            scanEnvVars = false,
            scanDependencies = false,
            scanNetwork = false,
        )
        assertEquals(Duration.ofMinutes(1), config.interval)
        assertEquals(10, config.maxIssuesPerTick)
        assertEquals(false, config.scanDatabaseConfig)
        assertEquals(false, config.scanEnvVars)
        assertEquals(false, config.scanDependencies)
        assertEquals(false, config.scanNetwork)
    }
}
