package net.kigawa.kivra.selfdev

import net.kigawa.kivra.core.DevelopmentRunState

/**
 * Outcome of one [SelfDevRunner.advance] call. Every field is a fixed enum or a state name. No task text, model output, patch
 * content or token is ever carried here.
 */
sealed interface AdvanceResult {
    /**
     * One or more transitions were persisted. A single call may persist DEVELOPING -> PATCH_READY -> PULL_REQUEST (ADR 0008 item 6),
     * so [from] and [to] are the first and the last persisted states. [reasonCode] is the fixed code written to the run's
     * state reason (for example POLICY_REJECTED), or null for a normal step.
     */
    data class Advanced(
        val from: DevelopmentRunState,
        val to: DevelopmentRunState,
        val reasonCode: String? = null,
    ) : AdvanceResult

    /** Nothing can be done yet. The run is left as it is. */
    data class Waiting(val reason: WaitReason) : AdvanceResult

    /** This call did nothing and wrote nothing. */
    data class Skipped(val reason: SkipReason) : AdvanceResult

    /**
     * A step could not finish and the run's state was not changed. Calling [SelfDevRunner.advance] again may succeed.
     * A failure that must end the run is reported as [Advanced] with `to = FAILED` instead.
     */
    data class Failed(val code: FailureCode) : AdvanceResult
}

enum class WaitReason {
    /** The pull request is open and younger than the review timeout. A human reviews and merges it. */
    AWAITING_HUMAN_REVIEW,

    /** The pull request is merged. Merge verification is not wired yet, so the run is not completed (see SelfDevRunner). */
    AWAITING_MERGE_VERIFICATION,

    /** UNKNOWN: an operator must resolve the run. The runner never retries an unknown outcome on its own. */
    AWAITING_OPERATOR,
}

enum class SkipReason {
    /** [SelfDevConfig.enabled] is false (kill switch). */
    DISABLED,
    RUN_NOT_FOUND,

    /** The run is not a PR_ONLY run. The self-development path never touches other runs. */
    NOT_PR_ONLY,
    TERMINAL,

    /** Another worker holds the lease, or the run is on hold. */
    LEASE_NOT_ACQUIRED,

    /** The lease was lost (fenced) before a write. Nothing was written by this call after the loss. */
    LEASE_LOST,

    /** A cancel was requested and this worker had no lease to acknowledge it with. */
    HALTED,
}

enum class FailureCode {
    /** The base branch could not be read. */
    BASE_BRANCH_UNAVAILABLE,

    /** A repository read (listing, file content, branch, pull request) failed. */
    GITHUB_READ_FAILED,

    /** The task text needed for the proposal is not available. */
    TASK_TEXT_UNAVAILABLE,

    /** The run changed under this call (version conflict). */
    VERSION_CONFLICT,

    /** The state machine refused a transition this call attempted. Indicates a bug, not a provider condition. */
    INVALID_TRANSITION,
}
