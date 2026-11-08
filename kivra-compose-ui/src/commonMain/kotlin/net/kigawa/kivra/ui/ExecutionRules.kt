package net.kigawa.kivra.ui

import kotlin.math.max
import kotlin.math.min
import net.kigawa.kivra.ui.api.ApiExecutionAttempt
import net.kigawa.kivra.ui.api.ApiExecutionCost
import net.kigawa.kivra.ui.api.ApiExecutionRevision
import net.kigawa.kivra.ui.api.ApiExecutionTools

/*
 * 実行の経過(CLI 経路の Run)の監視の純粋な部分。旧 UI の EXEC と beginExecution / execDelay / execAccepted の判定と文言に合わせる。
 * SSE(常時接続)は旧 UI でも任意の最適化なので移植しない(定期取得だけで動く)。
 */

/** 定期取得の間隔と再試行の上限(旧 UI の EXEC と同じ値)。TICK は隠れた/表示した切り替えを検出する細かさ。 */
object ExecutionPolling {
    const val POLL_MS = 5_000L
    const val HIDDEN_MS = 30_000L
    const val BACKOFF_MAX_MS = 60_000L
    const val TICK_MS = 250L
    const val POLL_LABEL = "5 秒ごと"
}

/** 終端の Run の状態。この状態になったら取得を止める(旧 UI の EXEC_TERMINAL)。 */
val EXECUTION_TERMINAL_STATES: Set<String> = setOf("COMPLETED", "FAILED", "CANCELLED")

fun isExecutionTerminal(runState: String): Boolean = runState in EXECUTION_TERMINAL_STATES

/** 試行が動いている状態(進み具合の不確定な棒を出す)。 */
val EXECUTION_ACTIVE_ATTEMPT_STATES: Set<String> = setOf("QUEUED", "CLAIMED", "RUNNING")

private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")

/** 実行の節を出すのは、自己開発(SELF_DEV)の Run で id の形が合う時だけ(旧 UI の beginExecution の条件)。 */
fun showsExecutionSection(mode: String, runId: String): Boolean = mode == "SELF_DEV" && RUN_ID_SHAPE.matches(runId)

/** 次の定期取得までの待ち(ミリ秒)。隠れている間は 30 秒、失敗が続くと倍々(上限 60 秒)。 */
fun execDelayMs(hidden: Boolean, failures: Int): Long {
    val base = if (hidden) ExecutionPolling.HIDDEN_MS else ExecutionPolling.POLL_MS
    if (failures <= 0) return base
    return min(ExecutionPolling.BACKOFF_MAX_MS, base shl min(failures, 4))
}

/**
 * いま取得すべきか。隠れていた状態から表示に戻ったら、すぐ取得する(旧 UI の visibilitychange)。
 * それ以外は、前回の取得からの待ち([execDelayMs])が過ぎたら取得する。
 */
fun execDue(now: Long, lastAttemptAt: Long, hidden: Boolean, wasHidden: Boolean, failures: Int): Boolean {
    if (wasHidden && !hidden) return true
    return now - lastAttemptAt >= execDelayMs(hidden, failures)
}

/** 旧い版(revision が小さい)は、表示中の版を置き換えない。同じ版は新しい方として受け入れる。 */
fun isOlderRevision(a: ApiExecutionRevision, b: ApiExecutionRevision): Boolean =
    a.attemptVersion < b.attemptVersion || (a.attemptVersion == b.attemptVersion && a.eventCount < b.eventCount)

/** 届いた版で表示中の版を置き換えてよいか(初めての版、または届いた版が古くない)。 */
fun shouldReplaceExecution(shown: ApiExecutionRevision?, incoming: ApiExecutionRevision): Boolean =
    shown == null || !isOlderRevision(incoming, shown)

private val ISO_UTC = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?Z")

/**
 * サーバーの時刻(UTC の ISO 形、例 `2026-10-19T00:00:00Z` または小数秒つき)をエポックのミリ秒にする。読めなければ null。
 * 小数は先頭 3 桁だけ使う(旧 UI の parseInstant と同じ)。
 */
fun parseInstantMillis(text: String?): Long? {
    if (text == null) return null
    val m = ISO_UTC.matchEntire(text) ?: return null
    val g = m.groupValues
    val year = g[1].toInt()
    val month = g[2].toInt()
    val day = g[3].toInt()
    val hour = g[4].toInt()
    val minute = g[5].toInt()
    val second = g[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
    val millis = if (g[7].isEmpty()) 0L else g[7].padEnd(3, '0').take(3).toLong()
    val days = daysFromCivil(year, month, day)
    return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
}

/** 1970-01-01 からの日数(グレゴリオ暦)。 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097L + doe - 719468L
}

/**
 * 最後の受信からの経過の分(切り捨て、0 以上)。受信が無ければ null。[skewMs] はサーバーの時刻と端末の時刻の差(旧 UI の skewMs)。
 */
fun execStaleMinutes(lastReceivedMs: Long?, nowMs: Long, skewMs: Long): Long? {
    if (lastReceivedMs == null) return null
    return max(0L, (nowMs + skewMs - lastReceivedMs) / 60_000L)
}

private val USD_DECIMAL = Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")

/** 金額(USD の 10 進数の文字列)を `USD x` の形にする。数でない値は `不明`(数に見せない)。 */
fun usdText(value: String?): String =
    if (value != null && USD_DECIMAL.matches(value)) "USD $value" else "不明"

/**
 * 推定の欄の文言。失敗した試行で CLI の報告がなく、推定が予約額のままなら「予約額。実費ではない」を添える(旧 UI と同じ)。
 */
fun execEstimateText(cost: ApiExecutionCost, attempts: List<ApiExecutionAttempt>): String {
    val failedNoReport = attempts.any { it.state == "FAILED" } &&
        Regex("0(\\.0+)?").matches(cost.confirmedUsd.orEmpty()) &&
        cost.estimatedBasis == "RESERVE_ESTIMATE"
    return usdText(cost.estimatedUsd) + "（推定）" + (if (failedNoReport) "。予約額。実費ではない" else "")
}

/** ツール名の表示順と見出し(サーバーの固定の名前。それ以外は OTHER に数える)。 */
val EXECUTION_TOOLS: List<Pair<String, String>> = listOf(
    "Read" to "読み取り（Read）",
    "Edit" to "編集（Edit）",
    "Write" to "書き込み（Write）",
    "Bash" to "シェル（Bash）",
    "Grep" to "内容の検索（Grep）",
    "Glob" to "ファイルの検索（Glob）",
    "Task" to "サブタスク（Task）",
    "TodoWrite" to "TODO の更新（TodoWrite）",
    "OTHER" to "その他",
)

/** 種類別の件数。OTHER は、固定の名前以外の全部を足す(旧 UI の toolCountFor と同じ)。 */
fun execToolCount(tools: ApiExecutionTools, name: String): Int {
    val by = tools.byName
    if (name != "OTHER") return by[name] ?: 0
    val known = EXECUTION_TOOLS.map { it.first }.toSet()
    return (by["OTHER"] ?: 0) + by.filterKeys { it !in known }.values.sum()
}

/** 注意の行(件数は数字と固定の文言だけ。旧 UI の renderExecWarnings と同じ順と文言)。 */
fun execWarningLines(warnings: Int, dropped: Int, truncated: Boolean, apiRetries: Map<String, Int>): List<String> {
    val lines = mutableListOf<String>()
    if (warnings > 0) lines += "警告: $warnings 件"
    if (dropped > 0) lines += "送信の取りこぼし（上限などで落とした件数）: $dropped 件"
    if (truncated) lines += "記録の上限に達したため、以降は一部だけ記録されています"
    apiRetries.forEach { (kind, count) -> lines += "API の再試行（$kind）: $count 回" }
    return lines
}

val EXECUTION_ATTEMPT_STATES: Map<String, String> = mapOf(
    "QUEUED" to "待機中",
    "CLAIMED" to "割り当て済み",
    "RUNNING" to "稼働中（CLI）",
    "RESULT_RECEIVED" to "結果を受信",
    "FAILED" to "失敗",
    "CANCELLED" to "取消し",
    "EXPIRED" to "期限切れ",
    "ORPHANED" to "孤立（対応する実行が見つからない）",
)

val EXECUTION_PHASES: Map<String, String> = mapOf(
    "STARTED" to "開始",
    "EDITING" to "編集中",
    "TESTING" to "テスト中",
    "ROUND_COMPLETED" to "ラウンド完了",
    "FINALIZING" to "仕上げ中",
)

val EXECUTION_STOP_STAGES: Map<String, String> = mapOf(
    "NONE" to "停止要求はありません",
    "REQUESTED" to "停止要求あり（エージェントの確認待ち）",
    "STOPPING" to "停止中（エージェントが停止要求を受けたと報告）",
    "EXIT_CONFIRMED" to "停止確認（エージェントがプロセスの終了を報告）",
    "CANCELLED" to "取消し済み（Run が CANCELLED）",
)

/** 固定の表から引く。表にない値は「不明な値（コード）」、空は「（不明）」(サーバーの文字は出さない形)。 */
fun execLookup(table: Map<String, String>, key: String?): String {
    val k = key.orEmpty()
    return when {
        table.containsKey(k) -> table.getValue(k)
        k.isEmpty() -> "（不明）"
        else -> "不明な値（$k）"
    }
}

const val EXECUTION_LOAD_FAILED_TEXT =
    "監視の情報を取得できませんでした（通信または応答の失敗）。Run の状態ではありません。しばらくすると自動で取り直します。"
const val EXECUTION_STALE_TEXT = "表示は古い可能性があります"
const val EXECUTION_NO_RECEIPT_TEXT = "受信の記録がありません。表示は古い可能性があります"
const val EXECUTION_TERMINAL_TEXT = "終了済み（Run の状態が終端のため、受信は止まっています）。"
const val EXECUTION_TURNS_NOTE = "メッセージ数は assistant のメッセージの数です。API のターン数とは異なることがあります。"
const val EXECUTION_PROGRESS_NOTE = "進み具合: 不確定（総量の上限は分からないため、割合は出しません）"
const val EXECUTION_READ_ONLY_NOTE = "自己開発（CLI 経路）の実行を、読み取り専用で表示します。停止はこの画面からは行えません。"
