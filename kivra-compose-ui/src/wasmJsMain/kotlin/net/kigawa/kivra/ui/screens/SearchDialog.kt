package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiClient

private data class SearchHit(val route: Route, val kind: String, val label: String, val id: String, val state: String)

/**
 * グローバル検索。専用 API は無いので、開いた時点で Run / Goal / order を取得し、id・タイトル・状態をクライアント側で絞り込む。
 * 選ぶと該当の詳細画面へ移動する。
 */
@Composable
fun SearchDialog(projectId: String?, onDismiss: () -> Unit, onNavigate: (Route) -> Unit) {
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(projectId) {
        loading = true
        val hits = mutableListOf<SearchHit>()
        val errors = mutableListOf<String>()
        try {
            ApiClient.getRuns("all").filter { projectId == null || it.projectId == projectId }
                .forEach { hits += SearchHit(Route.RunDetail(it.runId), "Run", it.title, it.runId, it.state) }
        } catch (e: Exception) { errors += "Run" }
        try {
            ApiClient.getGoals().forEach { hits += SearchHit(Route.GoalDetail(it.taskId), "Goal", it.title, it.taskId, it.state) }
        } catch (e: Exception) { errors += "Goal" }
        try {
            ApiClient.getOrders().filter { projectId == null || it.projectId == projectId }
                .forEach { hits += SearchHit(Route.OrderDetail(it.orderId), "order", it.orderId, it.orderId, it.state) }
        } catch (e: Exception) { errors += "order" }
        all = hits
        failed = errors
        loading = false
    }

    val q = query.trim()
    val results = if (q.isEmpty()) emptyList() else all.filter {
        it.label.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) || it.state.contains(q, ignoreCase = true)
    }.take(50)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("検索") },
        text = {
            Column(Modifier.width(480.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Run・Goal・order(id / タイトル / 状態)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed.isNotEmpty()) {
                    Text("取得できなかった種別: ${failed.joinToString()}", color = MaterialTheme.colorScheme.error)
                }
                if (q.isNotEmpty() && !loading && results.isEmpty()) Text("該当なし")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onNavigate(h.route); onDismiss() }.padding(vertical = 8.dp)
                        ) {
                            Text("[${h.kind}] ${h.label}", style = MaterialTheme.typography.bodyMedium)
                            Text("${h.id} · ${h.state}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}
