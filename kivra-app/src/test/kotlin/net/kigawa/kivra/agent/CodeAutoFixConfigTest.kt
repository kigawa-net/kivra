package net.kigawa.kivra.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Duration

/**
 * CodeAutoFixConfig validation tests.
 */
class CodeAutoFixConfigTest {

    @Test
    fun `default config is valid`() {
        val config = CodeAutoFixConfig()
        assertEquals(Duration.ofMinutes(10), config.interval)
        assertEquals(3, config.maxIssuesPerTick)
        assertEquals(3, config.maxAttemptsPerIssue)
        assertEquals(Duration.ofMinutes(5), config.issueDelay)
        assertEquals(true, config.scanCompilationErrors)
        assertEquals(true, config.scanTestFailures)
    }

    @Test
    fun `rejects negative interval`() {
        try {
            CodeAutoFixConfig(interval = Duration.ofMinutes(-1))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `rejects zero interval`() {
        try {
            CodeAutoFixConfig(interval = Duration.ZERO)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `rejects zero maxIssuesPerTick`() {
        try {
            CodeAutoFixConfig(maxIssuesPerTick = 0)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `rejects zero maxAttemptsPerIssue`() {
        try {
            CodeAutoFixConfig(maxAttemptsPerIssue = 0)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `rejects negative issueDelay`() {
        try {
            CodeAutoFixConfig(issueDelay = Duration.ofMinutes(-1))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `accepts custom config`() {
        val config = CodeAutoFixConfig(
            interval = Duration.ofMinutes(1),
            maxIssuesPerTick = 10,
            maxAttemptsPerIssue = 5,
            issueDelay = Duration.ofMinutes(2),
            scanCompilationErrors = false,
            scanTestFailures = false,
        )
        assertEquals(Duration.ofMinutes(1), config.interval)
        assertEquals(10, config.maxIssuesPerTick)
        assertEquals(5, config.maxAttemptsPerIssue)
        assertEquals(Duration.ofMinutes(2), config.issueDelay)
        assertEquals(false, config.scanCompilationErrors)
        assertEquals(false, config.scanTestFailures)
    }
}
