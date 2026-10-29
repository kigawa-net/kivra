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
import net.kigawa.kivra.ui.api.ApiGoal

/**
 * Goals screen: Goal list.
 */
@Composable
fun GoalsScreen() {
    var goals by remember { mutableStateOf<List<ApiGoal>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            goals = ApiClient.getGoals()
        } catch (e: Exception) {
            error = e.message ?: "エラーが発生しました"
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("開発目標", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (error != null) {
            Text("エラー: $error", color = MaterialTheme.colorScheme.error)
        } else if (goals.isEmpty()) {
            Text("開発目標が見つかりません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(goals) { goal ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(goal.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("状態: ${goal.state}", 
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
