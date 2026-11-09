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

/*
 * 最後に選んだプロジェクトの id(この端末のブラウザだけの記憶)。旧い URL(`#/goals` など)を開くときの行き先に使う。
 * 保存できない・読めない(プライベートウインドウ、サイトデータの無効化など)ときは空として扱い、何も壊さない。
 */
@JsFun("(key) => { try { return window.localStorage.getItem(key) || ''; } catch (e) { return ''; } }")
internal external fun storageGet(key: String): String

@JsFun("(key, value) => { try { window.localStorage.setItem(key, value); } catch (e) { } }")
internal external fun storageSet(key: String, value: String)

private const val LAST_PROJECT_KEY = "kivra.lastProjectId"

/** 最後に選んだプロジェクトの id。無い・読めないときは null。 */
internal fun loadLastProjectId(): String? = storageGet(LAST_PROJECT_KEY).takeIf { it.isNotEmpty() }

/** 最後に選んだプロジェクトの id を覚える。 */
internal fun saveLastProjectId(projectId: String) = storageSet(LAST_PROJECT_KEY, projectId)
