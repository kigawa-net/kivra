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
import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiMemory
import net.kigawa.kivra.ui.api.ApiAssignee

/**
 * Budget screen: Budget display.
 */
@Composable
fun BudgetScreen() {
    var budget by remember { mutableStateOf<ApiBudget?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            budget = ApiClient.getBudget()
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("予算", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (budget == null) {
            Text("予算情報が見つかりません")
        } else {
            val b = budget!!
            val limit = b.limitValue
            val used = b.usedValue
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("月: ${b.period}", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("上限: ${b.limitJpy} 円")
                    LinearProgressIndicator(
                        progress = if (limit != null && used != null && limit > 0) {
                            (used / limit).coerceIn(0.0, 1.0).toFloat()
                        } else {
                            0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("確定: ${b.committedJpy} 円 / 予約中: ${b.reservedJpy} 円 / 残り: ${b.remainingJpy} 円")
                    Text("確定(API): ${b.paths.api.committedJpy} 円 / 確定(CLI): ${b.paths.cli.committedJpy} 円")
                    Text("未確定の予約: ${b.unpricedCount} 件")
                }
            }
        }
    }
}

/**
 * Memory screen: Memory list.
 */
@Composable
fun MemoryScreen() {
    var memories by remember { mutableStateOf<List<ApiMemory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            memories = ApiClient.getMemories()
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("記憶", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (memories.isEmpty()) {
            Text("記憶が見つかりません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(memories, key = { it.recordId }) { mem ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(mem.code ?: mem.kind, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            // The body is null for an untrusted note (bodyWithheld tells whether one exists) and for a fact.
                            Text(
                                mem.body ?: if (mem.bodyWithheld) "（本文は表示されません）" else "（本文はありません）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("種類: ${mem.kind} | 出所: ${mem.source} | 層: ${mem.tier}",
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
 * Assignees screen: Assignee list.
 */
@Composable
fun AssigneesScreen(selectedProjectId: String? = null) {
    var assignees by remember { mutableStateOf<List<ApiAssignee>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedProjectId) {
        loading = true
        error = null
        try {
            val allAssignees = ApiClient.getAssignees()
            assignees = if (selectedProjectId != null) {
                allAssignees.filter { it.projectId == selectedProjectId }
            } else {
                allAssignees
            }
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("担当", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (assignees.isEmpty()) {
            Text("担当が見つかりません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(assignees, key = { it.assigneeId }) { asgn ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(asgn.displayName, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("ID: ${asgn.assigneeId} | 役割: ${asgn.role} | 状態: ${asgn.state}",
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
