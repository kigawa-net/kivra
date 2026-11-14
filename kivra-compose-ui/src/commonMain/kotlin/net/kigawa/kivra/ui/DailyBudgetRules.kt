package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiBudget

/*
 * The tenant's daily budget (本日) as pure logic. It is shown only when the server gives a daily limit; otherwise nothing is shown, and the
 * monthly figures are the same as before. The warning follows the monthly rule: an overspend is written out in words, not only in colour.
 */

/** The daily figures of [budget], or null when there is no daily limit (or the limit is not a number). */
fun dailyBudgetUsedPercent(budget: ApiBudget): Int? {
    val limit = budget.dailyLimitJpy?.toDoubleOrNull() ?: return null
    if (limit <= 0) return null
    val used = (budget.dailyCommittedJpy?.toDoubleOrNull() ?: return null) + (budget.dailyReservedJpy?.toDoubleOrNull() ?: return null)
    return kotlin.math.floor(used / limit * 100).toInt()
}

/**
 * The daily summary line, for example "本日の使用: 確定 30 円 + 予約中 20 円 / 日次上限 100 円（使用 50%）。残り 50 円。". Null when the server
 * gave no daily limit. An overspend reads "本日の上限を超えています（超過 …）。" instead of the remainder.
 */
fun dailyBudgetSummaryText(budget: ApiBudget): String? {
    val limit = budget.dailyLimitJpy ?: return null
    val committed = budget.dailyCommittedJpy ?: return null
    val reserved = budget.dailyReservedJpy ?: return null
    val remaining = budget.dailyRemainingJpy ?: return null
    val pct = dailyBudgetUsedPercent(budget) ?: 0
    val sb = StringBuilder()
    sb.append("本日の使用: 確定 ${moneyText(committed)} + 予約中 ${moneyText(reserved)} / 日次上限 ${moneyText(limit)}")
    sb.append("（使用 $pct%）。")
    if (dailyBudgetOverspent(budget)) {
        sb.append("本日の上限を超えています（超過 ${moneyText(remaining.removePrefix("-"))}）。")
    } else {
        sb.append("残り ${moneyText(remaining)}。")
    }
    return sb.toString()
}

/** True when the daily remainder is negative (the day's limit is exceeded). */
fun dailyBudgetOverspent(budget: ApiBudget): Boolean =
    budget.dailyRemainingJpy?.toDoubleOrNull()?.let { it < 0 } == true
