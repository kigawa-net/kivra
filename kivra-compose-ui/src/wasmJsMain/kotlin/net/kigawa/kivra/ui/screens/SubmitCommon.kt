package net.kigawa.kivra.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.Composable
import net.kigawa.kivra.ui.isUnassigned
import net.kigawa.kivra.ui.LocalProjects

/** 入力欄の下の「n / 上限」。コードポイントで数えた値。 */
@Composable
internal fun CounterText(count: Int, max: Int) {
    SelectableText("$count / $max", style = MaterialTheme.typography.bodySmall)
}

/**
 * 作成先のプロジェクト(表示だけ)。プロジェクトの中の画面で作るものは、そのプロジェクトに入る(選べない)。
 * 名前は読めていれば名前、読めていなければ id を出す。
 */
/** 未分類の画面の案内(作成フォームは出さない)。作成はプロジェクトに固定し、未分類にデータを増やさない。 */
@Composable
internal fun UnassignedCreateNote() {
    SelectableText(
        "未分類は、プロジェクトに属さない既存のデータの一覧です。開発目標・order・タスクの作成はプロジェクトの中で行うため、ここには作成フォームを出しません。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ProjectFixedLabel(projectId: String) {
    val name = LocalProjects.current.find { it.projectId == projectId }?.name ?: projectId
    SelectableText(
        "プロジェクト: $name（この画面のプロジェクトに作ります。変えるには上のプロジェクトの切替を使います）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
