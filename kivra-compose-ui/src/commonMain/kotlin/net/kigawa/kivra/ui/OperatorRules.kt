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

/** Run の解放の種類。UNKNOWN -> CANCELLED と、GitHub の PR と結び付いていない PULL_REQUEST -> CANCELLED。 */
enum class ReleaseKind { UNKNOWN, UNBOUND_PR }

private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val ACTUAL_SHAPE = Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")

/** Goal の終端の状態。終端の Goal には中止を出さない(app.js の TASK_TERMINAL と同じ)。 */
private val GOAL_TERMINAL_STATES = setOf("COMPLETED", "FAILED", "CANCELLED", "SKIPPED", "DONE", "STOPPED")

/**
 * Run に出す解放の種類。null は解放ボタンを出さない。app.js の releaseKindOf と同じ条件:
 * 自己開発(SELF_DEV)の Run で、UNKNOWN、または PULL_REQUEST かつ PR の番号が無い(束縛なし)のとき。
 */
fun releaseKindOf(run: ApiRun): ReleaseKind? {
    if (run.mode != "SELF_DEV" || !RUN_ID_SHAPE.matches(run.runId)) return null
    return when {
        run.state == "UNKNOWN" -> ReleaseKind.UNKNOWN
        run.state == "PULL_REQUEST" && run.pullRequestNumber == null -> ReleaseKind.UNBOUND_PR
        else -> null
    }
}

/** 解放の POST 先。UNKNOWN は `.../release`、束縛なしの PR は `.../release-unbound-pr`。 */
fun releasePath(runId: String, kind: ReleaseKind): String {
    val suffix = when (kind) {
        ReleaseKind.UNKNOWN -> "release"
        ReleaseKind.UNBOUND_PR -> "release-unbound-pr"
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
}

/**
 * 運用操作の失敗の日本語の文言。[failure] は [writeFailureOf] の結果、[code] は 400 のときの機械的なコード
 * (予算解除の TOO_LARGE / NOT_COMPARABLE)。サーバーの error 本文は表示しない(既存の規則と同じ)。
 * 通信・セッション・権限・未検出・不明は [writeFailureText] の共通文言を使う。
 */
fun operatorWriteFailureText(action: OperatorAction, failure: WriteFailure, code: String? = null): String = when (failure) {
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
        -> writeFailureText(failure)
    }
    else -> writeFailureText(failure)
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
}
