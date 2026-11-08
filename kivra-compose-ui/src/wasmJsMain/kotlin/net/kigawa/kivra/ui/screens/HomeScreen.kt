package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.LocalSelfDevAvailability
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException

private data class HomeSummary(
    val approvals: Int,
    val failedRuns: Int,
    val activeRuns: Int,
    val budget: ApiBudget?,
)

private val FAILED_STATES = setOf("FAILED", "UNKNOWN") // UNKNOWN は運用者の解放待ち(ADR 0009)
private val TERMINAL_STATES = setOf("FAILED", "COMPLETED", "CANCELLED", "NOT_EXECUTED")

/** ホーム: 要対応(承認待ち・失敗 Run・予算)を既存 API の読み取りだけで束ねる。 */
@Composable
fun HomeScreen(projectId: String?, onNavigate: (NavDestination) -> Unit) {
    val loaded = rememberLoad(projectId) { loadHomeSummary(projectId) }
    val selfDev = LocalSelfDevAvailability.current

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // 自己開発の有無(読めなかった時は「取得失敗」)。SELF_DEV の選択は別の作業で、ここでは表示だけ。
        Text(selfDev.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LoadedView(loaded) { s ->
            run {
                val attention = s.approvals + s.failedRuns
                Text(
                    if (attention == 0) "対応が必要なものはありません" else "対応が必要: $attention 件",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                SummaryCard("承認待ち", "${s.approvals} 件", s.approvals > 0) { onNavigate(NavDestination.Approvals) }
                SummaryCard("失敗 / 要解放の Run", "${s.failedRuns} 件", s.failedRuns > 0) { onNavigate(NavDestination.Tasks) }
                SummaryCard("実行中の Run", "${s.activeRuns} 件", false) { onNavigate(NavDestination.Tasks) }
                s.budget?.let { b ->
                    val limit = b.limitValue
                    val used = b.usedValue
                    val ratio = if (limit != null && used != null && limit > 0) used / limit else 0.0
                    SummaryCard("予算(${b.period})", "${b.usedValue?.toLong() ?: "不明"} / ${b.limitJpy} 円", ratio >= 0.8) { onNavigate(NavDestination.Budget) }
                }
            }
        }
    }
}

/**
 * The count of pending approvals (stop requests of the orders) for the project. The approvals are answered per order, so the project
 * comes from the order. 503 (autopilot off) and 403 (not the root approver) mean there are none for this caller, as in the old UI,
 * which hides the page then. Any other failure is thrown.
 */
private suspend fun approvalsOf(projectId: String?): Int {
    val requests = unavailableAsEmpty { ApiClient.getApprovals() }
    if (projectId == null || requests.isEmpty()) return requests.size
    val projectOf = unavailableAsEmpty { ApiClient.getOrders() }.associate { it.orderId to it.projectId }
    return requests.count { projectOf[it.orderId] == projectId }
}

/** ホームの読み取り。予算は [loadBudgetOrHidden](見せない利用者は予算の欄を出さない)。失敗は投げる。 */
private suspend fun loadHomeSummary(projectId: String?): HomeSummary {
    val runs = ApiClient.getRuns().filter { projectId == null || it.projectId == projectId }
    return HomeSummary(
        approvals = approvalsOf(projectId),
        failedRuns = runs.count { it.state in FAILED_STATES },
        activeRuns = runs.count { it.state !in TERMINAL_STATES },
        budget = loadBudgetOrHidden(),
    )
}

private suspend fun <T> unavailableAsEmpty(load: suspend () -> List<T>): List<T> =
    try {
        load()
    } catch (e: ApiException) {
        if (e.status == 503 || e.status == 403) emptyList() else throw e
    }

@Composable
private fun SummaryCard(title: String, value: String, highlight: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
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
