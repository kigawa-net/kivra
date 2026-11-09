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
import androidx.compose.material3.VerticalDivider
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
import kotlinx.coroutines.CancellationException
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

/** プロジェクトの一覧(id から名前を引く。作成フォームの表示などに使う)。[KivraAppContent] が入れる。 */
val LocalProjects = compositionLocalOf { emptyList<ApiProject>() }

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
    // 画面のルート。ブラウザのハッシュと双方向に同期する: 読み込み時に解釈し、遷移時に書き、戻る/進むで読み直す。
    var route by remember { mutableStateOf(parseRoute(currentLocationHash())) }
    val navigate: (Route) -> Unit = { target ->
        // プロジェクトの中の詳細は、今のプロジェクトを引き継ぐ(一覧から開いた詳細は、その一覧のプロジェクトの中に残る)。
        val next = target.withProjectOf(route)
        route = next
        setLocationHash(next.toHash())
    }
    LaunchedEffect(Unit) {
        // 戻る・進む・直接入力。今の画面と同じハッシュ(遷移の直後の通知や、置き換えた旧 URL)は無視する。
        addHashChangeListener {
            val hash = currentLocationHash()
            if (hash != route.toHash()) route = parseRoute(hash)
        }
    }
    // ページタイトル(旧 UI の renderNav の document.title)。画面が変わるたびに入れる。
    LaunchedEffect(route) {
        setDocumentTitle(documentTitleOf(route))
    }

    // プロジェクトの一覧。作成の後と再試行のときに読み直す(旧 UI の loadProjects)。
    var projects by remember { mutableStateOf<List<ApiProject>>(emptyList()) }
    var projectsError by remember { mutableStateOf<String?>(null) }
    var projectsReady by remember { mutableStateOf(false) }
    var projectsReload by remember { mutableStateOf(0) }
    LaunchedEffect(projectsReload) {
        try {
            projects = ApiClient.getProjects()
            projectsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            projectsError = loadErrorMessage(e)
        }
        projectsReady = true
    }

    // 最後に選んだプロジェクト(この端末の記憶)。プロジェクトの中の画面を開くたびに覚える。旧い URL の行き先に使う。
    // 端末の記憶は検証してから使う(壊れた値は無視。未分類は有効な値)。
    var lastProjectId by remember { mutableStateOf(validLastProjectId(loadLastProjectId())) }
    LaunchedEffect(route) {
        route.projectIdOrNull?.let { id ->
            lastProjectId = id
            saveLastProjectId(id)
        }
    }
    // 旧い一覧の URL(`#/goals` など)は、プロジェクトが決まったらプロジェクトの配下へ置き換える(履歴には残さない)。
    LaunchedEffect(route, projectsReady) {
        val current = route
        if (current is Route.Legacy && projectsReady) {
            val known = if (projectsError == null) projects.map { it.projectId } else null
            val target = resolveLegacy(current.dest, known, lastProjectId)
            route = target
            replaceLocationHash(target.toHash())
        }
    }

    val projectId = route.projectIdOrNull
    val nameOf: (String) -> String? = { id -> projects.find { it.projectId == id }?.name }
    val labelOf: (String) -> String = { id -> projectLabel(id, nameOf(id)) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalProjects provides projects) {
        // フォント(日本語)の読み込みを待ってから描く。テーマは KivraTheme が MaterialTheme に渡す。
        KivraTheme {
            if (searchOpen) {
                SearchDialog(
                    projectId = projectId,
                    onDismiss = { searchOpen = false },
                    onNavigate = navigate
                )
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // サイドナビ: 2 層(プロジェクト選択中はそのプロジェクトの節、最後に全体の節)。
                    PermanentDrawerSheet(modifier = Modifier.width(200.dp)) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text("Kivra", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
                            val approvalsBadge = approvalsBadgeText(pendingApprovals)
                            sidebarSections(projectId, projectId?.let { nameOf(it) }).forEach { section ->
                                // 権限と自律運転・API の結果で隠す項目を外し、残りが無い節は見出しごと出さない。
                                val items = section.items.filter {
                                    perms.navVisible(it.dest) &&
                                        navVisibleForAutopilot(it.dest, autopilot) &&
                                        navVisibleForSections(it.dest, autopilot, budgetVisible, pendingApprovals)
                                }
                                if (items.isEmpty()) return@forEach
                                section.title?.let { title ->
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                    )
                                }
                                items.forEach { item ->
                                    NavigationDrawerItem(
                                        selected = route.nav == item.dest && route.projectIdOrNull == item.target.projectIdOrNull,
                                        onClick = { navigate(item.target) },
                                        label = { Text(item.dest.label) },
                                        // 承認の件数のバッジ(旧 UI の nav-approvals-badge)。件数が読めている時だけ。
                                        badge = approvalsBadge.takeIf { item.dest == NavDestination.Approvals }?.let { text ->
                                            @Composable { Text(text) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Row の中の縦の区切り。HorizontalDivider は幅を残り全部取るので、後ろの weight の本文が幅 0 になっていた。
                    VerticalDivider()

                    // Main content area
                    Column(modifier = Modifier.weight(1f)) {
                        // セッションが切れた時の案内。入力中の内容は残る(再読み込みするまで送信しない)。
                        if (ApiSession.expired) SessionBanner()
                        // ヘッダーの 1 段目: パンくず(プロジェクト › ページ › 詳細)。全幅にして、長い id は折り返す(右の操作と並べると切れていた)。
                        when (val r = route) {
                            is Route.Invalid -> Text(
                                text = "リンクが正しくありません",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(16.dp)
                            )
                            is Route.Legacy -> Text(
                                text = "読み込み中…",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(16.dp)
                            )
                            else -> Breadcrumb(
                                crumbs = r.breadcrumbs(labelOf),
                                onNavigate = navigate,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // ヘッダーの 2 段目: 今いるプロジェクトの切替、検索、承認待ち、役割、更新。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 今いるプロジェクトの切替(絞り込みではない)。全体の画面では「プロジェクトを選ぶ」。
                            ProjectSelectorDropdown(
                                currentName = projectId?.let { labelOf(it) },
                                projects = projects,
                                onProjectSelected = { id -> navigate(route.inProjectContext(id)) },
                                onOverview = { navigate(Route.Page(NavDestination.Projects)) },
                                dropdownExpanded = dropdownExpanded,
                                onDropdownExpandedChange = { dropdownExpanded = it }
                            )
                            projectsError?.let { message ->
                                Text(
                                    text = "プロジェクト一覧: $message",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                TextButton(onClick = { projectsReload += 1 }) { Text("再試行") }
                            }

                            TextButton(onClick = { searchOpen = true }) { Text("検索") }

                            Text(
                                text = connection,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            // 承認待ちの件数(1 件以上のとき)。プロジェクトの中なら、そのプロジェクトの承認へ。全体ならホームの要対応へ。
                            headerApprovalsText(pendingApprovals)?.let { text ->
                                TextButton(onClick = {
                                    navigate(if (projectId != null) Route.ProjectPage(projectId, NavDestination.Approvals) else Route.Page(NavDestination.Home))
                                }) { Text(text) }
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
                        when (val r = route) {
                            is Route.Page -> when (r.dest) {
                                NavDestination.Home -> HomeScreen(projects, projectsError) { navigate(it) }
                                NavDestination.Projects -> ProjectsScreen(
                                    notice = null,
                                    onNavigate = navigate,
                                    onProjectsChanged = { projectsReload += 1 },
                                )
                                NavDestination.Budget -> BudgetScreen()
                                NavDestination.Autopilot -> AutopilotScreen()
                                NavDestination.Assignees -> AssigneesScreen(null)
                                else -> Unit
                            }
                            is Route.ProjectPage -> when (r.dest) {
                                NavDestination.Dashboard -> ProjectDashboardScreen(r.projectId, navigate)
                                NavDestination.Tasks -> TasksScreen(r.projectId, navigate)
                                NavDestination.Goals -> GoalsScreen(r.projectId, navigate)
                                NavDestination.Orders -> OrdersScreen(r.projectId, navigate)
                                NavDestination.Approvals -> ApprovalsScreen(r.projectId)
                                NavDestination.Memory -> MemoryScreen(r.projectId)
                                NavDestination.Assignees -> AssigneesScreen(r.projectId)
                                NavDestination.ProjectBudget -> ProjectBudgetScreen(r.projectId)
                                else -> Unit
                            }
                            is Route.ProjectChooser -> ProjectsScreen(
                                notice = "「${r.dest.label}」はプロジェクトごとのページです。プロジェクトを選んでください。",
                                onNavigate = navigate,
                                onProjectsChanged = { projectsReload += 1 },
                            )
                            is Route.Legacy -> Unit
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

/**
 * ヘッダーのプロジェクト切替。表示は今いるプロジェクトの名前(全体の画面では「プロジェクトを選ぶ」)。
 * 中身は「プロジェクト一覧へ」と、プロジェクトの一覧(選ぶと、今の画面と同じ種類の画面をそのプロジェクトで開く)。
 */
@Composable
fun ProjectSelectorDropdown(
    currentName: String?,
    projects: List<ApiProject>,
    onProjectSelected: (String) -> Unit,
    onOverview: () -> Unit,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit
) {
    Box(modifier = Modifier.padding(16.dp)) {
        TextButton(
            onClick = { onDropdownExpandedChange(!dropdownExpanded) }
        ) {
            Text(
                text = currentName ?: "プロジェクトを選ぶ",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        DropdownMenu(
            expanded = dropdownExpanded,
            onDismissRequest = { onDropdownExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("プロジェクト一覧へ") },
                onClick = {
                    onOverview()
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
            // 未分類(プロジェクトに属さない既存のデータ)。プロジェクトが無くても選べる。
            DropdownMenuItem(
                text = { Text(UNASSIGNED_LABEL) },
                onClick = {
                    onProjectSelected(UNASSIGNED_PROJECT_ID)
                    onDropdownExpandedChange(false)
                }
            )
        }
    }
}
