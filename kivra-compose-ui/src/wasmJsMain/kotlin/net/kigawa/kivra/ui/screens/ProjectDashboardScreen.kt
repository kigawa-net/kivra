package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
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
import net.kigawa.kivra.ui.PROJECT_SPEND_NOTE
import net.kigawa.kivra.ui.REPO_ADDED_MESSAGE
import net.kigawa.kivra.ui.REPO_EMPTY_MESSAGE
import net.kigawa.kivra.ui.REPO_INVALID_MESSAGE
import net.kigawa.kivra.ui.REPO_REMOVED_MESSAGE
import net.kigawa.kivra.ui.REPO_KEY_INPUT_MAX
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiBackfillReport
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiProjectActivity
import net.kigawa.kivra.ui.api.ApiProjectBudget
import net.kigawa.kivra.ui.api.ApiProjectDashboard
import net.kigawa.kivra.ui.api.ApiRun
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.backfillExplain
import net.kigawa.kivra.ui.backfillFailureText
import net.kigawa.kivra.ui.backfillTableLine
import net.kigawa.kivra.ui.canRunBackfill
import net.kigawa.kivra.ui.isProjectBudgetBody
import net.kigawa.kivra.ui.isRepoKeyShape
import net.kigawa.kivra.ui.projectActivityText
import net.kigawa.kivra.ui.projectRepoFailureText
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
fun ProjectDashboardScreen(projectId: String, onNavigate: (Route) -> Unit) {
    // 予算の設定・解除の後に増やして、ダッシュボードを読み直させる。
    var reload by remember { mutableStateOf(0) }
    val loaded = rememberLoad(projectId, reload) { loadProjectDashboard(projectId) }
    DetailPage {
        LoadedView(loaded) { d ->
            ProjectDashboardBody(projectId, d, onNavigate, onBudgetChanged = { reload += 1 })
        }
    }
}

/**
 * ダッシュボードの読み取り。運用者の予算の形が合わなければ、ダッシュボード全体を失敗にする(旧 UI の判定と同じ。一部だけ出さない)。
 */
private suspend fun loadProjectDashboard(projectId: String): ApiProjectDashboard {
    val d = ApiClient.getProjectDashboard(projectId)
    d.budget?.let { if (!isProjectBudgetBody(it)) throw ApiException(null, "response is not the expected format") }
    return d
}

@Composable
private fun ProjectDashboardBody(projectId: String, d: ApiProjectDashboard, onNavigate: (Route) -> Unit, onBudgetChanged: () -> Unit) {
    val operator = showsProjectOperator(d.scope)
    Text(d.project.name, style = MaterialTheme.typography.headlineSmall)
    d.project.description?.takeIf { it.isNotBlank() }?.let { description ->
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    Text(projectScopeText(d.scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))

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
    Spacer(Modifier.height(12.dp))

    SectionTitle("最近の Run")
    if (d.recentRuns.isEmpty()) {
        Text("Run はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        d.recentRuns.forEach { run ->
            ProjectRunRow(run, showCreator = operator, onClick = { onNavigate(Route.RunDetail(run.runId)) })
        }
    }
    Spacer(Modifier.height(12.dp))

    SectionTitle("費用（この Run に紐づく分）")
    Text(PROJECT_SPEND_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    DetailRow("確定", yenText(d.spend.committedJpy))
    DetailRow("予約中", yenText(d.spend.reservedJpy))
    DetailRow("見積もりで計上（確定待ち）", yenText(d.spend.lapsedJpy))
    DetailRow("見積もりで計上（金額の確認待ち）", yenText(d.spend.unpricedJpy))
    DetailRow("合計", yenText(d.spend.totalJpy))
    Spacer(Modifier.height(12.dp))

    SectionTitle("最近の活動")
    if (d.activity.isEmpty()) {
        Text("最近の活動はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        d.activity.forEach { event: ApiProjectActivity ->
            Text(projectActivityText(event), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
    }

    if (operator) {
        Spacer(Modifier.height(16.dp))
        ProjectOperatorSection(projectId, d.budget, onBudgetChanged)
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
private fun ProjectOperatorSection(projectId: String, budget: ApiProjectBudget?, onBudgetChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 登録の変更の後に増やして、一覧を読み直させる。
    var repoReload by remember { mutableStateOf(0) }
    val repos = rememberLoad(projectId, repoReload) { ApiClient.getProjectRepos(projectId) }
    var repoInput by remember { mutableStateOf("") }
    var repoBusy by remember { mutableStateOf(false) }
    var repoNotice by remember { mutableStateOf<ActionNotice?>(null) }

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

    fun removeRepo(key: String) {
        if (submitBlocked(repoBusy, ApiSession.expired)) return
        repoBusy = true
        repoNotice = null
        scope.launch {
            var removed = false
            try {
                ApiClient.removeProjectRepo(projectId, key)
                removed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repoNotice = ActionNotice(projectRepoFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                repoBusy = false
            }
            if (removed) {
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
            if (budget != null) {
                ProjectBudgetSection(budget, onBudgetChanged)
                Spacer(Modifier.height(16.dp))
            }
            Text("リポジトリの対応（運用者のみ）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LoadedList(repos, emptyText = REPO_EMPTY_MESSAGE) { list ->
                list.forEach { repo ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(repo.repoKey, modifier = Modifier.weight(1f))
                        OutlinedButton(enabled = !repoBusy && !ApiSession.expired, onClick = { removeRepo(repo.repoKey) }) {
                            Text("登録を外す")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(16.dp))
            ProjectMembersSection(projectId)
            Spacer(Modifier.height(16.dp))
            Text("既存データの振り分け", style = MaterialTheme.typography.titleMedium)
            Text(
                "プロジェクトが未設定の既存データだけを、repo の対応で振り分けます（全プロジェクトに効きます）。" +
                    "すでにプロジェクトを持つ行は変わりません。対応がない行は未設定のままです。まず確認（dry-run）をしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
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
