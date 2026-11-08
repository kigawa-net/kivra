package net.kigawa.kivra.ui.screens

/**
 * サーバーの時刻(ISO 8601)を端末の地域の表示にする。読めなければ「不明」(旧 UI の ordTime と同じ)。
 * ブラウザの Date を使うため @JsFun で直接呼ぶ(BrowserBridge.kt と同じ方針)。
 */
@JsFun("(iso) => { const d = new Date(iso); return isNaN(d.getTime()) ? '不明' : d.toLocaleString('ja-JP'); }")
internal external fun localDateTimeText(iso: String): String
