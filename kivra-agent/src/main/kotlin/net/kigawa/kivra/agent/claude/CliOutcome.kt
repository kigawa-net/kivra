package net.kigawa.kivra.agent.claude

import net.kigawa.kivra.agent.AgentErrorCode
import net.kigawa.kivra.agent.docker.DockerRunOutcome
import net.kigawa.kivra.agent.docker.DockerRunResult

/**
 * The classification of one attempt's CLI run. Each value maps to one kivra error code (or to none, for success), so that AgentLoop can
 * report it with `fail` without a free-text reason. Only these names and codes are logged.
 *
 * [errorCode] takes [newErrorCodes] (`KIVRA_AGENT_NEW_ERROR_CODES`). With it off (the default) the code is the one kivra has always
 * accepted. With it on, a value that has a dedicated code uses that code (see the `KIVRA_AGENT_NEW_ERROR_CODES` table in the runbook).
 */
enum class AgentRunOutcome(private val legacyCode: AgentErrorCode?, private val newCode: AgentErrorCode? = legacyCode) {
    SUCCEEDED(null),
    TIMEOUT(AgentErrorCode.TIMEOUT),
    CANCELLED(AgentErrorCode.CANCELLED),
    OUTPUT_LIMIT(AgentErrorCode.OUTPUT_LIMIT),
    AUTH_FAILED(AgentErrorCode.AUTH_REQUIRED),

    /**
     * The CLI stopped at `--max-budget-usd` (result subtype `error_max_budget_usd`). Before the new codes it is reported as CLI_NONZERO.
     * With the new codes it is BUDGET_EXCEEDED.
     */
    BUDGET_EXCEEDED(AgentErrorCode.CLI_NONZERO, AgentErrorCode.BUDGET_EXCEEDED),
    RATE_LIMITED(AgentErrorCode.RATE_LIMITED),

    /** Overloaded is transient, like a rate limit. Before the new codes it is reported as RATE_LIMITED; with them, OVERLOADED. */
    OVERLOADED(AgentErrorCode.RATE_LIMITED, AgentErrorCode.OVERLOADED),
    CLI_FAILED(AgentErrorCode.CLI_NONZERO),
    MALFORMED_OUTPUT(AgentErrorCode.BAD_OUTPUT),
    ;

    /** The code to send with `fail`, or null for [SUCCEEDED]. */
    fun errorCode(newErrorCodes: Boolean): AgentErrorCode? = if (newErrorCodes) newCode else legacyCode
}

/**
 * Turns a container run and its parsed stream into one [AgentRunOutcome]. Fail-closed, in this order (the first rule that applies wins):
 *
 * 1. The runner ended the run: a timeout, a cancel request, or an output limit ([DockerRunOutcome]).
 * 2. The output was cut at the limit, or a line was over the per-line limit: OUTPUT_LIMIT.
 * 3. No `result` event: an API error category decides (authentication, rate limit, overloaded). Otherwise a non-zero exit is
 *    CLI_FAILED, and a zero exit with an incomplete stream (the output was cut) is MALFORMED_OUTPUT.
 * 4. Any invalid line in a stream that has a result: MALFORMED_OUTPUT.
 * 5. A budget stop (subtype `error_max_budget_usd`): BUDGET_EXCEEDED. It comes before the API error check, so an earlier retried rate
 *    limit cannot turn it into RATE_LIMITED.
 * 5b. An error result (`is_error`, or a subtype other than `success`): an API error category decides, else CLI_FAILED.
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
        if (result.subtype == BUDGET_SUBTYPE) return AgentRunOutcome.BUDGET_EXCEEDED
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
    private const val BUDGET_SUBTYPE = "error_max_budget_usd"
}
