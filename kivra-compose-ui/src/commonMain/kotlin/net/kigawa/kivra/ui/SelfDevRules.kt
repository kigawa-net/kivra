package net.kigawa.kivra.ui

/** ホームに出す自己開発の状態。LOADING は最初の読み取りの前。 */
enum class SelfDevAvailability(val label: String) {
    LOADING("自己開発: 確認中"),
    ENABLED("自己開発: 有効"),
    DISABLED("自己開発: 無効"),
    UNKNOWN("自己開発: 取得失敗"),
}

/** `GET /api/selfdev/status` の `enabled`。null(読めなかった・型が違う)は取得失敗。 */
fun selfDevAvailabilityOf(enabled: Boolean?): SelfDevAvailability = when (enabled) {
    true -> SelfDevAvailability.ENABLED
    false -> SelfDevAvailability.DISABLED
    null -> SelfDevAvailability.UNKNOWN
}
