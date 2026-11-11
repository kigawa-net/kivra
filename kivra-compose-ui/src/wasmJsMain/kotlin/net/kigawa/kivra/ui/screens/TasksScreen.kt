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
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import net.kigawa.kivra.ui.isUnassigned
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.IdempotencyKeys
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.LocalSelfDevAvailability
import net.kigawa.kivra.ui.Route
import net.kigawa.kivra.ui.SubmitFailure
import net.kigawa.kivra.ui.SubmitKind
import net.kigawa.kivra.ui.SubmitResult
import net.kigawa.kivra.ui.TASK_BODY_MAX
import net.kigawa.kivra.ui.TASK_TITLE_MAX
import net.kigawa.kivra.ui.TaskSignature
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiRun
import net.kigawa.kivra.ui.displayTime
import net.kigawa.kivra.ui.pullRequestText
import net.kigawa.kivra.ui.runKindText
import net.kigawa.kivra.ui.runReasonText
import net.kigawa.kivra.ui.runStateText
import net.kigawa.kivra.ui.runTitleText
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.codePointCount
import net.kigawa.kivra.ui.effectiveKind
import net.kigawa.kivra.ui.selfDevNoteText
import net.kigawa.kivra.ui.selfDevSelectable
import net.kigawa.kivra.ui.showsBodyField
import net.kigawa.kivra.ui.submitBlocked
import net.kigawa.kivra.ui.taskFailureText
import net.kigawa.kivra.ui.taskSuccessText
import net.kigawa.kivra.ui.validatedBody
import net.kigawa.kivra.ui.validatedTaskTitle
import net.kigawa.kivra.ui.PROJECT_REQUIRED_MESSAGE
import net.kigawa.kivra.ui.SESSION_EXPIRED_MESSAGE

/**
 * Tasks screen (プロジェクトの中): そのプロジェクトの Run の一覧と、Task の投入フォーム。
 */
@Composable
fun TasksScreen(projectId: String, onNavigate: (Route) -> Unit) {
    var scope by remember { mutableStateOf("mine") }
    // 投入が成功したら一覧を読み直す(旧 UI の refreshRuns)。
    var listReload by remember { mutableStateOf(0) }
    val perms = LocalCallerPermissions.current

    // 一覧。このプロジェクトの Run だけ(サーバーで絞る)。読み直しの間は前の一覧を残す。入力中のフォーム(下)は読み直しで消えない。
    val loaded = rememberLoad(listOf(scope, projectId), reload = listReload) {
        ApiClient.getRunsIn(projectId, scope)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Scope toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectableText("Run の一覧", style = MaterialTheme.typography.headlineSmall)
            Row {
                FilterChip(
                    selected = scope == "mine",
                    onClick = { scope = "mine" },
                    label = { SelectableText("自分の Run") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = scope == "all",
                    onClick = { scope = "all" },
                    label = { SelectableText("全員の Run") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Run list. 残りの高さを使い、フォームを下に残す。
        Column(modifier = Modifier.weight(1f)) {
            LoadedList(loaded, emptyText = "Run が見つかりません") { runs ->
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(runs) { run ->
                        RunCard(run, showCreator = scope == "all", onClick = { onNavigate(Route.RunDetail(run.runId)) })
                        Divider()
                    }
                }
            }
        }

        // Task submit form. SPEND_START が無い人には出さない(UX の出し分け。サーバーも投入を 403 で拒否する)。
        if (isUnassigned(projectId)) {
            Spacer(modifier = Modifier.height(12.dp))
            UnassignedCreateNote()
        } else if (perms.can(GatedOperation.SUBMIT_TASK)) {
            Spacer(modifier = Modifier.height(12.dp))
            SubmitTaskCard(projectId, onAccepted = { listReload += 1 })
        }
    }
}

/**
 * Task の投入フォーム(旧 UI の submit-form)。種別、タイトル、本文(自己開発のみ)、投入。
 * 自己開発の投入先は、この画面のプロジェクト([projectId]、選べない)。シミュレーションはプロジェクトを持たない。
 * 失敗時は入力を残し、成功時だけ入力を消す。投入キーは内容(種別・タイトル・本文・プロジェクト)ごとに管理する。
 */
@Composable
private fun SubmitTaskCard(projectId: String, onAccepted: () -> Unit) {
    val availability = LocalSelfDevAvailability.current
    val coroutineScope = rememberCoroutineScope()
    val keys = remember { IdempotencyKeys() }

    var requestedKind by remember { mutableStateOf(SubmitKind.SIMULATED) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    // 結果の文言と、成功かどうか(色に使う)。
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val kind = effectiveKind(requestedKind, availability)
    // 自己開発を選べなくなったら、シミュレーションに戻す(旧 UI の applySelfDevAvailability)。
    LaunchedEffect(kind) {
        if (requestedKind != kind) requestedKind = kind
    }
    val blocked = submitBlocked(submitting, ApiSession.expired)

    fun send() {
        if (submitBlocked(submitting, ApiSession.expired)) return
        val validTitle = validatedTaskTitle(title)
        val validBody = if (showsBodyField(kind)) validatedBody(body) else ""
        if (validTitle == null || validBody == null) {
            result = taskFailureText(SubmitFailure.BAD_REQUEST, kind) to false
            return
        }
        val chosen = if (kind == SubmitKind.SELF_DEV) projectId else null
        val key = keys.keyFor(TaskSignature(kind, validTitle, validBody, chosen))
        submitting = true
        result = "送信中です…" to true
        coroutineScope.launch {
            val outcome = try {
                ApiClient.submitTask(validTitle, validBody.takeIf { it.isNotEmpty() }, kind, chosen, key)
            } finally {
                submitting = false
            }
            when (outcome) {
                is SubmitResult.Created, is SubmitResult.Replayed -> {
                    // 成功した投入の後は新しいキー。入力を消す(プロジェクトと種別は残す)。
                    keys.rotate()
                    title = ""
                    body = ""
                    result = taskSuccessText(outcome, kind) to true
                    onAccepted()
                }
                is SubmitResult.Failed -> {
                    // 409 は別の内容で使われたキー。作り直して、利用者が改めて送る。
                    if (outcome.failure == SubmitFailure.CONFLICT) keys.rotate()
                    result = taskFailureText(outcome.failure, kind) to false
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SelectableText("Task を投入", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == SubmitKind.SIMULATED,
                    onClick = { requestedKind = SubmitKind.SIMULATED },
                    label = { SelectableText("シミュレーション") },
                    enabled = !blocked,
                )
                FilterChip(
                    selected = kind == SubmitKind.SELF_DEV,
                    onClick = { requestedKind = SubmitKind.SELF_DEV },
                    label = { SelectableText("自己開発（GitHub に PR を作成）") },
                    enabled = !blocked && selfDevSelectable(availability),
                )
            }
            SelectableText(
                selfDevNoteText(availability),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { SelectableText("タイトル（1〜200 文字）") },
                enabled = !blocked,
                isError = codePointCount(title) > TASK_TITLE_MAX,
                supportingText = { CounterText(codePointCount(title), TASK_TITLE_MAX) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (showsBodyField(kind)) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { SelectableText("本文（任意、0〜8000 文字。自己開発のみ。タイトルが Issue の URL、#番号、owner/repo#番号のときは Issue の本文を kivra が読んで添えます）") },
                    enabled = !blocked,
                    isError = codePointCount(body) > TASK_BODY_MAX,
                    supportingText = { CounterText(codePointCount(body), TASK_BODY_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            if (kind == SubmitKind.SELF_DEV) {
                Spacer(modifier = Modifier.height(8.dp))
                ProjectFixedLabel(projectId)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { send() },
                enabled = !blocked,
                modifier = Modifier.align(Alignment.End)
            ) {
                SelectableText("投入")
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

/**
 * Run の一行(旧 UI の runRow)。題名、状態、種別、作成日時、(全員の一覧では)作成者、理由、PR。
 * 種別や状態の表示は [runKindText] / [runStateText] の表を使い、知らない値はそのまま出す。
 */
@Composable
private fun RunCard(run: ApiRun, showCreator: Boolean, onClick: () -> Unit) {
    val kind = runKindText(run.mode)
    val state = runStateText(run.state)
    val reason = runReasonText(run.reasonCode)
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SelectableText(runTitleText(run.mode, run.title), style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = { },
                    label = { SelectableText("状態: ${state.label}") }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val creator = if (showCreator) " | 作成者: ${run.createdBy ?: ""}" else ""
            SelectableText("${kind.label} | 作成日時: ${displayTime(run.createdAt)}$creator",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (run.projectName != null) {
                SelectableText("プロジェクト: ${run.projectName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reason.isNotEmpty()) {
                SelectableText("理由: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectableText("PR: ${pullRequestText(run.pullRequestRepository, run.pullRequestNumber)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
