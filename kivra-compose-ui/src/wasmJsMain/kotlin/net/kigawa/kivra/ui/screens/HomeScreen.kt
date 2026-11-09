package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.LocalSelfDevAvailability
import net.kigawa.kivra.ui.UNASSIGNED_LABEL
import net.kigawa.kivra.ui.UNASSIGNED_PROJECT_ID
import net.kigawa.kivra.ui.isUnassigned
import net.kigawa.kivra.ui.landingPageOf
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiProject

/** ホームの 1 行の対象(本物のプロジェクト、または未分類)。 */
private data class HomeTarget(val id: String, val name: String)

/**
 * ホーム(全体): 全プロジェクト横断の要対応サマリ。件数は各プロジェクトの要約([loadProjectSummary])を足したもの。
 * 項目はプロジェクトごとに並べ、押すとそのプロジェクトの配下のページへ行く(承認・失敗/解放待ち・実行中・予算)。
 * 未分類(project_id が無い既存のデータ)の行は、プロジェクトが無くても出す。データが無いときは薄く出す。
 */
@Composable
fun HomeScreen(projects: List<ApiProject>, projectsError: String?, onNavigate: (Route) -> Unit) {
    val selfDev = LocalSelfDevAvailability.current
    val targets = projects.map { HomeTarget(it.projectId, it.name) } + HomeTarget(UNASSIGNED_PROJECT_ID, UNASSIGNED_LABEL)
    val summaries = rememberLoad(targets.map { it.id }) {
        targets.associate { it.id to loadProjectSummary(it.id) }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // 自己開発の有無(読めなかった時は「取得失敗」)。
        Text(selfDev.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        projectsError?.let { Text("プロジェクト一覧: $it", color = MaterialTheme.colorScheme.error) }
        if (projects.isEmpty() && projectsError == null) {
            Text("プロジェクトがありません。「プロジェクト」から作成してください。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LoadedView(summaries) { byId ->
            val all = targets.map { it to byId[it.id] }
            val approvals = all.sumOf { (_, s) -> s?.approvals ?: 0 }
            val attention = all.sumOf { (_, s) -> s?.attention ?: 0L }
            val running = all.sumOf { (_, s) -> s?.running ?: 0L }
            val overBudget = all.count { (_, s) -> s?.budget?.softExceeded == true }
            val needed = approvals + attention
            Text(
                if (needed == 0L) "対応が必要なものはありません" else "対応が必要: $needed 件",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            SummaryCard("承認待ち", "$approvals 件", approvals > 0)
            SummaryCard("失敗 / 要解放の Run", "$attention 件", attention > 0)
            SummaryCard("実行中の Run", "$running 件", false)
            SummaryCard("今月の予算の超過(警告)", "$overBudget 件", overBudget > 0)
            Spacer(Modifier.height(16.dp))
            Text("プロジェクト別", style = MaterialTheme.typography.titleSmall)
            // 要対応の多いプロジェクトを先に出す(同数は一覧の順。未分類は最後)。
            all.sortedByDescending { (_, s) -> (s?.approvals ?: 0) + (s?.attention ?: 0L) }.forEach { (target, s) ->
                ProjectHomeRow(target, s, onNavigate)
            }
        }
    }
}

/** 1 プロジェクトの行。件数のボタンは、そのプロジェクトの該当ページへ行く。読めなかった項目は「取得できません」。 */
@Composable
private fun ProjectHomeRow(target: HomeTarget, s: ProjectSummary?, onNavigate: (Route) -> Unit) {
    val unassigned = isUnassigned(target.id)
    // 未分類のデータが 0 件なら薄く出す(読めなかった時は薄くしない)。
    val faint = unassigned && s?.unassignedRows == 0
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (faint) 0.6f else 1f)) {
        Column(Modifier.padding(12.dp)) {
            Text(target.name, style = MaterialTheme.typography.titleSmall)
            if (unassigned) {
                Text(
                    "プロジェクトに属さない既存のデータ(作成はできません)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s == null) {
                Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            if (faint) {
                Text("未分類のデータはありません", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onNavigate(Route.ProjectPage(target.id, NavDestination.Approvals)) }) {
                    Text("承認 ${s.approvals?.toString() ?: "取得できません"}")
                }
                TextButton(onClick = { onNavigate(Route.ProjectPage(target.id, NavDestination.Tasks)) }) {
                    Text("失敗・解放待ち ${s.attention?.toString() ?: "取得できません"}")
                }
                TextButton(onClick = { onNavigate(Route.ProjectPage(target.id, NavDestination.Tasks)) }) {
                    Text("実行中 ${s.running?.toString() ?: "取得できません"}")
                }
                s.budget?.let { b ->
                    TextButton(onClick = { onNavigate(Route.ProjectPage(target.id, NavDestination.ProjectBudget)) }) {
                        Text(if (b.softExceeded) "予算: 超過の警告" else "予算")
                    }
                }
                TextButton(onClick = { onNavigate(Route.ProjectPage(target.id, landingPageOf(target.id))) }) {
                    Text("開く")
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, highlight: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (highlight) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
