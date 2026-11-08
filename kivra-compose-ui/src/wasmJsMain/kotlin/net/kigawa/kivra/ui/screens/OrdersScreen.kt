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
import androidx.compose.foundation.clickable
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiOrder
import net.kigawa.kivra.ui.api.ApiStopRequest

/**
 * Orders screen: Order list.
 */
@Composable
fun OrdersScreen(selectedProjectId: String? = null, onNavigate: (Route) -> Unit) {
    var orders by remember { mutableStateOf<List<ApiOrder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedProjectId) {
        loading = true
        error = null
        try {
            val allOrders = ApiClient.getOrders()
            orders = if (selectedProjectId != null) {
                allOrders.filter { it.projectId == selectedProjectId }
            } else {
                allOrders
            }
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
                items(orders, key = { it.orderId }) { order ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onNavigate(Route.OrderDetail(order.orderId)) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("order ${order.orderId}", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("状態: ${order.state} | 深さ: ${order.depth} | 周: ${order.cyclesStarted} / ${order.maxCycles}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("使用: ${order.spentJpy} 円 / 予算: ${order.orderBudgetJpy} 円",
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
 * Approvals screen: Stop approval list (pending stop requests of the orders).
 */
@Composable
fun ApprovalsScreen(selectedProjectId: String? = null) {
    var approvals by remember { mutableStateOf<List<ApiStopRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedProjectId) {
        loading = true
        error = null
        try {
            val allApprovals = ApiClient.getApprovals()
            approvals = if (selectedProjectId != null) {
                // A stop request has no project of its own: it belongs to its order's project.
                val projectOf = ApiClient.getOrders().associate { it.orderId to it.projectId }
                allApprovals.filter { projectOf[it.orderId] == selectedProjectId }
            } else {
                allApprovals
            }
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
                items(approvals, key = { it.requestId }) { approval ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("停止の承認待ち", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("対象の order: ${approval.orderId} | 理由: ${approval.reasonCode}",
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
