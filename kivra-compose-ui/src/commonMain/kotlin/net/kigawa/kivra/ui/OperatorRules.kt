package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiRun

/*
 * 運用操作(Run の解放・Goal の中止・予算の未確定予約の解除)の純粋ロジック。画面に依存しない。
 * 旧 UI(kivra-app/src/main/resources/ui/app.js)とサーバー(SelfDevAdminRoutes.kt / SelfDevGoalRoutes.kt /
 * BudgetAdminRoutes.kt)の規則に合わせる。
 */

/** Run の解放の理由の上限(コードポイント)。app.js の RELEASE_MAX とサーバーの MAX_RELEASE_REASON_CHARS。 */
const val RUN_RELEASE_REASON_MAX = 500

/** Goal 中止の理由の上限(コードポイント)。app.js の GOAL_STOP_MAX とサーバーの MAX_REASON_CODE_POINTS。 */
const val GOAL_STOP_REASON_MAX = 500

/** 予算解除の根拠と理由の上限(コードポイント)。app.js の BUDGET_TEXT_MAX。サーバー側の上限は確認できないため画面の規則に合わせる。 */
const val BUDGET_TEXT_MAX = 500

/** 予算解除の実額の最大長(文字数)。app.js の BUDGET_ACTUAL_MAX。 */
const val BUDGET_ACTUAL_MAX = 24

/** 実額の入力欄の補足。app.js の BUDGET_ACTUAL_HINT と同じ。 */
const val BUDGET_ACTUAL_HINT = "半角の数字と小数点で入力します（小数は 6 桁まで）。実額は計上額の 100 倍まで。"

/**
 * Run の解放の種類。UNKNOWN -> CANCELLED、GitHub の PR と結び付いていない PULL_REQUEST -> CANCELLED、
 * 止まった DEVELOPING -> CANCELLED(attempt が無く、一定時間更新が無い Run だけ。判定はサーバー)。
 */
enum class ReleaseKind { UNKNOWN, UNBOUND_PR, STUCK_DEVELOPING }

private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val ACTUAL_SHAPE = Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")

/** Goal の終端の状態。終端の Goal には中止を出さない(app.js の TASK_TERMINAL と同じ)。 */
private val GOAL_TERMINAL_STATES = setOf("COMPLETED", "FAILED", "CANCELLED", "SKIPPED", "DONE", "STOPPED")

/**
 * Run に出す解放の種類。null は解放ボタンを出さない。app.js の releaseKindOf と同じ条件:
 * 自己開発(SELF_DEV)の Run で、UNKNOWN、PULL_REQUEST かつ PR の番号が無い(束縛なし)、または DEVELOPING かつ PR の番号が無いとき。
 * DEVELOPING は attempt の有無・lease・最終更新の経過を画面では判らないため、状態だけで出し、解放できるかはサーバーが決める。
 */
fun releaseKindOf(run: ApiRun): ReleaseKind? {
    if (run.mode != "SELF_DEV" || !RUN_ID_SHAPE.matches(run.runId)) return null
    return when {
        run.state == "UNKNOWN" -> ReleaseKind.UNKNOWN
        run.state == "PULL_REQUEST" && run.pullRequestNumber == null -> ReleaseKind.UNBOUND_PR
        run.state == "DEVELOPING" && run.pullRequestNumber == null -> ReleaseKind.STUCK_DEVELOPING
        else -> null
    }
}

/** 解放の POST 先。UNKNOWN は `.../release`、束縛なしの PR は `.../release-unbound-pr`、止まった DEVELOPING は `.../release-stuck-developing`。 */
fun releasePath(runId: String, kind: ReleaseKind): String {
    val suffix = when (kind) {
        ReleaseKind.UNKNOWN -> "release"
        ReleaseKind.UNBOUND_PR -> "release-unbound-pr"
        ReleaseKind.STUCK_DEVELOPING -> "release-stuck-developing"
    }
    return "/api/admin/selfdev/runs/${percentEncode(runId)}/$suffix"
}

/** Goal に中止を出すか。終端でなければ出す(app.js と同じ。計画の親タスクでも出す)。 */
fun canStopGoal(state: String): Boolean = state !in GOAL_TERMINAL_STATES

/** 実額の入力が形式に合うか。空欄は「実額不明」として許す(送らない)。app.js の budgetFormState と同じ。 */
fun isValidBudgetActual(raw: String): Boolean {
    val text = raw.trim()
    return text.isEmpty() || (ACTUAL_SHAPE.matches(text) && text.length <= BUDGET_ACTUAL_MAX)
}

/**
 * 操作の種類。失敗の文言を操作ごとに出し分けるために使う。PAUSE_AUTOPILOT 以降は緊急停止系
 * (自律運転・order の一時停止・再開、停止要求と取り消し)。
 */
enum class OperatorAction {
    RELEASE_RUN,
    STOP_GOAL,
    ACKNOWLEDGE_BUDGET,
    PAUSE_AUTOPILOT,
    RESUME_AUTOPILOT,
    RESET_BREAKER,
    PAUSE_ORDER,
    RESUME_ORDER,
    REQUEST_STOP,
    CANCEL_STOP,
    EXTEND_ORDER,
}

/**
 * 運用操作の失敗の日本語の文言。[failure] は [writeFailureOf] の結果、[code] は 400 のときの機械的なコード
 * (予算解除の TOO_LARGE / NOT_COMPARABLE)。サーバーの error 本文は表示しない(既存の規則と同じ)。
 * 通信・セッション・権限・未検出・不明は [writeFailureText] の共通文言を使う。
 */
fun operatorWriteFailureText(action: OperatorAction, failure: WriteFailure, code: String? = null): String {
    // 延長は 400 / 409 の機械的なコード(MAX_CYCLES_INVALID など)を表示に対応づける。規則は OrderExtendRules.kt。
    if (action == OperatorAction.EXTEND_ORDER) return extendFailureText(failure, code)
    if (action == OperatorAction.RELEASE_RUN && failure == WriteFailure.STALE) releaseConflictText(code)?.let { return it }
    return when (failure) {
        WriteFailure.STALE -> when (action) {
            OperatorAction.RELEASE_RUN -> "この Run は解放できる状態ではありません（状態が既に変わっています）。詳細を読み直してください。"
            OperatorAction.ACKNOWLEDGE_BUDGET -> "この予約は未確定ではありません（既に解除されたか、状態が変わりました）。一覧を更新してください。"
            OperatorAction.RESUME_ORDER -> "この order は再開できる状態ではありません（状態が既に変わっています）。詳細を読み直してください。"
            OperatorAction.STOP_GOAL,
            OperatorAction.PAUSE_AUTOPILOT,
            OperatorAction.RESUME_AUTOPILOT,
            OperatorAction.RESET_BREAKER,
            OperatorAction.PAUSE_ORDER,
            OperatorAction.REQUEST_STOP,
            OperatorAction.CANCEL_STOP,
            OperatorAction.EXTEND_ORDER,
            -> writeFailureText(failure)
        }
        WriteFailure.BAD_REQUEST -> when (action) {
            OperatorAction.RELEASE_RUN -> "理由を確認してください（1〜500 文字、空白だけは使えません）。"
            OperatorAction.STOP_GOAL -> "理由を確認してください（1〜500 文字、制御文字は使えません）。"
            OperatorAction.ACKNOWLEDGE_BUDGET -> when (code) {
                "TOO_LARGE" -> "実額が計上額の 100 倍を超えています。実額を確かめてください（上限は計上額の 100 倍まで）。"
                "NOT_COMPARABLE" -> "実額の単位が予約と合いません。円の数字で入力してください。"
                else -> "入力を確認してください（実額は円の数字、根拠と理由は 1〜500 文字、制御文字は使えません）。"
            }
            OperatorAction.PAUSE_AUTOPILOT,
            OperatorAction.RESUME_AUTOPILOT,
            OperatorAction.RESET_BREAKER,
            OperatorAction.PAUSE_ORDER,
            OperatorAction.RESUME_ORDER,
            OperatorAction.REQUEST_STOP,
            OperatorAction.CANCEL_STOP,
            OperatorAction.EXTEND_ORDER,
            -> writeFailureText(failure)
        }
        WriteFailure.DISABLED -> when (action) {
            OperatorAction.STOP_GOAL,
            OperatorAction.PAUSE_AUTOPILOT,
            OperatorAction.RESUME_AUTOPILOT,
            OperatorAction.RESET_BREAKER,
            -> "自律運転が今は無効です。時間をおいて、もう一度お試しください。"
            OperatorAction.ACKNOWLEDGE_BUDGET -> "予算の台帳に書けませんでした。予約は変わっていません。時間をおいて、もう一度お試しください。"
            OperatorAction.RELEASE_RUN -> writeFailureText(WriteFailure.UNKNOWN)
            OperatorAction.PAUSE_ORDER,
            OperatorAction.RESUME_ORDER,
            OperatorAction.REQUEST_STOP,
            OperatorAction.CANCEL_STOP,
            OperatorAction.EXTEND_ORDER,
            -> writeFailureText(failure)
        }
        else -> writeFailureText(failure)
    }
}

/**
 * 止まった DEVELOPING の解放を 409 で断られたときの文言(サーバーの code ごと)。code が無い・知らない値は null
 * (通常の「状態が既に変わっています」の文言を使う)。
 */
fun releaseConflictText(code: String?): String? = when (code) {
    "ACTIVE_ATTEMPT" -> "この Run には実行中（待ち・取得済み・実行中）の attempt があります。エージェントが動いているかもしれないため解放できません。attempt の状態とエージェントのログを確かめてください。"
    "TOO_RECENT" -> "この Run は最近まで更新されていました。一定時間（10 分）更新が無い Run だけ解放できます。しばらくしてからもう一度お試しください。"
    "LEASE_HELD" -> "この Run は worker が掴んでいます。動いている可能性があるため解放できません。時間をおいて、もう一度お試しください。"
    "PR_BOUND" -> "この Run には PR が結び付いています。この操作では解放できません。"
    "NOT_DEVELOPING" -> "この Run は DEVELOPING ではなくなりました（状態が既に変わっています）。詳細を読み直してください。"
    else -> null
}

private val CODING_KEY_SHAPE = Regex("coding:([^:]+):([0-9]+)")

/** 予約の鍵の短い表示名(app.js の keyLabel と同じ)。CLI の鍵(coding:<attemptId>:<世代>)は attempt を出す。 */
fun reservationLabel(key: String): String {
    val m = CODING_KEY_SHAPE.matchEntire(key)
    if (m != null) return "CLI の attempt ${m.groupValues[1].take(8)}…（世代 ${m.groupValues[2]}）"
    return "予約 ${key.take(8)}…"
}

/** 経路の表示名(app.js の pathLabel と同じ)。 */
fun budgetPathLabel(path: String): String = if (path == "cli") "CLI 経路" else "API 経路"

/** 解放ボタンの表示名。 */
fun releaseButtonLabel(kind: ReleaseKind): String = when (kind) {
    ReleaseKind.UNKNOWN -> "運用者の解放…"
    ReleaseKind.UNBOUND_PR -> "解放（束縛なし）…"
    ReleaseKind.STUCK_DEVELOPING -> "運用者の解放（止まった Run）…"
}

/** 解放の画面の文言(app.js の RELEASE_TEXT と同じ)。 */
data class ReleaseTexts(val title: String, val explain: String)

fun releaseTextsOf(kind: ReleaseKind): ReleaseTexts = when (kind) {
    ReleaseKind.UNKNOWN -> ReleaseTexts(
        title = "運用者の解放（UNKNOWN から CANCELLED へ）",
        explain = "この Run を CANCELLED にします。PR は閉じません・マージしません（PR の扱いは人が決めます）。同じ原因が続けば、次の Run も UNKNOWN になる可能性があります。解放は監査ログに残ります。",
    )
    ReleaseKind.UNBOUND_PR -> ReleaseTexts(
        title = "解放（GitHub の PR と結び付いていない Run）",
        explain = "この Run を CANCELLED にします。この Run は GitHub の PR と結び付いていないため、PR の状態は確認しません。GitHub には何も書き込みません（PR は閉じず、マージもしません）。",
    )
    ReleaseKind.STUCK_DEVELOPING -> ReleaseTexts(
        title = "解放（止まった DEVELOPING の Run）",
        explain = "この Run を CANCELLED にします。attempt が無く、一定時間（10 分）更新が無い Run だけ解放できます（attempt が有る Run、worker が掴んでいる Run、PR が結び付いた Run は解放できません）。先に attempt とエージェントのログを確かめてください。PR は閉じません・マージしません。解放は監査ログに残ります。",
    )
}
