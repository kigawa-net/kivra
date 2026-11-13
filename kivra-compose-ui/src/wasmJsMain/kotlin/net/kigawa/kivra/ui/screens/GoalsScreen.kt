package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import net.kigawa.kivra.ui.isUnassigned
import net.kigawa.kivra.ui.AutopilotAvailability
import net.kigawa.kivra.ui.GOAL_BAD_MESSAGE
import net.kigawa.kivra.ui.GoalSignature
import net.kigawa.kivra.ui.LocalAutopilotAvailability
import net.kigawa.kivra.ui.IdempotencyKeys
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.PROJECT_REQUIRED_MESSAGE
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SESSION_EXPIRED_MESSAGE
import net.kigawa.kivra.ui.SubmitFailure
import net.kigawa.kivra.ui.SubmitResult
import net.kigawa.kivra.ui.TASK_BODY_MAX
import net.kigawa.kivra.ui.TASK_TITLE_MAX
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.autopilotAvailabilityOf
import net.kigawa.kivra.ui.goalReasonText
import net.kigawa.kivra.ui.goalStateText
import net.kigawa.kivra.ui.codePointCount
import net.kigawa.kivra.ui.goalFailureText
import net.kigawa.kivra.ui.goalSuccessText
import net.kigawa.kivra.ui.isProjectId
import net.kigawa.kivra.ui.showsGoalForm
import net.kigawa.kivra.ui.submitBlocked
import net.kigawa.kivra.ui.validatedBody
import net.kigawa.kivra.ui.validatedTaskTitle

/**
 * Goals screen (プロジェクトの中): Goal の投入フォーム(自律運転が有効で GOAL_WRITE があるときだけ)と、このプロジェクトの Goal 一覧。
 */
@Composable
fun GoalsScreen(projectId: String, onNavigate: (Route) -> Unit) {
    val perms = LocalCallerPermissions.current
    var listReload by remember { mutableStateOf(0) }
    var autopilotReload by remember { mutableStateOf(0) }

    // 一覧は自律運転が有効なときだけ(旧 UI の plans-section と同じ。無効なら一覧ごと出さず、読みにも行かない)。
    val autopilotOn = LocalAutopilotAvailability.current == AutopilotAvailability.ENABLED
    val loaded = rememberLoad(Pair(projectId, autopilotOn), reload = listReload) {
        if (autopilotOn) ApiClient.getGoals(projectId) else emptyList()
    }
    // 投入フォームを出すかの元になる、自律運転の有無と対象リポジトリ。読めない・無効なら何も出さない(旧 UI の goal-section と同じ)。
    val autopilot = rememberLoad(Unit, reload = autopilotReload) { readGoalAutopilot() }
    val read = autopilot.value ?: GoalAutopilot(AutopilotAvailability.LOADING, null)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SelectableText("開発目標", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (isUnassigned(projectId)) {
            UnassignedCreateNote()
            Spacer(modifier = Modifier.height(16.dp))
        } else if (showsGoalForm(read.availability, perms)) {
            GoalFormCard(
                projectId = projectId,
                targetRepo = read.targetRepo,
                onSubmitted = { listReload += 1 },
                onAutopilotChanged = { autopilotReload += 1 },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (autopilotOn) Column(modifier = Modifier.weight(1f)) {
            LoadedList(loaded, emptyText = "開発目標が見つかりません") { goals ->
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(goals) { goal ->
                        val st = goalStateText(goal.state)
                        val reason = goal.stateReason?.takeIf { it.isNotEmpty() }?.let { goalReasonText(it) }
                        Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onNavigate(Route.GoalDetail(goal.taskId)) }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                SelectableText(goal.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                SelectableText("状態: ${st.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (reason != null) {
                                    SelectableText("理由: $reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 自律運転の読み取り。取得できない・無効・403 は HIDDEN(何も出さない)。 */
private data class GoalAutopilot(val availability: AutopilotAvailability, val targetRepo: String?)

private suspend fun readGoalAutopilot(): GoalAutopilot =
    try {
        val status = ApiClient.getAutopilot()
        GoalAutopilot(autopilotAvailabilityOf(status.enabled), status.targetRepo)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GoalAutopilot(AutopilotAvailability.HIDDEN, null)
    }

/**
 * Goal の投稿フォーム(旧 UI の goal-form)。目的、背景・制約・受け入れ条件(任意)、送信。作成先はこの画面のプロジェクト([projectId]、選べない)。
 * 失敗時は入力を残し、成功時だけ目的と本文を消す。自律運転が無効と分かった時は [onAutopilotChanged] で読み直す。
 */
@Composable
private fun GoalFormCard(projectId: String, targetRepo: String?, onSubmitted: () -> Unit, onAutopilotChanged: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val keys = remember { IdempotencyKeys() }

    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val blocked = submitBlocked(submitting, ApiSession.expired)

    fun send() {
        if (submitBlocked(submitting, ApiSession.expired)) return
        val validTitle = validatedTaskTitle(title)
        val validBody = validatedBody(body)
        if (validTitle == null || validBody == null) {
            result = GOAL_BAD_MESSAGE to false
            return
        }
        val key = keys.keyFor(GoalSignature(validTitle, validBody, projectId))
        submitting = true
        result = "送信中です…" to true
        coroutineScope.launch {
            val outcome = try {
                ApiClient.submitGoal(validTitle, validBody.takeIf { it.isNotEmpty() }, projectId, key)
            } finally {
                submitting = false
            }
            when (outcome) {
                is SubmitResult.Created, is SubmitResult.Replayed -> {
                    keys.rotate()
                    title = ""
                    body = ""
                    result = goalSuccessText(outcome) to true
                    onSubmitted()
                }
                is SubmitResult.Failed -> {
                    // 409 は別の内容で使われたキー。作り直す。
                    if (outcome.failure == SubmitFailure.CONFLICT) keys.rotate()
                    // 503 は自律運転が無効。フォームを出すかを読み直す。
                    if (outcome.failure == SubmitFailure.DISABLED) onAutopilotChanged()
                    result = goalFailureText(outcome.failure) to false
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SelectableText(
                "kivra が Goal から開発タスクを作り、承認したタスクだけを Run として投入します。PR のマージは人が行います。" +
                    "対象リポジトリ: ${targetRepo ?: "（未設定）"}（画面からは変えられません）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { SelectableText("目的（1〜200 文字）") },
                enabled = !blocked,
                isError = codePointCount(title) > TASK_TITLE_MAX,
                supportingText = { CounterText(codePointCount(title), TASK_TITLE_MAX) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { SelectableText("背景・制約・受け入れ条件（任意、0〜8000 文字）") },
                enabled = !blocked,
                isError = codePointCount(body) > TASK_BODY_MAX,
                supportingText = { CounterText(codePointCount(body), TASK_BODY_MAX) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProjectFixedLabel(projectId)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { send() },
                enabled = !blocked,
                modifier = Modifier.align(Alignment.End),
            ) {
                SelectableText("Goal を送る")
            }
            if (ApiSession.expired) {
                SelectableText(SESSION_EXPIRED_MESSAGE, color = MaterialTheme.colorScheme.error)
            }
            result?.let { (text, ok) ->
                SelectableText(
                    text,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
