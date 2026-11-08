package net.kigawa.kivra.ui

/*
 * 承認系の操作の純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js)と
 * サーバー(SelfDevGoalRoutes.kt / SelfDevOrderRoutes.kt)の規則に合わせる。
 */

/** 理由の上限(trim 後のコードポイント数)。app.js の RELEASE_MAX / GOAL_STOP_MAX と、サーバーの MAX_REASON_CODE_POINTS。 */
const val REASON_MAX_CODE_POINTS = 500

/** 理由欄の検証結果。 */
sealed interface ReasonCheck {
    /** 検証に通った理由(trim 済み)。 */
    data class Valid(val reason: String) : ReasonCheck
    data object Empty : ReasonCheck
    data object TooLong : ReasonCheck
    data object ControlChars : ReasonCheck
}

/**
 * 理由を検証する。前後の空白を除いてから、1 文字以上・[maxCodePoints] 以下・制御文字なし を確認する。
 * 制御文字は app.js の CONTROL_CHARS(U+0000-U+001F, U+007F-U+009F)と同じ範囲。
 */
fun checkReason(raw: String, maxCodePoints: Int = REASON_MAX_CODE_POINTS): ReasonCheck {
    val text = raw.trim()
    if (text.isEmpty()) return ReasonCheck.Empty
    if (text.any { isControlChar(it) }) return ReasonCheck.ControlChars
    if (codePointCount(text) > maxCodePoints) return ReasonCheck.TooLong
    return ReasonCheck.Valid(text)
}

/** 理由欄の表示用の文字数(コードポイント数)。 */
fun reasonLength(raw: String): Int = codePointCount(raw.trim())

/** 理由欄の検証結果の日本語の文言。Valid と Empty は null(Empty は送信ボタンの無効化で足りる)。 */
fun reasonErrorText(check: ReasonCheck, maxCodePoints: Int = REASON_MAX_CODE_POINTS): String? = when (check) {
    is ReasonCheck.Valid -> null
    ReasonCheck.Empty -> null
    ReasonCheck.TooLong -> "理由は $maxCodePoints 文字以内で入力してください。"
    ReasonCheck.ControlChars -> "理由に制御文字は使えません。"
}

/** 停止の承認に送る digest の形(64 文字の小文字 16 進数)。app.js の ORD_DIGEST と同じ。 */
private val DIGEST_SHAPE = Regex("[0-9a-f]{64}")

fun isValidStopDigest(digest: String?): Boolean = digest != null && DIGEST_SHAPE.matches(digest)

/** 書き込みの失敗の種類。HTTP ステータスから決まる。 */
enum class WriteFailure { NETWORK, SESSION, STALE, FORBIDDEN, NOT_FOUND, BAD_REQUEST, DISABLED, UNKNOWN }

/**
 * 書き込み失敗の種類。[status] が null は通信の失敗(応答が無い)。
 * 409 は状態・version の競合(再読み込みを促す)、403 は権限、503 は機能が off。
 */
fun writeFailureOf(status: Int?): WriteFailure = when (status) {
    null -> WriteFailure.NETWORK
    401 -> WriteFailure.SESSION
    403 -> WriteFailure.FORBIDDEN
    404 -> WriteFailure.NOT_FOUND
    400 -> WriteFailure.BAD_REQUEST
    409 -> WriteFailure.STALE
    503 -> WriteFailure.DISABLED
    else -> WriteFailure.UNKNOWN
}

/** 書き込み失敗の日本語の文言。サーバーの error 本文は表示しない(app.js と同じ固定文言)。 */
fun writeFailureText(failure: WriteFailure): String = when (failure) {
    WriteFailure.NETWORK -> "通信に失敗しました。操作されたかどうかは、一覧を読み直して確認してください。"
    WriteFailure.SESSION -> "ログインが切れています。ページを読み込み直してください。"
    WriteFailure.STALE -> "状態が変わっていました。一覧と詳細を読み直してから、もう一度操作してください。"
    WriteFailure.FORBIDDEN -> "この操作は、あなたの権限では行えません。"
    WriteFailure.NOT_FOUND -> "見つかりません（既に消えたか、状態が変わりました）。"
    WriteFailure.BAD_REQUEST -> "入力を確認できませんでした。ページを再読み込みしてください。"
    WriteFailure.DISABLED -> "自己開発の order は、今は無効です。"
    WriteFailure.UNKNOWN -> "結果を確認できませんでした。一覧を読み直してください。"
}

/** 画面に出す操作結果。[ok] が false ならエラー色で出す。 */
data class ActionNotice(val text: String, val ok: Boolean)

/** UTF-16 の文字列のコードポイント数(サロゲートペアは 1 と数える。common ソースで使える形)。 */
private fun codePointCount(text: String): Int = text.count { !it.isLowSurrogate() }

private fun isControlChar(c: Char): Boolean = c in '\u0000'..'\u001f' || c in '\u007f'..'\u009f'
