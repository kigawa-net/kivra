package net.kigawa.kivra.ui

/*
 * ブラウザの時刻・表示状態・再読み込みとの接続。kotlinx-browser を増やさず @JsFun で直接呼ぶ(LocationHash.kt と同じ方針)。
 */

/** 現在時刻(エポックのミリ秒)。 */
@JsFun("() => Date.now()")
internal external fun nowMillis(): Double

/** ページが隠れているか(タブが背面など)。隠れている間は定期更新を止める。 */
@JsFun("() => document.hidden === true")
internal external fun isDocumentHidden(): Boolean

/** 時刻を端末の地域の `HH:MM:SS`(ja-JP)で返す。旧 UI の fmtClock と同じ。 */
@JsFun("(ms) => new Date(ms).toLocaleTimeString('ja-JP')")
internal external fun formatClock(ms: Double): String

/** ページを読み直す(サインインのやり直し)。ボタンからだけ呼ぶ。 */
@JsFun("() => { window.location.reload(); }")
internal external fun reloadPage()
