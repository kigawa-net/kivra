package net.kigawa.kivra.agent

import java.time.Duration

/** Limits of the Agent Manager (all settable; defaults are conservative). */
data class AgentManagerConfig(
    val leaseTtl: Duration = Duration.ofSeconds(30),
    val leaseGrace: Duration = Duration.ofSeconds(5),
    /** How many Agent attempts (new Agent + Runtime per attempt) a Run may use before it fails for good. */
    val maxAgentAttempts: Int = 2,
    /** Max dispatches of one external operation that provably had no effect (Transient). UNKNOWN is never resent. */
    val maxOperationAttempts: Int = 3,
    val transientBackoff: Duration = Duration.ofSeconds(1),
    val pollInterval: Duration = Duration.ofSeconds(1),
    val workspaceReadyTimeout: Duration = Duration.ofMinutes(5),
    val runtimeReadyTimeout: Duration = Duration.ofMinutes(5),
    /** Used when the profile has no timeout of its own. */
    val executionTimeout: Duration = Duration.ofMinutes(60),
    /** Runs this manager instance drives at the same time. */
    val maxConcurrentRuns: Int = 1,
    val deleteWorkspaceOnCancel: Boolean = true,
    /** Keep the Workspace of a failed attempt for diagnosis (a retry reuses it). */
    val deleteWorkspaceOnFailure: Boolean = false,
) {
    init {
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "leaseTtl must be positive" }
        require(maxAgentAttempts >= 1) { "maxAgentAttempts must be >= 1" }
        require(maxOperationAttempts >= 1) { "maxOperationAttempts must be >= 1" }
        require(maxConcurrentRuns >= 1) { "maxConcurrentRuns must be >= 1" }
    }
}
