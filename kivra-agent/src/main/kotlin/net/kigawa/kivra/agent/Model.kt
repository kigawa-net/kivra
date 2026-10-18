package net.kigawa.kivra.agent

/** Where the task text came from. Only UNTRUSTED exists: the task is data, never an instruction. */
const val TRUST_UNTRUSTED = "UNTRUSTED"

/**
 * The task text of an attempt. [value] (the title) and [body] are untrusted data and must never be logged; [toString] shows lengths
 * only. [body] is "" and [bodyTruncated] is false for a claim from a kivra that does not send the body yet.
 */
class AgentTask(
    val origin: String,
    val value: String,
    val trust: String,
    val truncated: Boolean,
    val body: String = "",
    val bodyTruncated: Boolean = false,
) {
    override fun toString(): String =
        "AgentTask(origin=$origin, chars=${value.length}, truncated=$truncated, bodyChars=${body.length}, bodyTruncated=$bodyTruncated)"
}

/** The limits the agent must enforce for one attempt (copied from the server's CodingLimits). */
data class AgentLimits(
    val maxRuntimeSeconds: Long,
    val maxTurns: Int,
    val maxOutputBytes: Long,
    val maxChangedFiles: Int,
    val maxFileBytes: Long,
    val maxTotalBytes: Long,
    val maxDeletes: Int,
)

/** An attempt handed to this agent by `claim`. [generation] must be sent with every later request for it. */
data class ClaimedAttempt(
    val runId: String,
    val attemptId: String,
    val generation: Long,
    val baseSha: String,
    val task: AgentTask,
    val limits: AgentLimits,
    val allowedPathPatterns: List<String>,
    val deniedPathPatterns: List<String>,
)

/** Progress a heartbeat may report. Fixed names, mirroring the server's ProgressPhase. */
enum class ProgressPhase { STARTED, EDITING, TESTING, ROUND_COMPLETED, FINALIZING }

/**
 * Error codes this agent may report with `fail`. Mirrors the server's CodingErrorCode in kivra-app. The server refuses any other
 * name, so a free-text reason can never reach kivra.
 *
 * [EMPTY_PATCH], [BUDGET_EXCEEDED] and [OVERLOADED] are new (Issue #231). They are sent only when `KIVRA_AGENT_NEW_ERROR_CODES=1`
 * (see [net.kigawa.kivra.agent.claude.AgentRunOutcome.errorCode]). kivra must accept them before that is switched on.
 */
enum class AgentErrorCode {
    AUTH_REQUIRED,
    RATE_LIMITED,
    TIMEOUT,
    OUTPUT_LIMIT,
    CLI_NONZERO,
    BAD_OUTPUT,
    NEEDS_HUMAN,
    CANCELLED,
    EMPTY_PATCH,
    BUDGET_EXCEEDED,
    OVERLOADED,
}
