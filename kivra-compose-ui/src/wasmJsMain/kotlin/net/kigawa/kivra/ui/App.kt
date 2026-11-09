package net.kigawa.kivra.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.delay
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiMe
import net.kigawa.kivra.ui.api.ApiProject
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.screens.*

/**
 * 呼び出し者の権限。[KivraApp] が `/api/selfdev/me` を 1 回読んで入れる。読めていないときは [CallerPermissions.UNKNOWN]
 * (何も隠さない。旧 UI と同じ)。各画面は [LocalCallerPermissions].current で操作の出し分けに使う。
 */
val LocalCallerPermissions = compositionLocalOf { CallerPermissions.UNKNOWN }

/** 自己開発の有無(ホームに出す)。読み直しの度に [KivraApp] が読む。 */
val LocalSelfDevAvailability = compositionLocalOf { SelfDevAvailability.LOADING }

/** 自律運転の有無(節と、その画面の一覧を出すかに使う)。読み直しの度に [KivraApp] が読む。 */
val LocalAutopilotAvailability = compositionLocalOf { AutopilotAvailability.LOADING }

/** 時刻の表示(端末の地域の `HH:MM:SS`)。 */
private val clockText: (Long) -> String = { formatClock(it.toDouble()) }

/**
 * Root composable for the Kivra Compose UI.
 * This is the entry point for all platforms (Web, Desktop, Android, iOS).
 */
@Composable
fun KivraApp() {
    // 権限は読み込みのたびではなく起動時に 1 回だけ読む(旧 UI の loadMe と同じ)。失敗は UNKNOWN。
    // 表示の出し分けは UX のためで、サーバーが各リクエストで判定する。
    var permissions by remember { mutableStateOf(CallerPermissions.UNKNOWN) }
    // ヘッダーに出す役割(旧 UI の me-role / me-note)。同じ応答を 1 回だけ使う。
    var me by remember { mutableStateOf<ApiMe?>(null) }
    LaunchedEffect(Unit) {
        val read = try {
            ApiClient.getMe()
        } catch (e: Exception) {
            null
        }
        me = read
        permissions = read?.let { CallerPermissions.of(it) } ?: CallerPermissions.UNKNOWN
    }

    // 読み直しの合図。手動の「更新」と定期更新で増える。表示中の画面は [LocalRefreshCount] をキーにして読み直す。
    var refreshCount by remember { mutableStateOf(0) }
    // 手動の「更新」の時刻(ヘッダーの「最終更新」。旧 UI の refresh-all-at と同じく、手動の更新だけで変える)。
    var lastManualRefreshAt by remember { mutableStateOf<Long?>(null) }
    // 最後に読み直しを始めた時刻。定期更新の間隔の基準(手動も含む)。
    var lastAttemptAt by remember { mutableStateOf(0L) }
    var selfDev by remember { mutableStateOf(SelfDevAvailability.LOADING) }
    var autopilot by remember { mutableStateOf(AutopilotAvailability.LOADING) }
    var health by remember { mutableStateOf(HealthState()) }
    // ナビの出し分け(旧 UI の予算の節・承認の節の表示条件)。予算は読めたときだけ、承認は件数が読めたときだけ。
    var budgetVisible by remember { mutableStateOf(false) }
    var pendingApprovals by remember { mutableStateOf<Int?>(null) }

    val refresh: () -> Unit = {
        refreshCount += 1
        lastAttemptAt = nowMillis().toLong()
    }

    // 読み直しの度に、自己開発の有無・自律運転の有無と接続を読む(旧 UI の refreshStatus・refreshAutopilot と checkHealth)。
    // 自動化の項目を出すかだけに使う。自律運転の画面は自分で読む。
    LaunchedEffect(refreshCount) {
        selfDev = try {
            selfDevAvailabilityOf(ApiClient.getSelfDevStatus().enabled)
        } catch (e: Exception) {
            SelfDevAvailability.UNKNOWN
        }
        val autopilotNow = try {
            autopilotAvailabilityOf(ApiClient.getAutopilot().enabled)
        } catch (e: Exception) {
            AutopilotAvailability.HIDDEN
        }
        autopilot = autopilotNow
        // 承認の件数。自律運転が有効なときだけ読む(旧 UI の ORD と同じ。読めない・403・503 は null で出さない)。
        pendingApprovals = if (autopilotNow != AutopilotAvailability.ENABLED) {
            null
        } else {
            try {
                ApiClient.getApprovals().size
            } catch (e: Exception) {
                null
            }
        }
        budgetVisible = try {
            loadBudgetOrHidden() != null
        } catch (e: Exception) {
            false
        }
        val ok = ApiClient.checkHealth()
        health = health.after(ok, nowMillis().toLong())
    }

    // 定期更新。1 秒ごとに見て、間隔を過ぎていて、隠れていなくて、送信中・ダイアログ表示中でなければ読み直す(旧 UI の tick)。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            if (shouldAutoRefresh(
                    now = nowMillis().toLong(),
                    lastAttemptAt = lastAttemptAt,
                    hidden = isDocumentHidden(),
                    holds = ApiSession.holds,
                    sessionExpired = ApiSession.expired,
                )
            ) {
                refresh()
            }
        }
    }

    val onManualRefresh: () -> Unit = {
        lastManualRefreshAt = nowMillis().toLong()
        refresh()
    }

    CompositionLocalProvider(
        LocalCallerPermissions provides permissions,
        LocalRefreshCount provides refreshCount,
        LocalSelfDevAvailability provides selfDev,
        LocalAutopilotAvailability provides autopilot,
    ) {
        KivraAppContent(
            connection = connectionLabel(health, clockText),
            lastRefreshText = lastManualRefreshAt?.let { "最終更新: ${clockText(it)}" } ?: "最終更新: 未更新",
            onRefresh = onManualRefresh,
            autopilot = autopilot,
            roleHeader = roleHeaderOf(me),
            pendingApprovals = pendingApprovals,
            budgetVisible = budgetVisible,
        )
    }
}

@Composable
private fun KivraAppContent(
    connection: String,
    lastRefreshText: String,
    onRefresh: () -> Unit,
    autopilot: AutopilotAvailability,
    roleHeader: RoleHeader?,
    pendingApprovals: Int?,
    budgetVisible: Boolean,
) {
    val perms = LocalCallerPermissions.current
    // 画面のルート。ブラウザのハッシュ(#/runs/<id> 等)と双方向に同期する: 読み込み時に解釈し、遷移時に書き、戻る/進むで読み直す。
    var route by remember { mutableStateOf(parseRoute(canonicalHash(currentLocationHash()))) }
    val navigate: (Route) -> Unit = { target ->
        route = target
        setLocationHash(target.toHash())
    }
    LaunchedEffect(Unit) {
        route = settleLocationHash()
        addHashChangeListener { route = settleLocationHash() }
    }
    // ページタイトル(旧 UI の renderNav の document.title)。画面が変わるたびに入れる。
    LaunchedEffect(route) {
        setDocumentTitle(documentTitleOf(route))
    }
    var projects by remember { mutableStateOf<List<ApiProject>>(emptyList()) }
    // 選択中のプロジェクト(null は全体)。プロジェクト配下の画面(#/projects/<id>/<page>)を開くと、その画面のプロジェクトになる。
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        route.projectIdOrNull?.let { selectedProjectId = it }
    }
    // 表示に使うプロジェクト: 開いている画面の URL のプロジェクト、なければ選択中。
    val projectId = route.projectIdOrNull ?: selectedProjectId
    // 選択を変える。今の画面の続き(同じ一覧)を、そのプロジェクトのページで開く(旧 UI の chooseProjectContext と同じ)。
    val chooseProject: (String?) -> Unit = { id ->
        selectedProjectId = id
        val target = route.inProjectContext(id)
        if (target != route) navigate(target)
    }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    // プロジェクトの作成の後に増やして、選択の一覧を読み直させる(旧 UI の loadProjects)。
    var projectsReload by remember { mutableStateOf(0) }
    LaunchedEffect(projectsReload) {
        try {
            projects = ApiClient.getProjects()
        } catch (e: Exception) {
            // ignore
        }
    }

    MaterialTheme {
        if (searchOpen) {
            SearchDialog(
                projectId = projectId,
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
                        val approvalsBadge = approvalsBadgeText(pendingApprovals)
                        NavDestination.grouped.forEach { (group, allItems) ->
                            // 権限と自律運転・API の結果で隠す項目を外し、残りが無いグループは見出しごと出さない。
                            val items = allItems.filter {
                                perms.navVisible(it) &&
                                    navVisibleForAutopilot(it, autopilot) &&
                                    navVisibleForSections(it, autopilot, budgetVisible, pendingApprovals)
                            }
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
                                    // 選択中のプロジェクトがあれば、そのプロジェクトのページへ(旧 UI のヘッダのタブと同じ)。
                                    onClick = { navigate(dest.routeFor(projectId)) },
                                    label = { Text(dest.label) },
                                    // 承認の件数のバッジ(旧 UI の nav-approvals-badge)。件数が読めている時だけ。
                                    badge = approvalsBadge.takeIf { dest == NavDestination.Approvals }?.let { text ->
                                        @Composable { Text(text) }
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()

                // Main content area
                Column(modifier = Modifier.weight(1f)) {
                    // セッションが切れた時の案内。入力中の内容は残る(再読み込みするまで送信しない)。
                    if (ApiSession.expired) SessionBanner()
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
                            is Route.ProjectPage -> Text(
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
                            selectedProjectId = projectId,
                            projects = projects,
                            onProjectSelected = chooseProject,
                            dropdownExpanded = dropdownExpanded,
                            onDropdownExpandedChange = { dropdownExpanded = it }
                        )

                        TextButton(onClick = { searchOpen = true }) { Text("検索") }

                        Text(
                            text = connection,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        // 承認待ちの件数(1 件以上のとき。押すと承認の画面へ)。
                        headerApprovalsText(pendingApprovals)?.let { text ->
                            TextButton(onClick = { navigate(NavDestination.Approvals.routeFor(projectId)) }) { Text(text) }
                        }
                        // 役割と、判定の状態の補足(読めた時だけ)。
                        roleHeader?.let { role ->
                            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(role.roleText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                role.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        OutlinedButton(onClick = onRefresh) { Text("更新") }
                        Text(
                            text = lastRefreshText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp)
                        )
                    }
                    HorizontalDivider()

                    // Main content
                    // 一覧の画面(プロジェクト配下の一覧も同じ画面。プロジェクトは projectId で渡す)。
                    val pageDest = (route as? Route.Page)?.dest ?: (route as? Route.ProjectPage)?.dest
                    when (val r = route) {
                        is Route.Page, is Route.ProjectPage -> when (pageDest) {
                            NavDestination.Home -> HomeScreen(projectId) { navigate(it.routeFor(projectId)) }
                            NavDestination.Tasks -> TasksScreen(projectId, navigate)
                            NavDestination.Goals -> GoalsScreen(projectId, navigate)
                            NavDestination.Orders -> OrdersScreen(projectId, navigate)
                            NavDestination.Approvals -> ApprovalsScreen(projectId)
                            NavDestination.Budget -> BudgetScreen()
                            NavDestination.Memory -> MemoryScreen(projectId)
                            NavDestination.Projects -> ProjectsScreen(
                                onNavigate = navigate,
                                onProjectsChanged = { projectsReload += 1 },
                            )
                            NavDestination.Assignees -> AssigneesScreen(projectId)
                            NavDestination.Autopilot -> AutopilotScreen()
                            null -> Unit
                        }
                        is Route.ProjectDashboard -> ProjectDashboardScreen(r.projectId, navigate)
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

/**
 * 今のハッシュを読んでルートにする。旧い形(`#/projects/<id>/budget`)は、ブラウザの URL もダッシュボードのハッシュへ置き換える
 * (旧 UI の route と同じ。履歴には残さない)。
 */
private fun settleLocationHash(): Route {
    val raw = currentLocationHash()
    val canonical = canonicalHash(raw)
    if (canonical != raw) replaceLocationHash(canonical)
    return parseRoute(canonical)
}

/** セッション切れの案内(旧 UI の session-banner と同じ趣旨)。再読み込みでサインインし直す。 */
@Composable
private fun SessionBanner() {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "セッションが切れました（ログインが必要です）。入力中の内容は残っています。",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { reloadPage() }) { Text("再読み込みしてログインし直す") }
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
