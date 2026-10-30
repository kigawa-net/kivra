package net.kigawa.kivra.agent

import java.time.Duration

/**
 * Configuration for the PR Auto-Fix Worker.
 *
 * The worker periodically scans for PRs that need automatic fixes:
 * - PRs with failed CI checks
 * - PRs with review comments that request changes
 */
data class PrAutoFixConfig(
    /** How often the worker scans for PRs that need fixes. */
    val interval: Duration = Duration.ofMinutes(5),
    /** Maximum number of PRs to process per tick. */
    val maxPrsPerTick: Int = 5,
    /** Maximum number of auto-fix attempts per PR. */
    val maxAttemptsPerPr: Int = 3,
    /** How long to wait after a CI failure before attempting auto-fix. */
    val ciFailureDelay: Duration = Duration.ofMinutes(10),
    /** How long to wait after a review comment before attempting auto-fix. */
    val reviewCommentDelay: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        require(maxPrsPerTick > 0) { "maxPrsPerTick must be positive" }
        require(maxAttemptsPerPr > 0) { "maxAttemptsPerPr must be positive" }
        require(!ciFailureDelay.isNegative) { "ciFailureDelay must not be negative" }
        require(!reviewCommentDelay.isNegative) { "reviewCommentDelay must not be negative" }
    }
}
