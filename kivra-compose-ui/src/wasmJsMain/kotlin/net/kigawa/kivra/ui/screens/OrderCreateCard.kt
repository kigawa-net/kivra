package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.ORDER_BAD_MESSAGE
import net.kigawa.kivra.ui.ORDER_CREATED_MESSAGE
import net.kigawa.kivra.ui.ORDER_MAX_CYCLES_MESSAGE
import net.kigawa.kivra.ui.ORDER_NO_PROJECT_MESSAGE
import net.kigawa.kivra.ui.ORDER_POLICY_MAX
import net.kigawa.kivra.ui.ORDER_SENDING_MESSAGE
import net.kigawa.kivra.ui.ORDER_TITLE_MAX
import net.kigawa.kivra.ui.SESSION_EXPIRED_MESSAGE
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.codePointCount
import net.kigawa.kivra.ui.isProjectId
import net.kigawa.kivra.ui.orderCreateFailureText
import net.kigawa.kivra.ui.parseMaxCycles
import net.kigawa.kivra.ui.submitBlocked
import net.kigawa.kivra.ui.validatedOrderPolicy
import net.kigawa.kivra.ui.validatedOrderTitle

/**
 * order の作成フォーム(旧 UI の order-create)。ORDER_MANAGE の人で、自律運転が有効なときだけ出す([showsOrderCreateForm])。
 * 題名 1〜200、方針 1〜2000(コードポイント)、最大の周の数 1〜10(作成後は変えられない)、プロジェクト(必須)。
 * 冪等キーは送らない(サーバーの本文は厳密で、知らないフィールドは 400)。送信中は二重に送らない。
 * 失敗時は入力を残し、成功時は題名・方針・周の数を消す(プロジェクトは残す)。409 は一覧を読み直す(既存のルートを見せるため)。
 */
@Composable
internal fun OrderCreateCard(projectId: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("") }
    var maxCycles by remember { mutableStateOf("1") }
    var submitting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    val blocked = submitBlocked(submitting, ApiSession.expired)

    fun send() {
        if (submitBlocked(submitting, ApiSession.expired)) return
        val validTitle = validatedOrderTitle(title)
        val validPolicy = validatedOrderPolicy(policy)
        if (validTitle == null || validPolicy == null) {
            notice = ActionNotice(ORDER_BAD_MESSAGE, ok = false)
            return
        }
        val cycles = parseMaxCycles(maxCycles)
        if (cycles == null) {
            notice = ActionNotice(ORDER_MAX_CYCLES_MESSAGE, ok = false)
            return
        }
        submitting = true
        notice = ActionNotice(ORDER_SENDING_MESSAGE, ok = true)
        scope.launch {
            var created = false
            try {
                ApiClient.createOrder(validTitle, validPolicy, cycles, projectId)
                created = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val api = e as? ApiException
                notice = ActionNotice(orderCreateFailureText(api?.status, api?.code), ok = false)
                // 409 はルートの order が既にある。一覧を読み直して、今のルートを見せる。
                if (api?.status == 409) onChanged()
            } finally {
                submitting = false
            }
            if (created) {
                title = ""
                policy = ""
                maxCycles = "1"
                notice = ActionNotice(ORDER_CREATED_MESSAGE, ok = true)
                onChanged()
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("order を作る", style = MaterialTheme.typography.titleMedium)
            Text(
                "order の作成は、ルートの order を 1 件作る操作です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("題名（1〜200 文字）") },
                enabled = !blocked,
                singleLine = true,
                isError = codePointCount(title) > ORDER_TITLE_MAX,
                supportingText = { CounterText(codePointCount(title), ORDER_TITLE_MAX) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = policy,
                onValueChange = { policy = it },
                label = { Text("方針（1〜2000 文字）") },
                enabled = !blocked,
                isError = codePointCount(policy) > ORDER_POLICY_MAX,
                supportingText = { CounterText(codePointCount(policy), ORDER_POLICY_MAX) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = maxCycles,
                onValueChange = { maxCycles = it },
                label = { Text("最大の周の数（1〜10。作成後は変えられません。サーバーの上限を超えると拒否されます）") },
                enabled = !blocked,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProjectFixedLabel(projectId)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { send() },
                enabled = !blocked,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("order を作る")
            }
            if (ApiSession.expired) {
                Text(SESSION_EXPIRED_MESSAGE, color = MaterialTheme.colorScheme.error)
            }
            notice?.let { n ->
                Text(
                    n.text,
                    color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
