package net.kigawa.kivra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.kigawa.kivra.ui.EXECUTION_ACTIVE_ATTEMPT_STATES
import net.kigawa.kivra.ui.EXECUTION_ATTEMPT_STATES
import net.kigawa.kivra.ui.EXECUTION_LOAD_FAILED_TEXT
import net.kigawa.kivra.ui.EXECUTION_NO_RECEIPT_TEXT
import net.kigawa.kivra.ui.EXECUTION_PHASES
import net.kigawa.kivra.ui.EXECUTION_PROGRESS_NOTE
import net.kigawa.kivra.ui.EXECUTION_READ_ONLY_NOTE
import net.kigawa.kivra.ui.EXECUTION_STALE_TEXT
import net.kigawa.kivra.ui.EXECUTION_STOP_STAGES
import net.kigawa.kivra.ui.EXECUTION_TERMINAL_TEXT
import net.kigawa.kivra.ui.EXECUTION_TOOLS
import net.kigawa.kivra.ui.EXECUTION_TURNS_NOTE
import net.kigawa.kivra.ui.ExecutionPolling
import net.kigawa.kivra.ui.api.ApiClient
import net.kigawa.kivra.ui.api.ApiException
import net.kigawa.kivra.ui.api.ApiRunExecution
import net.kigawa.kivra.ui.api.ApiSession
import net.kigawa.kivra.ui.execDue
import net.kigawa.kivra.ui.execEstimateText
import net.kigawa.kivra.ui.execLookup
import net.kigawa.kivra.ui.execStaleMinutes
import net.kigawa.kivra.ui.execToolCount
import net.kigawa.kivra.ui.execWarningLines
import net.kigawa.kivra.ui.isExecutionTerminal
import net.kigawa.kivra.ui.isDocumentHidden
import net.kigawa.kivra.ui.nowMillis
import net.kigawa.kivra.ui.formatClock
import net.kigawa.kivra.ui.parseInstantMillis
import net.kigawa.kivra.ui.shouldReplaceExecution
import net.kigawa.kivra.ui.usdText

/** 時刻を端末の地域の日時(ja-JP)で出す。旧 UI の fmtLocal と同じ。 */
@JsFun("(ms) => new Date(ms).toLocaleString('ja-JP')")
internal external fun executionLocalTime(ms: Double): String

/**
 * 実行の経過の監視の状態。読み取りの結果を置き、表示に使う。ループ(取得の間隔)だけが [failures] と [lastAttemptAt] を書く。
 * 表示の値は Compose の状態にして、取得のたびに画面が変わるようにする。
 */
private class ExecutionWatch {
    /** 表示中の版(古い応答では置き換えない)。 */
    var snap by mutableStateOf<ApiRunExecution?>(null)
    /** 表示中の版を受け入れた時刻(端末の時刻、ミリ秒)。「最後に取得できた時点」に使う。 */
    var acceptedAtMs by mutableStateOf(0L)
    /** サーバーの時刻と端末の時刻の差(旧 UI の skewMs)。経過の分の計算に使う。 */
    var skewMs by mutableStateOf(0L)
    /** 直近の取得の時刻(端末の時刻)。経過の分はこの時点で計算する(旧 UI は取得のたびに描き直す)。 */
    var renderedAtMs by mutableStateOf(0L)
    /** 節を出すか。Run に試行が無い・見せない利用者は false。 */
    var shown by mutableStateOf(false)
    /** 直近の取得が失敗したか(表示は残し、固定の文言を上に出す)。 */
    var failing by mutableStateOf(false)

    // ループだけが読み書きする(表示には使わない)。
    var failures = 0
    var lastAttemptAt = 0L
}

/**
 * 実行の経過(CLI 経路の自己開発 Run の詳細に出す節)。取得は定期(5 秒、隠れていれば 30 秒、失敗は 10 / 20 / 40 / 60 秒)。
 * 終端の状態になったら止まり、詳細を離れたら止まる(Compose の LaunchedEffect がこの節の破棄で止める)。
 * 試行が無い・404・403・401 はこの節を出さない。SSE(常時接続)は任意の最適化なので移植しない。
 */
@Composable
internal fun RunExecutionSection(runId: String) {
    val watch = remember(runId) { ExecutionWatch() }
    LaunchedEffect(runId) {
        var wasHidden = isDocumentHidden()
        while (true) {
            val hidden = isDocumentHidden()
            if (execDue(nowMillis().toLong(), watch.lastAttemptAt, hidden, wasHidden, watch.failures)) {
                val keepWatching = readExecutionOnce(runId, watch)
                watch.lastAttemptAt = nowMillis().toLong()
                if (!keepWatching) break
            }
            wasHidden = hidden
            delay(ExecutionPolling.TICK_MS)
        }
    }
    if (!watch.shown) return
    ExecutionBody(watch)
}

/**
 * 一回の取得。戻り値は「続けて取得してよいか」。false は止める(終端、試行なし、見せない利用者、セッション切れ)。
 */
private suspend fun readExecutionOnce(runId: String, watch: ExecutionWatch): Boolean {
    return try {
        val d = ApiClient.getRunExecution(runId)
        watch.failures = 0
        watch.failing = false
        val now = nowMillis().toLong()
        watch.renderedAtMs = now
        if (d.attempts.isEmpty()) {
            // 試行が無い = CLI の Run ではない(まだ)。節は出さず、止める。
            watch.shown = false
            return false
        }
        // 古い版は表示中の版を置き換えない(後から届いた古い応答で状態を戻さない)。
        if (shouldReplaceExecution(watch.snap?.revision, d.revision)) {
            watch.snap = d
            watch.acceptedAtMs = now
            watch.skewMs = (parseInstantMillis(d.snapshotAt) ?: now) - now
        }
        watch.shown = true
        !isExecutionTerminal(watch.snap?.runState ?: d.runState)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (ApiSession.expired) return false
        if (e.status == 401 || e.status == 403 || e.status == 404) {
            // この Run の経過は、この利用者には見せない(API 経路の Run、または見えない Run)。
            watch.shown = false
            return false
        }
        failed(watch)
    } catch (e: Exception) {
        failed(watch)
    }
}

/** 失敗: 表示は残したまま、固定の文言を出し、次の取得は倍々に遅らせる。 */
private fun failed(watch: ExecutionWatch): Boolean {
    watch.failures += 1
    watch.failing = true
    watch.shown = true
    return true
}

@Composable
private fun ExecutionBody(watch: ExecutionWatch) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("実行の経過", style = MaterialTheme.typography.titleMedium)
        Text(EXECUTION_READ_ONLY_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (watch.failing) {
            val at = if (watch.acceptedAtMs > 0L) "（最後に取得できた時点: ${formatClock(watch.acceptedAtMs.toDouble())}）" else ""
            Text(EXECUTION_LOAD_FAILED_TEXT + at, color = MaterialTheme.colorScheme.error)
        }
        val snap = watch.snap
        if (snap == null) {
            if (!watch.failing) Text("読み込み中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        StatusLine(snap, watch)
        Text("取得方法: 定期取得（${ExecutionPolling.POLL_LABEL}）", style = MaterialTheme.typography.bodySmall)

        val warnings = execWarningLines(snap.warnings, snap.dropped, snap.truncated, snap.apiRetries)
        if (warnings.isNotEmpty()) {
            Text("注意", style = MaterialTheme.typography.titleSmall)
            warnings.forEach { Text("・$it") }
        }

        Text("試行", style = MaterialTheme.typography.titleSmall)
        snap.attempts.forEach { a ->
            val last = parseInstantMillis(a.lastReceivedAt)?.let { "、最終の受信 ${executionLocalTime(it.toDouble())}" } ?: ""
            Text("試行 #${a.attemptNo}：${execLookup(EXECUTION_ATTEMPT_STATES, a.state)}（世代 ${a.leaseGeneration}）$last")
        }
        if (snap.attempts.any { it.state in EXECUTION_ACTIVE_ATTEMPT_STATES }) {
            // 総量の上限は分からないので、割合の棒は出さず、不確定の棒だけ出す。
            Text(EXECUTION_PROGRESS_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Text("進み（メッセージ数・ツール呼び出し）", style = MaterialTheme.typography.titleSmall)
        DetailRow("メッセージ数", "${snap.turns} 件")
        DetailRow("ツール呼び出し（合計）", "${snap.tools.total} 回")
        DetailRow("ツール結果の失敗", "${snap.tools.errors} 件")
        DetailRow("権限の拒否", "${snap.permissionDenied} 件")
        DetailRow("最後の段階（エージェントの報告）", snap.lastPhase?.let { execLookup(EXECUTION_PHASES, it) } ?: "なし")
        DetailRow("テストの段階", if (snap.testingReported) "報告あり" else "報告なし")
        Text(EXECUTION_TURNS_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("ツールの種類別の件数", style = MaterialTheme.typography.titleSmall)
        EXECUTION_TOOLS.forEach { (name, label) ->
            DetailRow(label, "${execToolCount(snap.tools, name)} 回")
        }

        Text("費用", style = MaterialTheme.typography.titleSmall)
        DetailRow("確定（CLI の報告。終了後に確定します）", usdText(snap.cost.confirmedUsd))
        DetailRow("推定（予約からの見積り。確定ではありません）", execEstimateText(snap.cost, snap.attempts))

        Text("停止", style = MaterialTheme.typography.titleSmall)
        DetailRow("段階", execLookup(EXECUTION_STOP_STAGES, snap.stop.stage))
        DetailRow("停止要求", if (snap.stop.runCancelRequested) "あり" else "なし")
        DetailRow("エージェントの確認（エージェントの報告）", if (snap.stop.agentAcknowledged) "停止要求を受けたと報告" else "報告なし")
        DetailRow("プロセスの終了（エージェントの報告）", if (snap.stop.processExitConfirmed) "終了を報告" else "報告なし")
    }
}

/**
 * 受信の鮮度の行。終端の Run は受信が止まって当然なので、古い警告を出さない。走っている Run の STALE は警告の色と文字で出す。
 */
@Composable
private fun StatusLine(snap: ApiRunExecution, watch: ExecutionWatch) {
    if (isExecutionTerminal(snap.runState)) {
        val ended = parseInstantMillis(snap.lastReceivedAt)
        Text(EXECUTION_TERMINAL_TEXT + "最終の受信: " + (ended?.let { executionLocalTime(it.toDouble()) } ?: "不明"))
        return
    }
    if (snap.freshness == "STALE") {
        val minutes = execStaleMinutes(parseInstantMillis(snap.lastReceivedAt), watch.renderedAtMs, watch.skewMs)
        // 旧 UI と同じ: 1 分未満は「1 分未満」だけ(古い可能性の文は付けない)。
        val text = when {
            minutes == null -> EXECUTION_NO_RECEIPT_TEXT
            minutes < 1L -> "最終の受信から 1 分未満"
            else -> "最終の受信から $minutes 分経過。$EXECUTION_STALE_TEXT"
        }
        Text(text, color = MaterialTheme.colorScheme.error)
        return
    }
    val last = parseInstantMillis(snap.lastReceivedAt)
    Text("鮮度: ${snap.freshness}。最終の受信: " + (last?.let { executionLocalTime(it.toDouble()) } ?: "不明"))
}
