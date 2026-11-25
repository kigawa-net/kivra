package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiClaudeAccountOption
import net.kigawa.kivra.ui.api.ApiProjectClaudeAccount

internal sealed interface ClaudeAccountChange {
    data class Set(val account: ApiClaudeAccountOption) : ClaudeAccountChange
    data object Clear : ClaudeAccountChange
}

internal fun ApiProjectClaudeAccount.currentClaudeAccountLabel(): String {
    val id = accountId ?: return "未設定"
    return available.firstOrNull { it.id == id }?.label ?: "$id（選択肢にありません）"
}

internal fun ApiProjectClaudeAccount.claudeAccountChange(targetId: String?): ClaudeAccountChange? {
    if (targetId == accountId) return null
    if (targetId == null) return ClaudeAccountChange.Clear
    return available.firstOrNull { it.id == targetId }?.let { ClaudeAccountChange.Set(it) }
}

internal fun ApiProjectClaudeAccount.claudeAccountEmptyMessage(): String? =
    if (available.isEmpty()) "選択できるアカウントがありません（proxy にアカウントが登録されていません）。" else null

internal fun claudeAccountWriteFailure(status: Int?): String = when (status) {
    401 -> SESSION_EXPIRED_MESSAGE
    400 -> "このアカウントは選択できません。画面を更新してから選び直してください。"
    403 -> "この操作は運用者だけができます。"
    404 -> "このプロジェクトは見つかりません。画面を読み直してから、もう一度お試しください。"
    else -> "操作に失敗しました。時間をおいてもう一度お試しください。"
}
