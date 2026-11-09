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
import net.kigawa.kivra.ui.isUnassigned
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.AutopilotAvailability
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalAutopilotAvailability
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.ORDER_TREE_MAX_INDENT
import net.kigawa.kivra.ui.ORDER_REASONS
import net.kigawa.kivra.ui.OrderTreeRow
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.STOP_REASONS
import net.kigawa.kivra.ui.displayTime
import net.kigawa.kivra.ui.orderCodeText
import net.kigawa.kivra.ui.orderStateText
import net.kigawa.kivra.ui.orderTreeOf
import net.kigawa.kivra.ui.requesterKindText
import net.kigawa.kivra.ui.yenText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiOrder
import net.kigawa.kivra.ui.api.ApiStopRequest
import net.kigawa.kivra.ui.autopilotAvailabilityOf
import net.kigawa.kivra.ui.isValidStopDigest
import net.kigawa.kivra.ui.showsOrderCreateForm
import net.kigawa.kivra.ui.writeFailureOf
import net.kigawa.kivra.ui.writeFailureText

/**
 * Orders screen: Order list (旧 UI の order 一覧と同じ木構造。子は親の直後に一段深く並ぶ)。
 * 自律運転が無効のときは一覧ごと出さない(旧 UI の orders-section と同じ。読みにも行かない)。
 */
@Composable
fun OrdersScreen(projectId: String, onNavigate: (Route) -> Unit) {
    val availability = LocalAutopilotAvailability.current
    val autopilotOn = availability == AutopilotAvailability.ENABLED
    // 作成の後に増やして、一覧を読み直させる。
    var listReload by remember { mutableStateOf(0) }
    val loaded = rememberLoad(Triple(projectId, autopilotOn, listReload)) {
        if (!autopilotOn) return@rememberLoad emptyList()
        // 選んだプロジェクトの order だけ(別名ルート)。全体のときは全部。
        ApiClient.getOrders(projectId)
    }
    val perms = LocalCallerPermissions.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("order", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (isUnassigned(projectId)) {
            UnassignedCreateNote()
            Spacer(modifier = Modifier.height(16.dp))
        } else if (showsOrderCreateForm(availability, perms)) {
            OrderCreateCard(projectId = projectId, onChanged = { listReload += 1 })
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (autopilotOn) Column(modifier = Modifier.weight(1f)) {
            LoadedList(loaded, emptyText = "orderが見つかりません") { orders ->
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(orderTreeOf(orders), key = { it.order.orderId }) { row ->
                        OrderCard(row, onClick = { onNavigate(Route.OrderDetail(row.order.orderId)) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** order の一行(旧 UI の ordRow)。字下げは木の深さ(旧 UI の order-level-N と同じく、上限の段まで)。 */
@Composable
private fun OrderCard(row: OrderTreeRow, onClick: () -> Unit) {
    val order = row.order
    val st = orderStateText(order.state)
    val indent = (row.level.coerceAtMost(ORDER_TREE_MAX_INDENT) * 16).dp
    val reason = order.stateReason?.takeIf { it.isNotEmpty() }?.let { orderCodeText(ORDER_REASONS, it) }
    Card(modifier = Modifier.fillMaxWidth().padding(start = 8.dp + indent, end = 8.dp, top = 8.dp, bottom = 8.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("order ${order.orderId}", style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = { }, label = { Text("状態: ${st.label}") })
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("深さ: ${order.depth} | 子の数: ${order.childCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("使用: ${yenText(order.spentJpy)} / 予算: ${yenText(order.orderBudgetJpy)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("周: ${order.cyclesStarted} / ${order.maxCycles} | 連続して失敗した周: ${order.consecutiveFailedCycles}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("期限: ${displayTime(order.expiresAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reason != null) {
                Text("理由: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun ApprovalsScreen(projectId: String) {
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
    // 承認の一覧は自律運転が有効なときだけ(旧 UI の approval-section と同じ)。
    val autopilotOn = LocalAutopilotAvailability.current == AutopilotAvailability.ENABLED
    val loaded = rememberLoad(Pair(projectId, autopilotOn), reloadKey) {
        if (!autopilotOn) return@rememberLoad emptyList()
        // 選んだプロジェクトの停止要求だけ(別名ルート。停止要求は order の project に属する)。全体のときは全部。
        ApiClient.getApprovals(projectId)
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

        if (autopilotOn) LoadedList(loaded, emptyText = "承認待ちの項目はありません") { approvals ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(approvals, key = { it.requestId }) { approval ->
                    val digestOk = isValidStopDigest(approval.digest)
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("対象の order: ${approval.orderId}", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("理由: ${orderCodeText(STOP_REASONS, approval.reasonCode)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("要求した主体: ${requesterKindText(approval.requesterKind)} | 要求の時刻: ${displayTime(approval.createdAt)} | 期限: ${displayTime(approval.expiresAt)}",
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
                "対象の order: ${target.orderId}（理由: ${orderCodeText(STOP_REASONS, target.reasonCode)}）",
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
