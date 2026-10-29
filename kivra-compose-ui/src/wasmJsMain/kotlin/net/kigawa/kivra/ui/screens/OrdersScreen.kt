package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiOrder

/**
 * Orders screen: Order list.
 */
@Composable
fun OrdersScreen() {
    var orders by remember { mutableStateOf<List<ApiOrder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            orders = ApiClient.getOrders()
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("order", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (orders.isEmpty()) {
            Text("orderが見つかりません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(orders) { order ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("タイプ: ${order.type}", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("状態: ${order.state} | Run: ${order.runId ?: "なし"}", 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Approvals screen: Stop approval list.
 */
@Composable
fun ApprovalsScreen() {
    var approvals by remember { mutableStateOf<List<ApiOrder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            approvals = ApiClient.getApprovals()
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("承認", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (approvals.isEmpty()) {
            Text("承認待ちの項目はありません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(approvals) { approval ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("承認待ち", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Run: ${approval.runId ?: "なし"}", 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = { /* 承認処理 */ }) {
                                    Text("承認")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(onClick = { /* 拒否処理 */ }) {
                                    Text("拒否")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
