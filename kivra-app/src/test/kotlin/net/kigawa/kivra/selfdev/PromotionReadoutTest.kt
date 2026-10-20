package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stage 1 -> 2 readout (ADR 0011 section 6.3). Pure: the numbers come in, the conditions come out. */
class PromotionReadoutTest {

    private fun counts(completed: Long = 10, policy: Long = 0, scope: Long = 0) =
        PromotionCounts(approvedCompleted = completed, approvedPolicyRejected = policy, scopeExceeded = scope)

    private fun readout(counts: PromotionCounts) = stage1ToStage2Readout(counts).conditions.associateBy { it.id }

    @Test
    fun `ten approved tasks completed is the threshold, nine is not`() {
        val nine = readout(counts(completed = 9)).getValue("APPROVED_COMPLETED")
        assertEquals(9L, nine.current)
        assertEquals(10L, nine.required)
        assertFalse(nine.met!!)

        val ten = readout(counts(completed = 10)).getValue("APPROVED_COMPLETED")
        assertTrue(ten.met!!)
    }

    @Test
    fun `any POLICY_REJECTED or out-of-scope change keeps the promotion from being met`() {
        assertTrue(readout(counts(policy = 0)).getValue("POLICY_REJECTED").met!!)
        assertFalse(readout(counts(policy = 1)).getValue("POLICY_REJECTED").met!!)
        assertTrue(readout(counts(scope = 0)).getValue("OUT_OF_SCOPE").met!!)
        assertFalse(readout(counts(scope = 1)).getValue("OUT_OF_SCOPE").met!!)
    }

    @Test
    fun `the budget condition is not in the DB, so it is not measured and has no number`() {
        val budget = readout(counts()).getValue("BUDGET_CONFIRMED")
        assertFalse(budget.measured)
        assertNull(budget.current)
        assertNull(budget.required)
        assertNull(budget.met)
    }

    @Test
    fun `the readout lists exactly the four ADR conditions, in order`() {
        assertEquals(
            listOf("APPROVED_COMPLETED", "POLICY_REJECTED", "OUT_OF_SCOPE", "BUDGET_CONFIRMED"),
            stage1ToStage2Readout(counts()).conditions.map { it.id },
        )
    }
}
