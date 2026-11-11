package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiCurrentCycle

/*
 * order の進行中の周の表示と、周の放棄の純粋ロジック(画面に依存しない)。ADR 0015 の 2026-11-11 追補(V40 で消えた Goal を指す
 * current_goal_id)と、サーバーの POST /api/selfdev/orders/{id}/cycles/abandon(kivra-app の routes/SelfDevOrderRoutes.kt)の規則に合わせる。
 */

/** 周の放棄の確認文。何が起きて、何が起きないかを先に言う。 */
const val ORDER_ABANDON_CONFIRM = "進行中の周を失敗として閉じます。連続して失敗した周が 1 増えます。" +
    "動いている Task は止めます（作成済みの PR は閉じず、マージもしません）。order の状態は変わりません。"

/** 周の放棄の成功の文言。 */
const val ORDER_ABANDONED_DONE = "進行中の周を失敗として閉じました。order の状態は変わっていません。"

/** 進行中の周が無く、周も無いときの文言。 */
const val CYCLES_NOT_STARTED_TEXT = "周はまだ開始されていません"

/** Task 行が無い(旧 Goal を指している)ときの文言。 */
const val CURRENT_CYCLE_TASK_MISSING_TEXT = "Task が見つからない（旧 Goal）。自動で、周を失敗として閉じます"

/**
 * 周を放棄できる order か。ACTIVE で、進行中の周(`currentGoalId`)があるときだけ
 * (PAUSED は画面では出さない。STOP_REQUESTED・STOPPED と、周が無いときはサーバーが 409)。
 */
fun canAbandonCycle(state: String, currentGoalId: String?): Boolean = state == "ACTIVE" && !currentGoalId.isNullOrEmpty()

/**
 * 進行中の周の状態の表示。`currentGoalId` が無ければ null(出さない)。[cycle] が `taskMissing` なら
 * [CURRENT_CYCLE_TASK_MISSING_TEXT]、Task の状態があれば「状態名（理由）」。サーバーの応答に `currentCycle` が無い(古いサーバー)ときは、
 * 状態を知らない旨だけを出す。
 */
fun currentCycleText(currentGoalId: String?, cycle: ApiCurrentCycle?): String? {
    if (currentGoalId.isNullOrEmpty()) return null
    if (cycle == null) return "状態は不明です"
    if (cycle.taskMissing) return CURRENT_CYCLE_TASK_MISSING_TEXT
    val state = cycle.taskState ?: return "状態は不明です"
    val label = goalStateText(state).label
    val reason = goalReasonText(cycle.taskReason)
    return if (reason.isEmpty()) label else "$label（$reason）"
}

/**
 * 周の一覧が空のときの文言。進行中の周があれば、その状態([currentCycleText])を出し、無ければ「まだ開始されていません」。
 * 周の行が空でも、`currentGoalId` があるなら「開始されていません」とは言わない。
 */
fun emptyCyclesText(currentGoalId: String?, cycle: ApiCurrentCycle?): String =
    currentCycleText(currentGoalId, cycle)?.let { "進行中の周: $it" } ?: CYCLES_NOT_STARTED_TEXT

/**
 * 周の放棄の失敗の日本語の文言。[code] は 409 の機械的なコード(サーバーの固定値)。code の無い 409 は版の競合。
 * 400 は理由の確認。それ以外は [writeFailureText] の共通文言。サーバーの error 本文は表示しない。
 */
fun abandonFailureText(failure: WriteFailure, code: String?): String = when (failure) {
    WriteFailure.BAD_REQUEST -> "理由を確認してください（1〜500 文字、制御文字は使えません）。"
    WriteFailure.STALE -> when (code) {
        "NO_CYCLE_IN_PROGRESS" -> "進行中の周がありません。詳細を読み直してください。"
        "CYCLE_ALREADY_ENDED" -> "この周の Task は既に成功で終わっています。自動の評価が周を閉じます。詳細を読み直してください。"
        "CYCLE_IN_PROGRESS" -> "この周の Task を止められませんでした。詳細を読み直して、もう一度お試しください。"
        "STOP_ALREADY_PENDING" -> "停止が要求されているため、周を放棄できません。"
        "TERMINAL" -> "停止済みの order の周は放棄できません。"
        else -> writeFailureText(WriteFailure.STALE)
    }
    else -> writeFailureText(failure)
}
