package net.kigawa.kivra.orchestrator

import java.time.Duration

/**
 * Orchestrator startup settings (design #3 section 5, ADR 0008).
 *
 * The orchestrator is off by default. It is enabled only when the switch is explicitly set to true
 * and the fusha-task connection is configured. When enabled but fusha-task is missing, the startup
 * fails with a clear message (fail-closed).
 */
data class OrchestratorSettings(
    val enabled: Boolean,
    val config: OrchestratorConfig?,
) {
    companion object {
        private const val ENV_ENABLED = "KIVRA_ORCHESTRATOR_ENABLED"
        private const val ENV_TICK_INTERVAL_SECONDS = "KIVRA_ORCHESTRATOR_TICK_INTERVAL_SECONDS"
        private const val ENV_MAX_CONCURRENT_RUNS = "KIVRA_ORCHESTRATOR_MAX_CONCURRENT_RUNS"
        private const val ENV_LEASE_TTL_SECONDS = "KIVRA_ORCHESTRATOR_LEASE_TTL_SECONDS"

        /**
         * Reads the orchestrator settings from environment variables.
         * Fail-closed: a bad value fails the startup.
         */
        fun fromEnv(env: Map<String, String>): OrchestratorSettings {
            val enabled = env[ENV_ENABLED]?.toBooleanStrict() ?: false
            if (!enabled) return OrchestratorSettings(enabled = false, config = null)

            val tickInterval = env[ENV_TICK_INTERVAL_SECONDS]?.toLongOrNull()?.let {
                Duration.ofSeconds(it)
            } ?: Duration.ofSeconds(30)
            val maxConcurrentRuns = env[ENV_MAX_CONCURRENT_RUNS]?.toIntOrNull() ?: 5
            val leaseTtl = env[ENV_LEASE_TTL_SECONDS]?.toLongOrNull()?.let {
                Duration.ofSeconds(it)
            } ?: Duration.ofSeconds(60)

            require(tickInterval.isPositive) { "$ENV_TICK_INTERVAL_SECONDS must be positive" }
            require(maxConcurrentRuns >= 1) { "$ENV_MAX_CONCURRENT_RUNS must be >= 1" }
            require(leaseTtl.isPositive) { "$ENV_LEASE_TTL_SECONDS must be positive" }

            val config = OrchestratorConfig(
                tickInterval = tickInterval,
                maxConcurrentRuns = maxConcurrentRuns,
                leaderLeaseTtl = leaseTtl,
                leaderLeaseRenewInterval = leaseTtl.dividedBy(3),
            )
            return OrchestratorSettings(enabled = true, config)
        }
    }
}
