package net.kigawa.kivra.selfdev.memory

import java.util.EnumMap

/*
 * The deterministic rules of the suppression shadow (ADR 0016 section 5 (a) to (c), section 11 P3, U-7). Pure functions, no I/O.
 *
 * Shadow only: the rules count what they WOULD refuse. Nothing here changes a plan, refuses one, or retries a call. The inputs are
 * identities (the SHA-256 of a task's sorted path set) and fixed codes, never a title, a body, a summary or a path. The text columns of
 * the memory are never read for this (SuppressShadowArchitectureTest).
 */

/** The facts the rules read, for the identities of one new plan. Identities and fixed codes only. */
data class ShadowFacts(
    /** Identities of the tasks of the recent plans (the newest [SuppressShadowRules.RECENT_PLAN_COUNT], within the window). */
    val recentPlanIdentities: Set<String>,
    /** Identities that the operator skipped (OPERATOR_SKIPPED) within [SuppressShadowRules.SKIP_WINDOW_DAYS]. */
    val skippedIdentities: Set<String>,
    /** Identities whose last [SuppressShadowRules.FAILURE_RUN] outcome facts, within [SuppressShadowRules.FAILURE_WINDOW_DAYS], all have a failure code. */
    val repeatedFailureIdentities: Set<String>,
) {
    companion object {
        val EMPTY = ShadowFacts(emptySet(), emptySet(), emptySet())
    }
}

/** The rule that would refuse a task. The name is the code written to the audit. */
enum class ShadowRule { SKIPPED_BEFORE, REPEATED_FAILURE, DUPLICATE_RECENT_PLAN }

object SuppressShadowRules {
    /** The newest plans whose tasks are compared with a new plan (across goals). */
    const val RECENT_PLAN_COUNT = 3
    const val RECENT_PLAN_WINDOW_DAYS = 14L
    const val SKIP_WINDOW_DAYS = 90L
    const val FAILURE_WINDOW_DAYS = 14L

    /** Two outcome facts in a row with a failure code make the threshold (U-7: "2 回続いたら"). */
    const val FAILURE_RUN = 2

    /** The fixed codes. They are the names the recorder and the flows write. */
    const val PLAN_PROPOSED_CODE = "PLAN_PROPOSED"
    const val OPERATOR_SKIPPED_CODE = "OPERATOR_SKIPPED"

    /**
     * The failure codes of the threshold. EMPTY_PATCH (ADR 0016 U-7) is not here: it lives in the attempt facts, which P1 does not write.
     */
    val FAILURE_CODES: Set<String> = setOf("AUTO_SCOPE_EXCEEDED")

    /**
     * How many of [identities] (one per task of the new plan, in order) each rule would refuse. A task counts once, under the first rule
     * that matches, in the order SKIPPED_BEFORE, REPEATED_FAILURE, DUPLICATE_RECENT_PLAN. A rule with no task is absent from the map.
     */
    fun evaluate(identities: List<String>, facts: ShadowFacts): Map<ShadowRule, Int> {
        val counts = EnumMap<ShadowRule, Int>(ShadowRule::class.java)
        for (identity in identities) {
            val rule = when {
                identity in facts.skippedIdentities -> ShadowRule.SKIPPED_BEFORE
                identity in facts.repeatedFailureIdentities -> ShadowRule.REPEATED_FAILURE
                identity in facts.recentPlanIdentities -> ShadowRule.DUPLICATE_RECENT_PLAN
                else -> continue
            }
            counts[rule] = (counts[rule] ?: 0) + 1
        }
        return counts
    }
}
