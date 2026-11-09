package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiProjectBudget
import kotlin.math.floor

/*
 * 運用者の「今月の予算」(プロジェクト単位、ADR 0022 PR-E)の純粋ロジック。旧 UI(app.js の PBUD / renderProjectBudget)の
 * 入力の検査、文言、表示の整形に合わせる。サーバー(ProjectRoutes.kt)が最終判定をする。ここでは送る前の検査と、応答の表示だけを持つ。
 */

/** 月の上限の形: 0 以上の整数(円)、最大 12 桁(旧 UI の PBUD_LIMIT_SHAPE)。 */
private val BUDGET_LIMIT_SHAPE = Regex("0|[1-9][0-9]{0,11}")

/** 警告の基準の形: 小数は 3 桁まで(旧 UI の PBUD_RATIO_SHAPE)。 */
private val BUDGET_RATIO_SHAPE = Regex("[0-9]+(\\.[0-9]{1,3})?")

/** 上限の入力欄の最大長(旧 UI の maxlength=12)。 */
const val BUDGET_LIMIT_INPUT_MAX = 12

/** 警告の基準の入力欄の最大長(旧 UI の maxlength=5)。 */
const val BUDGET_RATIO_INPUT_MAX = 5

/** 警告の基準の既定値(旧 UI の入力欄の初期値)。 */
const val BUDGET_RATIO_DEFAULT = "0.8"

/** 上限の入力の検査。前後の空白は除いて見る。通れば円の整数、通らなければ null。 */
fun parseBudgetLimit(raw: String): Long? {
    val text = raw.trim()
    return if (BUDGET_LIMIT_SHAPE.matches(text)) text.toLong() else null
}

/** 警告の基準の入力の検査。形(小数は 3 桁まで)と、0 より大きく 1 以下。通れば値、通らなければ null。 */
fun parseBudgetRatio(raw: String): Double? {
    val text = raw.trim()
    if (!BUDGET_RATIO_SHAPE.matches(text)) return null
    val value = text.toDoubleOrNull() ?: return null
    return if (value > 0.0 && value <= 1.0) value else null
}

/** 入力の検査の失敗の文言(旧 UI の PBUD_MSG.invalid)。 */
const val BUDGET_INVALID_MESSAGE =
    "上限は 0 以上の整数（円）、警告の基準は 0 より大きく 1 以下（小数は 3 桁まで）で入力してください。"

const val BUDGET_SET_DONE_MESSAGE = "今月の上限を設定しました。"
const val BUDGET_CLEAR_DONE_MESSAGE = "今月の上限を解除しました。"

const val BUDGET_SET_TITLE = "今月の上限を設定"
const val BUDGET_CLEAR_TITLE = "今月の上限を解除"
const val BUDGET_SET_SUBMIT_LABEL = "上限を設定する"
const val BUDGET_CLEAR_SUBMIT_LABEL = "上限を解除する"

const val BUDGET_CLEAR_EXPLAIN =
    "今月の上限を解除します。このプロジェクトは、全体の上限だけで判定されるようになります。今月までの消化額は残ります。"

/** 運用者の欄の説明(旧 UI の project-budget の注記)。 */
const val BUDGET_SECTION_NOTE =
    "上限は、このプロジェクトの Run のコーディング CLI の費用だけに効きます。" +
        "上限に達すると、このプロジェクトの新しい Run の割り当てだけが止まります（ほかのプロジェクトと全体の上限には影響しません）。" +
        "警告の基準に達しても止まりません。"

/**
 * 設定の確認の文言(旧 UI の openBudgetSetDialog)。[limit] は円の整数、[ratio] は 0 より大きく 1 以下の値(検査済み)。
 * 警告の基準の % は旧 UI の Math.round と同じく、0.5 は上に丸める。
 */
fun budgetSetExplain(limit: Long, ratio: Double): String {
    val percent = floor(ratio * 100 + 0.5).toLong()
    return "今月の上限を ${groupDigits(limit.toString())} 円、警告の基準を $percent% に設定します。" +
        "このプロジェクトの新しい Run は、上限に達すると割り当てられなくなります（ほかのプロジェクトと全体の上限には影響しません）。"
}

/** 操作の失敗の文言。HTTP のステータスだけで決める(サーバーの本文は出さない)。401 はセッション切れ。 */
fun budgetFailText(status: Int?): String = when (status) {
    401 -> SESSION_EXPIRED_MESSAGE
    400 -> BUDGET_INVALID_MESSAGE
    404 -> "このプロジェクトは見つかりません。画面を読み直してから、もう一度お試しください。"
    403 -> "この操作は運用者だけができます。"
    else -> "操作に失敗しました。時間をおいてもう一度お試しください。"
}

/** 予算の金額の表示(旧 UI の budgetYenText)。null は上限が無いことを表す「なし」。 */
fun budgetYenText(value: String?): String = if (value == null) "なし" else yenText(value)

/** 対象の月の行(旧 UI の project-budget-period。消化額の説明を含む)。 */
fun budgetPeriodText(period: String): String =
    "対象の月：$period（日本時間）。消化額は、このプロジェクトの Run のコーディング CLI の費用です。"

/** 警告の行。[error] は赤で出す。 */
data class BudgetWarning(val text: String, val error: Boolean)

/**
 * 警告の文言(旧 UI の renderProjectBudget)。上限が無いときは「上限は設定されていません。」、警告の基準に達したら警告、
 * 達していなければ達していない旨。警告の基準に達しても止まらないことは、警告の文言で示す。
 */
fun budgetWarningOf(monthlyLimitJpy: String?, softExceeded: Boolean): BudgetWarning = when {
    monthlyLimitJpy == null -> BudgetWarning("上限は設定されていません。", error = false)
    softExceeded -> BudgetWarning(
        "警告：今月の消化（確定と予約中）が警告の基準に達しています。止まるのは上限に達したときだけです。",
        error = true,
    )
    else -> BudgetWarning("今月の消化は警告の基準に達していません。", error = false)
}

/**
 * 予算の応答の形(旧 UI の isProjectBudgetBody)。上限と消化の金額は十進の円の文字列(上限は null も可)でなければならない。
 * 形が合わない応答は、ダッシュボード全体を失敗として扱う(一部だけ出さない)。
 */
fun isProjectBudgetBody(b: ApiProjectBudget): Boolean {
    val limit = b.monthlyLimitJpy
    val limitOk = limit == null || YEN_DECIMAL.matches(limit)
    return limitOk && YEN_DECIMAL.matches(b.committedJpy) && YEN_DECIMAL.matches(b.reservedJpy)
}
