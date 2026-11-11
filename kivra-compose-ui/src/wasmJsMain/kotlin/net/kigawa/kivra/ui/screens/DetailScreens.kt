package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GOAL_STOP_REASON_MAX
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.ORDER_EXTENDED_DONE
import net.kigawa.kivra.ui.ORDER_PAUSED_DONE
import net.kigawa.kivra.ui.ORDER_RESUMED_DONE
import net.kigawa.kivra.ui.ORDER_STOP_CANCELLED_DONE
import net.kigawa.kivra.ui.ORDER_STOP_REQUESTED_DONE
import net.kigawa.kivra.ui.OperatorAction
import net.kigawa.kivra.ui.RUN_RELEASE_REASON_MAX
import net.kigawa.kivra.ui.ReleaseKind
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.canCancelOrderStop
import net.kigawa.kivra.ui.canExtendOrder
import net.kigawa.kivra.ui.canPauseOrder
import net.kigawa.kivra.ui.canRequestOrderStop
import net.kigawa.kivra.ui.canResumeOrder
import net.kigawa.kivra.ui.canStopGoal
import net.kigawa.kivra.ui.releaseButtonLabel
import net.kigawa.kivra.ui.releaseKindOf
import net.kigawa.kivra.ui.releaseTextsOf
import net.kigawa.kivra.ui.safePullRequestUrl
import net.kigawa.kivra.ui.showsExecutionSection
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiGoal
import net.kigawa.kivra.ui.api.ApiGoalDetail
import net.kigawa.kivra.ui.api.ApiOrder
import net.kigawa.kivra.ui.api.ApiOrderCycle
import net.kigawa.kivra.ui.api.ApiOrderDetail
import net.kigawa.kivra.ui.api.ApiRun
import net.kigawa.kivra.ui.api.ApiRunEvent
import net.kigawa.kivra.ui.ORDER_REASONS
import net.kigawa.kivra.ui.STOP_REASONS
import net.kigawa.kivra.ui.displayTime
import net.kigawa.kivra.ui.displayUtcTime
import net.kigawa.kivra.ui.goalReasonText
import net.kigawa.kivra.ui.goalStateText
import net.kigawa.kivra.ui.orderCodeText
import net.kigawa.kivra.ui.orderStateText
import net.kigawa.kivra.ui.pullRequestText
import net.kigawa.kivra.ui.requesterKindText
import net.kigawa.kivra.ui.runActionText
import net.kigawa.kivra.ui.runKindText
import net.kigawa.kivra.ui.runReasonText
import net.kigawa.kivra.ui.runStateText
import net.kigawa.kivra.ui.runTitleText
import net.kigawa.kivra.ui.yenText
import net.kigawa.kivra.ui.writeFailureOf
import net.kigawa.kivra.ui.writeFailureText

/*
 * 詳細画面。Run 詳細は UNKNOWN などの Run に運用者の解放を出す。Goal 詳細は承認待ちの Task に承認・見送りを、
 * 終端でない Goal に中止を出す。order 詳細は一時停止・再開と、停止要求・取り消しを出す(緊急停止系)。
 * 表示する項目は旧 UI(kivra-app/src/main/resources/ui/app.js)と同じ範囲に合わせる。
 * 時刻は API の文字列のまま表示する(旧 UI のローカル時刻変換は未移植)。
 */

/** Run 詳細: 基本情報、PR、イベント一覧。 */
@Composable
fun RunDetailScreen(runId: String, onNavigate: (Route) -> Unit) {
    // 解放の後に増やして、詳細を読み直させる。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val perms = LocalCallerPermissions.current
    val state = rememberLoad(runId, reloadKey) { ApiClient.getRun(runId) }
    DetailPage {
        notice?.let { n ->
            Text(n, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }
        LoadedView(state) { run ->
            Text(runTitleText(run.mode, run.title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val kind = runKindText(run.mode)
                    DetailRow("種別", kind.label + (kind.note?.let { "（$it）" } ?: ""))
                    val state = runStateText(run.state)
                    DetailRow("状態", "${state.label} — ${state.desc}")
                    DetailRow("実行", run.executed?.let { if (it) "実行した" else "実行していない" } ?: "—")
                    DetailRow("効果", run.effect)
                    DetailRow("理由", runReasonText(run.reasonCode).ifEmpty { "なし" })
                    DetailRow("プロジェクト", run.projectName ?: "未割り当て")
                    run.taskSource?.let { DetailRow("解決元（参照）", it) }
                    run.createdBy?.let { DetailRow("作成者", it) }
                    DetailRow("PR", pullRequestText(run.pullRequestRepository, run.pullRequestNumber))
                    DetailRow("Run ID", run.runId)
                    DetailRow("Task ID", run.taskId)
                    DetailRow("作成日時（ローカル）", displayTime(run.createdAt))
                    displayUtcTime(run.createdAt).takeIf { it.isNotEmpty() }?.let { DetailRow("作成日時（UTC）", it) }
                    DetailRow("更新日時（ローカル）", displayTime(run.updatedAt))
                }
            }
            Spacer(Modifier.height(12.dp))
            // 運用者の解放。UNKNOWN、または束縛のない PULL_REQUEST の Run だけ出す(旧 UI と同じ条件)。BUDGET_OVERRIDE の人だけ。
            releaseKindOf(run)?.takeIf { perms.can(GatedOperation.RELEASE_RUN) }?.let { kind ->
                RunReleaseSection(runId = run.runId, kind = kind, onReleased = { notice = it; reloadKey++ })
                Spacer(Modifier.height(12.dp))
            }
            PullRequestSection(run)
            // 実行の経過(自己開発の Run だけ。読み取りの定期更新。詳細を離れると止まる)。
            if (showsExecutionSection(run.mode, run.runId)) {
                Spacer(Modifier.height(12.dp))
                RunExecutionSection(run.runId)
            }
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

/**
 * 運用者の解放ボタンと理由入力のダイアログ。成功なら [onReleased] に操作結果の文言を渡す(詳細の読み直しは呼び出し側)。
 * 失敗はダイアログ内に出し、ダイアログは開いたままにする。
 */
@Composable
private fun RunReleaseSection(runId: String, kind: ReleaseKind, onReleased: (String) -> Unit) {
    val gate = rememberOperatorWriteGate()
    val texts = releaseTextsOf(kind)
    var open by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    OutlinedButton(onClick = { dialogError = null; open = true }) { Text(releaseButtonLabel(kind)) }
    if (open) {
        ReasonDialog(
            title = texts.title,
            message = texts.explain,
            submitLabel = "解放する",
            reasonLabel = "理由",
            busy = gate.busy,
            errorText = dialogError,
            maxCodePoints = RUN_RELEASE_REASON_MAX,
            onSubmit = { reason ->
                if (reason != null) {
                    dialogError = null
                    gate.send(
                        action = OperatorAction.RELEASE_RUN,
                        call = { ApiClient.releaseRun(runId, kind, reason) },
                        onSuccess = {
                            open = false
                            onReleased("解放しました（状態: CANCELLED）。")
                        },
                        onFailure = { dialogError = it },
                    )
                }
            },
            onDismiss = {
                if (!gate.busy) {
                    open = false
                    dialogError = null
                }
            },
        )
    }
}

/**
 * PR のリンク。PR が無い Run は「なし」。リンクは URL の形が GitHub の PR と合う時だけ出す([safePullRequestUrl]、旧 UI と同じ判定)。
 * 合わない URL は開かず、リポジトリと番号の行だけを出す。
 */
@Composable
private fun PullRequestSection(run: ApiRun) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("PR", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val repo = run.pullRequestRepository
            val number = run.pullRequestNumber
            val url = safePullRequestUrl(run.pullRequestUrl)
            if (repo == null && number == null && run.pullRequestUrl == null) {
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
    val reason = runReasonText(event.reasonCode).takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""
    Text(
        text = "${displayTime(event.occurredAt)}  ${runActionText(event.action)}$transition$reason  [${event.result}]",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** 開発目標(タスク)の詳細: 状態、対象リポジトリ、依存、関連 Run、本文。 */
@Composable
fun GoalDetailScreen(taskId: String, onNavigate: (Route) -> Unit) {
    // 承認・見送りの後に増やして、詳細を読み直させる。
    var reloadKey by remember { mutableStateOf(0) }
    val state = rememberLoad(taskId, reloadKey) { ApiClient.getGoal(taskId) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    val scope = rememberCoroutineScope()
    val stopGate = rememberOperatorWriteGate()
    val perms = LocalCallerPermissions.current
    var stopOpen by remember { mutableStateOf(false) }
    var stopError by remember { mutableStateOf<String?>(null) }

    // 承認・見送りを一つずつ送る。busy は送信の前に立てる(連打で二重に送らない)。version は読んだ値を送る。
    fun act(onSuccess: String, call: suspend () -> Unit) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                call()
                notice = ActionNotice(onSuccess, ok = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = ActionNotice(writeFailureText(writeFailureOf((e as? ApiException)?.status)), ok = false)
            } finally {
                busy = false
                reloadKey++
            }
        }
    }

    DetailPage {
        LoadedView(state) { detail ->
            val goal: ApiGoal = detail.task
            Text(goal.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val goalState = goalStateText(goal.state)
                    DetailRow("状態", "${goalState.label} — ${goalState.desc}")
                    goal.stateReason?.takeIf { it.isNotEmpty() }?.let { DetailRow("理由", goalReasonText(it)) }
                    DetailRow("対象リポジトリ", goal.targetRepo)
                    DetailRow("試行回数", goal.attempts.toString())
                    DetailRow("承認が必要", if (goal.requiresApproval) "はい" else "いいえ")
                    DetailRow("依存", if (goal.dependencies.isEmpty()) "なし" else goal.dependencies.joinToString())
                    DetailRow("Task ID", goal.taskId)
                    DetailRow("作成日時", displayTime(goal.createdAt))
                    DetailRow("更新日時", displayTime(goal.updatedAt))
                    if (goal.revision > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("計画の親タスクです。承認・見送りは子タスクごとに行います。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 承認待ちで、計画の親でない Task だけ承認・見送りを出す(旧 UI と同じ条件)。GOAL_WRITE の人だけ出す。
            if (goal.state == "PENDING_APPROVAL" && goal.revision == 0 && perms.can(GatedOperation.APPROVE_GOAL)) {
                notice?.let { n ->
                    Text(
                        n.text,
                        color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row {
                    Button(
                        enabled = !busy,
                        onClick = { act("タスクを承認しました（投入を待っています）。") { ApiClient.approveGoal(goal.taskId, goal.version) } },
                    ) { Text("このタスクを承認") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { act("タスクを見送りました。") { ApiClient.rejectGoal(goal.taskId, goal.version) } },
                    ) { Text("見送る") }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                notice?.let { n ->
                    Text(n.text, color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
            }
            // 中止は終端でない Goal だけ(旧 UI と同じ条件)。GOAL_STOP の人だけ。理由は必須で、送るだけで保存しない。
            if (canStopGoal(goal.state) && perms.can(GatedOperation.STOP_GOAL)) {
                OutlinedButton(onClick = { stopError = null; stopOpen = true }) { Text("中止…") }
                Spacer(Modifier.height(12.dp))
                if (stopOpen) {
                    ReasonDialog(
                        title = "開発目標の中止",
                        message = "この開発目標（タスク）を中止します。まだ投入していなければ見送りになります。" +
                            "作成済みの PR は閉じず、マージもしません（PR の扱いは人が決めます）。",
                        submitLabel = "中止する",
                        reasonLabel = "理由",
                        busy = stopGate.busy,
                        errorText = stopError,
                        maxCodePoints = GOAL_STOP_REASON_MAX,
                        onSubmit = { reason ->
                            if (reason != null) {
                                stopError = null
                                stopGate.send(
                                    action = OperatorAction.STOP_GOAL,
                                    call = { ApiClient.stopGoal(goal.taskId, goal.version, reason) },
                                    onSuccess = {
                                        stopOpen = false
                                        notice = ActionNotice("タスクを中止しました（作成済みの PR はそのままです）。", ok = true)
                                        reloadKey++
                                    },
                                    onFailure = { stopError = it },
                                )
                            }
                        },
                        onDismiss = {
                            if (!stopGate.busy) {
                                stopOpen = false
                                stopError = null
                            }
                        },
                    )
                }
            }
            // 計画の失敗で止まった Goal の再計画(旧 UI の「再計画する」)。GOAL_WRITE の人だけ。
            ReplanGoalAction(goal = goal, onChanged = { reloadKey++ })
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

/**
 * order 詳細: 状態、周、予算、親子、停止要求。題名と方針は旧 UI と同じくこの画面には出さない。
 * 運用操作: 一時停止・再開(ORDER_MANAGE)、停止要求・取り消し(STOP_REQUEST)。操作の可否は [canPauseOrder] などの純粋ロジック。
 * 成功したら詳細を読み直す。失敗は操作の下に固定の文言で出す(停止要求の失敗はダイアログ内に出し、ダイアログは開いたままにする)。
 */
@Composable
fun OrderDetailScreen(orderId: String, onNavigate: (Route) -> Unit) {
    // 操作の後に増やして、詳細を読み直させる。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    var stopOpen by remember { mutableStateOf(false) }
    var stopError by remember { mutableStateOf<String?>(null) }
    var extendOpen by remember { mutableStateOf(false) }
    var extendError by remember { mutableStateOf<String?>(null) }
    val perms = LocalCallerPermissions.current
    val gate = rememberOperatorWriteGate()
    val state = rememberLoad(orderId, reloadKey) { ApiClient.getOrder(orderId) }

    // 停止要求以外の操作を一つ送る。成否にかかわらず読み直す(旧 UI の ordAfterWrite と同じ)。
    val send: (OperatorAction, String, suspend () -> Unit) -> Unit = { action, doneText, call ->
        gate.send(
            action = action,
            call = call,
            onSuccess = {
                notice = ActionNotice(doneText, ok = true)
                reloadKey++
            },
            onFailure = {
                notice = ActionNotice(it, ok = false)
                reloadKey++
            },
        )
    }

    DetailPage {
        LoadedView(state) { detail ->
            val order: ApiOrder = detail.order
            val pending = detail.pendingStopRequest
            Text("order ${order.orderId}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val st = orderStateText(order.state)
                    DetailRow("状態", "${st.label} — ${st.desc}")
                    order.stateReason?.takeIf { it.isNotEmpty() }?.let { DetailRow("理由", orderCodeText(ORDER_REASONS, it)) }
                    DetailRow("深さ", order.depth.toString())
                    DetailRow("周", "開始 ${order.cyclesStarted} / 上限 ${order.maxCycles}")
                    DetailRow("連続して失敗した周", order.consecutiveFailedCycles.toString())
                    DetailRow("子 order 数", order.childCount.toString())
                    DetailRow("現在の開発目標", order.currentGoalId ?: "なし")
                    DetailRow("プロジェクト", order.projectId ?: "未割り当て")
                    DetailRow("有効期限", displayTime(order.expiresAt))
                    DetailRow("作成日時", displayTime(order.createdAt))
                    DetailRow("更新日時", displayTime(order.updatedAt))
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("予算(円)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    DetailRow("order 予算", yenText(order.orderBudgetJpy))
                    DetailRow("割り当て済み", yenText(order.allocatedJpy))
                    DetailRow("使用", yenText(order.spentJpy))
                    DetailRow("残り", yenText(order.remainingJpy))
                    DetailRow("1 周の予算", yenText(order.cycleBudgetJpy))
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
                        DetailRow("理由", orderCodeText(STOP_REASONS, pending.reasonCode))
                        DetailRow("要求した主体", requesterKindText(pending.requesterKind))
                        DetailRow("対象", if (pending.targetIsRoot) "order 全体(根)" else "子 order")
                        DetailRow("要求の時刻", displayTime(pending.createdAt))
                        DetailRow("有効期限", displayTime(pending.expiresAt))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 運用操作。ボタンは権限と状態で出し分ける(サーバーが最終判定し、403 / 409 は固定の文言で出す)。
            val showPause = perms.can(GatedOperation.MANAGE_ORDER) && canPauseOrder(order.state)
            val showResume = perms.can(GatedOperation.MANAGE_ORDER) && canResumeOrder(order.state, order.stateReason)
            val showRequest = perms.can(GatedOperation.REQUEST_ORDER_STOP) && canRequestOrderStop(order.state, pending != null)
            val showCancel = perms.can(GatedOperation.REQUEST_ORDER_STOP) && canCancelOrderStop(pending)
            // 延長はルートの order で、ACTIVE か PAUSED のときだけ(子・STOP_REQUESTED・STOPPED では出さない)。
            val showExtend = perms.can(GatedOperation.EXTEND_ORDER) && canExtendOrder(order.state, order.parentOrderId)
            notice?.let { n ->
                Text(n.text, color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (showPause || showResume || showExtend || showRequest || showCancel) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showPause) {
                        Button(enabled = !gate.busy, onClick = {
                            send(OperatorAction.PAUSE_ORDER, ORDER_PAUSED_DONE) { ApiClient.pauseOrder(order.orderId, order.version) }
                        }) { Text("一時停止") }
                    }
                    if (showResume) {
                        Button(enabled = !gate.busy, onClick = {
                            send(OperatorAction.RESUME_ORDER, ORDER_RESUMED_DONE) { ApiClient.resumeOrder(order.orderId, order.version) }
                        }) { Text("再開") }
                    }
                    if (showExtend) {
                        OutlinedButton(enabled = !gate.busy, onClick = { extendError = null; extendOpen = true }) { Text("延長…") }
                    }
                    if (showRequest) {
                        OutlinedButton(enabled = !gate.busy, onClick = { stopError = null; stopOpen = true }) { Text("停止を要求…") }
                    }
                    if (showCancel && pending != null) {
                        OutlinedButton(enabled = !gate.busy, onClick = {
                            send(OperatorAction.CANCEL_STOP, ORDER_STOP_CANCELLED_DONE) {
                                ApiClient.cancelOrderStopRequest(pending.requestId)
                            }
                        }) { Text("要求を取り消す") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (extendOpen && showExtend) {
                OrderExtendDialog(
                    order = order,
                    busy = gate.busy,
                    errorText = extendError,
                    onSubmit = { addCycles, extendDays ->
                        extendError = null
                        gate.send(
                            action = OperatorAction.EXTEND_ORDER,
                            call = { ApiClient.extendOrder(order.orderId, order.version, addCycles, extendDays) },
                            onSuccess = {
                                extendOpen = false
                                notice = ActionNotice(ORDER_EXTENDED_DONE, ok = true)
                                reloadKey++
                            },
                            onFailure = { extendError = it },
                        )
                    },
                    onDismiss = {
                        if (!gate.busy) {
                            extendOpen = false
                            extendError = null
                        }
                    },
                )
            }
            if (stopOpen && showRequest) {
                OrderStopRequestDialog(
                    orderId = order.orderId,
                    busy = gate.busy,
                    errorText = stopError,
                    onSubmit = { code ->
                        stopError = null
                        gate.send(
                            action = OperatorAction.REQUEST_STOP,
                            call = { ApiClient.requestOrderStop(order.orderId, order.version, code) },
                            onSuccess = {
                                stopOpen = false
                                notice = ActionNotice(ORDER_STOP_REQUESTED_DONE, ok = true)
                                reloadKey++
                            },
                            onFailure = { stopError = it },
                        )
                    },
                    onDismiss = {
                        if (!gate.busy) {
                            stopOpen = false
                            stopError = null
                        }
                    },
                )
            }
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
    val state = rememberLoad(orderId) { ApiClient.getOrderCycles(orderId) }
    DetailPage {
        Text("order $orderId の周", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        LoadedView(state) { cycles ->
            if (cycles.isEmpty()) {
                Text("周はまだ開始されていません")
            } else {
                cycles.forEach { cycle ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("周 ${cycle.cycleNo}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            val goalState = goalStateText(cycle.goalState)
                            DetailRow("開発目標の状態", goalState.label)
                            cycle.stateReason?.takeIf { it.isNotEmpty() }?.let { DetailRow("理由", goalReasonText(it)) }
                            DetailRow("開始日時", displayTime(cycle.createdAt))
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

/** 詳細画面の外枠: 縦スクロール付きの本文領域(自律運転の画面も使う)。 */
@Composable
internal fun DetailPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        content = content,
    )
}
