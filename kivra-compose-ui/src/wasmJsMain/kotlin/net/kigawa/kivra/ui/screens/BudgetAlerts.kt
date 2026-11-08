package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.FX_FAILED_TEXT
import net.kigawa.kivra.ui.FxLevel
import net.kigawa.kivra.ui.budgetOverspendText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.fxNoticeOf

/** 警告の文字色(黄は読める濃さの琥珀色)。段階は文字でも出すので、色だけに頼らない。 */
private val FX_WARN_COLOR = Color(0xFF8A5A00)

/**
 * 予算の為替の警告(運用者向け)。残り 2 日以下は黄、失効は赤、どちらも文字で書く。読めなければ固定の文言。
 * 予算の欄の中でだけ組み立てる(見せない利用者には要求しない)。
 */
@Composable
internal fun BudgetFxNotice() {
    val loaded = rememberLoad("budget-fx") { ApiClient.getBudgetFx() }
    Spacer(Modifier.height(8.dp))
    val value = loaded.value
    when {
        loaded.error != null -> Text(FX_FAILED_TEXT)
        value == null -> Text("読み込み中…")
        else -> {
            val notice = fxNoticeOf(value.fx)
            val color = when (notice.level) {
                FxLevel.PLAIN -> Color.Unspecified
                FxLevel.WARN -> FX_WARN_COLOR
                FxLevel.ERROR -> MaterialTheme.colorScheme.error
            }
            Text(notice.text, color = color)
        }
    }
}

/** 月の残りが負のとき、超過を文字で出す(バーが 100% で止まっても、数字と文字で分かるように)。負でなければ何も出さない。 */
@Composable
internal fun BudgetOverspendLine(remainingJpy: String) {
    budgetOverspendText(remainingJpy)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
