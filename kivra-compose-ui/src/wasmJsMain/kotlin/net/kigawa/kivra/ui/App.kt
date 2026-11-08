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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
 * 呼び出し者の権限。[KivraApp] が `/api/selfdev/me` を 1 回読んで入れる。読めていないときは [CallerPermissions.UNKNOWN]
 * (何も隠さない。旧 UI と同じ)。各画面は [LocalCallerPermissions].current で操作の出し分けに使う。
 */
val LocalCallerPermissions = compositionLocalOf { CallerPermissions.UNKNOWN }

/**
 * Root composable for the Kivra Compose UI.
 * This is the entry point for all platforms (Web, Desktop, Android, iOS).
 */
@Composable
fun KivraApp() {
    // 権限は読み込みのたびではなく起動時に 1 回だけ読む(旧 UI の loadMe と同じ)。失敗は UNKNOWN。
    // 表示の出し分けは UX のためで、サーバーが各リクエストで判定する。
    var permissions by remember { mutableStateOf(CallerPermissions.UNKNOWN) }
    LaunchedEffect(Unit) {
        permissions = try {
            CallerPermissions.of(ApiClient.getMe())
        } catch (e: Exception) {
            CallerPermissions.UNKNOWN
        }
    }
    CompositionLocalProvider(LocalCallerPermissions provides permissions) {
        KivraAppContent()
    }
}

@Composable
private fun KivraAppContent() {
    val perms = LocalCallerPermissions.current
    // 画面のルート。ブラウザのハッシュ(#/runs/<id> 等)と双方向に同期する: 読み込み時に解釈し、遷移時に書き、戻る/進むで読み直す。
    var route by remember { mutableStateOf(parseRoute(currentLocationHash())) }
    val navigate: (Route) -> Unit = { target ->
        route = target
        setLocationHash(target.toHash())
    }
    LaunchedEffect(Unit) {
        addHashChangeListener { route = parseRoute(currentLocationHash()) }
    }
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
                onNavigate = navigate
            )
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Sidebar: グループ見出し + 配下項目
                PermanentDrawerSheet(modifier = Modifier.width(200.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text("Kivra", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
                        NavigationDrawerItem(
                            selected = route.nav == NavDestination.Home,
                            onClick = { navigate(Route.Page(NavDestination.Home)) },
                            label = { Text(NavDestination.Home.label) }
                        )
                        NavDestination.grouped.forEach { (group, allItems) ->
                            // 権限で隠す項目を外し、残りが無いグループは見出しごと出さない。
                            val items = allItems.filter { perms.navVisible(it) }
                            if (items.isEmpty()) return@forEach
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                            )
                            items.forEach { dest ->
                                NavigationDrawerItem(
                                    selected = route.nav == dest,
                                    onClick = { navigate(Route.Page(dest)) },
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
                        // 一覧はタイトル、詳細はパンくず(作業 › 一覧 › 詳細)。
                        when (val r = route) {
                            is Route.Page -> Text(
                                text = r.dest.label,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(16.dp)
                            )
                            is Route.Invalid -> Text(
                                text = "リンクが正しくありません",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(16.dp)
                            )
                            else -> Breadcrumb(
                                crumbs = r.breadcrumbs(),
                                onNavigate = navigate,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
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
                    when (val r = route) {
                        is Route.Page -> when (r.dest) {
                            NavDestination.Home -> HomeScreen(projectId) { navigate(Route.Page(it)) }
                            NavDestination.Tasks -> TasksScreen(projectId, navigate)
                            NavDestination.Goals -> GoalsScreen(projectId, navigate)
                            NavDestination.Orders -> OrdersScreen(projectId, navigate)
                            NavDestination.Approvals -> ApprovalsScreen(projectId)
                            NavDestination.Budget -> BudgetScreen()
                            NavDestination.Memory -> MemoryScreen()
                            NavDestination.Assignees -> AssigneesScreen(projectId)
                        }
                        is Route.RunDetail -> RunDetailScreen(r.runId, navigate)
                        is Route.GoalDetail -> GoalDetailScreen(r.taskId, navigate)
                        is Route.OrderDetail -> OrderDetailScreen(r.orderId, navigate)
                        is Route.OrderCycles -> OrderCyclesScreen(r.orderId, navigate)
                        is Route.Invalid -> Text(
                            text = "このリンクは開けません。一覧から選び直してください。",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
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
