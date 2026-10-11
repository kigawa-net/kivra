package net.kigawa.kivra.db

import java.security.SecureRandom
import java.time.Clock

/** Minimal ULID generator (26 chars, Crockford base32, time-ordered). */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(clock: Clock = Clock.systemUTC()): String {
        val time = clock.millis()
        val chars = CharArray(26)
        var t = time
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t shr 5
        }
        val bytes = ByteArray(10).also { random.nextBytes(it) }
        // 80 random bits -> 16 base32 chars
        var acc = 0
        var bits = 0
        var idx = 10
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                chars[idx++] = ALPHABET[(acc shr (bits - 5)) and 31]
                bits -= 5
            }
            acc = acc and ((1 shl bits) - 1)
        }
        return String(chars)
    }
}
