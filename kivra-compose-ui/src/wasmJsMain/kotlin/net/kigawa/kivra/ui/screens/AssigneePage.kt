package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ASSIGNEE_MSG_BOUND
import net.kigawa.kivra.ui.ASSIGNEE_MSG_CREATED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_DISABLED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_ENABLED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_ISSUED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_NAME_BAD
import net.kigawa.kivra.ui.ASSIGNEE_MSG_NETWORK
import net.kigawa.kivra.ui.ASSIGNEE_MSG_NO_CHANGE
import net.kigawa.kivra.ui.ASSIGNEE_MSG_REVOKED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_ROLE_CHANGED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_ROLE_NEEDED
import net.kigawa.kivra.ui.ASSIGNEE_MSG_SUB_BAD
import net.kigawa.kivra.ui.ASSIGNEE_MSG_TTL_BAD
import net.kigawa.kivra.ui.ASSIGNEE_MSG_UNBOUND
import net.kigawa.kivra.ui.ASSIGNEE_NAME_MAX
import net.kigawa.kivra.ui.ASSIGNEE_ROLES
import net.kigawa.kivra.ui.ASSIGNEE_TOKEN_ONCE_TEXT
import net.kigawa.kivra.ui.ASSIGNEE_TTL_DEFAULT_DAYS
import net.kigawa.kivra.ui.ASSIGNEE_TTL_MAX_DAYS
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.GatedOperation
import net.kigawa.kivra.ui.LocalCallerPermissions
import net.kigawa.kivra.ui.api.ApiAssignee
import net.kigawa.kivra.ui.api.ApiAssigneeDetail
import net.kigawa.kivra.ui.api.ApiAssigneeToken
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiIssuedToken
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.assigneeFailureText
import net.kigawa.kivra.ui.assigneeCountText
import net.kigawa.kivra.ui.assigneeRefShort
import net.kigawa.kivra.ui.assigneeRoleLabel
import net.kigawa.kivra.ui.assigneeStateLabel
import net.kigawa.kivra.ui.assigneeTokenStateLabel
import net.kigawa.kivra.ui.canBindAssigneeMember
import net.kigawa.kivra.ui.canIssueAssigneeToken
import net.kigawa.kivra.ui.codePointCount
import net.kigawa.kivra.ui.validAssigneeDisplayName
import net.kigawa.kivra.ui.validAssigneeSub
import net.kigawa.kivra.ui.validAssigneeTtlDays

/**
 * 担当の画面の確認つきの操作。[action] は送信(成功なら発行したトークンを返す。トークン以外は null)。
 * [okText] は成功時の文言、[onOk] は成功時の後始末(入力欄を空にする、など)。
 */
internal class PendingAssigneeWrite(
    val title: String,
    val text: String,
    val label: String,
    val okText: String,
    val action: suspend () -> ApiIssuedToken?,
    val onOk: () -> Unit = {},
)

/**
 * 担当の画面の操作の状態。送信は一度に一つ([busy])。成功・失敗は [notice] に出し、一覧と詳細を読み直す([reloadKey])。
 * トークンの平文は [issued] にだけ入り、ダイアログを閉じると [closeToken] で消える(保持しない)。
 */
internal class AssigneeWriter(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<ActionNotice?>(null)
        private set
    var pending by mutableStateOf<PendingAssigneeWrite?>(null)
        private set
    var issued by mutableStateOf<ApiIssuedToken?>(null)
        private set
    var reloadKey by mutableStateOf(0)
        private set

    /** 入力の誤りなど、送らずに出す文言。 */
    fun fail(text: String) {
        notice = ActionNotice(text, false)
    }

    /** 確認の画面を開く。送信中・セッション切れの間は開かない。 */
    fun ask(write: PendingAssigneeWrite) {
        if (busy || ApiSession.expired) return
        pending = write
    }

    /** 確認を閉じる(送信中は閉じない)。 */
    fun cancel() {
        if (!busy) pending = null
    }

    /** 確認の画面の送信。 */
    fun confirm() {
        val write = pending ?: return
        perform(write)
    }

    /** 確認なしの送信(作成など)、または確認の後の送信。送信中は受け付けない。 */
    fun perform(write: PendingAssigneeWrite) {
        if (busy || ApiSession.expired) return
        busy = true
        scope.launch {
            var token: ApiIssuedToken? = null
            val outcome: ActionNotice = try {
                token = write.action()
                write.onOk()
                ActionNotice(write.okText, true)
            } catch (e: CancellationException) {
                busy = false
                throw e
            } catch (e: ApiException) {
                ActionNotice(assigneeFailureText(e.status, e.code), false)
            } catch (e: Exception) {
                ActionNotice(ASSIGNEE_MSG_NETWORK, false)
            }
            busy = false
            pending = null
            notice = outcome
            token?.let { issued = it }
            reloadKey++
        }
    }

    /** トークンのダイアログを閉じる。平文を消す。 */
    fun closeToken() {
        issued = null
    }
}

/**
 * 担当の画面(管理者向け、ADR 0020)。作成、ロール・有効化/無効化、メンバーの紐付けと解除、トークンの発行と失効。
 * 見せるのは担当の管理の権限があるときだけ([CallerPermissions.assigneesVisible])。
 *
 * [selectedProjectId] は選んだプロジェクトで絞る(旧 UI の PROJECT_SCOPED_GET と同じ)。
 */
@Composable
fun AssigneesScreen(selectedProjectId: String? = null) {
    val perms = LocalCallerPermissions.current
    val scope = rememberCoroutineScope()
    val writer = remember(scope) { AssigneeWriter(scope) }
    val visible = perms.assigneesVisible() && perms.can(GatedOperation.MANAGE_ASSIGNEE)

    var openId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var roleInput by remember { mutableStateOf<String?>(null) }

    val loaded = rememberLoad(Pair(selectedProjectId, visible), writer.reloadKey) {
        if (visible) ApiClient.getAssigneesIn(selectedProjectId) else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SelectableText("担当", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))

        if (!visible) {
            SelectableText("この画面は、担当の管理の権限がある人だけが見られます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            writer.notice?.let { n ->
                SelectableText(n.text, color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }
            loaded.value?.let { list ->
                SelectableText("担当: ${list.size} 件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 作成の欄。
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SelectableText("担当を作る", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { SelectableText("表示名（1〜$ASSIGNEE_NAME_MAX 文字）") },
                        supportingText = { SelectableText("${codePointCount(nameInput)} / $ASSIGNEE_NAME_MAX") },
                        singleLine = true,
                        enabled = !writer.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OptionPicker(
                        label = "ロール",
                        options = listOf<Pair<String?, String>>(null to "ロールを選んでください") +
                            ASSIGNEE_ROLES.map { it to (assigneeRoleLabel(it) ?: it) },
                        selected = roleInput,
                        onSelect = { roleInput = it },
                        enabled = !writer.busy,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val name = validAssigneeDisplayName(nameInput)
                            val role = roleInput
                            when {
                                name == null -> writer.fail(ASSIGNEE_MSG_NAME_BAD)
                                role == null || role !in ASSIGNEE_ROLES -> writer.fail(ASSIGNEE_MSG_ROLE_NEEDED)
                                else -> writer.perform(
                                    PendingAssigneeWrite(
                                        title = "",
                                        text = "",
                                        label = "",
                                        okText = ASSIGNEE_MSG_CREATED,
                                        action = {
                                            ApiClient.createAssignee(name, role)
                                            null
                                        },
                                        onOk = {
                                            nameInput = ""
                                            roleInput = null
                                        },
                                    ),
                                )
                            }
                        },
                        enabled = !writer.busy,
                    ) { SelectableText("担当を作る") }
                }
            }

            LoadedList(loaded, emptyText = "まだ担当はありません。") { list ->
                list.forEach { a ->
                    AssigneeRow(
                        a = a,
                        open = openId == a.assigneeId,
                        writer = writer,
                        onToggle = { openId = if (openId == a.assigneeId) null else a.assigneeId },
                    )
                }
            }
        }
    }

    // 確認の画面(理由なし)。送信中は閉じられない。
    writer.pending?.let { w ->
        ReasonDialog(
            title = w.title,
            message = w.text,
            submitLabel = w.label,
            reasonLabel = null,
            busy = writer.busy,
            errorText = null,
            onSubmit = { writer.confirm() },
            onDismiss = { writer.cancel() },
        )
    }

    // 発行したトークン。一度だけ表示し、閉じると消す。
    writer.issued?.let { token ->
        IssuedTokenDialog(token = token, onClose = { writer.closeToken() })
    }
}

/** 担当の一行(名前・ロール・状態・件数)。開くと詳細(ロール、メンバー、トークン)。 */
@Composable
private fun AssigneeRow(a: ApiAssignee, open: Boolean, writer: AssigneeWriter, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            SelectableText(a.displayName, style = MaterialTheme.typography.titleMedium)
            SelectableText(
                "ロール: ${a.role} | 状態: ${assigneeStateLabel(a.state)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectableText(
                "メンバー: ${assigneeCountText(a.memberCount)} 人 | 有効なトークン: ${assigneeCountText(a.activeTokenCount)} 個",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(onClick = onToggle) { SelectableText(if (open) "閉じる" else "詳細") }
            if (open) {
                AssigneeDetail(a = a, writer = writer)
            }
        }
    }
}

/** 担当の詳細。メンバーとトークンを読んで出す。読み直しは [AssigneeWriter.reloadKey] で行う。 */
@Composable
private fun AssigneeDetail(a: ApiAssignee, writer: AssigneeWriter) {
    val detail = rememberLoad(a.assigneeId, writer.reloadKey) { ApiClient.getAssigneeDetail(a.assigneeId) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LoadedView(detail) { d -> AssigneeDetailBody(a = a, detail = d, writer = writer) }
    }
}

@Composable
private fun AssigneeDetailBody(a: ApiAssignee, detail: ApiAssigneeDetail, writer: AssigneeWriter) {
    val id = a.assigneeId
    val busy = writer.busy
    var roleChoice by remember(id, a.role) { mutableStateOf<String?>(a.role) }
    var sub by remember(id) { mutableStateOf("") }
    var ttlInput by remember(id) { mutableStateOf(ASSIGNEE_TTL_DEFAULT_DAYS.toString()) }
    val roleOptions: List<Pair<String?, String>> = ASSIGNEE_ROLES.map { it to (assigneeRoleLabel(it) ?: it) }

    // ロールと状態。
    SelectableText("ロールと状態", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionPicker(
            label = "ロール",
            options = roleOptions,
            selected = roleChoice,
            onSelect = { roleChoice = it },
            enabled = !busy,
        )
        OutlinedButton(onClick = { askRoleChange(a, roleChoice, writer) }, enabled = !busy) { SelectableText("ロールを変更…") }
        val active = a.state == "ACTIVE"
        OutlinedButton(onClick = { writer.ask(stateChange(a, enable = !active)) }, enabled = !busy) {
            SelectableText(if (active) "担当を無効にする…" else "担当を有効にする…")
        }
    }

    // メンバー。
    Spacer(modifier = Modifier.height(8.dp))
    SelectableText("メンバー（この担当として入れる人）", style = MaterialTheme.typography.titleSmall)
    if (detail.memberRefs.isEmpty()) {
        SelectableText("紐付いている人はいません。", style = MaterialTheme.typography.bodySmall)
    }
    detail.memberRefs.forEach { ref ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectableText("参照: ${assigneeRefShort(ref)}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { writer.ask(memberUnbind(id, ref)) }, enabled = !busy) { SelectableText("解除…") }
        }
    }
    if (!canBindAssigneeMember(a.role)) {
        SelectableText(
            "AI_APPROVER の担当には、人（Keycloak の sub）を紐付けられません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = sub,
            onValueChange = { sub = it },
            label = { SelectableText("Keycloak の sub（入力した値は、送ったあとに消えます。画面には表示しません）") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                val value = validAssigneeSub(sub)
                if (value == null) {
                    writer.fail(ASSIGNEE_MSG_SUB_BAD)
                } else {
                    writer.ask(
                        PendingAssigneeWrite(
                            title = "メンバーの紐付け",
                            text = "入力した sub の人を、この担当に紐付けます。紐付いた人は、この担当のロールの権限で操作できます。",
                            label = "紐付ける",
                            okText = ASSIGNEE_MSG_BOUND,
                            action = {
                                ApiClient.bindAssigneeMember(id, value)
                                null
                            },
                            onOk = { sub = "" },
                        ),
                    )
                }
            },
            enabled = !busy,
        ) { SelectableText("紐付ける…") }
    }

    // トークン(AI と VIEWER で、有効な担当だけ発行できる)。
    Spacer(modifier = Modifier.height(8.dp))
    SelectableText("トークン", style = MaterialTheme.typography.titleSmall)
    when {
        !canIssueAssigneeToken(a.role, "ACTIVE") -> SelectableText(
            "このロールの担当は、トークンを持てません（AI と VIEWER だけです）。",
            style = MaterialTheme.typography.bodySmall,
        )
        a.state != "ACTIVE" -> SelectableText("無効な担当には、トークンを発行できません。", style = MaterialTheme.typography.bodySmall)
        else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ttlInput,
                onValueChange = { ttlInput = it },
                label = { SelectableText("有効期限（日数、1〜$ASSIGNEE_TTL_MAX_DAYS）") },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedButton(onClick = { askIssue(id, ttlInput, writer) }, enabled = !busy) { SelectableText("トークンを発行…") }
        }
    }
    if (detail.tokens.isEmpty()) {
        SelectableText("トークンはありません。", style = MaterialTheme.typography.bodySmall)
    }
    detail.tokens.forEach { t -> TokenRow(assigneeId = id, token = t, enabled = !busy, writer = writer) }
}

/** トークンの一行(参照の先頭 8 文字、状態、期限)。有効なものだけ失効できる。平文は持たない。 */
@Composable
private fun TokenRow(assigneeId: String, token: ApiAssigneeToken, enabled: Boolean, writer: AssigneeWriter) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectableText(
            "参照: ${assigneeRefShort(token.tokenRef)} | ${assigneeTokenStateLabel(token.status)} | 期限: ${localDateTimeText(token.expiresAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (token.status == "ACTIVE") {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { writer.ask(tokenRevoke(assigneeId, token.tokenRef)) }, enabled = enabled) { SelectableText("失効…") }
        }
    }
}

private fun askRoleChange(a: ApiAssignee, role: String?, writer: AssigneeWriter) {
    when {
        role == null || role !in ASSIGNEE_ROLES -> writer.fail(ASSIGNEE_MSG_ROLE_NEEDED)
        role == a.role -> writer.fail(ASSIGNEE_MSG_NO_CHANGE)
        else -> writer.ask(
            PendingAssigneeWrite(
                title = "ロールの変更",
                text = "この担当のロールを $role に変更します。変更すると、次の操作から新しい権限になります（AI と VIEWER でなくなると、トークンは使えなくなります）。",
                label = "変更する",
                okText = ASSIGNEE_MSG_ROLE_CHANGED,
                action = {
                    ApiClient.changeAssigneeRole(a.assigneeId, role, a.version)
                    null
                },
            ),
        )
    }
}

private fun stateChange(a: ApiAssignee, enable: Boolean): PendingAssigneeWrite =
    if (enable) {
        PendingAssigneeWrite(
            title = "担当の有効化",
            text = "この担当を有効にします。失効したトークンは戻りません（必要なら、発行し直します）。",
            label = "有効にする",
            okText = ASSIGNEE_MSG_ENABLED,
            action = {
                ApiClient.setAssigneeEnabled(a.assigneeId, enabled = true, version = a.version)
                null
            },
        )
    } else {
        PendingAssigneeWrite(
            title = "担当の無効化",
            text = "この担当を無効にします。この担当のトークンは、すべて失効します（有効にしても戻りません）。人の担当は、入れなくなります。",
            label = "無効にする",
            okText = ASSIGNEE_MSG_DISABLED,
            action = {
                ApiClient.setAssigneeEnabled(a.assigneeId, enabled = false, version = a.version)
                null
            },
        )
    }

private fun memberUnbind(assigneeId: String, ref: String): PendingAssigneeWrite =
    PendingAssigneeWrite(
        title = "メンバーの解除",
        text = "この人（参照 ${assigneeRefShort(ref)}）の紐付けを解除します。その人は、この担当として入れなくなります。",
        label = "解除する",
        okText = ASSIGNEE_MSG_UNBOUND,
        action = {
            ApiClient.unbindAssigneeMember(assigneeId, ref)
            null
        },
    )

private fun askIssue(assigneeId: String, ttlInput: String, writer: AssigneeWriter) {
    val days = validAssigneeTtlDays(ttlInput)
    if (days == null) {
        writer.fail(ASSIGNEE_MSG_TTL_BAD)
    } else {
        writer.ask(
            PendingAssigneeWrite(
                title = "トークンの発行",
                text = "この担当のトークンを、$days 日の期限で発行します。平文は、この 1 回しか表示されません。有効なトークンは、担当ごとに最大 2 つです。",
                label = "発行する",
                okText = ASSIGNEE_MSG_ISSUED,
                action = { ApiClient.issueAssigneeToken(assigneeId, days) },
            ),
        )
    }
}

private fun tokenRevoke(assigneeId: String, tokenRef: String): PendingAssigneeWrite =
    PendingAssigneeWrite(
        title = "トークンの失効",
        text = "このトークン（参照 ${assigneeRefShort(tokenRef)}）を失効します。次のリクエストから使えなくなります。元に戻せません。",
        label = "失効する",
        okText = ASSIGNEE_MSG_REVOKED,
        action = {
            ApiClient.revokeAssigneeToken(assigneeId, tokenRef)
            null
        },
    )

/**
 * 発行したトークンの表示。平文はこの 1 回だけ。コピーはボタンから。閉じる(Esc を含む)と [onClose] で平文が消える。
 */
@Composable
private fun IssuedTokenDialog(token: ApiIssuedToken, onClose: () -> Unit) {
    // 開いている間は定期更新を止める(読み直しで画面を組み替えないため)。
    HoldAutoRefreshWhileShown()
    var copyNote by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { SelectableText("発行したトークン") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SelectableText(
                    "参照: ${assigneeRefShort(token.tokenRef)}、期限: ${localDateTimeText(token.expiresAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SelectableText(ASSIGNEE_TOKEN_ONCE_TEXT, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token.token,
                    onValueChange = {},
                    readOnly = true,
                    label = { SelectableText("トークン（読み取り専用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copyNote.isNotEmpty()) {
                    SelectableText(copyNote, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { SelectableText("閉じる（二度と表示されません）") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    copyTokenToClipboard(
                        token = token.token,
                        onOk = { copyNote = "コピーしました。" },
                        onFail = { copyNote = "コピーできませんでした。入力欄を選択して、手動でコピーしてください。" },
                    )
                },
            ) { SelectableText("コピー") }
        },
    )
}

/**
 * トークンをクリップボードへ書く(navigator.clipboard)。成功と失敗は非同期に [onOk] / [onFail] で知らせる。
 * 使えない環境は失敗扱い(旧 UI の asgCopyToken と同じく、手動のコピーを案内する)。平文はこの関数の引数だけを通る。
 */
@JsFun("(text, onOk, onFail) => { const c = navigator.clipboard; if (!c || typeof c.writeText !== 'function') { onFail(); return; } c.writeText(text).then(() => onOk(), () => onFail()); }")
private external fun copyTokenToClipboard(token: String, onOk: () -> Unit, onFail: () -> Unit)
