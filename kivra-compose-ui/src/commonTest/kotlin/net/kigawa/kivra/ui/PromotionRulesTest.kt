package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiPromotionCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotionRulesTest {

    private val ok = listOf(
        ApiPromotionCondition("APPROVED_COMPLETED", measured = true, current = 10, required = 10, met = true),
        ApiPromotionCondition("POLICY_REJECTED", measured = true, current = 2, required = 0, met = false),
        ApiPromotionCondition("OUT_OF_SCOPE", measured = true, current = 0, required = 0, met = true),
        ApiPromotionCondition("BUDGET_CONFIRMED", measured = false),
    )

    @Test
    fun `each condition is shown as fixed words and numbers`() {
        assertEquals("承認したタスクのうち完了したもの：10 件（必要: 10 件以上、達成）", promotionLine(ok[0]))
        assertEquals("ポリシーで止まった承認済みタスク：2 件（必要: ゼロ、未達）", promotionLine(ok[1]))
        assertEquals("範囲外の変更として止まったタスク：0 件（必要: ゼロ、達成）", promotionLine(ok[2]))
        assertEquals("予算の計上の実機確認（#230）：$PROMOTION_UNMEASURED", promotionLine(ok[3]))
    }

    @Test
    fun `an id the page does not know is an unknown condition, never the id itself`() {
        val evil = ApiPromotionCondition("<img src=x onerror=alert(1)>", measured = false)
        assertEquals("不明な条件", promotionLine(evil))
    }

    @Test
    fun `the summary counts only the measured unmet conditions`() {
        assertEquals("測定できた条件のうち未達が 1 件あります。", promotionSummary(ok))
        val allMet = ok.map { if (it.id == "POLICY_REJECTED") it.copy(current = 0, met = true) else it }
        assertEquals("測定できた条件はすべて達成しています。予算の確認は手動です。", promotionSummary(allMet))
        val unmeasuredUnmet = listOf(ApiPromotionCondition("BUDGET_CONFIRMED", measured = false))
        assertEquals("測定できた条件はすべて達成しています。予算の確認は手動です。", promotionSummary(unmeasuredUnmet))
    }

    @Test
    fun `a measured condition without its numbers is a malformed readout`() {
        assertTrue(promotionShapeOk(ok))
        assertFalse(promotionShapeOk(listOf(ApiPromotionCondition("APPROVED_COMPLETED", measured = true, current = null, required = 10, met = true))))
        assertFalse(promotionShapeOk(listOf(ApiPromotionCondition("POLICY_REJECTED", measured = true, current = 1, required = 0, met = null))))
    }

    @Test
    fun `an unmeasured condition must carry no numbers`() {
        assertFalse(promotionShapeOk(listOf(ApiPromotionCondition("BUDGET_CONFIRMED", measured = false, current = 3))))
    }
}
