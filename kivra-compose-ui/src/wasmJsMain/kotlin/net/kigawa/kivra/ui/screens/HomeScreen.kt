package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.NavDestination
import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiClient

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
    var summary by remember { mutableStateOf<HomeSummary?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(projectId) {
        loading = true
        val runs = ApiClient.getRuns().filter { projectId == null || it.projectId == projectId }
        val approvals = ApiClient.getApprovals().filter { projectId == null || it.projectId == projectId }
        summary = HomeSummary(
            approvals = approvals.size,
            failedRuns = runs.count { it.state in FAILED_STATES },
            activeRuns = runs.count { it.state !in TERMINAL_STATES },
            budget = ApiClient.getBudget(),
        )
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val s = summary
        when {
            loading -> CircularProgressIndicator()
            s == null -> Text("読み込めませんでした")
            else -> {
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
                    val ratio = if (b.limit > 0) b.used / b.limit else 0.0
                    SummaryCard("予算(${b.month})", "${b.used} / ${b.limit}", ratio >= 0.8) { onNavigate(NavDestination.Budget) }
                }
            }
        }
    }
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
