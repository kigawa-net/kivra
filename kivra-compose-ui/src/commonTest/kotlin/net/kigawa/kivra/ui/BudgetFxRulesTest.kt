package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiFxExpiry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetFxRulesTest {

    private fun fx(daysLeft: Long, expired: Boolean = false, asOf: String = "2026-10-08") = ApiFxExpiry(asOf, daysLeft, expired)

    @Test
    fun `an unset rate says so in words and is plain`() {
        val n = fxNoticeOf(null)
        assertEquals(FxLevel.PLAIN, n.level)
        assertEquals(FX_UNSET_TEXT, n.text)
    }

    @Test
    fun `two days or less is a warning with words, and day zero is still usable`() {
        val soon = fxNoticeOf(fx(2))
        assertEquals(FxLevel.WARN, soon.level)
        assertTrue(soon.text.contains("残り 2 日です"), soon.text)
        assertEquals(FxLevel.WARN, fxNoticeOf(fx(0)).level)
    }

    @Test
    fun `an expired rate is an error with words, and names the base date and the runbook`() {
        val gone = fxNoticeOf(fx(0, expired = true, asOf = "2026-10-01"))
        assertEquals(FxLevel.ERROR, gone.level)
        assertTrue(gone.text.contains("為替レートが失効しています"), gone.text)
        assertTrue(gone.text.contains("（基準日 2026-10-01）"), gone.text)
        assertTrue(gone.text.contains("runbook §4.8"), gone.text)
    }

    @Test
    fun `more than two days left is plain with the days`() {
        val fine = fxNoticeOf(fx(10))
        assertEquals(FxLevel.PLAIN, fine.level)
        assertEquals("為替レート: 基準日 2026-10-08、残り 10 日", fine.text)
    }

    @Test
    fun `yen amounts are grouped, keep their decimals, and a non-number is unknown`() {
        assertEquals("10,000 円", yenText("10000"))
        assertEquals("0 円", yenText("0"))
        assertEquals("-1,300 円", yenText("-1300"))
        assertEquals("80.5 円", yenText("80.5"))
        assertEquals("1,234,567.5 円", yenText("1234567.500000"))
        assertEquals("（不明）", yenText("1e9"))
        assertEquals("（不明）", yenText(""))
    }

    @Test
    fun `overspend is said in words only when the remaining amount is negative`() {
        assertEquals("上限を超えています（超過 200 円）。", budgetOverspendText("-200"))
        assertEquals("上限を超えています（超過 1,200 円）。", budgetOverspendText("-1200"))
        assertNull(budgetOverspendText("8600"))
        assertNull(budgetOverspendText("0"))
        assertNull(budgetOverspendText("-0"))
        assertNull(budgetOverspendText("abc"))
    }
}
