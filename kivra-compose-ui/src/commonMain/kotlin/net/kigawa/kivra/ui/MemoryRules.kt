package net.kigawa.kivra.ui

/*
 * 記憶(selfdev memory)の操作の純粋ロジック(画面に依存しない)。旧 UI(kivra-app/src/main/resources/ui/app.js の MEM、
 * MEMS、memFailText、memFilterQuery)とサーバー(kivra-app/src/main/kotlin/net/kigawa/kivra/routes/SelfDevMemoryRoutes.kt)
 * の規則に合わせる。
 *
 * 本文は、信頼の文章(TRUSTED_OPERATOR)の本文だけを表示する。未検証の文章はサーバーが本文を返さない(U-1)ので、
 * ここでも扱わない。削除の理由は送信のあと捨てられ、画面にも残さない。
 */

/** 一覧の 1 ページの件数。app.js の MEM.LIMIT とサーバーの既定値。 */
const val MEMORY_PAGE_SIZE = 20

/** 削除の理由の上限(コードポイント)。app.js の MEM.REASON_MAX とサーバーの MEMORY_REASON_MAX。 */
const val MEMORY_REASON_MAX = 500

/** 条件での削除の 1 回の上限(件)。app.js の「1 回の上限は 500 件」とサーバーの上限。 */
const val MEMORY_CONDITION_LIMIT = 500

/** 種類の表示名(app.js の MEM.KIND の順)。キーはサーバーの MemoryKind の名前。 */
val MEMORY_KIND_LABELS: Map<String, String> = linkedMapOf(
    "OPERATOR_NOTE" to "運用者の覚え書き",
    "GOAL_TITLE" to "Goal の題名（運用者）",
    "REJECTION_REASON" to "却下の理由（運用者）",
    "PLAN_RATIONALE" to "計画の理由（モデル、未検証）",
    "FAILURE_EXPLANATION" to "失敗の説明（系統、未検証）",
    "CYCLE_REVIEW" to "order の周の振り返り（系統、未検証）",
    "PLAN_FACT" to "計画の事実",
    "TASK_FACT" to "タスクの事実",
    "RUN_FACT" to "Run の事実",
    "ATTEMPT_FACT" to "試行の事実",
    "COST_FACT" to "費用の事実",
    "GOAL_FACT" to "Goal の事実",
)

/** 信頼の表示名(app.js の MEM.TIER の順)。キーはサーバーの MemoryTier の名前。 */
val MEMORY_TIER_LABELS: Map<String, String> = linkedMapOf(
    "TRUSTED_OPERATOR" to "運用者の文章（信頼）",
    "UNTRUSTED" to "未検証（モデルや系統が書いた）",
)

private val MEMORY_CODE_SHAPE = Regex("[A-Z_]{1,64}")

/**
 * 一覧の絞り込み。null は「すべて」。種類と信頼のどちらかが入っていれば、条件での削除ができる(app.js の memFilterBody と同じ)。
 */
data class MemoryListFilter(val kind: String? = null, val tier: String? = null) {
    /** 条件での削除に使えるか(種類か信頼のどちらか 1 つ以上)。 */
    val hasCondition: Boolean get() = kind != null || tier != null

    /** 絞り込みの同一性の鍵(件数の確認と削除の対応づけに使う)。 */
    val key: String get() = "${kind ?: ""}|${tier ?: ""}"
}

/** 画面の選択値から絞り込みを作る。表の中にない値は「すべて」(送らない)。 */
fun memoryFilterOf(kind: String?, tier: String?): MemoryListFilter = MemoryListFilter(
    kind = kind?.takeIf { it in MEMORY_KIND_LABELS },
    tier = tier?.takeIf { it in MEMORY_TIER_LABELS },
)

/** 一覧の問い合わせの query(`limit`、`offset`、絞り込み、プロジェクト)。app.js の memFilterQuery と同じ並び。 */
fun memoryListQuery(filter: MemoryListFilter, offset: Int, projectId: String?): String {
    val parts = mutableListOf("limit=$MEMORY_PAGE_SIZE", "offset=$offset")
    filter.kind?.let { parts += "kind=${percentEncode(it)}" }
    filter.tier?.let { parts += "tier=${percentEncode(it)}" }
    projectId?.let { parts += "projectId=${percentEncode(it)}" }
    return parts.joinToString("&")
}

/** 種類の表示名。表にない値は「不明」。 */
fun memoryKindLabel(kind: String): String = MEMORY_KIND_LABELS[kind] ?: "不明"

/** 信頼の表示名。表にない値は「不明」。 */
fun memoryTierLabel(tier: String): String = MEMORY_TIER_LABELS[tier] ?: "不明"

/** 理由コードの表示。形が合わなければ「不明」(app.js の memCodeText と同じ)。 */
fun memoryCodeText(code: String): String = if (MEMORY_CODE_SHAPE.matches(code)) code else "不明"

/**
 * 本文の表示。本文があれば出す。本文が無く withheld なら「本文は表示しません」。どちらも無ければ何も出さない(事実など)。
 * 文言は app.js の MEM.MSG.withheld と同じ。
 */
fun memoryBodyText(body: String?, bodyWithheld: Boolean): String? = when {
    body != null -> body
    bodyWithheld -> MEMORY_MSG_WITHHELD
    else -> null
}

/** 前のページがあるか。 */
fun memoryHasPrev(offset: Int): Boolean = offset > 0

/** 次のページがあるか。今のページの終わりが総数に届いていなければ true(app.js と同じ)。 */
fun memoryHasNext(offset: Int, shownCount: Int, total: Int): Boolean = offset + shownCount < total

/** 表示中の範囲の文言。何も無ければ空。 */
fun memoryPageText(offset: Int, shownCount: Int): String =
    if (shownCount == 0) "" else "${offset + 1} から ${offset + shownCount} 件目を表示しています"

/** 墓標と監査の件数の文言(app.js の memory-counts と同じ)。 */
fun memoryCountsText(tombstones: Int, deletionAudit: Int): String =
    "墓標（削除された文章）: $tombstones 件、削除の監査: $deletionAudit 件"

/** 最終の見出し行の件数の文言。 */
fun memoryTotalText(total: Int): String = "該当 $total 件"

/** 件数の確認の結果の文言(app.js の onMemoryDryRun と同じ)。 */
fun memoryDryRunText(matched: Int): String = "該当: $matched 件（1 回の上限は $MEMORY_CONDITION_LIMIT 件）"

/** 件数の確認の前に条件が無い時の文言(app.js と同じ)。 */
const val MEMORY_MSG_NEED_FILTER = "条件を 1 つ以上選んでから確認してください（種類か信頼）。"

/** 削除の成功の文言。 */
const val MEMORY_MSG_DELETED = "記録を削除しました。"

const val MEMORY_MSG_WITHHELD = "本文は表示しません（未検証の文章）"

/**
 * 条件での削除の二段階の状態。件数を確かめた絞り込みの鍵と件数。削除は、同じ絞り込みで 1 件以上あったときだけ許す。
 */
data class MemoryDryRun(val filterKey: String, val matched: Int)

/** 条件での削除を出せるか(app.js の dryOk と同じ: 確かめた絞り込みが今の絞り込みと同じで、件数が 1 以上)。 */
fun memoryConditionDeleteEnabled(dryRun: MemoryDryRun?, filter: MemoryListFilter): Boolean =
    dryRun != null && dryRun.filterKey == filter.key && dryRun.matched >= 1

/** 個別の削除の確認の説明(app.js と同じ)。 */
const val MEMORY_EXPLAIN_ONE = "この記録を削除します。文章は消え、墓標（ID と種類と時刻だけ）と監査が残ります。取り消せません。"

/** 条件での削除の確認の説明(app.js と同じ)。 */
fun memoryExplainCondition(matched: Int): String =
    "表示中の条件に該当する $matched 件を削除します。文章は消え、墓標と監査が残ります。取り消せません。"

/** 削除の確認の理由欄の表示名(app.js の文言と同じ)。 */
const val MEMORY_REASON_LABEL = "理由（必須、1〜500 文字）"

/**
 * 削除の失敗の文言。ステータスだけで決める(サーバーの error 本文は表示しない)。
 * 順序は app.js の memFailText と同じ: 通信、セッション、400、404、409、その他。
 */
fun memoryFailureText(status: Int?): String = when (status) {
    null -> MEMORY_MSG_NETWORK
    401 -> MEMORY_MSG_SESSION
    400 -> MEMORY_MSG_REASON_INVALID
    404 -> MEMORY_MSG_NOT_FOUND
    409 -> MEMORY_MSG_LIMIT
    else -> MEMORY_MSG_UNKNOWN
}

/** 件数の確認の失敗の文言。409 は上限の案内、それ以外は [memoryFailureText]。 */
fun memoryDryRunFailureText(status: Int?): String = if (status == 409) MEMORY_MSG_LIMIT else memoryFailureText(status)

const val MEMORY_MSG_NETWORK = "通信に失敗しました。時間をおいて再度お試しください。"
const val MEMORY_MSG_SESSION = "セッションが切れました。画面を再読み込みしてください。"
const val MEMORY_MSG_REASON_INVALID = "理由は 1 から 500 文字で、制御文字を含めないでください。"
const val MEMORY_MSG_NOT_FOUND = "記録が見つかりませんでした（削除済みの可能性があります）。一覧を読み直しました。"
const val MEMORY_MSG_LIMIT = "該当が多すぎます（1 回の上限は 500 件です）。条件を絞ってください。"
const val MEMORY_MSG_UNKNOWN = "処理できませんでした。時間をおいて再度お試しください。"
