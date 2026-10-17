package net.kigawa.kivra.coding

import java.time.Duration
import net.kigawa.kivra.core.patch.PatchPolicyConfig
import net.kigawa.kivra.core.patch.RejectReason
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptState
import kotlinx.serialization.Serializable
import net.kigawa.kivra.db.CodingRefusal

/*
 * Values of the coding runtime service (design: Issue #187 design-coding-runtime.md sections 1.4, 2.2, 2.3; ADR 0010). Plain data:
 * no I/O. The service in [CodingRuntimeService] turns them into attempt transitions.
 *
 * Nothing here carries a secret, a credential or model output as a field name. Values that come from the task (the title) are
 * wrapped in [UntrustedText], whose toString shows only its length.
 */

/** Where a text came from, for the agent. Only [UNTRUSTED] exists: task text is data, never an instruction. */
enum class Trust { UNTRUSTED }

/** Text supplied by a person through the Task API. The agent must treat it as data. [toString] shows no content. */
class UntrustedText(val origin: String, val value: String, val trust: Trust, val truncated: Boolean) {
    override fun toString(): String = "UntrustedText(origin=$origin, chars=${value.length}, truncated=$truncated)"
}

/**
 * Limits the agent must enforce for one attempt. The file limits are copied from the PatchPolicy configuration, so the agent and the
 * server check against one set of numbers (see [CodingRuntimeService] for the copy).
 *
 * [maxRuntime] is the wall-clock limit for one attempt (the agent kills the container after it). [maxTurns] limits the number of
 * model turns. The CLI flag for it is unverified (design section 0, `--max-turns`), so the agent enforces it by its own count.
 * [maxOutputBytes] limits what the agent keeps from the CLI output.
 */
data class CodingLimits(
    val maxRuntime: Duration = Duration.ofMinutes(30),
    val maxTurns: Int = 30,
    val maxOutputBytes: Long = 1024L * 1024,
    val maxChangedFiles: Int = PatchPolicyConfig().maxFiles,
    val maxFileBytes: Long = PatchPolicyConfig().maxFileBytes,
    val maxTotalBytes: Long = PatchPolicyConfig().maxTotalBytes,
    val maxDeletes: Int = PatchPolicyConfig().maxDeletes,
) {
    init {
        require(!maxRuntime.isNegative && !maxRuntime.isZero) { "maxRuntime must be positive" }
        require(maxTurns > 0) { "maxTurns must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(maxChangedFiles > 0 && maxFileBytes > 0 && maxTotalBytes > 0 && maxDeletes >= 0) { "file limits are out of range" }
    }
}

/** What the agent receives when it claims an attempt. [leaseGeneration] is the fencing token it must send back with every call. */
data class CodingInput(
    val runId: String,
    val attemptId: String,
    val leaseGeneration: Long,
    val baseSha: String,
    val task: UntrustedText,
    /** The task body after the title (Issue #229 addendum). Empty when the run has none. UNTRUSTED. */
    val taskBody: UntrustedText = UntrustedText(origin = "task_body", value = "", trust = Trust.UNTRUSTED, truncated = false),
    val limits: CodingLimits,
    /** Path patterns the PatchPolicy accepts (summary for the agent; the server decides again). */
    val allowedPathPatterns: List<String>,
    /** Path patterns the PatchPolicy always refuses, even inside an allowed pattern. */
    val deniedPathPatterns: List<String>,
)

/** Progress phases a heartbeat may report. Fixed codes; the agent never sends free text. */
enum class ProgressPhase { STARTED, EDITING, TESTING, ROUND_COMPLETED, FINALIZING }

/**
 * Error codes an agent may report with `fail`. [state] is the terminal state the code leads to. Only these codes exist, so a free-text
 * reason cannot reach the database or a log.
 */
enum class CodingErrorCode(val state: CodingAttemptState) {
    AUTH_REQUIRED(CodingAttemptState.FAILED),
    RATE_LIMITED(CodingAttemptState.FAILED),
    TIMEOUT(CodingAttemptState.FAILED),
    OUTPUT_LIMIT(CodingAttemptState.FAILED),
    CLI_NONZERO(CodingAttemptState.FAILED),
    BAD_OUTPUT(CodingAttemptState.FAILED),
    NEEDS_HUMAN(CodingAttemptState.FAILED),
    CANCELLED(CodingAttemptState.CANCELLED),
}

/** Whether a change writes a file or deletes it. */
@Serializable
enum class ChangeKind { WRITE, DELETE }

/**
 * What kind of path entry a change is, as the agent saw it on the host (design section 1.4). Anything but [REGULAR] is refused by the
 * server: the agent is expected to refuse these too, and the server does not trust that.
 */
@Serializable
enum class FileKind { REGULAR, SYMLINK, SUBMODULE, EXECUTABLE, BINARY }

/**
 * One change of a result. A WRITE carries the full text of the file ([content]); a DELETE carries none. Paths are relative to the
 * repository root. [toString] shows no path and no content.
 */
@Serializable
data class CodingChange(
    val path: String,
    val kind: ChangeKind,
    val content: String? = null,
    val fileKind: FileKind = FileKind.REGULAR,
) {
    override fun toString(): String = "CodingChange(kind=$kind, fileKind=$fileKind, contentChars=${content?.length})"
}

/**
 * The agent's result for one attempt. [summary] becomes the PR body (after the runner's own sanitising). [exitStatus] and [sessionId]
 * describe the CLI run; [sessionId] is an identifier only. [toString] shows counts, never the summary or the changes.
 */
@Serializable
data class CodingResult(
    val summary: String,
    val changes: List<CodingChange>,
    val exitStatus: Int,
    val sessionId: String?,
) {
    override fun toString(): String = "CodingResult(changes=${changes.size}, summaryChars=${summary.length}, exitStatus=$exitStatus)"
}

/**
 * Fixed reasons a result is refused. [POLICY_REJECTED] means the PatchPolicy refused it; its own reasons are carried next to it in
 * [CodingSubmitOutcome.Rejected.policyReasons].
 */
enum class CodingRejectReason {
    TOO_MANY_CHANGES,
    SYMLINK,
    SUBMODULE,
    EXECUTABLE_BIT,
    BINARY_FILE,
    MALFORMED_CHANGE,
    MALFORMED_RESULT,
    POLICY_REJECTED,
}

/** What [CodingRuntimeService.enqueue] did. */
sealed interface CodingEnqueueOutcome {
    data class Queued(val attempt: CodingAttempt) : CodingEnqueueOutcome
    data class Active(val attempt: CodingAttempt) : CodingEnqueueOutcome

    /** The Run already has a terminal attempt. It is not queued again; the caller reads [latest] instead. */
    data class Settled(val latest: CodingAttempt) : CodingEnqueueOutcome
    data object RunNotFound : CodingEnqueueOutcome

    /** The Run is not DEVELOPING, is on hold, or has a cancel request. */
    data object RunNotDeveloping : CodingEnqueueOutcome

    /** The base sha is not a commit id. */
    data object InvalidInput : CodingEnqueueOutcome
}

/** What [CodingRuntimeService.claim] did. */
sealed interface CodingClaimOutcome {
    data class Claimed(val input: CodingInput) : CodingClaimOutcome
    data object NothingToClaim : CodingClaimOutcome
}

/** What [CodingRuntimeService.submitResult] did. Accepted means the change set was stored and the attempt is RESULT_RECEIVED. */
sealed interface CodingSubmitOutcome {
    data class Accepted(val digest: String) : CodingSubmitOutcome
    data class Rejected(val reasons: List<CodingRejectReason>, val policyReasons: List<RejectReason>) : CodingSubmitOutcome
    data class Refused(val reason: CodingRefusal) : CodingSubmitOutcome

    /** The result was valid but could not be stored. Nothing was marked as received. */
    data object StoreFailed : CodingSubmitOutcome
}

/** What [CodingRuntimeService.cancel] did. */
sealed interface CodingCancelOutcome {
    /** The Run was flagged and its QUEUED attempt was cancelled, so nothing will run. */
    data object QueuedCancelled : CodingCancelOutcome

    /**
     * The Run was flagged. A CLAIMED or RUNNING attempt keeps running until its holder reports (the agent-side delivery of the cancel is
     * part of the agent API, not in this PR).
     */
    data object CancelRequested : CodingCancelOutcome
    data object RunNotFound : CodingCancelOutcome

    /** The Run is in a state that cannot reach CANCELLED. */
    data object RunNotCancellable : CodingCancelOutcome
}

/** What the holder of an attempt may see of it. [cancelRequested] is the Run's cancel flag. */
data class AgentAttemptStatus(val state: CodingAttemptState, val cancelRequested: Boolean)

/** Counts of one reconcile pass. No ids and no text: the caller logs the counts only. */
data class CodingReconcileReport(
    val queuedExpired: Int,
    val orphaned: Int,
    val runsHeldForOperator: Int,
    val runsSkipped: Int,
)
