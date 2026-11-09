package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiFxExpiry

/*
 * 予算の為替の警告と、超過の文字表示の純粋な判定(旧 UI の renderBudgetFx / renderBudgetBar と同じ文言と閾値)。
 * 色だけに頼らず、どの段階も文字で書く。
 */

/** 残りがこの日数以下なら警告(黄)。旧 UI の FX_WARN_DAYS と同じ。 */
const val FX_WARN_DAYS = 2L

/** 為替の注意の段階。PLAIN は通常の表示、WARN は黄(文字つき)、ERROR は赤(文字つき)。 */
enum class FxLevel { PLAIN, WARN, ERROR }

data class FxNotice(val level: FxLevel, val text: String)

/** 為替の状態を取れなかった時の文言(旧 UI と同じ)。 */
const val FX_FAILED_TEXT = "為替の状態を取得できませんでした。"

/** 為替が未設定(USD を使わない構成)の文言(旧 UI と同じ)。 */
const val FX_UNSET_TEXT = "為替レートは設定されていません（USD の単価は使っていません）。"

/**
 * 為替の注意。[fx] が null は未設定。失効は ERROR、残り [FX_WARN_DAYS] 日以下は WARN、それ以外は PLAIN。
 * 残り 0 日は失効日の当日で、まだ使える(失効していなければ WARN)。
 */
fun fxNoticeOf(fx: ApiFxExpiry?): FxNotice {
    if (fx == null) return FxNotice(FxLevel.PLAIN, FX_UNSET_TEXT)
    val asOf = fx.asOf
    return when {
        fx.expired -> FxNotice(
            FxLevel.ERROR,
            "為替レートが失効しています（基準日 $asOf）。失効中は CLI の claim が止まり、報告は金額 0 の未確定になります。" +
                "レートを更新してください（runbook §4.8）。",
        )
        fx.daysLeft <= FX_WARN_DAYS -> FxNotice(
            FxLevel.WARN,
            "為替レートの有効期限まで残り ${fx.daysLeft} 日です（基準日 $asOf）。期限が切れる前に更新してください。",
        )
        else -> FxNotice(FxLevel.PLAIN, "為替レート: 基準日 $asOf、残り ${fx.daysLeft} 日")
    }
}

internal val YEN_DECIMAL = Regex("-?[0-9]+(\\.[0-9]+)?")

/**
 * 円の金額(サーバーの 10 進数の文字列)を `1,300 円` の形にする。数でない値は `（不明）`。
 * 小数は末尾の 0 を落とし、6 桁までにする(旧 UI の moneyText と同じ表示)。
 */
fun yenText(decimal: String): String {
    if (!YEN_DECIMAL.matches(decimal)) return "（不明）"
    val negative = decimal.startsWith("-")
    val body = decimal.removePrefix("-")
    val intPart = body.substringBefore('.').trimStart('0').ifEmpty { "0" }
    val fraction = body.substringAfter('.', "").take(6).trimEnd('0')
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    val sign = if (negative && (intPart != "0" || fraction.isNotEmpty())) "-" else ""
    val frac = if (fraction.isNotEmpty()) ".$fraction" else ""
    return "$sign$grouped$frac 円"
}

/**
 * 月の残り(サーバーの remainingJpy の文字列)が負なら、超過を文字で示す文言。負でなければ null。
 * 旧 UI の renderBudgetBar と同じ: 「上限を超えています（超過 200 円）。」
 */
fun budgetOverspendText(remainingJpy: String): String? {
    if (!YEN_DECIMAL.matches(remainingJpy) || !remainingJpy.startsWith("-")) return null
    val over = remainingJpy.removePrefix("-")
    if (over.all { it == '0' || it == '.' }) return null
    return "上限を超えています（超過 ${yenText(over)}）。"
}
