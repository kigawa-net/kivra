package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import net.kigawa.kivra.ui.api.ApiException

/** A detail screen's load: loading, failed (with the text shown on screen), or ready. */
internal sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val message: String) : LoadState<Nothing>
    data class Ready<out T>(val value: T) : LoadState<T>
}

/** Runs [block] and turns a failure into [LoadState.Failed]. A failure is never shown as an empty screen. */
internal suspend fun <T> loadState(block: suspend () -> T): LoadState<T> =
    try {
        LoadState.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadState.Failed(loadErrorMessage(e))
    }

/**
 * The text shown for a failed load. 404 / 403 / 503 are named, so the reader knows the cause (for 503 the server's fixed
 * `error` text, such as `AUTOPILOT_DISABLED`, is shown as is).
 */
internal fun loadErrorMessage(e: Throwable): String {
    if (e !is ApiException) return "読み込みに失敗しました: ${e.message ?: "不明なエラー"}"
    return when (e.status) {
        404 -> "見つかりません(404)。id が違うか、存在しません。"
        403 -> "この画面を見る権限がありません(403)。"
        503 -> "この機能は今使えません(503: ${e.detail})。自律運転などが無効の可能性があります。"
        null -> "応答を読み取れませんでした: ${e.detail}"
        else -> "読み込みに失敗しました(${e.status}: ${e.detail})。"
    }
}

/** Shows the loading indicator, the failure text in the error color, or [content] with the loaded value. */
@Composable
internal fun <T> LoadStateView(state: LoadState<T>, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> CircularProgressIndicator()
        is LoadState.Failed -> Text("エラー: ${state.message}", color = MaterialTheme.colorScheme.error)
        is LoadState.Ready<T> -> content(state.value)
    }
}

/** One label / value line of a detail screen. */
@Composable
internal fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
