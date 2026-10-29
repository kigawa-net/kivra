package net.kigawa.kivra.agent

import java.time.Duration

/**
 * Configuration for the Auto Recovery Worker.
 *
 * The worker periodically scans for runs and operations that can be automatically recovered:
 * - RETRY_WAITING runs that are eligible for retry
 * - UNKNOWN operations that can be reconciled
 * - RECONCILIATION_REQUIRED operations that need resolution
 */
data class AutoRecoveryConfig(
    /** How often the worker scans for recoverable items. */
    val interval: Duration = Duration.ofMinutes(1),
    /** Maximum number of RETRY_WAITING runs to process per tick. */
    val maxRetryRunsPerTick: Int = 10,
    /** Maximum number of operations to reconcile per tick. */
    val maxReconcileOpsPerTick: Int = 50,
    /** How long a run must be in RETRY_WAITING before it is eligible for auto-retry. */
    val retryWaitDelay: Duration = Duration.ofMinutes(5),
    /** How long an operation must be in UNKNOWN before it is eligible for auto-reconciliation. */
    val unknownOpDelay: Duration = Duration.ofMinutes(10),
    /** How long an operation must be in RECONCILIATION_REQUIRED before it is eligible for auto-resolution. */
    val reconcileDelay: Duration = Duration.ofMinutes(15),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(maxRetryRunsPerTick > 0) { "maxRetryRunsPerTick must be positive" }
        require(maxReconcileOpsPerTick > 0) { "maxReconcileOpsPerTick must be positive" }
        require(!retryWaitDelay.isNegative) { "retryWaitDelay must not be negative" }
        require(!unknownOpDelay.isNegative) { "unknownOpDelay must not be negative" }
        require(!reconcileDelay.isNegative) { "reconcileDelay must not be negative" }
    }
}
