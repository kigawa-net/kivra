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
import net.kigawa.kivra.ui.api.ApiGoal

/**
 * Goals screen: Goal list.
 */
@Composable
fun GoalsScreen(selectedProjectId: String? = null, onNavigate: (Route) -> Unit) {
    val loaded = rememberLoad(selectedProjectId) { ApiClient.getGoals() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("開発目標", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        LoadedList(loaded, emptyText = "開発目標が見つかりません") { goals ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(goals) { goal ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onNavigate(Route.GoalDetail(goal.taskId)) }) {
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
