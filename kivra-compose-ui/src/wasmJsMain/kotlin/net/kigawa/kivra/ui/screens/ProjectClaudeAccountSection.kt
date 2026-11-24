package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ClaudeAccountChange
import net.kigawa.kivra.ui.SelectableText
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiProjectClaudeAccount
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.claudeAccountChange
import net.kigawa.kivra.ui.claudeAccountEmptyMessage
import net.kigawa.kivra.ui.claudeAccountWriteFailure
import net.kigawa.kivra.ui.currentClaudeAccountLabel
import net.kigawa.kivra.ui.submitBlocked

@Composable
internal fun ProjectSettingsScreen(projectId: String) {
    DetailPage {
        SelectableText("設定", style = MaterialTheme.typography.headlineSmall)
        key(projectId) { ProjectClaudeAccountSection(projectId) }
    }
}

private data class ClaudeAccountRead(val settings: ApiProjectClaudeAccount?)

@Composable
internal fun ProjectClaudeAccountSection(projectId: String) {
    var reload by remember { mutableStateOf(0) }
    var success by remember { mutableStateOf<String?>(null) }
    val loaded = key(reload) {
        rememberLoad(projectId) {
            try {
                ClaudeAccountRead(ApiClient.getProjectClaudeAccount(projectId))
            } catch (e: ApiException) {
                if (e.status != 403) throw e
                ClaudeAccountRead(null)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectableText("Claude アカウント", style = MaterialTheme.typography.titleMedium)
        success?.let { SelectableText(it, color = MaterialTheme.colorScheme.primary) }
        LoadedView(loaded) { read ->
            val settings = read.settings
            if (settings == null) {
                SelectableText(
                    "Claude アカウントの設定は運用者だけが見られます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ClaudeAccountControls(
                    projectId = projectId,
                    settings = settings,
                    loadFailed = loaded.error != null,
                    onConfirming = { success = null },
                    onChanged = { message ->
                        success = message
                        reload += 1
                    },
                )
            }
        }
    }
}

@Composable
private fun ClaudeAccountControls(
    projectId: String,
    settings: ApiProjectClaudeAccount,
    loadFailed: Boolean,
    onConfirming: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ClaudeAccountChange?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun openConfirmation(change: ClaudeAccountChange?) {
        if (change == null || loadFailed || submitBlocked(busy, ApiSession.expired)) return
        onConfirming()
        dialogError = null
        pending = change
    }

    fun confirm(change: ClaudeAccountChange) {
        if (submitBlocked(busy, ApiSession.expired)) return
        busy = true
        dialogError = null
        scope.launch {
            try {
                val message = when (change) {
                    is ClaudeAccountChange.Set -> {
                        ApiClient.putProjectClaudeAccount(projectId, change.account.id)
                        "Claude アカウントを設定しました。"
                    }
                    ClaudeAccountChange.Clear -> {
                        ApiClient.deleteProjectClaudeAccount(projectId)
                        "Claude アカウントを未設定に戻しました。"
                    }
                }
                pending = null
                onChanged(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialogError = claudeAccountWriteFailure((e as? ApiException)?.status)
            } finally {
                busy = false
            }
        }
    }

    val enabled = !submitBlocked(busy, ApiSession.expired) && !loadFailed
    DetailRow("現在の設定", settings.currentClaudeAccountLabel())
    val emptyMessage = settings.claudeAccountEmptyMessage()
    if (emptyMessage != null) {
        SelectableText(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        OptionPicker(
            label = "Claude アカウントを選ぶ",
            options = listOf(null to "選択してください") + settings.available.map { it.id to it.label },
            selected = settings.accountId,
            onSelect = { id -> if (id != null) openConfirmation(settings.claudeAccountChange(id)) },
            enabled = enabled,
        )
    }
    val clear = settings.claudeAccountChange(null)
    OutlinedButton(enabled = enabled && clear != null, onClick = { openConfirmation(clear) }) {
        SelectableText("未設定に戻す…")
    }

    val change = pending
    if (change != null) {
        val setting = change is ClaudeAccountChange.Set
        ReasonDialog(
            title = if (setting) "Claude アカウントを設定" else "Claude アカウントの設定を解除",
            message = when (change) {
                is ClaudeAccountChange.Set -> "このプロジェクトの Claude アカウントを「${change.account.label}」に設定します。"
                ClaudeAccountChange.Clear -> "このプロジェクトの Claude アカウントを未設定に戻します。"
            },
            submitLabel = if (setting) "設定する" else "未設定に戻す",
            reasonLabel = null,
            busy = busy,
            errorText = dialogError,
            onSubmit = { confirm(change) },
            onDismiss = { pending = null },
        )
    }
}
