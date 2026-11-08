package net.kigawa.kivra.ui.api

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * API の利用状態の全体の共通部分。読み取りと書き込みの双方が [ApiClient] を通るので、ここで一元的に持つ。
 *
 * - [expired]: セッションが切れた(401、またはサインイン画面の HTML が返った)。読み直すまで /api への要求は送らない
 *   (旧 UI の sessionExpired と同じ)。App がバナーを出す。
 * - [holds]: 送信中の書き込みと、開いている書き込みダイアログの数。0 でない間は定期更新を止める。
 */
object ApiSession {
    var expired by mutableStateOf(false)
        private set

    var holds by mutableStateOf(0)
        private set

    fun markExpired() {
        expired = true
    }

    fun hold() {
        holds += 1
    }

    fun unhold() {
        if (holds > 0) holds -= 1
    }
}
