package net.kigawa.kivra.orchestrator

import java.time.Duration

/**
 * Orchestrator configuration (design #3 section 5, ADR 0008).
 *
 * Controls the main reconcile loop: tick interval, concurrency limits, and admission guards.
 */
data class OrchestratorConfig(
    /** How often the main loop runs (default 30 seconds). */
    val tickInterval: Duration = Duration.ofSeconds(30),
    /** Maximum concurrent runs across all tasks (design #3 section 5.5). */
    val maxConcurrentRuns: Int = 5,
    /** Maximum pending PRs before blocking new admissions. */
    val maxPendingPrs: Int = 10,
    /** Maximum runs per day (circuit breaker threshold). */
    val maxRunsPerDay: Int = 50,
    /** Leader lease TTL: how long the orchestrator holds leadership. */
    val leaderLeaseTtl: Duration = Duration.ofSeconds(60),
    /** Leader lease renewal interval: renew before TTL expires. */
    val leaderLeaseRenewInterval: Duration = Duration.ofSeconds(20),
)
