package net.kigawa.kivra.reviewer

import net.kigawa.kivra.core.DevelopmentRunState
import net.kigawa.kivra.agent.AgentProfile
import net.kigawa.kivra.db.DevelopmentRunRepository
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * Reviewer pipeline configuration (design #3 section 4.3).
 *
 * [maxReviewRounds] is the maximum number of Developer→Reviewer round-trips before escalation.
 * A value of 3 means: Developer → Reviewer → Developer → Reviewer → Developer → Reviewer → ESCALATED.
 */
data class ReviewerPipelineConfig(
    val maxReviewRounds: Int = 3,
    val reviewerProfile: AgentProfile? = null,
) {
    init {
        require(maxReviewRounds >= 1) { "maxReviewRounds must be >= 1" }
    }
}

/**
 * Tracks the review state for a single run (design #3 section 4.3).
 *
 * [reviewRound] counts how many times the run has gone through the Reviewer.
 * When it exceeds [ReviewerPipelineConfig.maxReviewRounds], the run is escalated.
 * [previousFindings] stores the hashes of previous findings to detect repeated issues.
 */
data class ReviewState(
    val reviewRound: Int = 0,
    val previousFindings: Set<String> = emptySet(),
    val lastVerdict: ReviewerVerdict? = null,
)

/**
 * Manages the Developer → Reviewer pipeline (design #3 section 4.3, C2).
 *
 * After a Developer Run completes and creates a PR, this component:
 * 1. Launches an independent Reviewer Run
 * 2. Evaluates the Reviewer's structured verdict
 * 3. On REQUEST_CHANGES: sends the run back to the Developer (up to the limit)
 * 4. On APPROVE: moves to human approval
 * 5. On REJECT or limit exceeded: escalates to human
 *
 * The Reviewer runs in a separate context from the Developer (design #3 section 4.3):
 * different Run, different Workspace, different model/provider if available.
 */
class ReviewerPipeline(
    private val runRepo: DevelopmentRunRepository,
    private val config: ReviewerPipelineConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ReviewerPipeline::class.java)

    /**
     * Retrieves or creates the review state for a run.
     * In a full implementation, this would be persisted in the database.
     * For now, it's in-memory only (will be persisted in follow-up).
     */
    private val reviewStates = mutableMapOf<UUID, ReviewState>()

    /**
     * Check if a run should enter the review phase.
     * Returns true when the run is in PULL_REQUEST state, indicating the Developer
     * has completed and created a PR.
     */
    suspend fun shouldEnterReview(runId: UUID): Boolean {
        val run = runRepo.find(runId) ?: return false
        return run.state == DevelopmentRunState.PULL_REQUEST
    }

    /**
     * Process a completed Reviewer run.
     *
     * Based on the [result.verdict]:
     * - APPROVE: transition to WAITING_APPROVAL for human review
     * - REQUEST_CHANGES: transition back to DEVELOPING if under the limit
     * - REJECT: escalate (transition to FAILED with escalation reason)
     *
     * Returns true if the run was successfully processed, false if escalation is needed.
     */
    suspend fun processReviewerResult(runId: UUID, result: ReviewerResult): ReviewerPipelineOutcome {
        val state = reviewStates[runId] ?: ReviewState()
        val newRound = state.reviewRound + 1

        logger.info(
            "reviewer pipeline: runId={}, round={}, verdict={}, findings={}",
            runId, newRound, result.verdict, result.findings.size
        )

        // Check for repeated findings (design #3 section 4.3)
        val findingHashes = result.findings.map { it.description.hashCode().toString() }.toSet()
        val repeatedFindings = findingHashes.intersect(state.previousFindings)
        if (repeatedFindings.isNotEmpty() && newRound > 1) {
            logger.warn(
                "reviewer pipeline: runId={}, repeated findings detected: {}",
                runId, repeatedFindings.size
            )
            return ReviewerPipelineOutcome.ESCALATED_REPEATED_FINDINGS
        }

        return when (result.verdict) {
            ReviewerVerdict.APPROVE -> {
                // Update state and return approval outcome
                reviewStates[runId] = state.copy(
                    reviewRound = newRound,
                    previousFindings = findingHashes,
                    lastVerdict = result.verdict
                )
                ReviewerPipelineOutcome.APPROVED
            }

            ReviewerVerdict.REQUEST_CHANGES -> {
                if (newRound >= config.maxReviewRounds) {
                    logger.warn(
                        "reviewer pipeline: runId={}, review limit reached ({}/{}), escalating",
                        runId, newRound, config.maxReviewRounds
                    )
                    ReviewerPipelineOutcome.ESCALATED_LIMIT_REACHED
                } else {
                    // Allow another round
                    reviewStates[runId] = state.copy(
                        reviewRound = newRound,
                        previousFindings = findingHashes,
                        lastVerdict = result.verdict
                    )
                    ReviewerPipelineOutcome.REQUEST_CHANGES_ACCEPTED
                }
            }

            ReviewerVerdict.REJECT -> {
                logger.warn(
                    "reviewer pipeline: runId={}, rejected by reviewer, escalating",
                    runId
                )
                ReviewerPipelineOutcome.ESCALATED_REJECTED
            }
        }
    }

    /**
     * Get the current review state for a run.
     */
    fun getReviewState(runId: UUID): ReviewState? = reviewStates[runId]

    /**
     * Check if a run has reached the review limit.
     */
    fun hasReachedLimit(runId: UUID): Boolean {
        val state = reviewStates[runId] ?: return false
        return state.reviewRound >= config.maxReviewRounds
    }
}

/**
 * Outcome of processing a Reviewer result.
 */
enum class ReviewerPipelineOutcome {
    /** The Reviewer approved; move to human approval. */
    APPROVED,

    /** The Reviewer requested changes; send back to Developer. */
    REQUEST_CHANGES_ACCEPTED,

    /** Escalated: review limit reached. */
    ESCALATED_LIMIT_REACHED,

    /** Escalated: Reviewer rejected. */
    ESCALATED_REJECTED,

    /** Escalated: repeated findings detected. */
    ESCALATED_REPEATED_FINDINGS,
}
