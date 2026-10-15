package net.kigawa.kivra.core.patch

import java.text.Normalizer
import java.util.Locale

/*
 * Pure helpers shared by [PatchPolicy] and [PatchSetParser]. Not part of the public surface.
 */

/** NFC form. Paths are compared and matched in this form. */
internal fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

/** Two paths collide when their keys are equal: NFC-normalized and lower-cased. */
internal fun collisionKey(path: String): String = nfc(path).lowercase(Locale.ROOT)

/** False when the text contains an unpaired UTF-16 surrogate, which is not valid Unicode text. */
internal fun isWellFormedUtf16(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            Character.isHighSurrogate(c) -> {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                    continue
                }
                return false
            }

            Character.isLowSurrogate(c) -> return false
        }
        i++
    }
    return true
}

/** UTF-8 byte length of [s]. Unpaired surrogates count as 3 bytes; callers reject them separately. */
internal fun utf8Size(s: String): Long {
    var n = 0L
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c.code < 0x80 -> n += 1
            c.code < 0x800 -> n += 2
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                n += 4
                i++
            }

            else -> n += 3
        }
        i++
    }
    return n
}

/** Characters that must never appear in a path: C0/C1 controls, NUL, format and line/paragraph separators, surrogates. */
private fun isForbiddenPathChar(ch: Char): Boolean {
    if (ch.code < 0x20 || ch.code == 0x7F) return true
    val type = Character.getType(ch)
    return type == Character.CONTROL.toInt() ||
        type == Character.FORMAT.toInt() ||
        type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt() ||
        type == Character.SURROGATE.toInt()
}

/**
 * Syntactic check of a repository-relative path. Returns the first failing reason, or null when the shape is valid.
 * Does not check the allowlist, the denylist or collisions.
 */
internal fun pathShapeReason(path: String, maxChars: Int): RejectReason? {
    if (path.isEmpty()) return RejectReason.PATH_EMPTY
    if (path.length > maxChars) return RejectReason.PATH_TOO_LONG
    if (path.any { isForbiddenPathChar(it) }) return RejectReason.PATH_CONTROL_CHARACTER
    if (path.contains('\\')) return RejectReason.PATH_BACKSLASH
    if (path.contains(':')) return RejectReason.PATH_COLON
    if (path.startsWith('/')) return RejectReason.PATH_ABSOLUTE
    if (path.endsWith('/')) return RejectReason.PATH_TRAILING_SLASH
    val segments = path.split('/')
    if (segments.any { it.isEmpty() }) return RejectReason.PATH_EMPTY_SEGMENT
    if (segments.any { it == "." || it == ".." }) return RejectReason.PATH_DOT_SEGMENT
    if (segments.any { it.startsWith('~') }) return RejectReason.PATH_TILDE
    return null
}

/**
 * Converts a glob into a regex that must match the whole path.
 * A `**` segment followed by a slash matches zero or more directories; a bare `**` matches anything including slashes;
 * `*` and `?` never match a slash.
 */
internal fun globToRegex(glob: String, ignoreCase: Boolean): Regex {
    val sb = StringBuilder()
    var i = 0
    while (i < glob.length) {
        when {
            glob.startsWith("**/", i) -> {
                sb.append("(?:.*/)?")
                i += 3
                continue
            }

            glob.startsWith("**", i) -> {
                sb.append(".*")
                i += 2
                continue
            }

            glob[i] == '*' -> sb.append("[^/]*")
            glob[i] == '?' -> sb.append("[^/]")
            else -> sb.append(Regex.escape(glob[i].toString()))
        }
        i++
    }
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    return Regex(sb.toString(), options)
}

/** Length of the longest line, in UTF-16 units, excluding the newline. */
internal fun longestLineChars(s: String): Int {
    var max = 0
    var current = 0
    for (c in s) {
        if (c == '\n') {
            current = 0
        } else {
            current++
            if (current > max) max = current
        }
    }
    return max
}
