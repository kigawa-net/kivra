package net.kigawa.kivra.agent.claude

import net.kigawa.kivra.agent.AgentErrorCode
import net.kigawa.kivra.agent.docker.DockerRunOutcome
import net.kigawa.kivra.agent.docker.DockerRunResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliOutcomeClassifierTest {
    private val noReport = StreamReport(null, emptySet(), 0, 0, 0, false)
    private val success = StreamReport(
        CliResult("success", false, "ok", 1, null, CliUsage(1, 1, null, null, 0.01)),
        emptySet(),
        1,
        0,
        0,
        false,
    )

    private fun run(outcome: DockerRunOutcome, exit: Int?, truncated: Boolean = false) =
        DockerRunResult(outcome, exit, "", "", truncated)

    @Test
    fun runnerOutcomesComeFirst() {
        assertEquals(AgentRunOutcome.TIMEOUT, CliOutcomeClassifier.classify(run(DockerRunOutcome.TIMEOUT, 137), success))
        assertEquals(AgentRunOutcome.CANCELLED, CliOutcomeClassifier.classify(run(DockerRunOutcome.CANCELLED, 137), success))
        assertEquals(AgentRunOutcome.OUTPUT_LIMIT, CliOutcomeClassifier.classify(run(DockerRunOutcome.OUTPUT_LIMIT, 137), success))
    }

    @Test
    fun cleanSuccessIsSucceeded() {
        assertEquals(AgentRunOutcome.SUCCEEDED, CliOutcomeClassifier.classify(run(DockerRunOutcome.EXITED, 0), success))
    }

    @Test
    fun noResultWithZeroExitIsMalformedAndWithNonZeroExitIsCliFailed() {
        assertEquals(AgentRunOutcome.MALFORMED_OUTPUT, CliOutcomeClassifier.classify(run(DockerRunOutcome.EXITED, 0), noReport))
        assertEquals(AgentRunOutcome.CLI_FAILED, CliOutcomeClassifier.classify(run(DockerRunOutcome.EXITED, 1), noReport))
    }

    @Test
    fun truncatedRunOutputIsOutputLimitBeforeAnythingElse() {
        assertEquals(
            AgentRunOutcome.OUTPUT_LIMIT,
            CliOutcomeClassifier.classify(run(DockerRunOutcome.EXITED, 0, truncated = true), noReport),
        )
    }

    @Test
    fun aBudgetStopIsBudgetExceededAndMapsToCliNonzero() {
        val budgetStop = StreamReport(
            CliResult("error_max_budget_usd", true, null, 4, null, CliUsage(1, 1, null, null, 0.5)),
            // An earlier retried rate limit must not win over the budget stop.
            setOf(ApiErrorKind.RATE_LIMIT),
            3,
            0,
            0,
            false,
        )
        assertEquals(AgentRunOutcome.BUDGET_EXCEEDED, CliOutcomeClassifier.classify(run(DockerRunOutcome.EXITED, 1), budgetStop))
        assertEquals(AgentErrorCode.CLI_NONZERO, AgentRunOutcome.BUDGET_EXCEEDED.errorCode(newErrorCodes = false))
    }

    @Test
    fun everyOutcomeMapsToAKivraErrorCodeOrNone() {
        // Without the new codes (the default): the codes kivra has always accepted.
        val legacy = mapOf(
            AgentRunOutcome.SUCCEEDED to null,
            AgentRunOutcome.TIMEOUT to AgentErrorCode.TIMEOUT,
            AgentRunOutcome.CANCELLED to AgentErrorCode.CANCELLED,
            AgentRunOutcome.OUTPUT_LIMIT to AgentErrorCode.OUTPUT_LIMIT,
            AgentRunOutcome.AUTH_FAILED to AgentErrorCode.AUTH_REQUIRED,
            AgentRunOutcome.BUDGET_EXCEEDED to AgentErrorCode.CLI_NONZERO,
            AgentRunOutcome.RATE_LIMITED to AgentErrorCode.RATE_LIMITED,
            AgentRunOutcome.OVERLOADED to AgentErrorCode.RATE_LIMITED,
            AgentRunOutcome.CLI_FAILED to AgentErrorCode.CLI_NONZERO,
            AgentRunOutcome.MALFORMED_OUTPUT to AgentErrorCode.BAD_OUTPUT,
        )
        // With KIVRA_AGENT_NEW_ERROR_CODES=1: only the two changed outcomes differ.
        val withNewCodes = legacy + mapOf(
            AgentRunOutcome.BUDGET_EXCEEDED to AgentErrorCode.BUDGET_EXCEEDED,
            AgentRunOutcome.OVERLOADED to AgentErrorCode.OVERLOADED,
        )
        assertEquals(AgentRunOutcome.entries.toSet(), legacy.keys, "every outcome is mapped")
        for ((outcome, code) in legacy) assertEquals(code, outcome.errorCode(newErrorCodes = false), outcome.name)
        for ((outcome, code) in withNewCodes) assertEquals(code, outcome.errorCode(newErrorCodes = true), outcome.name)
        assertNull(AgentRunOutcome.SUCCEEDED.errorCode(newErrorCodes = true))
    }
}
