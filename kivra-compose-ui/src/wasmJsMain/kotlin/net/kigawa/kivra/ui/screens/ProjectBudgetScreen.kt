package net.kigawa.kivra.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * プロジェクトの予算(`#/projects/<id>/budget`、運用者だけ)。今月の上限と使用量を出し、上限の設定と解除を行う。
 * 数字はダッシュボード(scope ALL)の budget から読む。予算が見えない利用者には、その旨だけを出す。
 */
@Composable
fun ProjectBudgetScreen(projectId: String) {
    // 設定・解除の後に増やして、予算を読み直させる。
    var reload by remember { mutableStateOf(0) }
    val loaded = rememberLoad(projectId, reload) { loadProjectDashboard(projectId) }
    DetailPage {
        LoadedView(loaded) { d ->
            val budget = d.budget
            if (budget == null) {
                SelectableText("予算は運用者だけが見られます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ProjectBudgetSection(budget, onChanged = { reload += 1 })
            }
        }
    }
}
