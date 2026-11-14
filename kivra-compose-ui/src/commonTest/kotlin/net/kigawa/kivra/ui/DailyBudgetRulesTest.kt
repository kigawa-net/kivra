package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiBudgetPathAmount
import net.kigawa.kivra.ui.api.ApiBudgetPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyBudgetRulesTest {
    private fun budget(
        dailyLimit: String? = "100",
        committed: String? = "30",
        reserved: String? = "20",
        remaining: String? = "50",
    ) = ApiBudget(
        period = "2026-11",
        limitJpy = "10000",
        committedJpy = "3000",
        reservedJpy = "500",
        remainingJpy = "6500",
        paths = ApiBudgetPaths(ApiBudgetPathAmount("3000"), ApiBudgetPathAmount("0")),
        unpricedCount = 0,
        unpriced = emptyList(),
        dailyPeriod = if (dailyLimit == null) null else "2026-11-14",
        dailyLimitJpy = dailyLimit,
        dailyCommittedJpy = committed,
        dailyReservedJpy = reserved,
        dailyRemainingJpy = remaining,
    )

    @Test
    fun `without a daily limit nothing is shown for the day`() {
        val b = budget(dailyLimit = null, committed = null, reserved = null, remaining = null)
        assertNull(dailyBudgetSummaryText(b))
        assertNull(dailyBudgetUsedPercent(b))
        assertFalse(dailyBudgetOverspent(b))
    }

    @Test
    fun `with a daily limit the day's use, the limit and the remainder are shown`() {
        val b = budget()
        assertEquals(50, dailyBudgetUsedPercent(b))
        assertEquals("本日の使用: 確定 30 円 + 予約中 20 円 / 日次上限 100 円（使用 50%）。残り 50 円。", dailyBudgetSummaryText(b))
        assertFalse(dailyBudgetOverspent(b))
    }

    @Test
    fun `an overspent day is written out in words, not only in colour`() {
        val b = budget(committed = "90", reserved = "30", remaining = "-20")
        assertTrue(dailyBudgetOverspent(b))
        assertEquals("本日の使用: 確定 90 円 + 予約中 30 円 / 日次上限 100 円（使用 120%）。本日の上限を超えています（超過 20 円）。", dailyBudgetSummaryText(b))
    }

    @Test
    fun `a limit that is not a number is shown as unknown, as the monthly summary does, and gives no percentage`() {
        assertEquals("本日の使用: 確定 30 円 + 予約中 20 円 / 日次上限 （不明）（使用 0%）。残り 50 円。", dailyBudgetSummaryText(budget(dailyLimit = "abc")))
        assertNull(dailyBudgetUsedPercent(budget(dailyLimit = "0")))
    }
}
