package net.kigawa.kivra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiProject
import net.kigawa.kivra.ui.screens.*

/**
 * Root composable for the Kivra Compose UI.
 * This is the entry point for all platforms (Web, Desktop, Android, iOS).
 */
@Composable
fun KivraApp() {
    var currentRoute by remember { mutableStateOf(NavDestination.Home.route) }
    val currentDestination = NavDestination.fromRoute(currentRoute)
    var projects by remember { mutableStateOf<List<ApiProject>>(emptyList()) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            projects = ApiClient.getProjects()
        } catch (e: Exception) {
            // ignore
        }
    }

    MaterialTheme {
        if (searchOpen) {
            SearchDialog(
                projectId = selectedProjectId,
                onDismiss = { searchOpen = false },
                onNavigate = { currentRoute = it.route }
            )
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Sidebar: グループ見出し + 配下項目
                PermanentDrawerSheet(modifier = Modifier.width(200.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text("Kivra", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
                        NavigationDrawerItem(
                            selected = currentRoute == NavDestination.Home.route,
                            onClick = { currentRoute = NavDestination.Home.route },
                            label = { Text(NavDestination.Home.label) }
                        )
                        NavDestination.grouped.forEach { (group, items) ->
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                            items.forEach { dest ->
                                NavigationDrawerItem(
                                    selected = currentRoute == dest.route,
                                    onClick = { currentRoute = dest.route },
                                    label = { Text(dest.label) }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                
                // Main content area
                Column(modifier = Modifier.weight(1f)) {
                    // Top header with project selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentDestination.label,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(16.dp)
                        )
                        
                        // Project selector dropdown
                        ProjectSelectorDropdown(
                            selectedProjectId = selectedProjectId,
                            projects = projects,
                            onProjectSelected = { selectedProjectId = it },
                            dropdownExpanded = dropdownExpanded,
                            onDropdownExpandedChange = { dropdownExpanded = it }
                        )
                        
                        TextButton(onClick = { searchOpen = true }) { Text("検索") }

                        Text(
                            text = "接続: 未確認",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    HorizontalDivider()
                    
                    // Main content
                    val projectId = selectedProjectId
                    when (currentDestination) {
                        NavDestination.Home -> HomeScreen(projectId) { currentRoute = it.route }
                        NavDestination.Tasks -> TasksScreen(projectId)
                        NavDestination.Goals -> GoalsScreen(projectId)
                        NavDestination.Orders -> OrdersScreen(projectId)
                        NavDestination.Approvals -> ApprovalsScreen(projectId)
                        NavDestination.Budget -> BudgetScreen()
                        NavDestination.Memory -> MemoryScreen()
                        NavDestination.Assignees -> AssigneesScreen(projectId)
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectSelectorDropdown(
    selectedProjectId: String?,
    projects: List<ApiProject>,
    onProjectSelected: (String?) -> Unit,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit
) {
    Box(modifier = Modifier.padding(16.dp)) {
        TextButton(
            onClick = { onDropdownExpandedChange(!dropdownExpanded) }
        ) {
            Text(
                text = if (selectedProjectId == null) "全プロジェクト" 
                       else projects.find { it.projectId == selectedProjectId }?.name ?: "選択",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        DropdownMenu(
            expanded = dropdownExpanded,
            onDismissRequest = { onDropdownExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("全プロジェクト") },
                onClick = { 
                    onProjectSelected(null)
                    onDropdownExpandedChange(false)
                }
            )
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text(project.name) },
                    onClick = { 
                        onProjectSelected(project.projectId)
                        onDropdownExpandedChange(false)
                    }
                )
            }
        }
    }
}
