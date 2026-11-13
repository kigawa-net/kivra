package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.loadFailureText

/**
 * 読み込み失敗の文言(一覧も詳細も共通)。API の失敗は [loadFailureText] の固定文言で、サーバーの本文は出さない。
 * API 以外(通信の失敗など)は、例外の説明だけを出す。
 */
internal fun loadErrorMessage(e: Throwable): String {
    if (e !is ApiException) return "読み込みに失敗しました: ${e.message ?: "不明なエラー"}"
    return loadFailureText(e.status, e.detail)
}

/** One label / value line of a detail screen. */
@Composable
internal fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        SelectableText(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        SelectableText(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
