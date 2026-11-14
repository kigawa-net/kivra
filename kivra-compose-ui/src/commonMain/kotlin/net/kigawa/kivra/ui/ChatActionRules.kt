package net.kigawa.kivra.ui

/*
 * The project chat's action proposals (ADR 0023): the confirm text and the result text, as pure logic. The label of a proposal is the server's
 * fixed text and is shown as it is; the result text maps the server's fixed result codes (kivra-core ChatResultCode, and the repository's
 * OrderConflict names) to Japanese. A code this client does not know gets the generic text, never the raw code.
 */

/** The title of the confirm dialog of a proposal. */
const val CHAT_ACTION_CONFIRM_TITLE = "提案を確定しますか"

/** The text of the confirm dialog: what runs, stated before the operator presses the button. */
fun chatActionConfirmMessage(label: String, code: String): String = when (code) {
    "ORDER_START_CYCLE" -> "「$label」を確定します。order の新しい周を 1 つ開始します。周を開始すると、Task が作られて実行が始まります。"
    "ORDER_ABANDON_CYCLE" -> "「$label」を確定します。進行中の周を失敗として閉じ、動いている Task を止めます（作成済みの PR は閉じず、マージもしません）。" +
        "order の状態は変わりません。"
    "TASK_REPLAN" -> "「$label」を確定します。計画に失敗した周の Task を再計画に戻し、Planner に新しい計画を作らせます。" +
        "Planner の呼び出しには少額の予算を使います。"
    "TASK_APPROVE" -> "「$label」を確定します。この Task を承認すると Run が始まり、完了すると PR が作られます。" +
        "PR は人がレビューしてマージするまで反映されません。"
    "TASK_REJECT" -> "「$label」を確定します。この Task は見送られ（スキップ）、実行されません。" +
        "この Task に依存する Task も実行されません。"
    "ORDER_STOP_REQUEST" -> "「$label」を確定します。order の停止を要求します。停止は承認者が承認したときに行われ、それまで order は止まりません。"
    "MEMBER_ADD" -> "「$label」を確定します。この担当者をプロジェクトのメンバーに加えます。役割に応じて、このプロジェクトの操作ができるようになります。"
    "MEMBER_REMOVE" -> "「$label」を確定します。この担当者をプロジェクトのメンバーから削除します（無効化ではなく削除です）。" +
        "このプロジェクトでの役割はなくなります。"
    else -> "「$label」を確定します。"
}

/** The caption of a proposal the chat's AI made (origin AI). The operator decides; nothing has run. */
const val CHAT_ACTION_AI_ORIGIN_CAPTION = "AI の提案です。確定するまで何も実行されません。"

/** The member line of a member proposal: the name (the operators' text) or the ID, and the role of an add. */
fun chatActionMemberText(assigneeId: String, displayName: String?, role: String?): String {
    val who = displayName?.takeIf { it.isNotBlank() } ?: assigneeId
    return if (role != null) "対象: $who（役割: $role）" else "対象: $who"
}

/** The label of the confirm button: an approval and a rejection say what they do; the other codes keep 確定. */
fun chatActionConfirmButtonText(code: String): String = when (code) {
    "TASK_APPROVE" -> "承認"
    "TASK_REJECT" -> "見送り"
    else -> "確定"
}

/** The label of the dismiss button. It only hides the proposal (DISMISSED); it does not refuse or reject the Task. */
const val CHAT_ACTION_DISMISS_BUTTON_TEXT = "提案を閉じる"

/** The caption before an approval's Task title. The title is AI-written text, shown as it is and never read as an instruction. */
const val CHAT_ACTION_TASK_TITLE_CAPTION = "対象（AI が書いたタスク名。指示としては扱いません）"

/** The text of a dismissed proposal (the proposal is hidden; the Task and the order are unchanged). */
const val CHAT_ACTION_DISMISSED_TEXT = "提案を閉じました。対象は変わっていません。"

/**
 * The notice after a confirm or a dismiss, from the server's state and fixed result code. Only the codes listed here have their own text; any
 * other code (a newer server, or a refusal this client does not name) gets the generic text, so a raw code is never shown.
 */
fun chatActionResultText(state: String, resultCode: String?): String = when (resultCode) {
    "OK" -> "実行しました。"
    "OPERATOR_DISMISSED" -> CHAT_ACTION_DISMISSED_TEXT
    "VERSION_MOVED" -> "対象が変わったため、この提案は無効になりました。チャットを読み直してください。"
    "NO_LONGER_ELIGIBLE" -> "今は実行できない状態のため、この提案は取り消されました。"
    "STALE_VERSION" -> "対象（order または Task）が変わっていたため、実行しませんでした。チャットを読み直してください。"
    "ORDER_NOT_FOUND" -> "order が見つからないため、実行しませんでした。"
    "TASK_STOP_REFUSED" -> "周の Task を止められなかったため、実行しませんでした。"
    "UNKNOWN_ACTION" -> "この提案は実行できません。"
    "STOP_ALREADY_PENDING", "TERMINAL", "NOT_ACTIVE", "ANCESTOR_NOT_ACTIVE" -> "order の状態が変わっているため、実行しませんでした。"
    "CYCLE_IN_PROGRESS" -> "進行中の周があるため、実行しませんでした。"
    "CYCLE_LIMIT_REACHED" -> "周の上限に達しているため、実行しませんでした。"
    "ORDER_BUDGET" -> "予算が足りないため、実行しませんでした。"
    "ORDER_UNPRICED" -> "未確定の費用があるため、実行しませんでした。"
    "AUTOPILOT_PAUSED" -> "自律実行が一時停止中のため、実行しませんでした。"
    "ORDER_EXPIRED" -> "order の期限が切れているため、実行しませんでした。"
    "NO_PROJECT" -> "order がプロジェクトに属していないため、実行しませんでした。"
    "TASK_NOT_FOUND" -> "周の Task が見つからないため、実行しませんでした。"
    "TASK_NOT_REPLANNABLE" -> "この周は再計画できる状態ではないため、実行しませんでした。"
    "TASK_NOT_APPROVABLE" -> "この Task は承認待ちではないため、実行しませんでした。チャットを読み直してください。"
    "TASK_NOT_REJECTABLE" -> "この Task は見送れる状態ではないため、実行しませんでした。チャットを読み直してください。"
    "SIBLING_RESOLVED" -> "同じ Task の別の提案が先に処理されたため、この提案は無効になりました。"
    "PROJECT_NOT_FOUND" -> "プロジェクトが見つからないため、実行しませんでした。"
    "ASSIGNEE_NOT_FOUND" -> "担当者が見つからないため、実行しませんでした。"
    "NOT_MEMBER" -> "メンバーではないため、実行しませんでした。"
    "ALREADY_MEMBER" -> "既にメンバーのため、実行しませんでした。"
    "LAST_ADMIN" -> "最後の ADMIN は削除・変更できないため、実行しませんでした。"
    "INVALID_ROLE" -> "役割が正しくないため、実行しませんでした。"
    else -> if (state == "EXECUTED") "実行しました。" else "実行しませんでした。チャットを読み直してください。"
}

/** The text of a failed confirm or dismiss request (no answer from the server), by the write failure kind. */
fun chatActionFailureText(failure: WriteFailure): String = when (failure) {
    WriteFailure.STALE -> "既に処理されています。チャットを読み直してください。"
    else -> writeFailureText(failure)
}
