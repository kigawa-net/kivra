package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiProjectBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 旧 UI の project-budget のテスト(ui-behavior.test.js の PBUD 系)に相当する、純粋ロジックの確認。 */
class ProjectBudgetRulesTest {

    private fun budget(over: ApiProjectBudget.() -> ApiProjectBudget = { this }) = ApiProjectBudget(
        projectId = "p1",
        period = "2026-10",
        monthlyLimitJpy = "10000",
        softRatio = "0.8",
        softLimitJpy = "8000",
        committedJpy = "850",
        reservedJpy = "0",
        remainingJpy = "9150",
        softExceeded = true,
    ).over()

    @Test
    fun limitTakesWholeYenUpToTwelveDigits() {
        assertEquals(0L, parseBudgetLimit("0"))
        assertEquals(10000L, parseBudgetLimit("10000"))
        assertEquals(5000L, parseBudgetLimit(" 5000 "), "the surrounding spaces are not part of the value")
        assertEquals(999999999999L, parseBudgetLimit("999999999999"), "12 digits is the largest")
        assertNull(parseBudgetLimit("1234567890123"), "13 digits is too large")
        assertNull(parseBudgetLimit("1.5"))
        assertNull(parseBudgetLimit("-1"))
        assertNull(parseBudgetLimit("0123"), "no leading zero")
        assertNull(parseBudgetLimit(""))
    }

    @Test
    fun ratioIsAboveZeroAtMostOneWithThreeDecimals() {
        assertEquals(0.8, parseBudgetRatio("0.8"))
        assertEquals(1.0, parseBudgetRatio("1"))
        assertEquals(1.0, parseBudgetRatio("1.000"))
        assertEquals(0.001, parseBudgetRatio("0.001"))
        assertNull(parseBudgetRatio("0"))
        assertNull(parseBudgetRatio("1.5"))
        assertNull(parseBudgetRatio("0.8123"), "at most three decimals")
        assertNull(parseBudgetRatio("-0.5"))
        assertNull(parseBudgetRatio(""))
    }

    @Test
    fun setConfirmationShowsTheLimitAndThePercentage() {
        val text = budgetSetExplain(5000, 0.75)
        assertTrue(text.contains("今月の上限を 5,000 円、警告の基準を 75% に設定します。"), text)
        assertTrue(budgetSetExplain(10000, 0.8).contains("警告の基準を 80% に"))
        assertTrue(budgetSetExplain(1, 0.005).contains("警告の基準を 1% に"), "0.5 rounds up, as Math.round does")
    }

    @Test
    fun failureTextIsFixedByStatusOnly() {
        assertEquals(BUDGET_INVALID_MESSAGE, budgetFailText(400))
        assertEquals("このプロジェクトは見つかりません。画面を読み直してから、もう一度お試しください。", budgetFailText(404))
        assertEquals("この操作は運用者だけができます。", budgetFailText(403))
        assertEquals(SESSION_EXPIRED_MESSAGE, budgetFailText(401))
        assertEquals("操作に失敗しました。時間をおいてもう一度お試しください。", budgetFailText(500))
        assertEquals("操作に失敗しました。時間をおいてもう一度お試しください。", budgetFailText(null))
        assertEquals(
            "上限は 0 以上の整数（円）、警告の基準は 0 より大きく 1 以下（小数は 3 桁まで）で入力してください。",
            BUDGET_INVALID_MESSAGE,
        )
    }

    @Test
    fun amountsShowNoneWhenThereIsNoLimit() {
        assertEquals("なし", budgetYenText(null))
        assertEquals("1,300 円", budgetYenText("1300"))
        assertEquals("（不明）", budgetYenText("abc"))
    }

    @Test
    fun warningSaysWhetherTheSoftLineIsReached() {
        val none = budgetWarningOf(null, softExceeded = false)
        assertEquals("上限は設定されていません。", none.text)
        assertFalse(none.error)

        val soft = budgetWarningOf("10000", softExceeded = true)
        assertTrue(soft.text.startsWith("警告："), soft.text)
        assertTrue(soft.error)

        val fine = budgetWarningOf("10000", softExceeded = false)
        assertEquals("今月の消化は警告の基準に達していません。", fine.text)
        assertFalse(fine.error)
    }

    @Test
    fun periodLineSaysTheMonthInJapanTime() {
        assertEquals(
            "対象の月：2026-10（日本時間）。消化額は、このプロジェクトの Run のコーディング CLI の費用です。",
            budgetPeriodText("2026-10"),
        )
    }

    @Test
    fun aBudgetWithTheExpectedShapeIsAccepted() {
        assertTrue(isProjectBudgetBody(budget()))
        assertTrue(isProjectBudgetBody(budget { copy(monthlyLimitJpy = null, softRatio = null, softLimitJpy = null, remainingJpy = null, softExceeded = false) }),
            "a project without a limit is a valid body")
        assertTrue(isProjectBudgetBody(budget { copy(remainingJpy = "-200") }), "a negative remainder (over the limit) is a valid yen text")
    }

    @Test
    fun aBudgetWithAnotherShapeIsRefused() {
        assertFalse(isProjectBudgetBody(budget { copy(monthlyLimitJpy = "abc") }))
        assertFalse(isProjectBudgetBody(budget { copy(committedJpy = "x") }))
        assertFalse(isProjectBudgetBody(budget { copy(reservedJpy = "") }))
    }

    @Test
    fun theSpendNoteOfTheDashboardIsTheCurrentWording() {
        val note = PROJECT_SPEND_NOTE
        assertNotNull(note)
        assertTrue(note.contains("運用者には下の「今月の予算」に出ます"), note)
        assertFalse(note.contains("プロジェクトごとの上限はありません"), "the old wording is gone")
    }
}
