package net.kigawa.kivra.ui

/*
 * ブラウザのハッシュ(`location.hash`)との接続。kotlinx-browser を使わず @JsFun で直接呼ぶ(依存を増やさない)。
 * ハッシュの解釈と生成は commonMain の [parseRoute] / [toHash] が行う。ここは読み書きと通知だけ。
 */

/** 現在のハッシュ(`#/runs/abc` の形。空なら ""). */
@JsFun("() => window.location.hash")
internal external fun currentLocationHash(): String

/** ハッシュを設定する(同じ値なら何もしない)。設定はブラウザ履歴に残るので、戻る/進むで [addHashChangeListener] が呼ばれる。 */
@JsFun("(hash) => { if (window.location.hash !== hash) { window.location.hash = hash; } }")
internal external fun setLocationHash(hash: String)

/** ハッシュを置き換える(履歴に残さない)。旧い形の URL(`#/projects/<id>/budget`)を正しい形へ直すのに使う。 */
@JsFun("(hash) => { window.location.replace(hash); }")
internal external fun replaceLocationHash(hash: String)

/** ハッシュ変更(戻る/進む/直接入力)の通知を登録する。登録は一度だけ(KivraApp の初回)。 */
@JsFun("(listener) => { window.addEventListener('hashchange', () => listener()); }")
internal external fun addHashChangeListener(listener: () -> Unit)
