package net.kigawa.kivra.reviewer

/**
 * Reviewer verdict (design #3 section 4.3).
 *
 * The Reviewer evaluates a Developer's output independently and returns a structured verdict.
 * - APPROVE: the PR is ready for human approval or merge.
 * - REQUEST_CHANGES: the Developer should address the findings; may be retried up to the limit.
 * - REJECT: the changes are fundamentally flawed; escalate to human.
 */
enum class ReviewerVerdict {
    APPROVE,
    REQUEST_CHANGES,
    REJECT,
}

/**
 * Severity of a single review finding (design #3 section 4.3).
 */
enum class FindingSeverity {
    CRITICAL,
    MAJOR,
    MINOR,
}

/**
 * One finding from a Reviewer run (design #3 section 4.3).
 *
 * [category] groups related findings (e.g., "security", "performance", "style").
 * [description] is a human-readable explanation.
 * [suggestion] is an optional fix recommendation.
 */
data class ReviewFinding(
    val category: String,
    val severity: FindingSeverity,
    val description: String,
    val suggestion: String? = null,
)

/**
 * Structured output of a Reviewer run (design #3 section 4.3).
 *
 * [verdict] determines the next action: APPROVE moves to human approval,
 * REQUEST_CHANGES sends the run back to the Developer, REJECT escalates.
 * [findings] lists all issues found, regardless of verdict.
 * [summary] is a high-level overview for audit logs.
 */
data class ReviewerResult(
    val verdict: ReviewerVerdict,
    val findings: List<ReviewFinding>,
    val summary: String,
) {
    init {
        require(findings.none { it.category.isBlank() }) { "finding category must not be blank" }
        require(findings.none { it.description.isBlank() }) { "finding description must not be blank" }
    }

    /** True when there are no critical findings. */
    val hasCriticalFindings: Boolean
        get() = findings.any { it.severity == FindingSeverity.CRITICAL }

    /** Number of findings by severity. */
    fun countBySeverity(): Map<FindingSeverity, Int> =
        findings.groupBy { it.severity }.mapValues { it.value.size }
}
