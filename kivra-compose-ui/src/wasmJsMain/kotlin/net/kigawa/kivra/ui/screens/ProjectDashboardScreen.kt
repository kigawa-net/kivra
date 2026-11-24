package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.CHAT_COMPACT_LIST_HEIGHT_DP
import net.kigawa.kivra.ui.DASHBOARD_GAP_DP
import net.kigawa.kivra.ui.DASHBOARD_SIDE_WIDTH_DP
import net.kigawa.kivra.ui.isPinnedChatLayout
import net.kigawa.kivra.ui.CHAT_NEAR_BOTTOM_DP
import net.kigawa.kivra.ui.ChatStreamEvent
import net.kigawa.kivra.ui.isAtNewest
import net.kigawa.kivra.ui.isNewerChatHistory
import net.kigawa.kivra.ui.isTurnEchoedInHistory
import net.kigawa.kivra.ui.shouldAutoScroll
import net.kigawa.kivra.ui.chatStreamErrorText
import net.kigawa.kivra.ui.chatToolLines
import net.kigawa.kivra.ui.CHAT_ACTION_AI_ORIGIN_CAPTION
import net.kigawa.kivra.ui.CHAT_ACTION_CONFIRM_TITLE
import net.kigawa.kivra.ui.CHAT_ACTION_DISMISS_BUTTON_TEXT
import net.kigawa.kivra.ui.CHAT_ACTION_TASK_TITLE_CAPTION
import net.kigawa.kivra.ui.WriteFailure
import net.kigawa.kivra.ui.chatActionConfirmButtonText
import net.kigawa.kivra.ui.chatActionConfirmMessage
import net.kigawa.kivra.ui.chatActionFailureText
import net.kigawa.kivra.ui.chatActionMemberText
import net.kigawa.kivra.ui.chatActionResultText
import net.kigawa.kivra.ui.writeFailureOf
import net.kigawa.kivra.ui.api.ApiChatAction
import net.kigawa.kivra.ui.BACKFILL_DRY_DONE_MESSAGE
import net.kigawa.kivra.ui.BACKFILL_RUN_DONE_MESSAGE
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.PROJECT_SPEND_NOTE
import net.kigawa.kivra.ui.REPO_ADDED_MESSAGE
import net.kigawa.kivra.ui.REPO_EMPTY_MESSAGE
import net.kigawa.kivra.ui.REPO_INVALID_MESSAGE
import net.kigawa.kivra.ui.REPO_REMOVED_MESSAGE
import net.kigawa.kivra.ui.REPO_KEY_INPUT_MAX
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiBackfillReport
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiDrainReport
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiProjectActivity
import net.kigawa.kivra.ui.api.ApiProjectBudget
import net.kigawa.kivra.ui.api.ApiProjectDashboard
import net.kigawa.kivra.ui.api.ApiRun
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.api.ApiChatMessage
import net.kigawa.kivra.ui.api.ApiChatSession
import net.kigawa.kivra.ui.backfillExplain
import net.kigawa.kivra.ui.backfillFailureText
import net.kigawa.kivra.ui.backfillTableLine
import net.kigawa.kivra.ui.DRAIN_DONE_MESSAGE
import net.kigawa.kivra.ui.DRAIN_DRY_DONE_MESSAGE
import net.kigawa.kivra.ui.DRAIN_NO_TARGET_MESSAGE
import net.kigawa.kivra.ui.canDeleteEmptied
import net.kigawa.kivra.ui.canRunBackfill
import net.kigawa.kivra.ui.canRunDrain
import net.kigawa.kivra.ui.deleteExplain
import net.kigawa.kivra.ui.deleteProjectFailureText
import net.kigawa.kivra.ui.drainCountLines
import net.kigawa.kivra.ui.drainExplain
import net.kigawa.kivra.ui.drainFailureText
import net.kigawa.kivra.ui.drainTargetChoices
import net.kigawa.kivra.ui.drainTotal
import net.kigawa.kivra.ui.isProjectBudgetBody
import net.kigawa.kivra.ui.isRepoKeyShape
import net.kigawa.kivra.ui.projectActivityText
import net.kigawa.kivra.ui.projectRepoFailureText
import net.kigawa.kivra.ui.projectRepoRemoveExplain
import net.kigawa.kivra.ui.projectScopeText
import net.kigawa.kivra.ui.projectStateRows
import net.kigawa.kivra.ui.projectTotalText
import net.kigawa.kivra.ui.projectRunStateText
import net.kigawa.kivra.ui.showsProjectOperator
import net.kigawa.kivra.ui.submitBlocked
import net.kigawa.kivra.ui.yenText

/**
 * プロジェクトのダッシュボード(`#/projects/<id>/dashboard`、旧 UI の project-dash): 状態別の件数、最近の Run、費用、最近の活動。
 * scope が ALL(運用者)のときだけ、リポジトリの登録と既存データの振り分けを出す。読み直しは共通の更新に従う。
 * 読み込みの失敗は固定の文言(サーバーの本文は出さない)。
 */
@Composable
fun ProjectDashboardScreen(
    projectId: String,
    onNavigate: (Route) -> Unit,
) {
    val loaded = rememberLoad(projectId) { loadProjectDashboard(projectId) }
    // The body scrolls on its own (the chat stays pinned when wide, the whole page scrolls when narrow), so the frame does not.
    DetailPage(scrollable = false) {
        LoadedView(loaded) { d ->
            ProjectDashboardBody(projectId, d, onNavigate)
        }
    }
}

/**
 * ダッシュボードの読み取り。運用者の予算の形が合わなければ、ダッシュボード全体を失敗にする(旧 UI の判定と同じ。一部だけ出さない)。
 * ダッシュボードと、プロジェクトの予算の画面([ProjectBudgetScreen])で共有する。
 */
internal suspend fun loadProjectDashboard(projectId: String): ApiProjectDashboard {
    val d = ApiClient.getProjectDashboard(projectId)
    d.budget?.let { if (!isProjectBudgetBody(it)) throw ApiException(null, "response is not the expected format") }
    return d
}

@Composable
private fun ProjectDashboardBody(
    projectId: String,
    d: ApiProjectDashboard,
    onNavigate: (Route) -> Unit,
) {
    val operator = showsProjectOperator(d.scope)
    // Wide and tall enough, the chat stays pinned and only the side column scrolls. Otherwise the whole page scrolls and the chat keeps a fixed height.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pinned = isPinnedChatLayout(maxWidth.value, maxHeight.value)
        val pageScroll = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize().then(if (pinned) Modifier else Modifier.verticalScroll(pageScroll))) {
            SelectableText(d.project.name, style = MaterialTheme.typography.headlineSmall)
            d.project.description?.takeIf { it.isNotBlank() }?.let { description ->
                SelectableText(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            SelectableText(projectScopeText(d.scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            // 中央：プロジェクトごとのチャット（V53）。右サイド：Run・費用・活動と、運用者の操作。
            DashboardSplitLayout(
                pinned = pinned,
                modifier = if (pinned) Modifier.weight(1f) else Modifier,
                main = { ProjectChatSection(projectId, pinned) },
                side = { ProjectDashboardSide(projectId, d, operator, pinned, onNavigate) },
            )
        }
    }
}

/** The right side: the Run, cost and activity cards, and the operator's actions. Pinned, it scrolls on its own; stacked, the page does. */
@Composable
private fun ProjectDashboardSide(
    projectId: String,
    d: ApiProjectDashboard,
    operator: Boolean,
    pinned: Boolean,
    onNavigate: (Route) -> Unit,
) {
    val sideScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth().then(if (pinned) Modifier.fillMaxHeight().verticalScroll(sideScroll) else Modifier)) {
        // --- Run 関連：状態別件数 + 最近の Run ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionTitle("Run の状態別件数")
                SelectableText(projectTotalText(d.tasks.total))
                val rows = projectStateRows(d.tasks.byState)
                if (rows.isEmpty()) {
                    SelectableText("Run はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    rows.forEach { (code, count) ->
                        SelectableText("${projectRunStateText(code)}：$count 件", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))

                SectionTitle("最近の Run")
                if (d.recentRuns.isEmpty()) {
                    SelectableText("Run はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    d.recentRuns.forEach { run ->
                        ProjectRunRow(run, showCreator = operator, onClick = { onNavigate(Route.RunDetail(run.runId)) })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // --- 費用 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionTitle("費用（この Run に紐づく分）")
                SelectableText(PROJECT_SPEND_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DetailRow("確定", yenText(d.spend.committedJpy))
                DetailRow("予約中", yenText(d.spend.reservedJpy))
                DetailRow("見積もりで計上（確定待ち）", yenText(d.spend.lapsedJpy))
                DetailRow("見積もりで計上（金額の確認待ち）", yenText(d.spend.unpricedJpy))
                DetailRow("合計", yenText(d.spend.totalJpy))
            }
        }
        Spacer(Modifier.height(12.dp))

        // --- 最近の活動 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionTitle("最近の活動")
                if (d.activity.isEmpty()) {
                    SelectableText("最近の活動はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    d.activity.forEach { event: ApiProjectActivity ->
                        SelectableText(projectActivityText(event), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        TextButton(onClick = { onNavigate(Route.ProjectPage(projectId, NavDestination.ProjectSettings)) }) {
            SelectableText(NavDestination.ProjectSettings.label)
        }
        if (operator) {
            // 今月の予算は、プロジェクトの「予算」のページにある(設定と解除もそこで行う)。
            TextButton(onClick = { onNavigate(Route.ProjectPage(projectId, NavDestination.ProjectBudget)) }) {
                SelectableText("今月の予算を見る（運用者のみ）")
            }
            ProjectOperatorSection(projectId)
            ProjectDrainSection(projectId, d.project.name, onNavigate)
        }
    }
}

/**
 * The chat (main) and the side column. Pinned: side by side, both as tall as the space, the side scrolling on its own.
 * Stacked: one under the other, each sized by its content, and the page scrolls. The children keep their place either way, so the chat's
 * state survives a resize across the breakpoint.
 */
@Composable
private fun DashboardSplitLayout(
    pinned: Boolean,
    modifier: Modifier,
    main: @Composable () -> Unit,
    side: @Composable () -> Unit,
) {
    Layout(content = { main(); side() }, modifier = modifier) { measurables, constraints ->
        val mainMeasurable = measurables[0]
        val sideMeasurable = measurables[1]
        val gap = DASHBOARD_GAP_DP.dp.roundToPx()
        if (pinned && constraints.hasBoundedHeight) {
            val height = constraints.maxHeight
            val width = constraints.maxWidth
            val sideWidth = DASHBOARD_SIDE_WIDTH_DP.dp.roundToPx().coerceAtMost(width)
            val mainWidth = (width - sideWidth - gap).coerceAtLeast(0)
            val mainPlaceable = mainMeasurable.measure(Constraints.fixed(mainWidth, height))
            val sidePlaceable = sideMeasurable.measure(Constraints.fixed(sideWidth, height))
            layout(width, height) {
                mainPlaceable.place(0, 0)
                sidePlaceable.place(mainWidth + gap, 0)
            }
        } else {
            val mainPlaceable = mainMeasurable.measure(Constraints(maxWidth = constraints.maxWidth))
            val sidePlaceable = sideMeasurable.measure(Constraints(maxWidth = constraints.maxWidth))
            val height = mainPlaceable.height + gap + sidePlaceable.height
            layout(constraints.maxWidth, height) {
                mainPlaceable.place(0, 0)
                sidePlaceable.place(0, mainPlaceable.height + gap)
            }
        }
    }
}

/**
 * 運用者だけの「空にして削除」(ADR 0022 PR-H)。移し先を選び、確認（dry-run）の件数を見て、確認ダイアログから空にする(実行)。空になったら
 * 削除できる。判定は運用者の権限と行の状態をサーバーが決める。移し先を変えたら、それまでの確認の結果は捨てる(実行の対象が変わるため)。
 */
@Composable
private fun ProjectDrainSection(projectId: String, projectName: String, onNavigate: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    val projects = rememberLoad(projectId) { ApiClient.getProjects() }
    var targetId by remember { mutableStateOf<String?>(null) }
    var targetName by remember { mutableStateOf("") }
    // 確認（dry-run）の結果。実行の対象になる。実行の結果は [shown] に出す。
    var dry by remember { mutableStateOf<ApiDrainReport?>(null) }
    var shown by remember { mutableStateOf<ApiDrainReport?>(null) }
    // 空になったか(確認で移すものが 0 件、または移しが済んだ)。削除できるかの材料。
    var emptied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    var drainConfirm by remember { mutableStateOf(false) }
    var drainError by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    fun chooseTarget(id: String, name: String) {
        targetId = id
        targetName = name
        dry = null
        shown = null
        emptied = false
        notice = null
    }

    fun dryRun() {
        val target = targetId ?: return
        if (submitBlocked(busy, ApiSession.expired)) return
        busy = true
        notice = null
        scope.launch {
            try {
                val report = ApiClient.drainProject(projectId, target, dryRun = true)
                if (!report.dryRun) error("the dry run answered as a real run")
                dry = report
                emptied = drainTotal(report.counts) == 0L
                notice = ActionNotice(DRAIN_DRY_DONE_MESSAGE, ok = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dry = null
                emptied = false
                notice = ActionNotice(drainFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                busy = false
            }
        }
    }

    fun runDrain() {
        val target = targetId ?: return
        if (submitBlocked(busy, ApiSession.expired)) return
        busy = true
        drainError = null
        scope.launch {
            var done: ApiDrainReport? = null
            try {
                val report = ApiClient.drainProject(projectId, target, dryRun = false)
                if (report.dryRun) error("the real run answered as a dry run")
                done = report
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                drainError = drainFailureText((e as? ApiException)?.status)
            } finally {
                busy = false
            }
            done?.let { report ->
                shown = report
                dry = null
                emptied = true
                drainConfirm = false
                drainError = null
                notice = ActionNotice(DRAIN_DONE_MESSAGE, ok = true)
            }
        }
    }

    fun runDelete() {
        if (submitBlocked(busy, ApiSession.expired)) return
        busy = true
        deleteError = null
        scope.launch {
            var deleted = false
            try {
                ApiClient.deleteProject(projectId)
                deleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleteError = deleteProjectFailureText((e as? ApiException)?.status)
            } finally {
                busy = false
            }
            if (deleted) {
                deleteConfirm = false
                // The project is gone: go back to the list, which no longer shows it.
                onNavigate(Route.Page(NavDestination.Projects))
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SelectableText("空にして削除（運用者のみ）", style = MaterialTheme.typography.titleMedium)
            SelectableText(
                "このプロジェクトの行（Task、Order、記憶、Run、担当、repo）を別のプロジェクトへ移して空にし、削除できるようにします。" +
                    "データは消しません。メンバーと予算の上限は、このプロジェクトから外れます。まず確認（dry-run）をしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LoadedView(projects) { list ->
                val choices = drainTargetChoices(list, projectId)
                if (choices.isEmpty()) {
                    SelectableText(DRAIN_NO_TARGET_MESSAGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SelectableText("移し先", style = MaterialTheme.typography.labelLarge)
                    choices.forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && !ApiSession.expired) { chooseTarget(p.projectId, p.name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = targetId == p.projectId,
                                onClick = { chooseTarget(p.projectId, p.name) },
                                enabled = !busy && !ApiSession.expired,
                            )
                            SelectableText(p.name)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = targetId != null && !busy && !ApiSession.expired, onClick = { dryRun() }) {
                            SelectableText("確認（dry-run）")
                        }
                        Button(
                            enabled = canRunDrain(dry, targetId, busy) && !ApiSession.expired,
                            onClick = {
                                drainError = null
                                drainConfirm = true
                            },
                        ) {
                            SelectableText("空にして移す…")
                        }
                    }
                }
            }
            dry?.let { report ->
                Spacer(Modifier.height(4.dp))
                SelectableText("移す件数（確認の結果）", style = MaterialTheme.typography.labelLarge)
                val lines = drainCountLines(report.counts)
                if (lines.isEmpty()) {
                    SelectableText("移す行はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    lines.forEach { SelectableText(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            shown?.let { report ->
                Spacer(Modifier.height(4.dp))
                SelectableText("移した件数", style = MaterialTheme.typography.labelLarge)
                drainCountLines(report.counts).forEach { SelectableText(it, style = MaterialTheme.typography.bodySmall) }
            }
            notice?.let { n ->
                SelectableText(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = canDeleteEmptied(emptied, busy) && !ApiSession.expired,
                onClick = {
                    deleteError = null
                    deleteConfirm = true
                },
            ) {
                SelectableText("削除…")
            }
        }
    }

    // 空にする(実行)の確認。確認の結果が無いときは開かない。失敗時は開いたままにする。
    val report = dry
    if (drainConfirm && report != null && targetId != null) {
        ReasonDialog(
            title = "空にして移す",
            message = drainExplain(report, targetName),
            submitLabel = "移す",
            reasonLabel = null,
            busy = busy,
            errorText = drainError,
            onSubmit = { runDrain() },
            onDismiss = {
                if (!busy) {
                    drainConfirm = false
                    drainError = null
                }
            },
        )
    }

    // 削除の確認。失敗時は開いたままにする(409 は行が残っている)。
    if (deleteConfirm) {
        ReasonDialog(
            title = "プロジェクトの削除",
            message = deleteExplain(projectName),
            submitLabel = "削除する",
            reasonLabel = null,
            busy = busy,
            errorText = deleteError,
            onSubmit = { runDelete() },
            onDismiss = {
                if (!busy) {
                    deleteConfirm = false
                    deleteError = null
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    SelectableText(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
}

/** 一件の Run の行。作成者は運用者だけに出す(旧 UI の runRow と同じ)。 */
@Composable
private fun ProjectRunRow(run: ApiRun, showCreator: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            SelectableText(run.title, style = MaterialTheme.typography.titleSmall)
            SelectableText(
                "状態: ${run.state} | 作成: ${run.createdAt}" +
                    (if (showCreator) run.createdBy?.let { " | 作成者: $it" } ?: "" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 運用者だけの block(旧 UI の project-ops): プロジェクトのリポジトリ(登録と解除)と、既存データの振り分け(dry-run と実行)。
 * 振り分けは先に確認(dry-run)をして、対象が 1 件以上のときだけ実行できる。実行は確認ダイアログを経る。
 * repo を変えたら、それまでの確認の結果は捨てる(実行の対象が変わるため)。権限の最終判定はサーバーが行う。
 */
@Composable
private fun ProjectOperatorSection(projectId: String) {
    val scope = rememberCoroutineScope()
    // 登録の変更の後に増やして、一覧を読み直させる。
    var repoReload by remember { mutableStateOf(0) }
    val repos = rememberLoad(projectId, repoReload) { ApiClient.getProjectRepos(projectId) }
    var repoInput by remember { mutableStateOf("") }
    var repoBusy by remember { mutableStateOf(false) }
    var repoNotice by remember { mutableStateOf<ActionNotice?>(null) }
    // 外すの確認の対象の repo(開いている間だけ non-null)と、その失敗の文言(ダイアログの中に出す)。
    var repoRemoveTarget by remember { mutableStateOf<String?>(null) }
    var repoRemoveError by remember { mutableStateOf<String?>(null) }

    // 確認(dry-run)の結果。実行の対象になる。実行の結果は [backfillShown] に出す。
    var backfillDry by remember { mutableStateOf<ApiBackfillReport?>(null) }
    var backfillShown by remember { mutableStateOf<ApiBackfillReport?>(null) }
    var backfillBusy by remember { mutableStateOf(false) }
    var backfillNotice by remember { mutableStateOf<ActionNotice?>(null) }
    var confirmOpen by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf<String?>(null) }

    // repo が変わったら、確認の結果は古いので捨てる(旧 UI の invalidateBackfill)。
    fun repoChanged() {
        backfillDry = null
        backfillShown = null
        repoReload += 1
    }

    fun addRepo() {
        if (submitBlocked(repoBusy, ApiSession.expired)) return
        val key = repoInput.trim()
        if (!isRepoKeyShape(key)) {
            repoNotice = ActionNotice(REPO_INVALID_MESSAGE, ok = false)
            return
        }
        repoBusy = true
        repoNotice = null
        scope.launch {
            var added = false
            try {
                ApiClient.addProjectRepo(projectId, key)
                added = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repoNotice = ActionNotice(projectRepoFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                repoBusy = false
            }
            if (added) {
                repoInput = ""
                repoNotice = ActionNotice(REPO_ADDED_MESSAGE, ok = true)
                repoChanged()
            }
        }
    }

    // 登録の解除は確認ダイアログ(ReasonDialog)から呼ぶ。失敗はダイアログの中に出し、開いたままにする。
    fun removeRepo(key: String) {
        if (submitBlocked(repoBusy, ApiSession.expired)) return
        repoBusy = true
        repoRemoveError = null
        scope.launch {
            var removed = false
            try {
                ApiClient.removeProjectRepo(projectId, key)
                removed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repoRemoveError = projectRepoFailureText((e as? ApiException)?.status)
            } finally {
                repoBusy = false
            }
            if (removed) {
                repoRemoveTarget = null
                repoRemoveError = null
                repoNotice = ActionNotice(REPO_REMOVED_MESSAGE, ok = true)
                repoChanged()
            }
        }
    }

    fun dryRun() {
        if (submitBlocked(backfillBusy, ApiSession.expired)) return
        backfillBusy = true
        backfillNotice = null
        scope.launch {
            try {
                val report = ApiClient.backfillProjects(dryRun = true)
                if (!report.dryRun) error("the dry run answered as a real run")
                backfillDry = report
                backfillShown = report
                backfillNotice = ActionNotice(BACKFILL_DRY_DONE_MESSAGE, ok = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backfillNotice = ActionNotice(backfillFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                backfillBusy = false
            }
        }
    }

    fun runBackfill() {
        if (submitBlocked(backfillBusy, ApiSession.expired)) return
        backfillBusy = true
        confirmError = null
        scope.launch {
            var done = false
            try {
                val report = ApiClient.backfillProjects(dryRun = false)
                if (report.dryRun) error("the real run answered as a dry run")
                backfillDry = null
                backfillShown = report
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                confirmError = backfillFailureText((e as? ApiException)?.status)
            } finally {
                backfillBusy = false
            }
            if (done) {
                confirmOpen = false
                confirmError = null
                backfillNotice = ActionNotice(BACKFILL_RUN_DONE_MESSAGE, ok = true)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SelectableText("リポジトリの対応（運用者のみ）", style = MaterialTheme.typography.titleMedium)
            Text("リポジトリの対応（運用者のみ）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LoadedList(repos, emptyText = REPO_EMPTY_MESSAGE) { list ->
                list.forEach { repo ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SelectableText(repo.repoKey, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            enabled = !repoBusy && !ApiSession.expired,
                            onClick = {
                                repoRemoveError = null
                                repoRemoveTarget = repo.repoKey
                            },
                        ) {
                            SelectableText("登録を外す…")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { SelectableText("repo（owner/name）") },
                    placeholder = { SelectableText("owner/name") },
                    singleLine = true,
                    enabled = !repoBusy && !ApiSession.expired,
                    isError = repoInput.length > REPO_KEY_INPUT_MAX,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !repoBusy && !ApiSession.expired, onClick = { addRepo() }) { SelectableText("追加") }
            }
            repoNotice?.let { n ->
                SelectableText(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            ProjectMembersSection(projectId)
            Spacer(Modifier.height(16.dp))
            SelectableText(
                "プロジェクトが未設定の既存データだけを、repo の対応で振り分けます（全プロジェクトに効きます）。" +
                    "すでにプロジェクトを持つ行は変わりません。対応がない行は未設定のままです。まず確認（dry-run）をしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !backfillBusy && !ApiSession.expired, onClick = { dryRun() }) {
                    SelectableText("確認（dry-run）")
                }
                Button(
                    enabled = canRunBackfill(backfillDry, backfillBusy) && !ApiSession.expired,
                    onClick = {
                        confirmError = null
                        confirmOpen = true
                    },
                ) {
                    SelectableText("振り分けを実行…")
                }
            }
            backfillNotice?.let { n ->
                SelectableText(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            backfillShown?.tables?.forEach { table ->
                SelectableText(backfillTableLine(table), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }

    // repo の登録を外すの確認。対象が無いときは開かない。失敗時は開いたままにする。
    val removing = repoRemoveTarget
    if (removing != null) {
        ReasonDialog(
            title = "リポジトリの対応",
            message = projectRepoRemoveExplain(removing),
            submitLabel = "登録を外す",
            reasonLabel = null,
            busy = repoBusy,
            errorText = repoRemoveError,
            onSubmit = { removeRepo(removing) },
            onDismiss = {
                if (!repoBusy) {
                    repoRemoveTarget = null
                    repoRemoveError = null
                }
            },
        )
    }

    // 実行の確認(旧 UI の backfill-dialog)。確認の結果が無いときは開かない。失敗時は開いたままにする。
    val dry = backfillDry
    if (confirmOpen && dry != null) {
        ReasonDialog(
            title = "既存データの振り分け",
            message = backfillExplain(dry),
            submitLabel = "振り分けを実行する",
            reasonLabel = null,
            busy = backfillBusy,
            errorText = confirmError,
            onSubmit = { runBackfill() },
            onDismiss = {
                if (!backfillBusy) {
                    confirmOpen = false
                    confirmError = null
                }
            },
        )
    }
}

/**
 * プロジェクトチャットセクション（V53）。メッセージの表示と送信を行う。
 * 送信すると、運用者の文をすぐ表示し、AI の返信を生成中から文字ごとに追記する(`POST .../chat/stream`)。返信が済んだら履歴を読み直す。
 * AI はツールでプロジェクトと order を読み、安全な操作は運用者の権限で実行し、破壊的な操作は提案だけを出す(ADR 0023 追補 5)。
 * ツールの状況は固定の文言で表示し、提案は下のカードの確定ボタンで運用者が確定する。
 */
@Composable
private fun ProjectChatSection(projectId: String, pinned: Boolean) {
    val scope = rememberCoroutineScope()
    val chat = rememberLoad(projectId) { loadChatSession(projectId) }
    // The history read after the last turn. It replaces the first load, so a finished turn is not shown twice.
    var reloaded by remember(projectId) { mutableStateOf<ApiChatSession?>(null) }
    // The turn in progress: the user's text and the reply so far. Null when no turn runs.
    var turn by remember(projectId) { mutableStateOf<ChatTurn?>(null) }
    var inputText by remember { mutableStateOf("") }
    var sendBusy by remember { mutableStateOf(false) }
    // The turn's coroutine. A new message cancels it and starts over; the cancelled turn's tail (reload, reset) is skipped.
    var turnJob by remember { mutableStateOf<Job?>(null) }
    var sendNotice by remember { mutableStateOf<ActionNotice?>(null) }
    var replyNotice by remember { mutableStateOf<ActionNotice?>(null) }
    // The history on screen: the one read after the last turn, or a newer background refresh (the periodic reload) of it.
    val session = latestChatSession(reloaded, chat.value)
    // The proposals (ADR 0023): confirm after the dialog, dismiss at once. The result is the server's fixed code, made into text.
    var pendingAction by remember { mutableStateOf<ApiChatAction?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionNotice by remember { mutableStateOf<ActionNotice?>(null) }

    suspend fun reloadChat() {
        val fresh = try {
            loadChatSession(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fresh != null) reloaded = fresh
    }

    fun runChatAction(action: ApiChatAction, confirm: Boolean) {
        actionBusy = true
        actionNotice = null
        scope.launch {
            try {
                val result = if (confirm) ApiClient.confirmChatAction(projectId, action.actionId) else ApiClient.dismissChatAction(projectId, action.actionId)
                actionNotice = ActionNotice(
                    chatActionResultText(result.state, result.resultCode),
                    ok = result.state == "EXECUTED" || result.state == "DISMISSED",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                actionNotice = ActionNotice(chatActionFailureText(writeFailureOf(e.status)), ok = false)
            } catch (e: Exception) {
                actionNotice = ActionNotice(chatActionFailureText(WriteFailure.NETWORK), ok = false)
            } finally {
                pendingAction = null
                actionBusy = false
                // Read the chat again: a resolved or expired proposal leaves the list.
                reloadChat()
            }
        }
    }

    // The message list is reversed: item 0 is the newest message, drawn at the bottom, so the first layout already shows it.
    // Pinned, it takes the space the page leaves; stacked, it has a fixed height and scrolls inside. The input box below it is always visible.
    val rows = chatListRows(projectId, session, turn, actionNotice)
    val listState = remember(projectId) { LazyListState() }
    val nearBottomPx = with(LocalDensity.current) { CHAT_NEAR_BOTTOM_DP.dp.roundToPx() }
    // Whether the reader is at the newest message. It is updated only while the reader scrolls: a new message moves the list's
    // items under the reader, which is not the reader's doing and must not change this.
    var atNewest by remember(projectId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (scrolling, index, offset) -> if (scrolling) atNewest = isAtNewest(index, offset, nearBottomPx) }
    }
    // Once per session load, after the list's first layout with its viewport known: show the newest message.
    LaunchedEffect(projectId) {
        snapshotFlow { listState.layoutInfo.let { it.totalItemsCount > 0 && it.viewportSize.height > 0 } }.first { it }
        listState.scrollToItem(0)
        atNewest = true
    }
    // The next change of the list goes to the newest message whatever the reader's position: the user's own send.
    var forceScroll by remember(projectId) { mutableStateOf(false) }
    val newestKey = rows.lastOrNull()?.key
    LaunchedEffect(newestKey, turn?.reply?.length, turn?.tools?.size) {
        if (newestKey == null) return@LaunchedEffect
        // A new newest message, or a reply's new text: the list is at the newest only if the reader was there, so the new item stays in view.
        if (!shouldAutoScroll(contentChanged = true, nearBottom = atNewest, forceScroll = forceScroll)) return@LaunchedEffect
        forceScroll = false
        atNewest = true
        listState.scrollToItem(0)
    }

    Column(modifier = if (pinned) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        SectionTitle("チャット")
        Card(modifier = if (pinned) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth().then(if (pinned) Modifier.fillMaxHeight() else Modifier)) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = if (pinned) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(CHAT_COMPACT_LIST_HEIGHT_DP.dp),
                ) {
                    items(rows.asReversed(), key = { it.key }) { row ->
                        when (row) {
                            is ChatListRow.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            is ChatListRow.Note -> SelectableText(row.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            is ChatListRow.Message -> ChatMessageRow(row.msg)
                            is ChatListRow.Tool -> SelectableText(
                                row.line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                            )
                            // The open proposals (the server returns them only to an operator who may confirm them; never to the AI or a viewer).
                            is ChatListRow.Action -> ChatActionCard(
                                action = row.action,
                                enabled = !actionBusy && !ApiSession.expired,
                                onConfirm = { pendingAction = row.action },
                                onDismiss = { runChatAction(row.action, confirm = false) },
                            )
                            is ChatListRow.Notice -> SelectableText(
                                row.notice.text,
                                color = if (row.notice.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 入力エリア
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { SelectableText("指示を入力") },
                        placeholder = { SelectableText("指示を入力...") },
                        singleLine = true,
                        enabled = !ApiSession.expired,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = inputText.isNotBlank() && !ApiSession.expired,
                        onClick = {
                            if (submitBlocked(false, ApiSession.expired)) return@Button
                            // Stop the reply in progress: closing the stream makes the server store the reply so far and stop.
                            turnJob?.cancel()
                            sendBusy = true
                            sendNotice = null
                            replyNotice = null
                            val trimmed = inputText.trim()
                            inputText = ""
                            turn = ChatTurn(userText = trimmed)
                            // The user's own send always shows the new message, even when the reader was up in the history.
                            forceScroll = true
                            turnJob = scope.launch {
                                var chatOk = false
                                var replyFailure: String? = null
                                try {
                                    try {
                                        ApiClient.streamChatReply(projectId, trimmed) { event ->
                                            when (event) {
                                                is ChatStreamEvent.User -> chatOk = true
                                                is ChatStreamEvent.Delta -> turn = turn?.let { it.copy(reply = it.reply + event.text) }
                                                is ChatStreamEvent.Tool -> turn = turn?.let { it.copy(tools = chatToolLines(it.tools, event)) }
                                                is ChatStreamEvent.Done -> Unit
                                                is ChatStreamEvent.Failed -> replyFailure = event.code
                                            }
                                        }
                                    } catch (e: ApiException) {
                                        // 404: the server has no reply service. The message is stored as before, without a reply.
                                        if (e.status != 404) throw e
                                        ApiClient.sendChatMessage(projectId, trimmed)
                                        chatOk = true
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    if (!chatOk) sendNotice = ActionNotice("チャットの保存に失敗しました", ok = false)
                                    else replyFailure = "NETWORK"
                                }
                                replyFailure?.let { code -> replyNotice = ActionNotice(chatStreamErrorText(code), ok = false) }
                                // No Task is submitted from the chat: the reply is the model's, and a Task is created elsewhere.
                                // The stored history first, then the turn's text goes: no gap in between.
                                val fresh = try {
                                    loadChatSession(projectId)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                if (fresh != null) reloaded = fresh
                                turn = null
                                sendBusy = false
                            }
                        },
                    ) {
                        SelectableText("送信")
                    }
                }
                replyNotice?.let { n ->
                    SelectableText(
                        n.text,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                sendNotice?.let { n ->
                    SelectableText(
                        n.text,
                        color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        pendingAction?.let { action ->
            ReasonDialog(
                title = CHAT_ACTION_CONFIRM_TITLE,
                message = chatActionConfirmMessage(action.label, action.code),
                submitLabel = chatActionConfirmButtonText(action.code),
                reasonLabel = null,
                busy = actionBusy,
                errorText = null,
                onSubmit = { runChatAction(action, confirm = true) },
                onDismiss = { if (!actionBusy) pendingAction = null },
            )
        }
    }
}

/** A send in progress: the user's text, the assistant's reply as it has streamed so far, and the lines of its tool calls. */
private data class ChatTurn(val userText: String, val reply: String = "", val tools: List<Pair<String, ChatStreamEvent.Tool>> = emptyList())

/** One row of the chat's message list, in display order. [key] is unique within the list. */
private sealed interface ChatListRow {
    val key: String

    data class Loading(override val key: String = "loading") : ChatListRow
    data class Note(override val key: String = "note", val text: String) : ChatListRow
    data class Message(override val key: String, val msg: ApiChatMessage) : ChatListRow
    data class Tool(override val key: String, val line: String) : ChatListRow
    data class Action(override val key: String, val action: ApiChatAction) : ChatListRow
    data class Notice(override val key: String = "notice", val notice: ActionNotice) : ChatListRow
}

/**
 * The rows of the message list: the saved history (or a placeholder while it loads), then the turn in progress, then the open proposals and
 * the last notice. A turn's tool lines are fixed text (「order を確認中…」), not stored.
 */
private fun chatListRows(projectId: String, session: ApiChatSession?, turn: ChatTurn?, actionNotice: ActionNotice?): List<ChatListRow> = buildList {
    when {
        session == null -> add(ChatListRow.Loading())
        session.messages.isEmpty() && turn == null -> add(ChatListRow.Note(text = "メッセージはありません。"))
        else -> session.messages.forEach { add(ChatListRow.Message("m${it.id}", it)) }
    }
    turn?.let { t ->
        // A refresh may already store the turn's user message: it is drawn once, from the history.
        val last = session?.messages?.lastOrNull()
        if (!isTurnEchoedInHistory(last?.role, last?.content, t.userText)) {
            add(ChatListRow.Message("turn-user", ApiChatMessage(id = 0, sessionId = projectId, role = "user", content = t.userText, createdAt = "")))
        }
        add(
            ChatListRow.Message(
                "turn-reply",
                ApiChatMessage(id = 0, sessionId = projectId, role = "assistant", content = t.reply.ifEmpty { "返信を生成中…" }, createdAt = ""),
            ),
        )
        t.tools.forEachIndexed { i, (line, _) -> add(ChatListRow.Tool("tool-$i", line)) }
    }
    session?.actions?.forEach { add(ChatListRow.Action("action-${it.actionId}", it)) }
    actionNotice?.let { add(ChatListRow.Notice(notice = it)) }
}

/**
 * The history on screen: the one read after the last turn, or the latest background refresh, whichever has the newer messages.
 * Taking only the turn's read would hide every later refresh (new messages from other sessions, proposals resolved elsewhere).
 */
private fun latestChatSession(reloaded: ApiChatSession?, refreshed: ApiChatSession?): ApiChatSession? {
    if (reloaded == null) return refreshed
    if (refreshed == null) return reloaded
    val reloadedNewer = isNewerChatHistory(
        candidateCount = reloaded.messageCount,
        candidateLastId = reloaded.messages.lastOrNull()?.id ?: 0L,
        currentCount = refreshed.messageCount,
        currentLastId = refreshed.messages.lastOrNull()?.id ?: 0L,
    )
    return if (reloadedNewer) reloaded else refreshed
}

/**
 * One proposal's card. The label is the server's fixed text. Confirm opens the dialog (the caller); 「提案を閉じる」 only hides the proposal.
 * An approval shows its Task's title as AI-written text; a member proposal shows the member; an AI proposal says it is the AI's (ADR 0023 追補 5).
 */
@Composable
private fun ChatActionCard(action: ApiChatAction, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            SelectableText("提案: ${action.label}", style = MaterialTheme.typography.bodyMedium)
            if (action.origin == "AI") {
                SelectableText(CHAT_ACTION_AI_ORIGIN_CAPTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action.member?.let { m ->
                SelectableText(chatActionMemberText(m.assigneeId, m.displayName, m.role), style = MaterialTheme.typography.bodySmall)
            }
            action.detail?.let { detail ->
                Spacer(Modifier.height(4.dp))
                SelectableText(detail.reasonText, style = MaterialTheme.typography.bodySmall)
                SelectableText(CHAT_ACTION_TASK_TITLE_CAPTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectableText(detail.taskTitle, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = enabled, onClick = onConfirm) { SelectableText(chatActionConfirmButtonText(action.code)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = enabled, onClick = onDismiss) { SelectableText(CHAT_ACTION_DISMISS_BUTTON_TEXT) }
            }
        }
    }
}

/** 1件のチャットメッセージの表示。roleによってスタイルを変える。 */
@Composable
private fun ChatMessageRow(msg: ApiChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SelectableText(
                if (isUser) "あなた" else "kivra",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            SelectableText(msg.content, style = MaterialTheme.typography.bodyMedium)
        }
        SelectableText(msg.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** チャットセッションの読み込み。セッションがなければnull（UIは「メッセージはありません」を表示）。 */
internal suspend fun loadChatSession(projectId: String): ApiChatSession {
    return try {
        ApiClient.getChatSession(projectId)
    } catch (e: ApiException) {
        // 404: セッション未作成。空のセッションを返す
        if (e.status == 404) {
            ApiChatSession(
                sessionId = projectId,
                title = "Chat",
                updatedAt = "",
                messages = emptyList(),
                messageCount = 0,
            )
        } else {
            throw e
        }
    }
}
