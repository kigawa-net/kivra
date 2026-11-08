package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiPromotionCondition

/*
 * 段階 1 → 2 の昇格条件の表示の純粋な部分(旧 UI の PROMOTION_LABELS / promotionLine / renderPromotion と同じ文言)。
 * 条件の id は固定の表だけで名前を付ける。知らない id は「不明な条件」にし、サーバーの文字は出さない。
 */

/** 条件の名前と、必要の向き(atLeast は「以上」、false は「ゼロ」)。 */
data class PromotionLabel(val label: String, val atLeast: Boolean)

val PROMOTION_LABELS: Map<String, PromotionLabel> = mapOf(
    "APPROVED_COMPLETED" to PromotionLabel("承認したタスクのうち完了したもの", atLeast = true),
    "POLICY_REJECTED" to PromotionLabel("ポリシーで止まった承認済みタスク", atLeast = false),
    "OUT_OF_SCOPE" to PromotionLabel("範囲外の変更として止まったタスク", atLeast = false),
    "BUDGET_CONFIRMED" to PromotionLabel("予算の計上の実機確認（#230）", atLeast = false),
)

const val PROMOTION_UNMEASURED = "未計測（DB の集計では分かりません。runbook の手順で確認してください）"
const val PROMOTION_FAILED_TEXT = "昇格条件を取得できませんでした。しばらくしてから、もう一度お試しください。"
const val PROMOTION_NOT_FETCHED_TEXT = "未取得"
const val PROMOTION_NOTE_TEXT = "昇格は設定の変更で、運用者が判断します。この表示は何も変えません。"

/**
 * 応答の形。測定できた条件は current・required・met がそろい、測定できない条件はそれらがすべて null でなければならない。
 * 形が合わない応答は全体を失敗として扱う(一部だけ出さない)。
 */
fun promotionShapeOk(conditions: List<ApiPromotionCondition>): Boolean = conditions.all { c ->
    if (!c.measured) {
        c.current == null && c.required == null && c.met == null
    } else {
        c.current != null && c.required != null && c.met != null
    }
}

/** 1 条件の行。測定できない条件は「未計測」、知らない id は「不明な条件」。 */
fun promotionLine(c: ApiPromotionCondition): String {
    val known = PROMOTION_LABELS[c.id] ?: return "不明な条件"
    if (!c.measured) return "${known.label}：$PROMOTION_UNMEASURED"
    val need = if (known.atLeast) "${c.required} 件以上" else "ゼロ"
    val verdict = if (c.met == true) "達成" else "未達"
    return "${known.label}：${c.current} 件（必要: $need、$verdict）"
}

/** 測定できた条件のうち未達の件数に応じた、一行の要約。 */
fun promotionSummary(conditions: List<ApiPromotionCondition>): String {
    val unmet = conditions.count { it.measured && it.met == false }
    return if (unmet > 0) {
        "測定できた条件のうち未達が $unmet 件あります。"
    } else {
        "測定できた条件はすべて達成しています。予算の確認は手動です。"
    }
}
