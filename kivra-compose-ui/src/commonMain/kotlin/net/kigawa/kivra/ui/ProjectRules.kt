package net.kigawa.kivra.ui

import net.kigawa.kivra.ui.api.ApiBackfillReport
import net.kigawa.kivra.ui.api.ApiBackfillTable
import net.kigawa.kivra.ui.api.ApiProjectActivity

/*
 * プロジェクトの純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js)の規則と文言に合わせる:
 * 作成フォーム(MaxLength は UTF-16 の長さ)、ダッシュボードの表示(STATES / ACTIONS / REASONS / 費用の行)、
 * repo の登録(REPO_KEY_SHAPE と PREPO_MSG)、既存データの振り分け(BACKFILL_MSG と BACKFILL_TABLE_LABEL)。
 * サーバー(ProjectRoutes.kt)が最終判定をする。ここでは送る前の検査と、応答の表示だけを持つ。
 */

/** プロジェクト名の上限(UTF-16 の長さ。旧 UI の maxlength=100 と count の数え方)。 */
const val PROJECT_NAME_MAX = 100

/** プロジェクトの説明の上限(UTF-16 の長さ。旧 UI の maxlength=500)。 */
const val PROJECT_DESCRIPTION_MAX = 500

/** repo の入力欄の上限(旧 UI の maxlength=140)。 */
const val REPO_KEY_INPUT_MAX = 140

private val REPO_KEY_SHAPE = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")

/** プロジェクト名の検査。前後の空白を除き、1 文字以上・上限以下。通れば trim 済みの値、通らなければ null。 */
fun validatedProjectName(raw: String): String? {
    val text = raw.trim()
    return if (text.isEmpty() || text.length > PROJECT_NAME_MAX) null else text
}

/** プロジェクトの説明の検査。前後の空白を除き、上限以下。空は空のまま(送らない)。上限超えは null。 */
fun validatedProjectDescription(raw: String): String? {
    val text = raw.trim()
    return if (text.length > PROJECT_DESCRIPTION_MAX) null else text
}

/** repo の形(owner/name)。旧 UI の REPO_KEY_SHAPE。[raw] は trim 済みの値を渡す。 */
fun isRepoKeyShape(raw: String): Boolean = raw.length <= REPO_KEY_INPUT_MAX && REPO_KEY_SHAPE.matches(raw)

/** プロジェクト作成の成功の文言(旧 UI)。 */
const val PROJECT_CREATED_MESSAGE = "プロジェクトを作成しました。"

/** プロジェクト作成の失敗の文言。旧 UI と同じ一つの文言に、セッション切れだけを分ける。 */
fun projectCreateFailureText(status: Int?): String =
    if (status == 401) SESSION_EXPIRED_MESSAGE else "プロジェクトの作成に失敗しました。"

/* ---- ダッシュボードの表示 ---- */

/** Run の状態の説明(旧 UI の STATES)。表示は「コード（説明）」。 */
val RUN_STATE_DESCRIPTIONS: Map<String, String> = linkedMapOf(
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

/** 活動の操作の名前(旧 UI の ACTIONS)。知らない code は code のまま出す。 */
private val ACTION_LABELS = mapOf(
    "RUN_CREATED" to "作成",
    "RUN_TRANSITION" to "状態の変更",
    "RUN_PR_BOUND" to "PR と結び付け",
    "RUN_RELEASED" to "運用者が解放",
)

/** 理由の説明(旧 UI の REASONS)。知らない code は「不明な理由」、DEVELOPER_ の接頭は別の文言。 */
private val REASON_LABELS = mapOf(
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
    // The CODING_ codes below are written from the runbook (docs/runbook-selfdev.md, 6.7 and the Run state table) and CodingPatchSource.
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
)

/** 状態の表示名。旧 UI の stateInfo と同じ: 説明が無い code は「不明な状態」。 */
fun runStateText(code: String): String {
    val desc = RUN_STATE_DESCRIPTIONS[code] ?: "不明な状態です（この画面に説明はありません）"
    return "$code（$desc）"
}

/**
 * 件数の行の並び。STATES の順、その次に知らない code(受け取った順)。0 件の状態は含まれない(サーバーが出した分だけ)。
 */
fun projectStateRows(byState: Map<String, Long>): List<Pair<String, Long>> {
    val known = RUN_STATE_DESCRIPTIONS.keys.filter { byState.containsKey(it) }
    val unknown = byState.keys.filter { !RUN_STATE_DESCRIPTIONS.containsKey(it) }
    return (known + unknown).map { it to (byState[it] ?: 0L) }
}

/** 理由の表示(旧 UI の reasonText): 「説明（code）」。空は空。 */
fun reasonText(code: String?): String {
    if (code.isNullOrEmpty()) return ""
    REASON_LABELS[code]?.let { return "$it（$code）" }
    if (code.startsWith("DEVELOPER_")) return "開発エージェント（モデル）の結果を使えなかった（$code）"
    return "不明な理由（$code）"
}

/**
 * 活動の 1 行(旧 UI の eventLine)。「Run <id>  <時刻>  <操作> → <状態>（<理由>）  [<結果>]」。時刻は受け取った文字列のまま。
 */
fun projectActivityText(event: ApiProjectActivity): String {
    val action = ACTION_LABELS[event.action] ?: event.action
    var text = "Run ${event.runId}  ${event.occurredAt}  $action"
    event.toState?.takeIf { it.isNotEmpty() }?.let { text += " → $it" }
    event.reasonCode?.takeIf { it.isNotEmpty() }?.let { text += "（${reasonText(it)}）" }
    return "$text  [${event.result}]"
}

private val YEN_SHAPE = Regex("-?[0-9]+(\\.[0-9]+)?")

/**
 * 円の表示。十進の文字列を、整数部の 3 桁区切りと「 円」で出す(旧 UI の moneyText)。形が違えば「（不明）」。
 */
fun yenText(raw: String): String {
    if (!YEN_SHAPE.matches(raw)) return "（不明）"
    val negative = raw.startsWith("-")
    val unsigned = raw.removePrefix("-")
    val intPart = unsigned.substringBefore('.')
    val fraction = if (unsigned.contains('.')) "." + unsigned.substringAfter('.') else ""
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + fraction + " 円"
}

/** scope の説明(旧 UI の project-dash-scope)。ALL は運用者の表示、OWN は自分の Run だけ。 */
fun projectScopeText(scope: String): String = if (scope == "ALL") {
    "表示は、このプロジェクトのすべての Run です（運用者の表示）。"
} else {
    "表示は、あなたが投入した Run だけです。ほかの人の Run は件数にも一覧にも入りません。"
}

/** 件数の見出し(旧 UI の project-dash-total)。 */
fun projectTotalText(total: Long): String = if (total == 0L) "Run はまだありません。" else "全部で $total 件です。"

/** 費用の注記(旧 UI の project-dash-spend-note)。 */
const val PROJECT_SPEND_NOTE = "プロジェクトごとの上限はありません（月ごとの上限は、運用者向けの予算画面で確認します）。計画（Planner）の費用は含みません。"

/** 運用者だけに出す repo の操作を、ダッシュボードの scope が ALL のときだけ出すか(旧 UI の renderProjectOps)。 */
fun showsProjectOperator(scope: String): Boolean = scope == "ALL"

/* ---- repo の登録と解除(旧 UI の PREPO_MSG) ---- */

const val REPO_INVALID_MESSAGE = "repo は owner/name の形で入力してください（英数字、ハイフン、ピリオド、アンダースコア）。"
const val REPO_ADDED_MESSAGE = "repo を登録しました。振り分けは下の「既存データの振り分け」で行います。"
const val REPO_REMOVED_MESSAGE = "repo の登録を外しました。"
const val REPO_EMPTY_MESSAGE = "登録された repo はありません。"
const val REPO_LOAD_FAILED_MESSAGE = "repo の一覧を読めませんでした。"

/** repo の登録・解除の失敗の文言(HTTP のステータスだけで決める。サーバーの本文は出さない)。 */
fun projectRepoFailureText(status: Int?): String = when (status) {
    401 -> SESSION_EXPIRED_MESSAGE
    409 -> "この repo は、どれかのプロジェクトに既に登録されています。1 つの repo は 1 つのプロジェクトにだけ登録できます。"
    400 -> REPO_INVALID_MESSAGE
    404 -> "このプロジェクトは見つかりません。画面を読み直してから、もう一度お試しください。"
    403 -> "この操作は運用者だけができます。"
    else -> "操作に失敗しました。時間をおいてもう一度お試しください。"
}

/* ---- 既存データの振り分け(旧 UI の BACKFILL_MSG と BACKFILL_TABLE_LABEL) ---- */

const val BACKFILL_FAILED_MESSAGE = "振り分けに失敗しました。時間をおいてもう一度お試しください。"
const val BACKFILL_NOTHING_MESSAGE = "振り分けるものはありません（確認の結果、対応がつく行がありません）。"
const val BACKFILL_DRY_DONE_MESSAGE = "確認の結果です（dry-run。何も書き込んでいません）。"
const val BACKFILL_RUN_DONE_MESSAGE = "振り分けを実行しました。"

private val BACKFILL_TABLE_LABELS = mapOf(
    "selfdev_tasks" to "Task",
    "selfdev_orders" to "Order",
    "development_runs" to "Run",
    "selfdev_memory_records" to "記憶",
)

/** 表の名前の表示(知らない表は「その他」)。 */
fun backfillTableLabel(table: String): String = BACKFILL_TABLE_LABELS[table] ?: "その他"

/** 表ごとの結果の 1 行(旧 UI の renderBackfillResult)。 */
fun backfillTableLine(t: ApiBackfillTable): String =
    "${backfillTableLabel(t.table)}：対応あり ${t.matched} 件、更新 ${t.updated} 件、対応なし（未設定のまま）${t.unmatched} 件"

/** 振り分けの対象の数(確認の結果の matched の合計)。 */
fun backfillTotal(report: ApiBackfillReport?): Long = report?.tables?.sumOf { it.matched } ?: 0L

/** 実行できるか: 確認(dry-run)の結果があり、対象が 1 件以上あり、送信中でない。 */
fun canRunBackfill(dryRun: ApiBackfillReport?, busy: Boolean): Boolean =
    !busy && dryRun != null && backfillTotal(dryRun) > 0L

/** 実行の確認ダイアログの説明(旧 UI の openBackfillDialog)。 */
fun backfillExplain(report: ApiBackfillReport): String {
    val parts = report.tables.filter { it.matched > 0 }.map { "${backfillTableLabel(it.table)} ${it.matched} 件" }
    return "確認の結果、次の行をプロジェクトに振り分けます：" + parts.joinToString("、") +
        "。プロジェクトが未設定の行だけが対象で、すでにプロジェクトを持つ行は変わりません。対応がない行は未設定のままです。この操作を取り消す機能はありません。"
}

/** 振り分けの失敗の文言(状態だけで決める)。 */
fun backfillFailureText(status: Int?): String = when (status) {
    401 -> SESSION_EXPIRED_MESSAGE
    403 -> "この操作は運用者だけができます。"
    else -> BACKFILL_FAILED_MESSAGE
}
