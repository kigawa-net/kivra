package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiGoal
import net.kigawa.kivra.ui.api.ApiGoalDetail
import net.kigawa.kivra.ui.api.ApiOrder
import net.kigawa.kivra.ui.api.ApiOrderCycle
import net.kigawa.kivra.ui.api.ApiOrderDetail
import net.kigawa.kivra.ui.api.ApiRun
import net.kigawa.kivra.ui.api.ApiRunEvent

/*
 * 詳細画面(読み取り専用)。承認・解放・中止などの操作は含まない(別 PR)。
 * 表示する項目は旧 UI(kivra-app/src/main/resources/ui/app.js)と同じ範囲に合わせる。
 * 時刻は API の文字列のまま表示する(旧 UI のローカル時刻変換は未移植)。
 */

/** Run 詳細: 基本情報、PR、イベント一覧。 */
@Composable
fun RunDetailScreen(runId: String, onNavigate: (Route) -> Unit) {
    val state by produceState<LoadState<ApiRun>>(LoadState.Loading, runId) {
        value = LoadState.Loading
        value = loadState { ApiClient.getRun(runId) }
    }
    DetailPage {
        LoadStateView(state) { run ->
            Text(run.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    DetailRow("状態", run.state)
                    DetailRow("モード", run.mode)
                    DetailRow("実行", run.executed?.let { if (it) "実行した" else "実行していない" } ?: "—")
                    DetailRow("効果", run.effect)
                    DetailRow("理由", run.reasonCode ?: "なし")
                    DetailRow("プロジェクト", run.projectName ?: "未割り当て")
                    run.taskSource?.let { DetailRow("解決元(参照)", it) }
                    run.createdBy?.let { DetailRow("作成者", it) }
                    DetailRow("Run ID", run.runId)
                    DetailRow("Task ID", run.taskId)
                    DetailRow("作成日時", run.createdAt)
                    DetailRow("更新日時", run.updatedAt)
                }
            }
            Spacer(Modifier.height(12.dp))
            PullRequestSection(run)
            Spacer(Modifier.height(16.dp))
            Text("状態の経過", style = MaterialTheme.typography.titleMedium)
            val events = run.events
            if (events == null) {
                Text("イベントは含まれていません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (events.isEmpty()) {
                Text("記録がありません")
            } else {
                events.forEach { EventLine(it) }
            }
        }
    }
}

/** PR のリンク。PR が無い Run は「なし」。 */
@Composable
private fun PullRequestSection(run: ApiRun) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("PR", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val repo = run.pullRequestRepository
            val number = run.pullRequestNumber
            val url = run.pullRequestUrl
            if (repo == null && number == null && url == null) {
                Text("PR はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                DetailRow("リポジトリ", repo ?: "—")
                DetailRow("番号", number?.let { "#$it" } ?: "—")
                if (url != null) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { uriHandler.openUri(url) }) { Text("PR を開く") }
                }
            }
        }
    }
}

@Composable
private fun EventLine(event: ApiRunEvent) {
    val transition = event.toState?.let { " → $it" } ?: ""
    val reason = event.reasonCode?.let { "（$it）" } ?: ""
    Text(
        text = "${event.occurredAt}  ${event.action}$transition$reason  [${event.result}]",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** 開発目標(タスク)の詳細: 状態、対象リポジトリ、依存、関連 Run、本文。 */
@Composable
fun GoalDetailScreen(taskId: String, onNavigate: (Route) -> Unit) {
    val state by produceState<LoadState<ApiGoalDetail>>(LoadState.Loading, taskId) {
        value = LoadState.Loading
        value = loadState { ApiClient.getGoal(taskId) }
    }
    DetailPage {
        LoadStateView(state) { detail ->
            val goal: ApiGoal = detail.task
            Text(goal.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    DetailRow("状態", goal.state + (goal.stateReason?.let { "（$it）" } ?: ""))
                    DetailRow("対象リポジトリ", goal.targetRepo)
                    DetailRow("試行回数", goal.attempts.toString())
                    DetailRow("承認が必要", if (goal.requiresApproval) "はい" else "いいえ")
                    DetailRow("依存", if (goal.dependencies.isEmpty()) "なし" else goal.dependencies.joinToString())
                    DetailRow("Task ID", goal.taskId)
                    DetailRow("作成日時", goal.createdAt)
                    DetailRow("更新日時", goal.updatedAt)
                    if (goal.revision > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("計画の親タスクです。承認・見送りは子タスクごとに行います。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            goal.runId?.let { runId ->
                OutlinedButton(onClick = { onNavigate(Route.RunDetail(runId)) }) { Text("関連する Run を開く") }
                Spacer(Modifier.height(12.dp))
            }
            Text("本文", style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail.body?.takeIf { it.isNotBlank() } ?: "（本文はありません）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** order 詳細: 状態、周、予算、親子、停止要求。題名と方針は旧 UI と同じくこの画面には出さない。 */
@Composable
fun OrderDetailScreen(orderId: String, onNavigate: (Route) -> Unit) {
    val state by produceState<LoadState<ApiOrderDetail>>(LoadState.Loading, orderId) {
        value = LoadState.Loading
        value = loadState { ApiClient.getOrder(orderId) }
    }
    DetailPage {
        LoadStateView(state) { detail ->
            val order: ApiOrder = detail.order
            Text("order ${order.orderId}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    DetailRow("状態", order.state + (order.stateReason?.let { "（$it）" } ?: ""))
                    DetailRow("深さ", order.depth.toString())
                    DetailRow("周", "開始 ${order.cyclesStarted} / 上限 ${order.maxCycles}")
                    DetailRow("連続して失敗した周", order.consecutiveFailedCycles.toString())
                    DetailRow("子 order 数", order.childCount.toString())
                    DetailRow("現在の開発目標", order.currentGoalId ?: "なし")
                    DetailRow("プロジェクト", order.projectId ?: "未割り当て")
                    DetailRow("有効期限", order.expiresAt)
                    DetailRow("作成日時", order.createdAt)
                    DetailRow("更新日時", order.updatedAt)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("予算(円)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    DetailRow("order 予算", order.orderBudgetJpy.toString())
                    DetailRow("割り当て済み", order.allocatedJpy.toString())
                    DetailRow("使用", order.spentJpy.toString())
                    DetailRow("残り", order.remainingJpy.toString())
                    DetailRow("1 周の予算", order.cycleBudgetJpy.toString())
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("停止の要求", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    val pending = detail.pendingStopRequest
                    if (pending == null) {
                        Text("なし")
                    } else {
                        DetailRow("状態", pending.state)
                        DetailRow("理由", pending.reasonCode)
                        DetailRow("対象", if (pending.targetIsRoot) "order 全体(根)" else "子 order")
                        DetailRow("有効期限", pending.expiresAt)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                order.parentOrderId?.let { parent ->
                    OutlinedButton(onClick = { onNavigate(Route.OrderDetail(parent)) }) { Text("親の order") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onNavigate(Route.OrderCycles(order.orderId)) }) { Text("周の一覧") }
                order.currentGoalId?.let { goalId ->
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onNavigate(Route.GoalDetail(goalId)) }) { Text("現在の開発目標") }
                }
            }
        }
    }
}

/** order の周の一覧(周番号順)。周ごとの開発目標へ遷移できる。 */
@Composable
fun OrderCyclesScreen(orderId: String, onNavigate: (Route) -> Unit) {
    val state by produceState<LoadState<List<ApiOrderCycle>>>(LoadState.Loading, orderId) {
        value = LoadState.Loading
        value = loadState { ApiClient.getOrderCycles(orderId) }
    }
    DetailPage {
        Text("order $orderId の周", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        LoadStateView(state) { cycles ->
            if (cycles.isEmpty()) {
                Text("周はまだ開始されていません")
            } else {
                cycles.forEach { cycle ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("周 ${cycle.cycleNo}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            DetailRow("開発目標の状態", cycle.goalState + (cycle.stateReason?.let { "（$it）" } ?: ""))
                            DetailRow("開始日時", cycle.createdAt)
                            OutlinedButton(onClick = { onNavigate(Route.GoalDetail(cycle.goalId)) }) {
                                Text("この周の開発目標を開く")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 詳細画面の外枠: 縦スクロール付きの本文領域。 */
@Composable
private fun DetailPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        content = content,
    )
}
