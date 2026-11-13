package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.OperatorAction
import net.kigawa.kivra.ui.budgetPathLabel
import net.kigawa.kivra.ui.budgetSummaryText
import net.kigawa.kivra.ui.moneyText
import net.kigawa.kivra.ui.countText
import net.kigawa.kivra.ui.assigneeStateText
import net.kigawa.kivra.ui.clipText
import net.kigawa.kivra.ui.reservationLabel
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiBudget
import net.kigawa.kivra.ui.api.ApiUnpricedReservation

/**
 * Budget screen: Budget display.
 */
@Composable
fun BudgetScreen() {
    // 解除の後に増やして、予算を読み直させる。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    var target by remember { mutableStateOf<ApiUnpricedReservation?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    val gate = rememberOperatorWriteGate()
    val perms = LocalCallerPermissions.current

    // 予算。読み直しの間は前の値を残す。見せない利用者は欄ごと出さない。
    val loaded = rememberLoad(Unit, reloadKey) { BudgetRead(loadBudgetOrHidden()) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SelectableText("予算", style = MaterialTheme.typography.headlineSmall)
        notice?.let { n ->
            Spacer(modifier = Modifier.height(8.dp))
            SelectableText(n.text, color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))

        loaded.error?.let { SelectableText("エラー: $it", color = MaterialTheme.colorScheme.error) }
        val b = loaded.value?.budget
        if (b == null) {
            // 読み込み中だけスピナー。見せない利用者(401 / 403 / 404)は何も出さない。
            if (loaded.value == null && loaded.error == null) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            val limit = b.limitValue
            val used = b.usedValue
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SelectableText("月: ${b.period}（JST の暦月。上限は月ごとに数えます）", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    SelectableText("今月の使用額（確定と予約中の合計）", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = if (limit != null && used != null && limit > 0) {
                            (used / limit).coerceIn(0.0, 1.0).toFloat()
                        } else {
                            0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 超過は文字でも示す(色だけに頼らない)。旧 UI の budget-bar-text と同じ。
                    val over = b.remainingJpy.toDoubleOrNull()?.let { it < 0 } == true
                    SelectableText(
                        budgetSummaryText(b),
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    SelectableText("確定(API): ${moneyText(b.paths.api.committedJpy)} / 確定(CLI): ${moneyText(b.paths.cli.committedJpy)}")
                    SelectableText("未確定の予約: ${b.unpricedCount} 件")
                }
            }
            BudgetFxNotice()
            Spacer(modifier = Modifier.height(12.dp))
            UnpricedReservationsSection(
                b,
                mayRelease = perms.can(GatedOperation.ACKNOWLEDGE_BUDGET),
                onRelease = { target = it; dialogError = null },
            )
        }
    }

    // 解除ダイアログ。成功なら閉じて予算を読み直す。失敗はダイアログ内に出し、開いたままにする。
    target?.let { row ->
        BudgetAcknowledgeDialog(
            row = row,
            busy = gate.busy,
            errorText = dialogError,
            onSubmit = { actual, evidence, reason ->
                dialogError = null
                gate.send(
                    action = OperatorAction.ACKNOWLEDGE_BUDGET,
                    call = {
                        ApiClient.acknowledgeBudget(
                            scopeId = row.scopeId,
                            period = row.period,
                            reservationKey = row.reservationKey,
                            actualAmountJpy = actual,
                            evidence = evidence,
                            reason = reason,
                        )
                    },
                    onSuccess = { alreadyDone ->
                        target = null
                        notice = ActionNotice(
                            if (alreadyDone) "既に解除済みでした（変更はありません）。" else "解除しました（監査ログに記録されました）。",
                            ok = true,
                        )
                        reloadKey++
                    },
                    onFailure = { dialogError = it },
                )
            },
            onDismiss = {
                if (!gate.busy) {
                    target = null
                    dialogError = null
                }
            },
        )
    }
}

/**
 * 未確定の予約の一覧(旧 UI の予算カードと同じ内容)。各行の「解除…」で [onRelease] を呼ぶ。
 * サーバーの一覧は上限件数まで。残りがあれば件数の注記を出す。[mayRelease] が false なら「解除…」を出さない(BUDGET_OVERRIDE が要る)。
 */
@Composable
private fun UnpricedReservationsSection(
    budget: ApiBudget,
    mayRelease: Boolean,
    onRelease: (ApiUnpricedReservation) -> Unit,
) {
    SelectableText("未確定の予約", style = MaterialTheme.typography.titleMedium)
    SelectableText(
        if (budget.unpricedCount == 0L) {
            "未確定の予約はありません。"
        } else {
            "未確定の予約: ${budget.unpricedCount} 件。1 件でも残っている間は、新しい予約（CLI の claim を含む）が UNKNOWN_COST で止まります。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    budget.unpriced.forEach { row ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                SelectableText("${reservationLabel(row.reservationKey)}（${budgetPathLabel(row.path)}）", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                SelectableText("金額 ${row.amountJpy} 円、期間 ${row.period}、状態 ${row.state}", style = MaterialTheme.typography.bodySmall)
                SelectableText("理由: ${BUDGET_REASON_TEXT[row.reason] ?: row.reason}", style = MaterialTheme.typography.bodySmall)
                if (mayRelease) OutlinedButton(onClick = { onRelease(row) }) { SelectableText("解除…") }
            }
        }
    }
    if (budget.unpricedCount > budget.unpriced.size) {
        SelectableText(
            "一覧は ${budget.unpriced.size} 件までです。残りは runbook §6.13 の SQL で確認してください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 未確定の理由の表示文言(旧 UI の BUDGET_REASON と同じ)。 */
private val BUDGET_REASON_TEXT = mapOf(
    "COST_NOT_ESTABLISHED" to "費用が確定しない（予約額のまま計上。実額の確認が必要）",
    "TTL_ELAPSED" to "期限切れ（保守的に予約額で計上。請求が無かったかもしれない）",
)
