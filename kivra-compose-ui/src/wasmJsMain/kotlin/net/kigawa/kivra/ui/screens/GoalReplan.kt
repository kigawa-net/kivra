package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.REPLAN_REQUESTED_MESSAGE
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiGoal
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.canReplanGoal
import net.kigawa.kivra.ui.replanFailureText

/**
 * 「再計画する」(旧 UI の replanTask)。計画の失敗で止まった Goal(BLOCKED、理由が PLAN_*)だけ出し、GOAL_WRITE の人だけ押せる。
 * 成功したら [onChanged] で詳細を読み直す。結果の文言は、読み直しで出す条件が消えても残す。
 */
@Composable
internal fun ReplanGoalAction(goal: ApiGoal, onChanged: () -> Unit) {
    val perms = LocalCallerPermissions.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ActionNotice?>(null) }

    if (canReplanGoal(goal.state, goal.stateReason) && perms.can(GatedOperation.REPLAN_GOAL)) {
        OutlinedButton(
            enabled = !busy && !ApiSession.expired,
            onClick = {
                if (busy || ApiSession.expired) return@OutlinedButton
                busy = true
                notice = null
                scope.launch {
                    var done = false
                    try {
                        ApiClient.replanGoal(goal.taskId, goal.version)
                        done = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notice = ActionNotice(replanFailureText((e as? ApiException)?.status), ok = false)
                    } finally {
                        busy = false
                    }
                    if (done) {
                        notice = ActionNotice(REPLAN_REQUESTED_MESSAGE, ok = true)
                        onChanged()
                    }
                }
            },
        ) {
            Text("再計画する")
        }
    }
    notice?.let { n ->
        Text(
            n.text,
            color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
    }
}
