package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiBudgetPathAmount
import net.kigawa.kivra.ui.api.ApiBudgetPaths
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayLabelsTest {

    @Test
    fun knownRunStateShowsCodeAndDescription() {
        val st = runStateText("UNKNOWN")
        assertEquals("UNKNOWN", st.label)
        assertEquals("結果不明。自動では動きません。運用者が確認して解放するまで、同時上限を占有します", st.desc)
    }

    @Test
    fun unknownRunStateKeepsTheCodeWithoutDescription() {
        assertEquals(StateText("NEW_STATE", "不明な状態です（この画面に説明はありません）"), runStateText("NEW_STATE"))
        assertEquals("（不明）", runStateText("").label)
    }

    @Test
    fun runKindHasLabelsAndAnUnknownFallback() {
        assertEquals(KindText("シミュレーション", "AI や GitHub は呼ばれません"), runKindText("SIMULATED"))
        assertEquals("自己開発（PR を作成）", runKindText("SELF_DEV").label)
        assertEquals("不明な種別（NEW）", runKindText("NEW").label)
        assertEquals("不明な種別（空）", runKindText("").label)
    }

    @Test
    fun runReasonShowsExplanationThenCode() {
        assertEquals("PR が閉じられた（マージされず）（PR_ABANDONED）", runReasonText("PR_ABANDONED"))
        assertEquals("CLI の出力が上限を超えた（出力の合計、または 1 行の上限）（CODING_OUTPUT_LIMIT）", runReasonText("CODING_OUTPUT_LIMIT"))
        assertEquals("不明な理由（CODING_SOMETHING_NEW）", runReasonText("CODING_SOMETHING_NEW"))
        assertEquals("開発エージェント（モデル）の結果を使えなかった（DEVELOPER_NEW）", runReasonText("DEVELOPER_NEW"))
        assertEquals("", runReasonText(null))
        assertEquals("", runReasonText(""))
    }

    @Test
    fun everyCodingFailureCodeHasAnExplanation() {
        val codes = listOf(
            "CODING_AUTH_REQUIRED", "CODING_NEEDS_HUMAN", "CODING_TIMEOUT", "CODING_OUTPUT_LIMIT", "CODING_RATE_LIMITED",
            "CODING_BAD_OUTPUT", "CODING_CLI_NONZERO", "CODING_CANCELLED", "CODING_FAILED", "CODING_ORPHANED", "CODING_QUEUE_EXPIRED",
            "CODING_RESULT_MISSING", "CODING_NO_ATTEMPT", "CODING_NOT_DEVELOPING", "CODING_ENQUEUE_REFUSED", "CODING_EMPTY_PATCH",
            "CODING_BUDGET_EXCEEDED", "CODING_OVERLOADED", "OPERATOR_RELEASED_UNBOUND_PR",
        )
        for (code in codes) {
            assertEquals(false, runReasonText(code).contains("不明な理由"), "$code has an explanation")
        }
    }

    @Test
    fun runActionUsesTheLegacyLabelsAndKeepsUnknownActions() {
        assertEquals("作成", runActionText("RUN_CREATED"))
        assertEquals("状態の変更", runActionText("RUN_TRANSITION"))
        assertEquals("NEW_ACTION", runActionText("NEW_ACTION"))
    }

    @Test
    fun runTitleIsHiddenForAnUnknownKindAndClippedAt200() {
        assertEquals("（非表示：種別が不明）", runTitleText("NEW", "題名"))
        assertEquals("題名", runTitleText("SIMULATED", "題名"))
        val long = "あ".repeat(201)
        assertEquals("あ".repeat(200) + "…", runTitleText("SELF_DEV", long))
        assertEquals("あ".repeat(200), runTitleText("SELF_DEV", "あ".repeat(200)))
    }

    @Test
    fun clipCountsCodePointsNotUtf16Units() {
        val emoji = "😀" // 1 code point, 2 UTF-16 units
        assertEquals("$emoji$emoji…", clipText("$emoji$emoji$emoji", 2))
        assertEquals("$emoji$emoji", clipText("$emoji$emoji", 2))
    }

    @Test
    fun orderStateAndCodesFollowTheLegacyTable() {
        assertEquals(StateText("一時停止中", "周を始めません。理由を確認してください"), orderStateText("PAUSED"))
        assertEquals(StateText("不明（WEIRD）", "不明な状態です（この画面に説明はありません）"), orderStateText("WEIRD"))
        assertEquals("不明", orderStateText("weird").label)
        assertEquals("周の上限に達しました（再開できません。新しい order が必要です）（LIMIT_REACHED）", orderCodeText(ORDER_REASONS, "LIMIT_REACHED"))
        assertEquals("不明（NEW_CODE）", orderCodeText(ORDER_REASONS, "NEW_CODE"))
        assertEquals("運用者の要求（OPERATOR_REQUEST）", orderCodeText(STOP_REASONS, "OPERATOR_REQUEST"))
        assertEquals("", orderCodeText(STOP_REASONS, null))
        assertEquals("AI の主体", requesterKindText("AI"))
        assertEquals("不明", requesterKindText("ROBOT"))
    }

    @Test
    fun goalStateAndReasonFollowTheLegacyTable() {
        assertEquals("承認待ち", goalStateText("PENDING_APPROVAL").label)
        assertEquals("NEW", goalStateText("NEW").label)
        assertEquals("運用者が承認しました（OPERATOR_APPROVED）", goalReasonText("OPERATOR_APPROVED"))
        // A code outside the table is returned as it is; the caller adds 理由:.
        assertEquals("NEW_REASON", goalReasonText("NEW_REASON"))
        assertEquals("", goalReasonText(null))
    }

    @Test
    fun assigneeStateAndCountsAreShownAsLegacy() {
        assertEquals("有効", assigneeStateText("ACTIVE"))
        assertEquals("無効", assigneeStateText("DISABLED"))
        assertEquals("不明（REVOKED_ALL）", assigneeStateText("REVOKED_ALL"))
        assertEquals("不明", assigneeStateText("x y"))
        assertEquals("3", countText(3))
        assertEquals("不明", countText(-1))
    }

    @Test
    fun yenAndMoneyTextGroupDigits() {
        assertEquals("1,000 円", yenText(1000))
        assertEquals("1,234,567 円", yenText(1234567))
        assertEquals("-1,000 円", yenText(-1000))
        assertEquals("0 円", yenText(0))
        assertEquals("100,000.5 円", moneyText("100000.5"))
        assertEquals("-2,500 円", moneyText("-2500"))
        assertEquals("（不明）", moneyText("abc"))
        assertEquals("（不明）", moneyText(""))
    }

    @Test
    fun budgetSummaryShowsUsageAndRemaining() {
        val b = budget(limit = "100000", committed = "30000", reserved = "20000", remaining = "50000")
        assertEquals(
            "確定 30,000 円 + 予約中 20,000 円 / 上限 100,000 円（使用 50%）。残り 50,000 円。",
            budgetSummaryText(b),
        )
    }

    @Test
    fun budgetSummaryNamesAnOverspend() {
        val b = budget(limit = "100000", committed = "90000", reserved = "30000", remaining = "-20000")
        assertEquals(
            "確定 90,000 円 + 予約中 30,000 円 / 上限 100,000 円（使用 120%）。上限を超えています（超過 20,000 円）。",
            budgetSummaryText(b),
        )
    }

    @Test
    fun budgetSummaryWithoutAUsableLimitShowsZeroPercent() {
        val b = budget(limit = "0", committed = "0", reserved = "0", remaining = "0")
        assertEquals(
            "確定 0 円 + 予約中 0 円 / 上限 0 円（使用 0%）。残り 0 円。",
            budgetSummaryText(b),
        )
    }

    private fun budget(limit: String, committed: String, reserved: String, remaining: String) = ApiBudget(
        period = "2026-11",
        limitJpy = limit,
        committedJpy = committed,
        reservedJpy = reserved,
        remainingJpy = remaining,
        paths = ApiBudgetPaths(ApiBudgetPathAmount("0"), ApiBudgetPathAmount("0")),
        unpricedCount = 0,
        unpriced = emptyList(),
    )
}
