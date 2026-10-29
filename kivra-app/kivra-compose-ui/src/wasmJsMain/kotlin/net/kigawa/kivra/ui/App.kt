package net.kigawa.kivra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.screens.*

/**
 * Root composable for the Kivra Compose UI.
 * This is the entry point for all platforms (Web, Desktop, Android, iOS).
 */
@Composable
fun KivraApp() {
    var currentRoute by remember { mutableStateOf("tasks") }
    val currentDestination = NavDestination.fromRoute(currentRoute)

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Sidebar (Navigation Rail)
                NavigationRail {
                    Text("Kivra", modifier = Modifier.padding(8.dp))
                    HorizontalDivider()
                    NavDestination.all.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { currentRoute = dest.route },
                            icon = {},
                            label = { Text(dest.label) }
                        )
                    }
                }
                HorizontalDivider()
                
                // Main content area
                Column(modifier = Modifier.weight(1f)) {
                    // Top header
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
                        Text(
                            text = "接続: 未確認",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    HorizontalDivider()
                    
                    // Main content
                    when (currentDestination) {
                        NavDestination.Tasks -> TasksScreen()
                        NavDestination.Goals -> GoalsScreen()
                        NavDestination.Orders -> OrdersScreen()
                        NavDestination.Approvals -> ApprovalsScreen()
                        NavDestination.Budget -> BudgetScreen()
                        NavDestination.Memory -> MemoryScreen()
                        NavDestination.Assignees -> AssigneesScreen()
                    }
                }
            }
        }
    }
}
