package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SelectableText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiOperation

/** 全体の Operation 一覧。API が未実装でもサンプルで画面を確認できる。 */
@Composable
fun OperationsScreen(onNavigate: (Route) -> Unit) {
    val loaded = rememberLoad<List<ApiOperation>>(Unit) { ApiClient.getOperations() }
    val useMock = loaded.error != null || loaded.value?.isEmpty() == true
    val displayed = if (useMock) Loaded<List<ApiOperation>>(mockOperations) else loaded

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SelectableText("Operation", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (useMock) {
            SelectableText("モックデータを表示しています。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        LoadedList(displayed, emptyText = "Operation がありません。") { operations ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(operations, key = { it.id }) { operation ->
                    Card(modifier = Modifier.fillMaxWidth().clickable {
                        onNavigate(Route.OperationDetail(operation.id))
                    }) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectableText(operation.name, style = MaterialTheme.typography.titleMedium)
                            StatusBadgeInfo(operation.status)
                            SelectableText(operation.description, style = MaterialTheme.typography.bodyMedium)
                            SelectableText("更新日時: ${operation.updatedAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** 一覧と詳細で同じサンプルを使い、行を開いた際の内容を揃える。 */
internal val mockOperations: List<ApiOperation> = listOf(
    ApiOperation(
        id = "mock-ci-cd", name = "CI/CD パイプライン", status = "running",
        description = "コードの検証とビルドを実行しています。",
        createdAt = "2026-11-14T09:00:00Z", updatedAt = "2026-11-14T09:05:00Z",
    ),
    ApiOperation(
        id = "mock-deploy-staging", name = "デプロイ (staging)", status = "completed",
        description = "ステージング環境へのデプロイが完了しました。",
        createdAt = "2026-11-14T08:00:00Z", updatedAt = "2026-11-14T08:10:00Z",
    ),
    ApiOperation(
        id = "mock-test", name = "テスト実行", status = "failed",
        description = "テストの実行中に失敗が発生しました。",
        createdAt = "2026-11-14T07:00:00Z", updatedAt = "2026-11-14T07:03:00Z",
    ),
)
