package net.kigawa.kivra.agent

import java.time.Duration

/**
 * Configuration for the Config Auto-Fix Worker.
 *
 * The worker periodically scans for configuration and environment issues
 * and attempts to fix them automatically.
 */
data class ConfigAutoFixConfig(
    /** How often the worker scans for configuration issues. */
    val interval: Duration = Duration.ofMinutes(5),
    /** Maximum number of issues to process per tick. */
    val maxIssuesPerTick: Int = 5,
    /** Whether to scan for database connection issues. */
    val scanDatabaseConfig: Boolean = true,
    /** Whether to scan for missing environment variables. */
    val scanEnvVars: Boolean = true,
    /** Whether to scan for dependency version conflicts. */
    val scanDependencies: Boolean = true,
    /** Whether to scan for network connectivity issues. */
    val scanNetwork: Boolean = true,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(maxIssuesPerTick > 0) { "maxIssuesPerTick must be positive" }
    }
}
