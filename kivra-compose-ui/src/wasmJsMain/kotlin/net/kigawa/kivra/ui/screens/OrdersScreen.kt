package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiOrder
import net.kigawa.kivra.ui.api.ApiStopRequest
import net.kigawa.kivra.ui.isValidStopDigest
import net.kigawa.kivra.ui.writeFailureOf
import net.kigawa.kivra.ui.writeFailureText

/**
 * Orders screen: Order list.
 */
@Composable
fun OrdersScreen(selectedProjectId: String? = null, onNavigate: (Route) -> Unit) {
    val loaded = rememberLoad(selectedProjectId) {
        val allOrders = ApiClient.getOrders()
        if (selectedProjectId != null) allOrders.filter { it.projectId == selectedProjectId } else allOrders
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("order", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        LoadedList(loaded, emptyText = "orderが見つかりません") { orders ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(orders, key = { it.orderId }) { order ->
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onNavigate(Route.OrderDetail(order.orderId)) }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("order ${order.orderId}", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("状態: ${order.state} | 深さ: ${order.depth} | 周: ${order.cyclesStarted} / ${order.maxCycles}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("使用: ${order.spentJpy} 円 / 予算: ${order.orderBudgetJpy} 円",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Approvals screen: Stop approval list (pending stop requests of the orders).
 *
 * 承認は確認ダイアログ(理由なし)を経て送る。却下は旧 UI と同じく確認なしで送る(理由は取らない)。
 * 書き込みの後は一覧を読み直す。プロジェクトの絞り込みは読み込みのたびに効く。
 */
@Composable
fun ApprovalsScreen(selectedProjectId: String? = null) {
    // 書き込みの後に増やして、一覧を読み直させる。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 承認の確認ダイアログの対象。null なら閉じている。
    var pendingApprove by remember { mutableStateOf<ApiStopRequest?>(null) }
    var approveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val perms = LocalCallerPermissions.current

    // 一覧。書き込みの後の読み直し(reloadKey)でも前の一覧を残す。
    val loaded = rememberLoad(selectedProjectId, reloadKey) {
        val allApprovals = ApiClient.getApprovals()
        if (selectedProjectId != null) {
            // A stop request has no project of its own: it belongs to its order's project.
            val projectOf = ApiClient.getOrders().associate { it.orderId to it.projectId }
            allApprovals.filter { projectOf[it.orderId] == selectedProjectId }
        } else {
            allApprovals
        }
    }

    // 送信を一つだけ走らせる。busy は送信の前に立てるので、連打しても二重に送られない。
    fun send(
        onSuccess: String,
        onFailure: (String) -> Unit,
        call: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                call()
                notice = ActionNotice(onSuccess, ok = true)
                pendingApprove = null
                approveError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = writeFailureText(writeFailureOf((e as? ApiException)?.status))
                notice = ActionNotice(failure, ok = false)
                onFailure(failure)
            } finally {
                busy = false
                reloadKey++
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("承認", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        notice?.let { n ->
            Text(
                n.text,
                color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LoadedList(loaded, emptyText = "承認待ちの項目はありません") { approvals ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(approvals, key = { it.requestId }) { approval ->
                    val digestOk = isValidStopDigest(approval.digest)
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("停止の承認待ち", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("対象の order: ${approval.orderId} | 理由: ${approval.reasonCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 承認・却下は STOP_DECIDE の人だけ出す(旧 UI と同じ。サーバーも 403 で拒否する)。
                            if (perms.can(GatedOperation.DECIDE_STOP_REQUEST)) Row(modifier = Modifier.padding(top = 8.dp)) {
                                Button(
                                    enabled = !busy && digestOk,
                                    onClick = {
                                        if (busy || !digestOk) return@Button
                                        approveError = null
                                        pendingApprove = approval
                                    },
                                ) {
                                    Text("承認")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        send(
                                            onSuccess = "停止の要求を却下しました。order は一時停止中になります（自動では再開しません）。",
                                            onFailure = { },
                                        ) {
                                            ApiClient.rejectStopRequest(approval.requestId)
                                        }
                                    },
                                ) {
                                    Text("拒否")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingApprove?.let { target ->
        ReasonDialog(
            title = "停止の承認",
            message = "この order を停止します。元に戻せません。\n" +
                "対象の order: ${target.orderId}（理由: ${target.reasonCode}）",
            submitLabel = "承認する",
            reasonLabel = null,
            busy = busy,
            errorText = approveError,
            onSubmit = { _ ->
                val digest = target.digest
                if (digest != null && isValidStopDigest(digest)) {
                    send(
                        onSuccess = "停止を承認しました。order は停止済みになりました（元に戻せません）。",
                        onFailure = { approveError = it },
                    ) {
                        ApiClient.approveStopRequest(target.requestId, digest)
                    }
                }
            },
            onDismiss = {
                if (!busy) {
                    pendingApprove = null
                    approveError = null
                }
            },
        )
    }
}
