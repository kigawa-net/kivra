package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.BACKFILL_DRY_DONE_MESSAGE
import net.kigawa.kivra.ui.BACKFILL_RUN_DONE_MESSAGE
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.NavItem
import net.kigawa.kivra.ui.ProjectLinks
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
import net.kigawa.kivra.ui.api.ApiChatAction
import net.kigawa.kivra.ui.api.ApiChatMessage
import net.kigawa.kivra.ui.CHAT_ACTION_CONFIRM_TITLE
import net.kigawa.kivra.ui.WriteFailure
import net.kigawa.kivra.ui.CHAT_ACTION_DISMISS_BUTTON_TEXT
import net.kigawa.kivra.ui.CHAT_ACTION_TASK_TITLE_CAPTION
import net.kigawa.kivra.ui.chatActionConfirmButtonText
import net.kigawa.kivra.ui.chatActionConfirmMessage
import net.kigawa.kivra.ui.chatActionFailureText
import net.kigawa.kivra.ui.chatActionResultText
import net.kigawa.kivra.ui.writeFailureOf
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
import net.kigawa.kivra.ui.IdempotencyKeys
import net.kigawa.kivra.ui.SubmitKind
import net.kigawa.kivra.ui.SubmitResult
import net.kigawa.kivra.ui.taskSuccessText
import net.kigawa.kivra.ui.validatedTaskTitle

/**
 * プロジェクトのダッシュボード(`#/projects/<id>/dashboard`、旧 UI の project-dash): 状態別の件数、最近の Run、費用、最近の活動。
 * scope が ALL(運用者)のときだけ、リポジトリの登録と既存データの振り分けを出す。読み直しは共通の更新に従う。
 * 読み込みの失敗は固定の文言(サーバーの本文は出さない)。
 */
@Composable
fun ProjectDashboardScreen(
    projectId: String,
    onNavigate: (Route) -> Unit,
    links: List<NavItem> = emptyList(),
    approvalsBadge: String? = null,
) {
    val loaded = rememberLoad(projectId) { loadProjectDashboard(projectId) }
    DetailPage {
        LoadedView(loaded) { d ->
            ProjectDashboardBody(projectId, d, onNavigate, links, approvalsBadge)
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
    links: List<NavItem>,
    approvalsBadge: String?,
) {
    val operator = showsProjectOperator(d.scope)
    Text(d.project.name, style = MaterialTheme.typography.headlineSmall)
    d.project.description?.takeIf { it.isNotBlank() }?.let { description ->
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    Text(projectScopeText(d.scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    // このプロジェクトの項目への入口(サイドナビからはここへ移した)。
    ProjectLinks(links, approvalsBadge, Route.ProjectPage(projectId, NavDestination.Dashboard), onNavigate)
    Spacer(Modifier.height(8.dp))


    // --- Run 関連：状態別件数 + 最近の Run ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Run の状態別件数")
            Text(projectTotalText(d.tasks.total))
            val rows = projectStateRows(d.tasks.byState)
            if (rows.isEmpty()) {
                Text("Run はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEach { (code, count) ->
                    Text("${projectRunStateText(code)}：$count 件", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))

            SectionTitle("最近の Run")
            if (d.recentRuns.isEmpty()) {
                Text("Run はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                d.recentRuns.forEach { run ->
                    ProjectRunRow(run, showCreator = operator, onClick = { onNavigate(Route.RunDetail(run.runId)) })
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // --- 費用 ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("費用（この Run に紐づく分）")
            Text(PROJECT_SPEND_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow("確定", yenText(d.spend.committedJpy))
                }
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow("予約中", yenText(d.spend.reservedJpy))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow("見積もりで計上（確定待ち）", yenText(d.spend.lapsedJpy))
                }
                Column(modifier = Modifier.weight(1f)) {
                    DetailRow("見積もりで計上（金額の確認待ち）", yenText(d.spend.unpricedJpy))
                }
            }
            DetailRow("合計", yenText(d.spend.totalJpy))
        }
    }
    Spacer(Modifier.height(8.dp))

    // --- 最近の活動 ---
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("最近の活動")
            if (d.activity.isEmpty()) {
                Text("最近の活動はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                d.activity.forEach { event: ApiProjectActivity ->
                    Text(projectActivityText(event), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // プロジェクトごとのチャット（V53）。kivra Agentへの指示投入用。
    ProjectChatSection(projectId)

    if (operator) {
        // 今月の予算は、プロジェクトの「予算」のページにある(設定と解除もそこで行う)。
        TextButton(onClick = { onNavigate(Route.ProjectPage(projectId, NavDestination.ProjectBudget)) }) {
            Text("今月の予算を見る（運用者のみ）")
        }
        ProjectOperatorSection(projectId)
        ProjectDrainSection(projectId, d.project.name, onNavigate)
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
            Text("空にして削除（運用者のみ）", style = MaterialTheme.typography.titleMedium)
            Text(
                "このプロジェクトの行（Task、Order、記憶、Run、担当、repo）を別のプロジェクトへ移して空にし、削除できるようにします。" +
                    "データは消しません。メンバーと予算の上限は、このプロジェクトから外れます。まず確認（dry-run）をしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LoadedView(projects) { list ->
                val choices = drainTargetChoices(list, projectId)
                if (choices.isEmpty()) {
                    Text(DRAIN_NO_TARGET_MESSAGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("移し先", style = MaterialTheme.typography.labelLarge)
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
                            Text(p.name)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = targetId != null && !busy && !ApiSession.expired, onClick = { dryRun() }) {
                            Text("確認（dry-run）")
                        }
                        Button(
                            enabled = canRunDrain(dry, targetId, busy) && !ApiSession.expired,
                            onClick = {
                                drainError = null
                                drainConfirm = true
                            },
                        ) {
                            Text("空にして移す…")
                        }
                    }
                }
            }
            dry?.let { report ->
                Spacer(Modifier.height(4.dp))
                Text("移す件数（確認の結果）", style = MaterialTheme.typography.labelLarge)
                val lines = drainCountLines(report.counts)
                if (lines.isEmpty()) {
                    Text("移す行はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            shown?.let { report ->
                Spacer(Modifier.height(4.dp))
                Text("移した件数", style = MaterialTheme.typography.labelLarge)
                drainCountLines(report.counts).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            notice?.let { n ->
                Text(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = canDeleteEmptied(emptied, busy) && !ApiSession.expired,
                onClick = {
                    deleteError = null
                    deleteConfirm = true
                },
            ) {
                Text("削除…")
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
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
}

/** 一件の Run の行。作成者は運用者だけに出す(旧 UI の runRow と同じ)。 */
@Composable
private fun ProjectRunRow(run: ApiRun, showCreator: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(run.title, style = MaterialTheme.typography.titleSmall)
            Text(
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
            Text("リポジトリの対応（運用者のみ）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            LoadedList(repos, emptyText = REPO_EMPTY_MESSAGE) { list ->
                list.forEach { repo ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(repo.repoKey, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            enabled = !repoBusy && !ApiSession.expired,
                            onClick = {
                                repoRemoveError = null
                                repoRemoveTarget = repo.repoKey
                            },
                        ) {
                            Text("登録を外す…")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { Text("repo（owner/name）") },
                    placeholder = { Text("owner/name") },
                    singleLine = true,
                    enabled = !repoBusy && !ApiSession.expired,
                    isError = repoInput.length > REPO_KEY_INPUT_MAX,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !repoBusy && !ApiSession.expired, onClick = { addRepo() }) { Text("追加") }
            }
            repoNotice?.let { n ->
                Text(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            ProjectMembersSection(projectId)
            Spacer(Modifier.height(12.dp))
            Text("既存データの振り分け", style = MaterialTheme.typography.titleMedium)
            Text(
                "プロジェクトが未設定の既存データだけを、repo の対応で振り分けます（全プロジェクトに効きます）。" +
                    "すでにプロジェクトを持つ行は変わりません。対応がない行は未設定のままです。まず確認（dry-run）をしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !backfillBusy && !ApiSession.expired, onClick = { dryRun() }) {
                    Text("確認（dry-run）")
                }
                Button(
                    enabled = canRunBackfill(backfillDry, backfillBusy) && !ApiSession.expired,
                    onClick = {
                        confirmError = null
                        confirmOpen = true
                    },
                ) {
                    Text("振り分けを実行…")
                }
            }
            backfillNotice?.let { n ->
                Text(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            backfillShown?.tables?.forEach { table ->
                Text(backfillTableLine(table), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
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
 * チャットメッセージはDBに保存され、同時にTaskとしてkivra Agentへ指示が投入される。
 */
@Composable
private fun ProjectChatSection(projectId: String) {
    val scope = rememberCoroutineScope()
    // チャットの読み直し。提案の確定・却下の後に増やして、提案の一覧を読み直す(ADR 0023)。
    var chatReload by remember { mutableStateOf(0) }
    val chat = rememberLoad(projectId, reload = chatReload) { loadChatSession(projectId) }
    var inputText by remember { mutableStateOf("") }
    var sendBusy by remember { mutableStateOf(false) }
    var sendNotice by remember { mutableStateOf<ActionNotice?>(null) }
    val keys = remember { IdempotencyKeys() }
    // 提案(ADR 0023)。確定は確認ダイアログの後、却下は即時。結果はサーバーの固定コードから文言にする。
    var pendingAction by remember { mutableStateOf<ApiChatAction?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionNotice by remember { mutableStateOf<ActionNotice?>(null) }

    fun runChatAction(action: ApiChatAction, confirm: Boolean) {
        actionBusy = true
        actionNotice = null
        scope.launch {
            try {
                val result = if (confirm) {
                    ApiClient.confirmChatAction(projectId, action.actionId)
                } else {
                    ApiClient.dismissChatAction(projectId, action.actionId)
                }
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
                // Read the chat again: a proposal that was resolved, or expired by the server, leaves the list.
                chatReload++
            }
        }
    }

    SectionTitle("チャット（kivra Agentへの指示）")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // メッセージ一覧
            when {
                chat.value == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                chat.value!!.messages.isEmpty() -> Text("メッセージはありません。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        chat.value!!.messages.forEach { msg: ApiChatMessage ->
                            ChatMessageRow(msg)
                        }
                    }
                }
            }
            // 提案のカード(サーバーが出したものだけ。AI や閲覧者には届かない)
            chat.value?.actions?.forEach { action ->
                ChatActionCard(
                    action = action,
                    enabled = !actionBusy && !ApiSession.expired,
                    onConfirm = { pendingAction = action },
                    onDismiss = { runChatAction(action, confirm = false) },
                )
            }
            actionNotice?.let { n ->
                Text(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            // 入力エリア
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("指示を入力") },
                    placeholder = { Text("指示を入力...") },
                    singleLine = true,
                    enabled = !sendBusy && !ApiSession.expired,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = inputText.isNotBlank() && !sendBusy && !ApiSession.expired,
                    onClick = {
                        if (submitBlocked(sendBusy, ApiSession.expired)) return@Button
                        sendBusy = true
                        sendNotice = null
                        val trimmed = inputText.trim()
                        inputText = ""
                        scope.launch {
                            var chatOk = false
                            try {
                                ApiClient.sendChatMessage(projectId, trimmed)
                                chatOk = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                sendNotice = ActionNotice("チャットの保存に失敗しました", ok = false)
                            }
                            try {
                                val title = validatedTaskTitle(trimmed) ?: trimmed
                                val key = keys.keyFor(title)
                                val result = ApiClient.submitTask(title, null, SubmitKind.SELF_DEV, projectId, key)
                                keys.rotate()
                                sendNotice = if (chatOk) {
                                    ActionNotice(taskSuccessText(result, SubmitKind.SELF_DEV), ok = result !is SubmitResult.Failed)
                                } else {
                                    ActionNotice("Taskとして投入しました（チャットの保存は失敗しました）", ok = true)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!chatOk) {
                                    sendNotice = ActionNotice("送信に失敗しました", ok = false)
                                }
                            } finally {
                                sendBusy = false
                            }
                        }
                    },
                ) {
                    Text("送信")
                }
            }
            sendNotice?.let { n ->
                Text(
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

/**
 * 1件の提案のカード。ラベルはサーバーの固定文言。確定は確認の後(呼び出し側)、「提案を閉じる」は提案を隠すだけで Task は変えない(ADR 0023)。
 * 承認の提案は、対象の Task 名を AI が書いた文章として、プレーンテキストで出す(指示としては扱わない)。
 */
@Composable
private fun ChatActionCard(action: ApiChatAction, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("提案: ${action.label}", style = MaterialTheme.typography.bodyMedium)
            action.detail?.let { detail ->
                Spacer(Modifier.height(4.dp))
                Text(detail.reasonText, style = MaterialTheme.typography.bodySmall)
                Text(
                    CHAT_ACTION_TASK_TITLE_CAPTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(detail.taskTitle, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = enabled, onClick = onConfirm) { Text(chatActionConfirmButtonText(action.code)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = enabled, onClick = onDismiss) { Text(CHAT_ACTION_DISMISS_BUTTON_TEXT) }
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
            Text(
                if (isUser) "あなた" else "kivra",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            )
            Text(msg.content, style = MaterialTheme.typography.bodyMedium)
        }
        Text(msg.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
