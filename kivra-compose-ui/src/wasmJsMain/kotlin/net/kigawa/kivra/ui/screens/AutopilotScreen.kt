package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.AUTOPILOT_BREAKER_DONE
import net.kigawa.kivra.ui.AUTOPILOT_PAUSED_DONE
import net.kigawa.kivra.ui.AUTOPILOT_RESUMED_DONE
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.OperatorAction
import net.kigawa.kivra.ui.api.ApiAutopilot
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.autoMergeText
import net.kigawa.kivra.ui.autopilotOverallText
import net.kigawa.kivra.ui.autopilotToggleLabel
import net.kigawa.kivra.ui.breakerOpenText

/**
 * 自律運転の画面(`#/autopilot`、管理グループ)。状態の表示、一時停止・再開、サーキットブレーカーの手動再開。
 * 自律運転が無効(503 `AUTOPILOT_DISABLED`)か取得できないときは、状態の欄の代わりに固定の文言を出す(サイドナビの項目は出ない)。
 * 旧 UI の自律運転の節と同じく、操作に確認は挟まない。送信は OperatorWriteGate で二重送信を防ぎ、
 * 成功でも失敗でも状態を読み直す(旧 UI の refreshAutopilot と同じ)。
 */
@Composable
fun AutopilotScreen() {
    // 操作の後に増やして、状態を読み直させる。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }
    val perms = LocalCallerPermissions.current
    val gate = rememberOperatorWriteGate()
    val state = rememberLoad("autopilot", reloadKey) { ApiClient.getAutopilot() }

    DetailPage {
        notice?.let { n ->
            Text(n.text, color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        LoadedView(state) { status: ApiAutopilot ->
            Text("自律運転", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(autopilotOverallText(status.paused, status.runsToday, status.maxRunsPerDay))
                    Spacer(Modifier.height(4.dp))
                    Text(autoMergeText(status.autoMergePaused))
                    Spacer(Modifier.height(4.dp))
                    Text("連続失敗: ${status.consecutiveFailures} 件")
                    Spacer(Modifier.height(4.dp))
                    DetailRow("対象リポジトリ", status.targetRepo?.takeIf { it.isNotBlank() } ?: "（未設定）")
                    if (status.breakerOpen) {
                        Spacer(Modifier.height(8.dp))
                        Text(breakerOpenText(status.consecutiveFailures), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (perms.can(GatedOperation.MANAGE_AUTOPILOT)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !gate.busy,
                        onClick = {
                            val resume = status.paused
                            gate.send(
                                action = if (resume) OperatorAction.RESUME_AUTOPILOT else OperatorAction.PAUSE_AUTOPILOT,
                                call = { if (resume) ApiClient.resumeAutopilot() else ApiClient.pauseAutopilot() },
                                onSuccess = {
                                    notice = ActionNotice(if (resume) AUTOPILOT_RESUMED_DONE else AUTOPILOT_PAUSED_DONE, ok = true)
                                    reloadKey++
                                },
                                onFailure = {
                                    notice = ActionNotice(it, ok = false)
                                    reloadKey++
                                },
                            )
                        },
                    ) { Text(autopilotToggleLabel(status.paused)) }
                    if (status.breakerOpen) {
                        OutlinedButton(
                            enabled = !gate.busy,
                            onClick = {
                                gate.send(
                                    action = OperatorAction.RESET_BREAKER,
                                    call = { ApiClient.resetAutopilotBreaker() },
                                    onSuccess = {
                                        notice = ActionNotice(AUTOPILOT_BREAKER_DONE, ok = true)
                                        reloadKey++
                                    },
                                    onFailure = {
                                        notice = ActionNotice(it, ok = false)
                                        reloadKey++
                                    },
                                )
                            },
                        ) { Text("サーキットブレーカーを手動で再開") }
                    }
                }
            }
        }
    }
}
