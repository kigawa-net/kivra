package net.kigawa.kivra.ui

/** セッション切れの案内(旧 UI の MESSAGES.session と同じ趣旨。Compose では再読み込みのボタンを付ける)。 */
const val SESSION_EXPIRED_TEXT = "セッションが切れました。再読み込みしてください。"

/** 機械コードの形(大文字と `_` のみ)。これに合う error 文言だけを画面に出す。 */
private val MACHINE_CODE = Regex("[A-Z_]{1,40}")

/**
 * 読み込み失敗の固定文言。サーバーの error 本文は出さない(一覧も詳細も同じ文言)。
 * 401 はセッション切れ、403 / 404 は理由を名指しする。503 は機械コード(例 `AUTOPILOT_DISABLED`)の形のときだけ添える。
 * [detail] は ApiException.detail の値。
 */
fun loadFailureText(status: Int?, detail: String?): String = when (status) {
    401 -> SESSION_EXPIRED_TEXT
    404 -> "見つかりません(404)。id が違うか、存在しません。"
    403 -> "この画面を見る権限がありません(403)。"
    503 -> {
        val code = detail?.takeIf { MACHINE_CODE.matches(it) }
        "この機能は今使えません(503${if (code != null) ": $code" else ""})。自律運転などが無効の可能性があります。"
    }
    null -> "応答を読み取れませんでした。"
    else -> "読み込みに失敗しました($status)。"
}

/** 401 / 403 / 404 は「この利用者には見せない」と扱う(旧 UI の予算: 静かに隠す)。 */
fun isHiddenForCaller(status: Int?): Boolean = status == 401 || status == 403 || status == 404
