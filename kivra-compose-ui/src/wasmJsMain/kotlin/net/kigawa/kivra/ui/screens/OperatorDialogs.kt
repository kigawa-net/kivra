package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.BUDGET_ACTUAL_HINT
import net.kigawa.kivra.ui.BUDGET_TEXT_MAX
import net.kigawa.kivra.ui.ORDER_EXTEND_CONFIRM
import net.kigawa.kivra.ui.ORDER_EXTEND_HINT
import net.kigawa.kivra.ui.ORDER_STOP_CONFIRM
import net.kigawa.kivra.ui.OperatorAction
import net.kigawa.kivra.ui.REQUESTABLE_STOP_REASONS
import net.kigawa.kivra.ui.ReasonCheck
import net.kigawa.kivra.ui.EXTEND_DAYS_MAX
import net.kigawa.kivra.ui.budgetPathLabel
import net.kigawa.kivra.ui.checkReason
import net.kigawa.kivra.ui.displayTime
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiOrder
import net.kigawa.kivra.ui.api.ApiUnpricedReservation
import net.kigawa.kivra.ui.isExtendFormOk
import net.kigawa.kivra.ui.isValidAddCycles
import net.kigawa.kivra.ui.isValidBudgetActual
import net.kigawa.kivra.ui.isValidExtendDays
import net.kigawa.kivra.ui.addCyclesOrNull
import net.kigawa.kivra.ui.extendDaysOrNull
import net.kigawa.kivra.ui.operatorWriteFailureText
import net.kigawa.kivra.ui.reasonLength
import net.kigawa.kivra.ui.reservationLabel
import net.kigawa.kivra.ui.writeFailureOf

/**
 * 運用操作の送信ゲート。二重送信を防ぐ([busy] は送信の前に立てる)。成功なら [onSuccess] に結果を、
 * 失敗なら操作ごとの日本語の文言を [onFailure] に渡す。ダイアログは成功時に呼び出し側が閉じ、失敗時は開いたままにする。
 */
internal class OperatorWriteGate(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    fun <T> send(
        action: OperatorAction,
        call: suspend () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = try {
                Result.success(call())
            } catch (e: CancellationException) {
                busy = false
                throw e
            } catch (e: Exception) {
                Result.failure<T>(e)
            }
            busy = false
            outcome.fold(
                onSuccess = onSuccess,
                onFailure = { e ->
                    val api = e as? ApiException
                    onFailure(operatorWriteFailureText(action, writeFailureOf(api?.status), api?.code))
                },
            )
        }
    }
}

@Composable
internal fun rememberOperatorWriteGate(): OperatorWriteGate {
    val scope = rememberCoroutineScope()
    return remember(scope) { OperatorWriteGate(scope) }
}

private const val BUDGET_TEXT_BAD = "1〜500 文字で、制御文字は使えません。"
private const val BUDGET_ACTUAL_BAD = "実額は円の数字（小数は 6 桁まで、12 桁まで）で入力してください。空欄は「実額不明」です。"

/**
 * 未確定の予約の解除ダイアログ(2 段: 入力 → 確認)。根拠・理由は [checkReason] の規則(1 文字以上・上限・制御文字なし)、
 * 実額は任意([isValidBudgetActual])。空欄の実額は送らない(実額不明)。送信は [onSubmit] に trim 済みの値を渡す。
 * 送信中は [busy] の間、すべての操作を止める。失敗時は [errorText] をダイアログ内に出し、ダイアログは閉じない。
 */
@Composable
internal fun BudgetAcknowledgeDialog(
    row: ApiUnpricedReservation,
    busy: Boolean,
    errorText: String?,
    onSubmit: (actual: String?, evidence: String, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 開いている間は定期更新を止める(入力中の内容を読み直しで消さないため)。
    HoldAutoRefreshWhileShown()
    var actualInput by remember { mutableStateOf("") }
    var evidenceInput by remember { mutableStateOf("") }
    var reasonInput by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    val actualOk = isValidBudgetActual(actualInput)
    val evidenceCheck = checkReason(evidenceInput, BUDGET_TEXT_MAX)
    val reasonCheck = checkReason(reasonInput, BUDGET_TEXT_MAX)
    val formOk = actualOk && evidenceCheck is ReasonCheck.Valid && reasonCheck is ReasonCheck.Valid

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("未確定の予約を解除") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${reservationLabel(row.reservationKey)}（${budgetPathLabel(row.path)}、期間 ${row.period}、" +
                        "計上額 ${row.amountJpy} 円）を解除します。実額が分かれば入力してください。" +
                        "実額が分からなければ空欄のままにします（計上額が残ります）。",
                )
                Spacer(Modifier.height(12.dp))
                if (!confirming) {
                    OutlinedTextField(
                        value = actualInput,
                        onValueChange = { actualInput = it },
                        label = { Text("実額（円・任意）") },
                        enabled = !busy,
                        isError = !actualOk,
                        supportingText = { Text(if (actualOk) BUDGET_ACTUAL_HINT else BUDGET_ACTUAL_BAD) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = evidenceInput,
                        onValueChange = { evidenceInput = it },
                        label = { Text("根拠") },
                        enabled = !busy,
                        isError = evidenceCheck !is ReasonCheck.Valid && evidenceInput.isNotEmpty(),
                        supportingText = { Text(textSupport(evidenceCheck, evidenceInput)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        label = { Text("理由") },
                        enabled = !busy,
                        isError = reasonCheck !is ReasonCheck.Valid && reasonInput.isNotEmpty(),
                        supportingText = { Text(textSupport(reasonCheck, reasonInput)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(confirmText(actualInput.trim(), row.amountJpy))
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (!confirming) {
                TextButton(enabled = !busy && formOk, onClick = { confirming = true }) { Text("次へ") }
            } else {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val evidence = (evidenceCheck as? ReasonCheck.Valid)?.reason ?: return@TextButton
                        val reason = (reasonCheck as? ReasonCheck.Valid)?.reason ?: return@TextButton
                        val actual = actualInput.trim().takeIf { it.isNotEmpty() }
                        onSubmit(actual, evidence, reason)
                    },
                ) { Text("解除する") }
            }
        },
        dismissButton = {
            if (!confirming) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") }
            } else {
                TextButton(enabled = !busy, onClick = { confirming = false }) { Text("戻る") }
            }
        },
    )
}

private fun textSupport(check: ReasonCheck, raw: String): String =
    if (check is ReasonCheck.Valid || raw.isEmpty()) "${reasonLength(raw)} / $BUDGET_TEXT_MAX 文字" else BUDGET_TEXT_BAD

private fun confirmText(actual: String, amountJpy: String): String =
    if (actual.isEmpty()) {
        "実額は不明のままにします。計上額 $amountJpy 円が残ります。"
    } else {
        "実額として $actual 円を記録します（0 は「請求されていないことを確認済み」の意味です）。"
    }

/**
 * order の延長ダイアログ。追加する周数と延長する日数(どちらか一方か両方。両方空は不可)。現在の開始済みの周・上限・期限を示す。
 * 形式の検査は [isExtendFormOk]、上限はサーバーが判定する。送信中は [busy] の間、すべての操作を止める。
 * 失敗時は [errorText] をダイアログ内に出し、ダイアログは閉じない。成功時は呼び出し側が閉じる。
 */
@Composable
internal fun OrderExtendDialog(
    order: ApiOrder,
    busy: Boolean,
    errorText: String?,
    onSubmit: (addCycles: Int?, extendDays: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 開いている間は定期更新を止める(入力中の内容を読み直しで消さないため)。
    HoldAutoRefreshWhileShown()
    var addInput by remember { mutableStateOf("") }
    var daysInput by remember { mutableStateOf("") }
    val addOk = isValidAddCycles(addInput)
    val daysOk = isValidExtendDays(daysInput)
    val formOk = isExtendFormOk(addInput, daysInput)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("order の延長") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(ORDER_EXTEND_CONFIRM)
                Spacer(Modifier.height(4.dp))
                Text(ORDER_EXTEND_HINT)
                Spacer(Modifier.height(8.dp))
                DetailRow("開始した周", "${order.cyclesStarted} 周")
                DetailRow("現在の上限", "${order.maxCycles} 周")
                DetailRow("現在の期限", displayTime(order.expiresAt))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addInput,
                    onValueChange = { addInput = it },
                    label = { Text("追加する周数") },
                    enabled = !busy,
                    isError = !addOk,
                    supportingText = { Text(if (addOk) "空欄は追加しません（1 以上の整数）" else "1 以上の整数で入力してください") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = daysInput,
                    onValueChange = { daysInput = it },
                    label = { Text("延長する日数") },
                    enabled = !busy,
                    isError = !daysOk,
                    supportingText = {
                        Text(if (daysOk) "空欄は延長しません（1〜$EXTEND_DAYS_MAX 日）" else "1〜$EXTEND_DAYS_MAX の整数で入力してください")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && formOk,
                onClick = { onSubmit(addCyclesOrNull(addInput), extendDaysOrNull(daysInput)) },
            ) { Text("延長する") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/**
 * order の停止要求ダイアログ。理由は要求できる 4 種から一つ選ぶ(自由入力はない)。選んで「停止を要求する」を押すまで送らない。
 * 送信中は [busy] の間、すべての操作を止める。失敗時は [errorText] をダイアログ内に出し、ダイアログは閉じない。
 * 成功時は呼び出し側が閉じる。旧 UI の order-stop-dialog と同じ(確認文・理由の 4 種)。
 */
@Composable
internal fun OrderStopRequestDialog(
    orderId: String,
    busy: Boolean,
    errorText: String?,
    onSubmit: (reasonCode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 開いている間は定期更新を止める(選んだ理由を読み直しで消さないため)。
    HoldAutoRefreshWhileShown()
    var chosen by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("停止の要求") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(ORDER_STOP_CONFIRM)
                Spacer(Modifier.height(4.dp))
                Text("対象の order: $orderId")
                Spacer(Modifier.height(8.dp))
                REQUESTABLE_STOP_REASONS.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = chosen == code,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = { chosen = code },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == code, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && chosen != null,
                onClick = { chosen?.let(onSubmit) },
            ) { Text("停止を要求する") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
