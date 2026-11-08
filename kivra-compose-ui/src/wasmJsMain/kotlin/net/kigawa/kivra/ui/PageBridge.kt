package net.kigawa.kivra.ui

/*
 * ページタイトルと、API の時刻文字列の表示(端末の地域)。BrowserBridge.kt と同じく @JsFun で直接呼ぶ。
 */

/** ブラウザのページタイトル(`document.title`)。画面が変わるたびに [documentTitleOf] の結果を入れる。 */
@JsFun("(title) => { document.title = title; }")
internal external fun setDocumentTitle(title: String)

/** ISO の時刻を端末の地域の日時(ja-JP)にする。読めない文字列は空文字。旧 UI の fmtLocal と同じ。 */
@JsFun("(iso) => { const t = Date.parse(iso); return isNaN(t) ? '' : new Date(t).toLocaleString('ja-JP'); }")
internal external fun localDateTimeOrEmpty(iso: String): String

/** ISO の時刻を UTC の `YYYY-MM-DD HH:MM:SS UTC` にする。読めない文字列は空文字。旧 UI の fmtUtc と同じ。 */
@JsFun(
    "(iso) => { const t = Date.parse(iso); return isNaN(t) ? '' : new Date(t).toISOString().replace('T', ' ').replace(/\\.\\d{3}Z\$/, ' UTC'); }",
)
internal external fun utcDateTimeOrEmpty(iso: String): String

/** API の時刻の表示。読めない値は、受け取ったそのままの文字列を出す(旧 UI と同じ)。 */
internal fun displayTime(iso: String): String = localDateTimeOrEmpty(iso).ifEmpty { iso }

/** 表示用の UTC の時刻。読めない値は空文字(行を出さない)。 */
internal fun displayUtcTime(iso: String): String = utcDateTimeOrEmpty(iso)
