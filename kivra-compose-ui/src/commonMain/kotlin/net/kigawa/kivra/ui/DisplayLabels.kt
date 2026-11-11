package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiBudget

/*
 * 状態・理由コードの日本語の説明(表示だけ)。旧 UI(kivra-app/src/main/resources/ui/app.js)の STATES / REASONS /
 * ACTIONS / KINDS / ORDER_STATES / ORDER_REASONS / STOP_REASONS / TASK_STATES / GOAL_REASONS / ASG_STATES と、
 * stateInfo / reasonText / kindInfo / ordCodeText / ordStateInfo / taskStateInfo / goalReasonText の規則に合わせる。
 *
 * 表のどれにも無い値は、そのまま表示する(説明は付けない)。コードの形(大文字と `_`)だけを確かめる箇所は旧 UI と同じ。
 * 本文はサーバーの応答をそのまま出さず、ここの固定文言だけを使う(旧 UI と同じ)。
 */

/** 状態の表示。[label] は短い名前、[desc] は説明。 */
data class StateText(val label: String, val desc: String)

/** Run の種別の表示。[note] は説明の補足(無い種別は null)。 */
data class KindText(val label: String, val note: String?)

/** 表示の上限(旧 UI の MAX_TITLE)。Run の題名に使う。 */
const val RUN_TITLE_MAX = 200

private val CODE_SHAPE = Regex("[A-Z_]{1,40}")
private val YEN_SHAPE = Regex("-?[0-9]+(\\.[0-9]+)?")

private val RUN_STATES = mapOf(
    "CREATED" to "作成済み。まだ何も実行されていません",
    "PLANNING" to "計画中",
    "PREPARING" to "準備中",
    "DEVELOPING" to "実装中",
    "TESTING" to "テスト中",
    "REVIEWING" to "レビュー中",
    "PATCH_READY" to "パッチ準備完了（PR の作成を待っています）",
    "PULL_REQUEST" to "PR を作成済み。人がレビューしてマージします（kivra はマージしません）",
    "WAITING_APPROVAL" to "承認待ち",
    "RELEASING" to "リリース中",
    "VERIFYING" to "検証中",
    "RETRY_WAITING" to "再試行待ち",
    "COMPLETED" to "完了（マージを検証済み）",
    "FAILED" to "失敗",
    "CANCELLED" to "中止",
    "UNKNOWN" to "結果不明。自動では動きません。運用者が確認して解放するまで、同時上限を占有します",
)

private val RUN_REASONS = mapOf(
    "PR_ABANDONED" to "PR が閉じられた（マージされず）",
    "OPERATOR_RELEASED" to "運用者が解放した",
    "CANCEL_REQUESTED" to "中止が要求された",
    "PR_MERGED" to "PR がマージされ、マージを検証した",
    "PR_NOT_FOUND" to "PR が見つからない（GitHub 側の確認が必要）",
    "PR_AMBIGUOUS" to "PR が一つに決まらない（GitHub 側の確認が必要）",
    "GITHUB_UNKNOWN_OUTCOME" to "GitHub の結果が不明",
    "POLICY_REJECTED" to "変更がポリシーに通らなかった",
    "TASK_REFERENCE_UNRESOLVED" to "依頼文の参照（Issue）を解決できなかった",
    "TASK_REFERENCE_REPO_NOT_ALLOWED" to "参照先が対象のリポジトリではない",
    "TASK_REFERENCE_PULL_REQUEST" to "参照先が Issue ではなく PR",
    "TASK_REFERENCE_CLOSED" to "参照先の Issue はクローズ済み",
    "TASK_REFERENCE_NOT_FOUND" to "参照先の Issue が見つからない",
    "TASK_REFERENCE_FORBIDDEN" to "参照先の Issue を読めない（権限）",
    "TASK_REFERENCE_UNAVAILABLE" to "参照先の Issue を取得できなかった（一時的な失敗）",
    "TASK_REFERENCE_RESPONSE_TOO_LARGE" to "参照先の Issue が大きすぎる",
    "TASK_REFERENCE_NO_ROOM_FOR_REFERENCE" to "手動の本文が長く、参照の本文を入れる余地がない",
    "COMMIT_NOT_APPLIED_TWICE" to "コミットが二度とも反映されなかった（GitHub 側の確認が必要）",
    "BRANCH_ADVANCED_BEFORE_COMMIT" to "コミットの前にブランチが進んでいた",
    "DEVELOPER_OUTCOME_UNKNOWN" to "モデル呼び出しの結果が不明",
    "DEVELOPER_TIMEOUT" to "モデル呼び出しの期限が過ぎた（結果が不明な可能性）",
    "DEVELOPER_DUPLICATE_REQUEST" to "同じ要求が処理中、または結果が不明",
    "DEVELOPER_BUDGET" to "予算の予約が拒否された、または単価が不明（モデルは呼ばれていない）",
    "DEVELOPER_POLICY_REJECTED" to "モデルの変更案がポリシーに通らなかった",
    "CODING_EMPTY_PATCH" to "変更が無く終了しました",
    "CODING_BUDGET_EXCEEDED" to "1 attempt の費用上限に達しました",
    "CODING_OVERLOADED" to "モデルが混雑していました",
    "CODING_AUTH_REQUIRED" to "認証に失敗した（運用者が判断する）",
    "CODING_NEEDS_HUMAN" to "ホスト側の異常で、運用者の判断が必要（自動の再試行はしない）",
    "CODING_TIMEOUT" to "実行が時間切れで止められた",
    "CODING_OUTPUT_LIMIT" to "CLI の出力が上限を超えた（出力の合計、または 1 行の上限）",
    "CODING_RATE_LIMITED" to "レート制限で止まった（自動の再試行はしない）",
    "CODING_BAD_OUTPUT" to "CLI の出力を読めなかった、または変更が拒否された",
    "CODING_CLI_NONZERO" to "CLI が異常終了した（終了コードが 0 以外、または結果が失敗）",
    "CODING_CANCELLED" to "実行が取り消された",
    "CODING_FAILED" to "実行が失敗したが、理由の内容が記録されていない",
    "CODING_ORPHANED" to "実行の期限（lease）が切れ、結果が分からない（運用者が判断する）",
    "CODING_QUEUE_EXPIRED" to "実行の待ち行列で 24 時間を過ぎ、期限切れになった（運用者が判断する）",
    "CODING_RESULT_MISSING" to "変更の結果を読めなかった（再提案はしない）",
    "CODING_NO_ATTEMPT" to "実行の記録（試行）が見つからなかった",
    "CODING_NOT_DEVELOPING" to "開発の段階ではなかったため、実行を始めなかった",
    "CODING_ENQUEUE_REFUSED" to "実行の投入が拒否された（Run が見つからない、開発中でない、または入力が不正）",
    "OPERATOR_RELEASED_UNBOUND_PR" to "運用者が解放した（PR と結び付いていない）",
    "OPERATOR_RELEASED_STUCK" to "運用者が解放した（開発が止まっていた）",
)

private val RUN_ACTIONS = mapOf(
    "RUN_CREATED" to "作成",
    "RUN_TRANSITION" to "状態の変更",
    "RUN_PR_BOUND" to "PR と結び付け",
    "RUN_RELEASED" to "運用者が解放",
)

private val RUN_KINDS = mapOf(
    "SIMULATED" to KindText("シミュレーション", "AI や GitHub は呼ばれません"),
    "SELF_DEV" to KindText("自己開発（PR を作成）", "GitHub に PR を作成します"),
)

private val ORDER_STATES = mapOf(
    "ACTIVE" to StateText("稼働中", "周を始められる状態です（周は運用者が開始します）"),
    "PAUSED" to StateText("一時停止中", "周を始めません。理由を確認してください"),
    "STOP_REQUESTED" to StateText("停止の承認待ち", "停止が要求されています。承認されるまで、新しい周は始まりません"),
    "STOPPED" to StateText("停止済み", "終端の状態です。元に戻せません"),
)

/** order の理由(state_reason)。 */
val ORDER_REASONS: Map<String, String> = mapOf(
    "OPERATOR_PAUSED" to "運用者が一時停止しました",
    "OPERATOR_RESUMED" to "運用者が再開しました",
    "STARTUP_HOLD" to "機能の有効化後の起動時に保留されました（運用者が確認して再開します）",
    "BUDGET_HOLD" to "月次の予算の割当てが尽きたため保留されました（運用者が再開します）",
    "CYCLE_FAILURES" to "周が続けて失敗したため保留されました（運用者が再開します）",
    "UNKNOWN_RUN" to "周の Run の結果が不明なため保留されました（運用者が確認して再開します）",
    "FX_EXPIRED" to "為替レートが古く、費用を換算できないため保留されました（運用者が再開します）",
    "LIMIT_REACHED" to "周の上限に達しました（再開できません。新しい order が必要です）",
    "EXPIRED" to "期限が過ぎました（再開できません。新しい order が必要です）",
    "STOP_REQUESTED" to "停止が要求されました",
    "STOP_NOT_APPROVED" to "停止は承認されませんでした（却下、取り消し、または期限切れ）。再開できます",
    "OPERATOR_STOPPED" to "承認により停止しました",
)

/** 停止の要求の理由(reason_code)。承認の一覧と詳細で使う。 */
val STOP_REASONS: Map<String, String> = mapOf(
    "OPERATOR_REQUEST" to "運用者の要求",
    "RUNAWAY_SUSPECTED" to "暴走の疑い",
    "BUDGET_CONCERN" to "予算への懸念",
    "SCOPE_CONCERN" to "範囲への懸念",
    "CHILD_CYCLE_FAILURES" to "親が自動で停止しました（子の周が続けて失敗したため）",
    "CHILD_BUDGET_EXHAUSTED" to "親が自動で停止しました（子の予算を使い切ったため）",
)

private val REQUESTER_KINDS = mapOf("AI" to "AI の主体", "HUMAN" to "人（運用者）")

private val TASK_STATES = mapOf(
    "TASK_SUBMITTED" to StateText("受付済み", "Task を受け付けました。Planner が作業を始めるのを待っています"),
    "PLANNING" to StateText("計画中", "Planner が計画を作っています"),
    "PENDING_APPROVAL" to StateText("承認待ち", "運用者の承認が必要です。承認すると投入を待つ状態になります"),
    "QUEUED" to StateText("承認済み", "承認されています。投入を待っています"),
    "SUBMITTING" to StateText("投入中", "Run を作成しています"),
    "SUBMITTED" to StateText("Run 作成済み", "Run が作られています"),
    "COMPLETED" to StateText("完了", "PR をマージ済みです"),
    "FAILED" to StateText("失敗", "失敗しました（自動では再投入しません）"),
    "CANCELLED" to StateText("解放済み", "運用者が再投入するか見送ります"),
    "SKIPPED" to StateText("見送り", "却下、または中止により見送られました"),
    "BLOCKED" to StateText("停止中", "止まっています。理由を確認してください"),
    "PAUSED" to StateText("一時停止中", "運用者が一時停止しました"),
    "DONE" to StateText("全体完了", "全タスクが完了しました"),
    "STOPPED" to StateText("中止", "運用者が中止しました"),
)

private val GOAL_REASONS = mapOf(
    "PLAN_INVALID" to "計画の検証に失敗しました（再計画できます）",
    "PLAN_BUDGET_HOLD" to "予算の上限で計画を作れませんでした（再計画できます）",
    "PLAN_CALL_FAILED" to "モデル呼び出しに失敗しました（再計画できます）",
    "CLAIM_EXPIRED" to "計画の作成が時間切れになりました",
    "REPLAN_REQUESTED" to "再計画を要求しました",
    "PENDING_PR_LIMIT" to "マージ待ちの PR が上限に達しています（自動で再開します）",
    "DAILY_RUN_CAP" to "1 日の Run 数の上限に達しています（翌日に再開します）",
    "BUDGET_HOLD" to "月次の予算の割当てに達しています（次の期間に再開します）",
    "CIRCUIT_OPEN" to "連続して失敗したため止まっています（運用者の再開が必要）",
    "AUTO_SCOPE_EXCEEDED" to "変更範囲が計画を超えました（運用者の確認が必要）",
    "OPERATOR_PAUSED" to "運用者が一時停止しました",
    "OPERATOR_RESUMED" to "運用者が再開しました",
    "OPERATOR_STOPPED" to "運用者が中止しました",
    "OPERATOR_APPROVED" to "運用者が承認しました",
    "OPERATOR_SKIPPED" to "運用者が見送りました",
    "GOAL_STOPPED" to "Goal の中止により見送りました",
    "ALL_TASKS_COMPLETED" to "すべてのタスクが完了しました",
    "TASKS_INCOMPLETE" to "タスクが終わりましたが、すべては完了しませんでした（PR とタスクを確認してください）",
)

private val ASSIGNEE_STATES = mapOf("ACTIVE" to "有効", "DISABLED" to "無効")

/** Run の状態。知らない値は、そのままの名前と「説明なし」を出す(旧 UI の stateInfo)。 */
fun runStateText(code: String): StateText {
    RUN_STATES[code]?.let { return StateText(code, it) }
    return StateText(
        label = if (code.isEmpty()) "（不明）" else code,
        desc = "不明な状態です（この画面に説明はありません）",
    )
}

/** Run の種別。知らない種別は「不明な種別（…）」と出す(旧 UI の kindInfo)。 */
fun runKindText(mode: String): KindText =
    RUN_KINDS[mode] ?: KindText("不明な種別（${mode.ifEmpty { "空" }}）", null)

/**
 * Run の理由コードの文言。説明があれば「説明（コード）」、`DEVELOPER_` で始まるものは開発エージェントの定型、
 * それ以外は「不明な理由（コード）」。空・null は空文字(表示しない)。
 */
fun runReasonText(code: String?): String {
    if (code.isNullOrEmpty()) return ""
    RUN_REASONS[code]?.let { return "$it（$code）" }
    if (code.startsWith("DEVELOPER_")) return "開発エージェント（モデル）の結果を使えなかった（$code）"
    return "不明な理由（$code）"
}

/** PR の表示(旧 UI の prNode の文字の部分): 「リポジトリ #番号」。番号が正の整数でなければ「-」。 */
fun pullRequestText(repository: String?, number: Long?): String {
    if (number == null || number <= 0) return "-"
    return (repository?.let { "$it " } ?: "") + "#$number"
}

/** Run の出来事(action)の名前。表に無いものはそのまま。 */
fun runActionText(action: String): String = RUN_ACTIONS[action] ?: action

/**
 * Run の題名の表示。種別が分からない Run は題名を出さない(旧 UI の titleOf と同じ。意味が分からないので何も出さない)。
 */
fun runTitleText(mode: String, title: String): String =
    if (mode == "SIMULATED" || mode == "SELF_DEV") clipText(title, RUN_TITLE_MAX) else "（非表示：種別が不明）"

/** 題名を [maxCodePoints] 文字(コードポイント)で切り、超えたら「…」を付ける(旧 UI の clip)。 */
fun clipText(text: String, maxCodePoints: Int): String {
    var count = 0
    var i = 0
    while (i < text.length) {
        if (count == maxCodePoints) return text.substring(0, i) + "…"
        count++
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
    }
    return text
}

/**
 * order の状態。表に無い値は「不明（コード）」(コードの形のときだけ)か「不明」。説明は付けない(旧 UI の ordStateInfo)。
 */
fun orderStateText(code: String): StateText {
    ORDER_STATES[code]?.let { return it }
    val label = if (CODE_SHAPE.matches(code)) "不明（$code）" else "不明"
    return StateText(label, "不明な状態です（この画面に説明はありません）")
}

/** order の理由・停止の理由などのコード表示(旧 UI の ordCodeText)。説明は「説明（コード）」、無ければ「不明（コード）」。 */
fun orderCodeText(table: Map<String, String>, code: String?): String {
    if (code.isNullOrEmpty()) return ""
    table[code]?.let { return "$it（$code）" }
    return if (CODE_SHAPE.matches(code)) "不明（$code）" else "不明"
}

/** 停止の要求者の種別(旧 UI の ordRequesterText)。 */
fun requesterKindText(kind: String): String = REQUESTER_KINDS[kind] ?: "不明"

/** 開発目標(Task)の状態(旧 UI の taskStateInfo)。表に無ければ、そのままの名前と「不明な状態です」。 */
fun goalStateText(code: String): StateText {
    TASK_STATES[code]?.let { return it }
    return StateText(if (code.isEmpty()) "（不明）" else code, "不明な状態です")
}

/**
 * 開発目標の理由。表にあれば「説明（コード）」、無ければコードそのまま(呼び出し側が「理由: 」を付ける。旧 UI の goalReasonText)。
 */
fun goalReasonText(code: String?): String {
    if (code.isNullOrEmpty()) return ""
    return GOAL_REASONS[code]?.let { "$it（$code）" } ?: code
}

/** 担当の状態。表に無ければ「不明（コード）」(コードの形のとき)か「不明」(旧 UI の asgLabel)。 */
fun assigneeStateText(code: String): String {
    ASSIGNEE_STATES[code]?.let { return it }
    return if (CODE_SHAPE.matches(code)) "不明（$code）" else "不明"
}

/** 担当の件数。負・非数は「不明」(旧 UI の asgCount)。 */
fun countText(value: Int): String = if (value >= 0) value.toString() else "不明"

/**
 * 円の表示。整数部を 3 桁区切りにして「円」を付ける(旧 UI の moneyText の範囲: 数字と小数点だけの文字列)。
 * 数字の形でない値は「（不明）」。
 */
fun moneyText(value: String): String {
    if (!YEN_SHAPE.matches(value)) return "（不明）"
    val negative = value.startsWith("-")
    val digits = value.removePrefix("-")
    val intPart = digits.substringBefore('.')
    val fraction = if (digits.contains('.')) "." + digits.substringAfter('.') else ""
    return (if (negative) "-" else "") + groupDigits(intPart) + fraction + " 円"
}

/** 整数の 3 桁区切り(`1234567` → `1,234,567`)。 */
fun groupDigits(digits: String): String {
    val sb = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}

/** 円(整数)の表示。order の金額など、JSON の数値を表示するとき。 */
fun yenText(value: Long): String = (if (value < 0) "-" else "") + groupDigits(kotlin.math.abs(value).toString()) + " 円"

/**
 * 予算の要約文(旧 UI の renderBudgetBar の文言)。確定 + 予約中 / 上限(使用 N%)。超過なら「上限を超えています(超過 …)」、
 * そうでなければ「残り …」。使用率は切り捨ての整数。上限が 0 以下・数でない値のときは使用率を 0 と数える(旧 UI と同じ)。
 */
fun budgetSummaryText(budget: ApiBudget): String {
    val limit = budget.limitValue
    val used = budget.usedValue
    val remaining = budget.remainingJpy.toDoubleOrNull()
    val pct = if (limit != null && used != null && limit > 0) used / limit * 100 else 0.0
    val sb = StringBuilder()
    sb.append("確定 ${moneyText(budget.committedJpy)} + 予約中 ${moneyText(budget.reservedJpy)} / 上限 ${moneyText(budget.limitJpy)}")
    sb.append("（使用 ${kotlin.math.floor(pct).toLong()}%）。")
    if (remaining != null && remaining < 0) {
        sb.append("上限を超えています（超過 ${moneyText(budget.remainingJpy.removePrefix("-"))}）。")
    } else {
        sb.append("残り ${moneyText(budget.remainingJpy)}。")
    }
    return sb.toString()
}
