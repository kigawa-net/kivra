package net.kigawa.kivra.agent

import java.time.Duration

/**
 * Configuration for the Code Auto-Fix Worker.
 *
 * The worker periodically scans for code issues (compilation errors, test failures)
 * and attempts to fix them using the DeveloperPort.
 */
data class CodeAutoFixConfig(
    /** How often the worker scans for code issues. */
    val interval: Duration = Duration.ofMinutes(10),
    /** Maximum number of issues to process per tick. */
    val maxIssuesPerTick: Int = 3,
    /** Maximum number of auto-fix attempts per issue. */
    val maxAttemptsPerIssue: Int = 3,
    /** How long to wait after an issue is detected before attempting auto-fix. */
    val issueDelay: Duration = Duration.ofMinutes(5),
    /** Whether to scan for compilation errors. */
    val scanCompilationErrors: Boolean = true,
    /** Whether to scan for test failures. */
    val scanTestFailures: Boolean = true,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(maxIssuesPerTick > 0) { "maxIssuesPerTick must be positive" }
        require(maxAttemptsPerIssue > 0) { "maxAttemptsPerIssue must be positive" }
        require(!issueDelay.isNegative) { "issueDelay must not be negative" }
    }
}
