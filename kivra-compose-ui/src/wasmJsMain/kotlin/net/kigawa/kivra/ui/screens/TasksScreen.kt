package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
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
 * Tasks screen: Run list and Task submit form.
 */
@Composable
fun TasksScreen(selectedProjectId: String? = null, onNavigate: (Route) -> Unit) {
    var scope by remember { mutableStateOf("mine") }
    // 投入が成功したら一覧を読み直す(旧 UI の refreshRuns)。
    var listReload by remember { mutableStateOf(0) }
    val perms = LocalCallerPermissions.current

    // 一覧。読み直しの間は前の一覧を残す。入力中のフォーム(下)は読み直しで消えない。
    val loaded = rememberLoad(listOf(scope, selectedProjectId), reload = listReload) {
        val allRuns = ApiClient.getRuns(scope)
        if (selectedProjectId != null) allRuns.filter { it.projectId == selectedProjectId } else allRuns
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Scope toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Run の一覧", style = MaterialTheme.typography.headlineSmall)
            Row {
                FilterChip(
                    selected = scope == "mine",
                    onClick = { scope = "mine" },
                    label = { Text("自分の Run") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = scope == "all",
                    onClick = { scope = "all" },
                    label = { Text("全員の Run") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Run list. 残りの高さを使い、フォームを下に残す。
        Column(modifier = Modifier.weight(1f)) {
            LoadedList(loaded, emptyText = "Run が見つかりません") { runs ->
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(runs) { run ->
                        RunCard(run, onClick = { onNavigate(Route.RunDetail(run.runId)) })
                        Divider()
                    }
                }
            }
        }

        // Task submit form. SPEND_START が無い人には出さない(UX の出し分け。サーバーも投入を 403 で拒否する)。
        if (perms.can(GatedOperation.SUBMIT_TASK)) {
            Spacer(modifier = Modifier.height(12.dp))
            SubmitTaskCard(onAccepted = { listReload += 1 })
        }
    }
}

/**
 * Task の投入フォーム(旧 UI の submit-form)。種別、タイトル、本文(自己開発のみ)、自己開発のプロジェクト、投入。
 * 失敗時は入力を残し、成功時だけ入力を消す。投入キーは内容(種別・タイトル・本文・プロジェクト)ごとに管理する。
 */
@Composable
private fun SubmitTaskCard(onAccepted: () -> Unit) {
    val availability = LocalSelfDevAvailability.current
    val coroutineScope = rememberCoroutineScope()
    val keys = remember { IdempotencyKeys() }
    val projects = rememberLoad(Unit) { ApiClient.getProjects() }

    var requestedKind by remember { mutableStateOf(SubmitKind.SIMULATED) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf<String?>(null) }
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
        if (kind == SubmitKind.SELF_DEV && chosen == null) {
            result = PROJECT_REQUIRED_MESSAGE to false
            return
        }
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
            Text("Task を投入", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == SubmitKind.SIMULATED,
                    onClick = { requestedKind = SubmitKind.SIMULATED },
                    label = { Text("シミュレーション") },
                    enabled = !blocked,
                )
                FilterChip(
                    selected = kind == SubmitKind.SELF_DEV,
                    onClick = { requestedKind = SubmitKind.SELF_DEV },
                    label = { Text("自己開発（GitHub に PR を作成）") },
                    enabled = !blocked && selfDevSelectable(availability),
                )
            }
            Text(
                selfDevNoteText(availability),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("タイトル（1〜200 文字）") },
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
                    label = { Text("本文（任意、0〜8000 文字。自己開発のみ。タイトルが Issue の URL、#番号、owner/repo#番号のときは Issue の本文を kivra が読んで添えます）") },
                    enabled = !blocked,
                    isError = codePointCount(body) > TASK_BODY_MAX,
                    supportingText = { CounterText(codePointCount(body), TASK_BODY_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
            if (kind == SubmitKind.SELF_DEV) {
                Spacer(modifier = Modifier.height(8.dp))
                ProjectChoice(
                    projects = projects,
                    selected = projectId,
                    enabled = !blocked,
                    onSelect = { projectId = it },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { send() },
                enabled = !blocked,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("投入")
            }
            if (ApiSession.expired) {
                Text(SESSION_EXPIRED_MESSAGE, color = MaterialTheme.colorScheme.error)
            }
            result?.let { (text, ok) ->
                Text(
                    text,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RunCard(run: ApiRun, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp).clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(run.title, style = MaterialTheme.typography.titleMedium)
                AssistChip(
                    onClick = { },
                    label = { Text(run.state) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("状態: ${run.state} | モード: ${run.mode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (run.projectName != null) {
                Text("プロジェクト: ${run.projectName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
