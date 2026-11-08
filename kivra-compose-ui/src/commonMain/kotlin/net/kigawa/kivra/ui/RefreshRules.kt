package net.kigawa.kivra.ui

/** 定期更新の間隔(旧 UI の REFRESH_MS と同じ 30 秒)。 */
const val AUTO_REFRESH_INTERVAL_MS = 30_000L

/**
 * 定期更新をいま走らせてよいか。旧 UI の tick と同じ条件に加え、書き込みの送信中・ダイアログ表示中は止める
 * (表示中の内容を書き換えないため)。ページが隠れている時、セッション切れの後も止める(旧 UI と同じ)。
 */
fun shouldAutoRefresh(
    now: Long,
    lastAttemptAt: Long,
    hidden: Boolean,
    holds: Int,
    sessionExpired: Boolean,
): Boolean =
    !hidden && holds == 0 && !sessionExpired && now - lastAttemptAt >= AUTO_REFRESH_INTERVAL_MS

/** `GET /health` の結果。[ok] が null は未確認。時刻はエポックのミリ秒。 */
data class HealthState(
    val ok: Boolean? = null,
    val checkedAt: Long = 0L,
    val okAt: Long = 0L,
) {
    /** 確認の結果を反映した新しい状態。成功なら最後の成功時刻も更新する。 */
    fun after(ok: Boolean, now: Long): HealthState =
        HealthState(ok = ok, checkedAt = now, okAt = if (ok) now else okAt)
}

/**
 * ヘッダーの接続表示(旧 UI の renderHealth と同じ文言)。[clock] は時刻を `HH:MM:SS` に整える関数。
 */
fun connectionLabel(health: HealthState, clock: (Long) -> String): String = when (health.ok) {
    null -> "接続: 未確認"
    true -> "接続: OK（確認 ${clock(health.checkedAt)}）"
    false -> {
        val last = if (health.okAt > 0L) "、最後に成功 ${clock(health.okAt)}" else "、成功したことはありません"
        "接続: 失敗（確認 ${clock(health.checkedAt)}$last）"
    }
}
