package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.api.ApiProject

/** 入力欄の下の「n / 上限」。コードポイントで数えた値。 */
@Composable
internal fun CounterText(count: Int, max: Int) {
    Text("$count / $max", style = MaterialTheme.typography.bodySmall)
}

/**
 * プロジェクトの選択(旧 UI の goal-project の select)。先頭に「選んでください」相当の未選択を持つ。ラジオの一覧で選ぶ。
 * 一覧の読み込みに失敗したら、その文言を出す(選べないので送信も「プロジェクトを選んでください」で止まる)。
 */
@Composable
internal fun ProjectChoice(
    projects: Loaded<List<ApiProject>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Text("プロジェクト（必須）", style = MaterialTheme.typography.labelLarge)
    projects.error?.let { Text("エラー: $it", color = MaterialTheme.colorScheme.error) }
    val list = projects.value
    when {
        list == null -> if (projects.error == null) CircularProgressIndicator()
        list.isEmpty() -> Text(
            "プロジェクトがありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> list.forEach { project ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == project.projectId,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(project.projectId) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == project.projectId, onClick = null, enabled = enabled)
                Text(project.name, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
