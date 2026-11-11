package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import net.kigawa.kivra.ui.SelectableText
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
import kotlinx.coroutines.launch
import net.kigawa.kivra.ui.ActionNotice
import net.kigawa.kivra.ui.MEMBER_ADDED_MESSAGE
import net.kigawa.kivra.ui.MEMBER_ASSIGNEES_FAILED_MESSAGE
import net.kigawa.kivra.ui.MEMBER_DEFAULT_ROLE
import net.kigawa.kivra.ui.MEMBER_IMPORT_DONE_MESSAGE
import net.kigawa.kivra.ui.MEMBER_IMPORT_DRY_DONE_MESSAGE
import net.kigawa.kivra.ui.MEMBER_LIST_EMPTY_MESSAGE
import net.kigawa.kivra.ui.MEMBER_NO_CANDIDATE_MESSAGE
import net.kigawa.kivra.ui.MEMBER_REMOVED_MESSAGE
import net.kigawa.kivra.ui.MEMBER_ROLES
import net.kigawa.kivra.ui.MEMBER_ROLE_CHANGED_MESSAGE
import net.kigawa.kivra.ui.SESSION_EXPIRED_MESSAGE
import net.kigawa.kivra.ui.api.ApiAssignee
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiMemberImportReport
import net.kigawa.kivra.ui.api.ApiProjectMember
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.canRunMemberImport
import net.kigawa.kivra.ui.memberCandidates
import net.kigawa.kivra.ui.memberFailureText
import net.kigawa.kivra.ui.memberImportExplain
import net.kigawa.kivra.ui.memberImportSummary
import net.kigawa.kivra.ui.memberLabel
import net.kigawa.kivra.ui.memberRemoveExplain
import net.kigawa.kivra.ui.submitBlocked

/** 担当の一覧の読み取り結果。[list] が null なら [error] の固定文言を出す(追加の候補が読めなかった)。 */
private data class AssigneePickerRead(val list: List<ApiAssignee>?, val error: String?)

/** 担当の一覧(プロジェクトの絞り込みなし。候補は全員から選ぶ)。401 はセッション切れ、ほかは固定の文言。 */
private suspend fun readAssigneePicker(): AssigneePickerRead =
    try {
        AssigneePickerRead(ApiClient.getAssignees(), null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val text = if ((e as? ApiException)?.status == 401) SESSION_EXPIRED_MESSAGE else MEMBER_ASSIGNEES_FAILED_MESSAGE
        AssigneePickerRead(null, text)
    }

/**
 * 運用者だけの欄(旧 UI の project-members): メンバーの一覧、役割の変更、外す(確認つき)、追加(担当と役割)、
 * 全担当の取り込み(dry-run の確認を経てから実行)。出し分けは呼び出し側(運用者の欄)が行う。
 * 変更の後は一覧を読み直し、取り込みの dry-run の結果は捨てる(旧 UI の invalidateMemberImport)。
 * 送信中は二重送信を防ぐ。権限と判定はサーバーが行い、失敗は [memberFailureText] の固定文言で出す。
 */
@Composable
internal fun ProjectMembersSection(projectId: String) {
    val scope = rememberCoroutineScope()
    // 変更の後に増やして、一覧と候補を読み直させる。
    var reload by remember { mutableStateOf(0) }
    val members = rememberLoad(projectId, reload) { ApiClient.getProjectMembers(projectId) }
    val picker = rememberLoad(projectId, reload) { readAssigneePicker() }
    var busy by remember { mutableStateOf(false) }
    val expired = ApiSession.expired

    // 追加の欄。担当は候補から選ぶ。未選択なら先頭の候補。
    var pickedAssignee by remember { mutableStateOf<String?>(null) }
    var addRole by remember { mutableStateOf(MEMBER_DEFAULT_ROLE) }
    var memberNotice by remember { mutableStateOf<ActionNotice?>(null) }

    // 外すの確認。対象があるときだけ開く。失敗時は開いたままにする。
    var removeTarget by remember { mutableStateOf<ApiProjectMember?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }

    // 取り込み。dry-run の結果が実行の対象。実行の結果は [importShown] に出す。
    var importRole by remember { mutableStateOf(MEMBER_DEFAULT_ROLE) }
    var importDry by remember { mutableStateOf<ApiMemberImportReport?>(null) }
    var importShown by remember { mutableStateOf<ApiMemberImportReport?>(null) }
    var importNotice by remember { mutableStateOf<ActionNotice?>(null) }
    var importConfirmOpen by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // メンバーが変わったら、取り込みの dry-run の結果は古いので捨てる(旧 UI の invalidateMemberImport)。
    fun invalidateImport() {
        importDry = null
        importShown = null
    }

    fun afterWrite(changed: Boolean) {
        if (changed) invalidateImport()
        reload += 1
    }

    val currentMembers: List<ApiProjectMember> = members.value ?: emptyList()
    val candidates = memberCandidates(picker.value?.list ?: emptyList(), currentMembers)
    val chosen = candidates.firstOrNull { it.assigneeId == pickedAssignee } ?: candidates.firstOrNull()

    fun addMember() {
        if (submitBlocked(busy, expired)) return
        val target = chosen
        if (target == null) {
            memberNotice = ActionNotice(MEMBER_NO_CANDIDATE_MESSAGE, ok = false)
            return
        }
        if (addRole !in MEMBER_ROLES) {
            memberNotice = ActionNotice(memberFailureText(400), ok = false)
            return
        }
        busy = true
        memberNotice = null
        scope.launch {
            var ok = false
            try {
                ApiClient.putProjectMember(projectId, target.assigneeId, addRole)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                memberNotice = ActionNotice(memberFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                busy = false
            }
            if (ok) memberNotice = ActionNotice(MEMBER_ADDED_MESSAGE, ok = true)
            afterWrite(changed = ok)
        }
    }

    fun changeRole(member: ApiProjectMember, role: String) {
        if (submitBlocked(busy, expired) || role == member.role) return
        busy = true
        memberNotice = null
        scope.launch {
            var ok = false
            try {
                ApiClient.putProjectMember(projectId, member.assigneeId, role)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                memberNotice = ActionNotice(memberFailureText((e as? ApiException)?.status), ok = false)
            } finally {
                busy = false
            }
            if (ok) memberNotice = ActionNotice(MEMBER_ROLE_CHANGED_MESSAGE, ok = true)
            afterWrite(changed = ok)
        }
    }

    fun removeMember(member: ApiProjectMember) {
        if (submitBlocked(busy, expired)) return
        busy = true
        removeError = null
        scope.launch {
            var ok = false
            try {
                ApiClient.removeProjectMember(projectId, member.assigneeId)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                removeError = memberFailureText((e as? ApiException)?.status)
            } finally {
                busy = false
            }
            if (ok) {
                removeTarget = null
                removeError = null
                memberNotice = ActionNotice(MEMBER_REMOVED_MESSAGE, ok = true)
            }
            afterWrite(changed = ok)
        }
    }

    /** 取り込み。dry-run は数えるだけ、実行は確認の後だけ。実行の役割は dry-run のときの役割(確認の文言と同じ)。 */
    fun runImport(dryRun: Boolean, role: String) {
        if (submitBlocked(busy, expired)) return
        busy = true
        importNotice = null
        scope.launch {
            var report: ApiMemberImportReport? = null
            var failure: String? = null
            try {
                val r = ApiClient.importProjectMembers(projectId, role, dryRun)
                if (r.dryRun != dryRun) error("the answer does not match the request")
                report = r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = memberFailureText((e as? ApiException)?.status)
            } finally {
                busy = false
            }
            val done = report
            if (done != null) {
                importShown = done
                if (dryRun) {
                    importDry = done
                    importNotice = ActionNotice(MEMBER_IMPORT_DRY_DONE_MESSAGE, ok = true)
                } else {
                    importDry = null
                    importConfirmOpen = false
                    importError = null
                    importNotice = ActionNotice(MEMBER_IMPORT_DONE_MESSAGE, ok = true)
                    reload += 1
                }
            } else if (dryRun) {
                importNotice = ActionNotice(failure ?: memberFailureText(null), ok = false)
            } else {
                importError = failure ?: memberFailureText(null)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SelectableText("メンバー（運用者のみ）", style = MaterialTheme.typography.titleMedium)
        SelectableText(
            "担当をこのプロジェクトのメンバーにし、役割（role）を決めます。プロジェクトの最後の管理者（ADMIN）は外せず、role も変えられません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LoadedList(members, emptyText = MEMBER_LIST_EMPTY_MESSAGE) { list ->
            list.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectableText(memberLabel(m), modifier = Modifier.weight(1f))
                    OptionPicker(
                        label = "役割",
                        options = MEMBER_ROLES.map { it to it },
                        selected = m.role,
                        onSelect = { role -> if (role != null) changeRole(m, role) },
                        enabled = !busy && !expired,
                    )
                    OutlinedButton(
                        enabled = !busy && !expired,
                        onClick = {
                            removeError = null
                            removeTarget = m
                        },
                    ) { SelectableText("外す…") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SelectableText("追加", style = MaterialTheme.typography.titleSmall)
        picker.value?.error?.let { SelectableText(it, color = MaterialTheme.colorScheme.error) }
        if (candidates.isEmpty()) {
            SelectableText(MEMBER_NO_CANDIDATE_MESSAGE, style = MaterialTheme.typography.bodySmall)
        } else {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OptionPicker(
                    label = "担当",
                    options = candidates.map { it.assigneeId to it.displayName },
                    selected = chosen?.assigneeId,
                    onSelect = { pickedAssignee = it },
                    enabled = !busy && !expired,
                )
                OptionPicker(
                    label = "役割（role）",
                    options = MEMBER_ROLES.map { it to it },
                    selected = addRole,
                    onSelect = { addRole = it ?: addRole },
                    enabled = !busy && !expired,
                )
                Button(enabled = !busy && !expired, onClick = { addMember() }) { SelectableText("追加") }
            }
        }
        memberNotice?.let { n ->
            SelectableText(
                n.text,
                color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        SelectableText("全担当の取り込み", style = MaterialTheme.typography.titleSmall)
        SelectableText(
            "有効で、まだメンバーでない担当を、指定の role でこのプロジェクトに追加します。すでにメンバーの人の role は変えません。" +
                "まず確認（dry-run）をしてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionPicker(
                label = "取り込みの役割（role）",
                options = MEMBER_ROLES.map { it to it },
                selected = importRole,
                onSelect = { importRole = it ?: importRole },
                enabled = !busy && !expired,
            )
            OutlinedButton(enabled = !busy && !expired, onClick = { runImport(dryRun = true, role = importRole) }) {
                SelectableText("全担当を取り込む（dry-run）")
            }
            Button(
                enabled = canRunMemberImport(importDry, busy) && !expired,
                onClick = { importConfirmOpen = true },
            ) { SelectableText("全担当を取り込む…") }
        }
        importNotice?.let { n ->
            SelectableText(
                n.text,
                color = if (n.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        importShown?.let { report ->
            SelectableText(memberImportSummary(report), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
    }

    // 外すの確認(旧 UI の members-dialog の remove)。
    val removing = removeTarget
    if (removing != null) {
        ReasonDialog(
            title = "メンバー",
            message = memberRemoveExplain(removing.displayName),
            submitLabel = "実行する",
            reasonLabel = null,
            busy = busy,
            errorText = removeError,
            onSubmit = { removeMember(removing) },
            onDismiss = {
                if (!busy) {
                    removeTarget = null
                    removeError = null
                }
            },
        )
    }

    // 取り込みの実行の確認。dry-run の結果が無い、または対象が 0 件のときは開かない。失敗時は開いたままにする。
    val dry = importDry
    if (importConfirmOpen && dry != null) {
        ReasonDialog(
            title = "全担当の取り込み",
            message = memberImportExplain(dry),
            submitLabel = "実行する",
            reasonLabel = null,
            busy = busy,
            errorText = importError,
            onSubmit = { runImport(dryRun = false, role = dry.role) },
            onDismiss = {
                if (!busy) {
                    importConfirmOpen = false
                    importError = null
                }
            },
        )
    }
}
