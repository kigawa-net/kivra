package net.kigawa.kivra.ui

/*
 * Goal の再計画の純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js の renderTaskDetail、
 * PLAN_BLOCK_CODES、replanTask、taskWrite と AP.replanned)とサーバー(SelfDevGoalRoutes.kt の
 * POST /api/selfdev/goals/{id}/replan)に合わせる。
 * サーバーは BLOCKED の Task だけを TASK_SUBMITTED へ戻す(それ以外は 409)。権限は GOAL_WRITE(旧 UI の can('GOAL_WRITE'))。
 */

/** 計画の失敗で止まった Goal の理由。この理由の BLOCKED だけ再計画を出す(旧 UI の PLAN_BLOCK_CODES)。 */
private val PLAN_BLOCK_CODES = setOf("PLAN_INVALID", "PLAN_BUDGET_HOLD", "PLAN_CALL_FAILED")

/** 再計画を出すか(状態と理由。権限は別: [GatedOperation.REPLAN_GOAL])。 */
fun canReplanGoal(state: String, stateReason: String?): Boolean =
    state == "BLOCKED" && stateReason != null && stateReason in PLAN_BLOCK_CODES

const val REPLAN_REQUESTED_MESSAGE = "再計画を要求しました。Planner が新しい計画を作ります。"

/** 再計画の失敗の文言(旧 UI の taskWrite の AP 文言。サーバーの本文は出さない)。 */
fun replanFailureText(status: Int?): String = when (status) {
    null -> "通信に失敗しました。もう一度お試しください。"
    401 -> SESSION_EXPIRED_MESSAGE
    409 -> "状態が変わっていました。一覧と詳細を読み直してから、もう一度操作してください。"
    404 -> "見つかりません（既に消えたか、ID が違います）。"
    400 -> "入力内容を確認してください（1〜200 文字、制御文字は使えません）"
    else -> "結果を確認できませんでした。一覧を読み直してください。"
}
