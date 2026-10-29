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
import net.kigawa.kivra.ui.api.ApiRun

/**
 * Tasks screen: Run list and Task submit form.
 */
@Composable
fun TasksScreen() {
    var runs by remember { mutableStateOf<List<ApiRun>>(emptyList()) }
    var scope by remember { mutableStateOf("mine") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var submitResult by remember { mutableStateOf<String?>(null) }
    
    val scopeLauncher = rememberCoroutineScope()

    LaunchedEffect(scope) {
        loading = true
        error = null
        try {
            runs = ApiClient.getRuns(scope)
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Scope toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Run の一覧", style = MaterialTheme.typography.headlineSmall)
            Row {
                FilterChip(
                    selected = scope == "mine",
                    onClick = { scope = "mine" },
                    label = { Text("自分の Run") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = scope == "all",
                    onClick = { scope = "all" },
                    label = { Text("全員の Run") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Run list
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (runs.isEmpty()) {
            Text("Run が見つかりません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(runs) { run ->
                    RunCard(run)
                    Divider()
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Task submit form
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Task を投入", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("タイトル") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("本文（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            scopeLauncher.launch {
                                val success = ApiClient.submitTask(title, body.ifBlank { null })
                                submitResult = if (success) "送信成功" else "送信失敗"
                                title = ""
                                body = ""
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("投入")
                }
                if (submitResult != null) {
                    Text(submitResult ?: "", color = if (submitResult == "送信成功") 
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RunCard(run: ApiRun) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(run.title, style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = { },
                    label = { Text(run.state) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("状態: ${run.state} | モード: ${run.mode}", 
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (run.projectName != null) {
                Text("プロジェクト: ${run.projectName}", 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
