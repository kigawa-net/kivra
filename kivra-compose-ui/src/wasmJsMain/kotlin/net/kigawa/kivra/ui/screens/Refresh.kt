package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.isHiddenForCaller

/**
 * 表示中の画面を読み直す回数。App が手動の「更新」と定期更新で増やす。各画面の読み込みは [rememberLoad] を通して、この値をキーに含める。
 */
val LocalRefreshCount = compositionLocalOf { 0 }

/**
 * 読み込みの結果。[value] と [error] が両方 null なら読み込み中。
 * 読み直しの間は前の値を残す(入力や一覧の位置を消さないため)。読み直しが失敗したら、前の値と [error] を両方出す
 * (旧 UI の「N 秒前のデータ(更新に失敗)」に当たる)。
 */
internal data class Loaded<out T>(val value: T? = null, val error: String? = null)

private object NoIdentity

/**
 * [load] の結果を持つ。[identity] が変わった時(別の Run、別の絞り込み)は読み込み中に戻す。
 * [reload] と [LocalRefreshCount] が変わった時は、前の値を残したまま読み直す(スピナーで消さない)。
 * 失敗は [loadErrorMessage] の固定文言。キャンセルは握りつぶさない。
 */
@Composable
internal fun <T> rememberLoad(identity: Any?, reload: Int = 0, load: suspend () -> T): Loaded<T> {
    val refresh = LocalRefreshCount.current
    var current by remember { mutableStateOf(Loaded<T>()) }
    var shownFor by remember { mutableStateOf<Any?>(NoIdentity) }
    LaunchedEffect(identity, reload, refresh) {
        val same = shownFor == identity
        val previous = if (same) current.value else null
        if (!same) current = Loaded()
        current = try {
            Loaded(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded(previous, loadErrorMessage(e))
        }
        shownFor = identity
    }
    return current
}

/** 一覧の表示。失敗の文言は上に出し、前に読めていた一覧は残す。読み込み中だけスピナー。 */
@Composable
internal fun <T> ColumnScope.LoadedList(state: Loaded<List<T>>, emptyText: String, content: @Composable ColumnScope.(List<T>) -> Unit) {
    state.error?.let {
        Text("エラー: $it", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
    }
    val items = state.value
    when {
        items == null -> if (state.error == null) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        items.isEmpty() -> Text(emptyText)
        else -> content(items)
    }
}

/** 詳細の表示。読み込み中はスピナー、失敗の文言は上に出し、読めていた値は残す。 */
@Composable
internal fun <T> LoadedView(state: Loaded<T>, content: @Composable (T) -> Unit) {
    state.error?.let { Text("エラー: $it", color = MaterialTheme.colorScheme.error) }
    val value = state.value
    if (value != null) {
        content(value)
    } else if (state.error == null) {
        CircularProgressIndicator()
    }
}

/** 予算の読み取り。見せない利用者(401 / 403 / 404)は null で、欄ごと出さない(旧 UI と同じ)。それ以外の失敗は投げる。 */
internal suspend fun loadBudgetOrHidden(): ApiBudget? =
    try {
        ApiClient.getBudget()
    } catch (e: ApiException) {
        if (isHiddenForCaller(e.status)) null else throw e
    }

/** 予算の画面の読み取り結果。[budget] が null は欄を出さない(見せない利用者)。 */
internal data class BudgetRead(val budget: ApiBudget?)

/**
 * 書き込みダイアログの表示中は定期更新を止める。ダイアログの中身(入力・状態)を、読み直しで消さないため。
 * ダイアログは開いている間だけ組み立てるので、組み立てと破棄で数える。
 */
@Composable
internal fun HoldAutoRefreshWhileShown() {
    DisposableEffect(Unit) {
        ApiSession.hold()
        onDispose { ApiSession.unhold() }
    }
}
