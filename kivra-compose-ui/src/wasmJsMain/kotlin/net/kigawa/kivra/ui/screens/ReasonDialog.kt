package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.REASON_MAX_CODE_POINTS
import net.kigawa.kivra.ui.ReasonCheck
import net.kigawa.kivra.ui.checkReason
import net.kigawa.kivra.ui.reasonErrorText
import net.kigawa.kivra.ui.reasonLength

/**
 * 操作の確認ダイアログ(共通)。[reasonLabel] が null なら確認だけ(理由欄なし)。非 null なら理由を入力させ、
 * 送信時は trim 済みの理由を [onSubmit] に渡す。理由は [checkReason] の規則(1 文字以上・上限・制御文字なし)で検証する。
 *
 * 送信中は [busy] が true になり、送信ボタン・キャンセル・外側のタップ/Esc での閉じ方がすべて効かなくなる(二重送信の防止)。
 * 呼び出し側は送信を始める時に busy を true にし、結果が来たら false に戻す。失敗時は [errorText] をダイアログ内に出し、
 * ダイアログは閉じない(入力を失わないため)。成功時は呼び出し側が閉じる。
 */
@Composable
fun ReasonDialog(
    title: String,
    message: String,
    submitLabel: String,
    reasonLabel: String?,
    busy: Boolean,
    errorText: String?,
    onSubmit: (reason: String?) -> Unit,
    onDismiss: () -> Unit,
    maxCodePoints: Int = REASON_MAX_CODE_POINTS,
) {
    var reasonInput by remember { mutableStateOf("") }
    val check = checkReason(reasonInput, maxCodePoints)
    val reasonOk = reasonLabel == null || check is ReasonCheck.Valid
    val submitEnabled = !busy && reasonOk

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(message)
                if (reasonLabel != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        label = { Text(reasonLabel) },
                        enabled = !busy,
                        isError = reasonErrorText(check, maxCodePoints) != null,
                        supportingText = {
                            Text(
                                reasonErrorText(check, maxCodePoints)
                                    ?: "${reasonLength(reasonInput)} / $maxCodePoints 文字",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorText, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = submitEnabled,
                onClick = {
                    if (!submitEnabled) return@TextButton
                    onSubmit((check as? ReasonCheck.Valid)?.reason)
                },
            ) { Text(submitLabel) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
