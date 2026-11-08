package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiStopRequest

/*
 * 緊急停止系の運用操作の純粋ロジック(画面に依存しない)。自律運転(一時停止・再開・ブレーカーの手動再開)と、
 * order の一時停止・再開・停止要求・取り消し。旧 UI(kivra-app/src/main/resources/ui/app.js の renderAutopilot /
 * onAutopilotToggle / onBreakerReset、ordRenderDetail / ordWrite / ordOpenStop / ordCancelStop)と、
 * サーバー(routes/SelfDevGoalRoutes.kt、routes/SelfDevOrderRoutes.kt、kivra-core の OrderStates.kt)の規則に合わせる。
 */

/** 自律運転の節(と、その項目のナビ)を出すかの状態。ENABLED は GET /api/selfdev/autopilot が 200 で enabled のとき。 */
enum class AutopilotAvailability { LOADING, ENABLED, HIDDEN }

/** `GET /api/selfdev/autopilot` の `enabled`。true だけ ENABLED、false・null(読めなかった)は HIDDEN(何も出さない)。 */
fun autopilotAvailabilityOf(enabled: Boolean?): AutopilotAvailability =
    if (enabled == true) AutopilotAvailability.ENABLED else AutopilotAvailability.HIDDEN

/** 項目を出すか。自動化の項目は自律運転が有効なときだけ出す(旧 UI の自律運転の節と同じ条件)。他の項目は隠さない。 */
fun navVisibleForAutopilot(destination: NavDestination, availability: AutopilotAvailability): Boolean =
    destination != NavDestination.Autopilot || availability == AutopilotAvailability.ENABLED

const val AUTOPILOT_PAUSED_DONE = "自律運転を一時停止しました。新しい投入は止まります（投入済みの Run は止めません）。"
const val AUTOPILOT_RESUMED_DONE = "自律運転を再開しました。"
const val AUTOPILOT_BREAKER_DONE = "サーキットブレーカーを再開しました（連続失敗の数を 0 に戻しました）。"

/** 自律運転の全体の状態(旧 UI の autopilot-status と同じ文言)。 */
fun autopilotOverallText(paused: Boolean, runsToday: Int, maxRunsPerDay: Int): String =
    if (paused) {
        "全体: 一時停止中（新しい投入は止まっています。投入済みの Run は止めません）"
    } else {
        "全体: 稼働中（今日の Run $runsToday / $maxRunsPerDay 件）"
    }

/** 自動マージの一時停止の状態(旧 UI の auto-merge-status と同じ文言)。 */
fun autoMergeText(paused: Boolean): String =
    if (paused) "自動マージ: 一時停止中（自動マージの判定を行っていません）" else "自動マージ: 一時停止していません"

/** 一時停止・再開の切り替えのボタンの文言。 */
fun autopilotToggleLabel(paused: Boolean): String = if (paused) "自律運転を再開" else "自律運転を一時停止"

/** ブレーカーが開いている時の説明(旧 UI の breaker-status と同じ文言)。 */
fun breakerOpenText(consecutiveFailures: Int): String =
    "連続で $consecutiveFailures 件失敗したため、自律運転を止めています。原因を確認してから再開してください。"

/** 再開できない停止の理由。kivra-core の NOT_RESUMABLE(LIMIT_REACHED / EXPIRED)と同じ。運用者は再開できず、新しい order が要る。 */
private val ORDER_NOT_RESUMABLE_REASONS = listOf("LIMIT_REACHED", "EXPIRED")

const val ORDER_PAUSED_DONE = "order を一時停止しました。"
const val ORDER_RESUMED_DONE = "order を再開しました。"
const val ORDER_STOP_CONFIRM = "この order の停止を要求します。承認者が承認するまで、order は停止しません。"
const val ORDER_STOP_REQUESTED_DONE = "停止を要求しました。承認されるまで、order は停止しません。"
const val ORDER_STOP_CANCELLED_DONE = "停止の要求を取り消しました。order は一時停止中になります（自動では再開しません）。"

/** order を一時停止できるか。ACTIVE のときだけ(サーバー: ACTIVE -> PAUSED は運用者の pause)。 */
fun canPauseOrder(state: String): Boolean = state == "ACTIVE"

/** order を再開できるか。PAUSED で、再開できない理由(LIMIT_REACHED / EXPIRED)でないとき。理由が無い PAUSED は再開できる。 */
fun canResumeOrder(state: String, stateReason: String?): Boolean =
    state == "PAUSED" && ORDER_NOT_RESUMABLE_REASONS.none { it == stateReason }

/** 停止を要求できるか。ACTIVE か PAUSED で、待っている要求が無いとき(サーバー: ACTIVE・PAUSED から STOP_REQUESTED へ)。 */
fun canRequestOrderStop(state: String, hasPendingRequest: Boolean): Boolean =
    (state == "ACTIVE" || state == "PAUSED") && !hasPendingRequest

/** 待っている停止要求を取り消せるか。PENDING の要求があるとき。要求者の確認はサーバーが行う(403 になりうる)。 */
fun canCancelOrderStop(pending: ApiStopRequest?): Boolean = pending?.state == "PENDING"

/**
 * 運用者が要求できる停止の理由(コードと表示名)。kivra-core の StopRequestReason.REQUESTABLE と同じ。
 * 自動の子の停止の理由(CHILD_CYCLE_FAILURES / CHILD_BUDGET_EXHAUSTED)は含まない。
 */
val REQUESTABLE_STOP_REASONS: List<Pair<String, String>> = listOf(
    "OPERATOR_REQUEST" to "運用者の要求",
    "RUNAWAY_SUSPECTED" to "暴走の疑い",
    "BUDGET_CONCERN" to "予算への懸念",
    "SCOPE_CONCERN" to "範囲への懸念",
)

/** 停止の理由のコードが要求できるものか。null や知らないコードは false。 */
fun isRequestableStopReason(code: String?): Boolean = REQUESTABLE_STOP_REASONS.any { it.first == code }
