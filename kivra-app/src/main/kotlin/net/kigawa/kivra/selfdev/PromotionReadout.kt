package net.kigawa.kivra.selfdev

/*
 * The stage 1 -> 2 promotion readout (ADR 0011 section 6.3, Issue #233). It only counts what the DB already holds and
 * compares the counts with the ADR's numbers. It changes nothing: the promotion itself is a settings change made by the operator.
 */

/** ADR 0011 section 6.3 (stage 1 -> 2): at least this many approved tasks are COMPLETED. The number is provisional (U-8). */
const val STAGE_1_TO_2_MIN_APPROVED_COMPLETED = 10L

/** Raw counts read from the DB by `SelfDevPlanRepository.promotionCounts()`. Each count is for all of history. */
data class PromotionCounts(
    /** Tasks the operator approved (`approved_by` set) that are COMPLETED. */
    val approvedCompleted: Long,
    /** Operator-approved tasks whose task row or whose Run ended with the reason POLICY_REJECTED. */
    val approvedPolicyRejected: Long,
    /** Tasks with the reason AUTO_SCOPE_EXCEEDED (the stage 2 scope re-check). See the note in `docs/runbook-selfdev.md`. */
    val scopeExceeded: Long,
)

/**
 * One condition of the readout. [measured] is false when the DB cannot show the condition; then [current], [required] and [met]
 * are null. The UI names the reason for each id, so no free text crosses this boundary.
 */
data class PromotionCondition(
    val id: String,
    val measured: Boolean,
    val current: Long?,
    val required: Long?,
    val met: Boolean?,
)

data class PromotionReadout(val conditions: List<PromotionCondition>)

/** The readout for stage 1 -> 2, one entry per ADR condition. The budget condition (#230) is not in the DB, so it is not measured. */
fun stage1ToStage2Readout(counts: PromotionCounts): PromotionReadout = PromotionReadout(
    listOf(
        PromotionCondition(
            id = "APPROVED_COMPLETED",
            measured = true,
            current = counts.approvedCompleted,
            required = STAGE_1_TO_2_MIN_APPROVED_COMPLETED,
            met = counts.approvedCompleted >= STAGE_1_TO_2_MIN_APPROVED_COMPLETED,
        ),
        PromotionCondition(
            id = "POLICY_REJECTED",
            measured = true,
            current = counts.approvedPolicyRejected,
            required = 0L,
            met = counts.approvedPolicyRejected == 0L,
        ),
        PromotionCondition(
            id = "OUT_OF_SCOPE",
            measured = true,
            current = counts.scopeExceeded,
            required = 0L,
            met = counts.scopeExceeded == 0L,
        ),
        PromotionCondition(
            id = "BUDGET_CONFIRMED",
            measured = false,
            current = null,
            required = null,
            met = null,
        ),
    ),
)
