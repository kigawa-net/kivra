package net.kigawa.kivra.selfdev.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0016 P3 (shadow): the deterministic rules count what they would refuse, each task once, under the first rule that matches. */
class SuppressShadowRulesTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val d = "d".repeat(64)

    @Test
    fun `a duplicate of a recent plan is counted`() {
        val facts = ShadowFacts(recentPlanIdentities = setOf(a), skippedIdentities = emptySet(), repeatedFailureIdentities = emptySet())
        assertEquals(mapOf(ShadowRule.DUPLICATE_RECENT_PLAN to 1), SuppressShadowRules.evaluate(listOf(a, b), facts))
    }

    @Test
    fun `a task the operator skipped is counted under SKIPPED_BEFORE, whatever else matches it`() {
        val facts = ShadowFacts(recentPlanIdentities = setOf(a), skippedIdentities = setOf(a, b), repeatedFailureIdentities = setOf(b))
        assertEquals(mapOf(ShadowRule.SKIPPED_BEFORE to 1), SuppressShadowRules.evaluate(listOf(a), facts))
        assertEquals(mapOf(ShadowRule.SKIPPED_BEFORE to 1), SuppressShadowRules.evaluate(listOf(b), facts))
    }

    @Test
    fun `a repeated failure is counted under REPEATED_FAILURE before a duplicate`() {
        val facts = ShadowFacts(recentPlanIdentities = setOf(a), skippedIdentities = emptySet(), repeatedFailureIdentities = setOf(a))
        assertEquals(mapOf(ShadowRule.REPEATED_FAILURE to 1), SuppressShadowRules.evaluate(listOf(a), facts))
    }

    @Test
    fun `thresholds and matches are counted per task, and a rule with no task is absent`() {
        val facts = ShadowFacts(
            recentPlanIdentities = setOf(a, c), skippedIdentities = setOf(b), repeatedFailureIdentities = setOf(c),
        )
        val counts = SuppressShadowRules.evaluate(listOf(a, b, c, d, a), facts)
        assertEquals(
            mapOf(ShadowRule.DUPLICATE_RECENT_PLAN to 2, ShadowRule.SKIPPED_BEFORE to 1, ShadowRule.REPEATED_FAILURE to 1),
            counts,
        )
        assertEquals(emptyMap(), SuppressShadowRules.evaluate(listOf(d), facts))
        assertEquals(emptyMap(), SuppressShadowRules.evaluate(emptyList(), facts))
    }

    @Test
    fun `the empty facts refuse nothing`() {
        assertEquals(emptyMap(), SuppressShadowRules.evaluate(listOf(a, b), ShadowFacts.EMPTY))
    }

    @Test
    fun `the rules are pure, and the thresholds are the ones of ADR 0016 U-7 and section 5`() {
        assertEquals(3, SuppressShadowRules.RECENT_PLAN_COUNT)
        assertEquals(14L, SuppressShadowRules.RECENT_PLAN_WINDOW_DAYS)
        assertEquals(90L, SuppressShadowRules.SKIP_WINDOW_DAYS)
        assertEquals(14L, SuppressShadowRules.FAILURE_WINDOW_DAYS)
        assertEquals(2, SuppressShadowRules.FAILURE_RUN)
        assertTrue("AUTO_SCOPE_EXCEEDED" in SuppressShadowRules.FAILURE_CODES)
    }
}
