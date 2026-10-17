package net.kigawa.kivra.agent.claude

import net.kigawa.kivra.agent.AgentErrorCode
import net.kigawa.kivra.agent.docker.DockerRunOutcome
import net.kigawa.kivra.agent.docker.DockerRunResult

/**
 * The classification of one attempt's CLI run. Each value maps to one kivra error code (or to none, for success), so that AgentLoop can
 * report it with `fail` without a free-text reason. Only these names and codes are logged.
 */
enum class AgentRunOutcome(val errorCode: AgentErrorCode?) {
    SUCCEEDED(null),
    TIMEOUT(AgentErrorCode.TIMEOUT),
    CANCELLED(AgentErrorCode.CANCELLED),
    OUTPUT_LIMIT(AgentErrorCode.OUTPUT_LIMIT),
    AUTH_FAILED(AgentErrorCode.AUTH_REQUIRED),
    RATE_LIMITED(AgentErrorCode.RATE_LIMITED),

    /** Overloaded is transient, like a rate limit. kivra has no separate code for it, so it is reported as RATE_LIMITED. */
    OVERLOADED(AgentErrorCode.RATE_LIMITED),
    CLI_FAILED(AgentErrorCode.CLI_NONZERO),
    MALFORMED_OUTPUT(AgentErrorCode.BAD_OUTPUT),
}

/**
 * Turns a container run and its parsed stream into one [AgentRunOutcome]. Fail-closed, in this order (the first rule that applies wins):
 *
 * 1. The runner ended the run: a timeout, a cancel request, or an output limit ([DockerRunOutcome]).
 * 2. The output was cut at the limit, or a line was over the per-line limit: OUTPUT_LIMIT.
 * 3. No `result` event: an API error category decides (authentication, rate limit, overloaded). Otherwise a non-zero exit is
 *    CLI_FAILED, and a zero exit with an incomplete stream (the output was cut) is MALFORMED_OUTPUT.
 * 4. Any invalid line in a stream that has a result: MALFORMED_OUTPUT.
 * 5. An error result (`is_error`, or a subtype other than `success`): an API error category decides, else CLI_FAILED.
 * 6. A non-zero or unknown exit code: CLI_FAILED.
 * 7. Otherwise SUCCEEDED.
 *
 * The result text is not inspected here, so a model's text cannot change the classification.
 */
object CliOutcomeClassifier {
    fun classify(run: DockerRunResult, report: StreamReport): AgentRunOutcome {
        when (run.outcome) {
            DockerRunOutcome.TIMEOUT -> return AgentRunOutcome.TIMEOUT
            DockerRunOutcome.CANCELLED -> return AgentRunOutcome.CANCELLED
            DockerRunOutcome.OUTPUT_LIMIT -> return AgentRunOutcome.OUTPUT_LIMIT
            DockerRunOutcome.EXITED -> Unit
        }
        if (run.outputTruncated || report.outputLimitReached || report.oversizedLines > 0) return AgentRunOutcome.OUTPUT_LIMIT

        val apiFailure = apiFailure(report.apiErrors)
        val result = report.result
        if (result == null) {
            return apiFailure ?: if (run.exitCode == 0) AgentRunOutcome.MALFORMED_OUTPUT else AgentRunOutcome.CLI_FAILED
        }
        if (report.invalidLines > 0) return AgentRunOutcome.MALFORMED_OUTPUT
        if (result.isError || result.subtype != SUCCESS_SUBTYPE) return apiFailure ?: AgentRunOutcome.CLI_FAILED
        if (run.exitCode != 0) return AgentRunOutcome.CLI_FAILED
        return AgentRunOutcome.SUCCEEDED
    }

    /** The first API error category that applies, by priority: authentication, then rate limit, then overloaded. */
    private fun apiFailure(kinds: Set<ApiErrorKind>): AgentRunOutcome? = when {
        ApiErrorKind.AUTHENTICATION in kinds -> AgentRunOutcome.AUTH_FAILED
        ApiErrorKind.RATE_LIMIT in kinds -> AgentRunOutcome.RATE_LIMITED
        ApiErrorKind.OVERLOADED in kinds -> AgentRunOutcome.OVERLOADED
        else -> null
    }

    private const val SUCCESS_SUBTYPE = "success"
}
