package net.kigawa.kivra.policy

/** Minimal glob: `*` matches any run of characters (including none). No other metacharacters. */
internal fun globMatches(pattern: String, value: String): Boolean {
    val parts = pattern.split('*')
    if (parts.size == 1) return pattern == value
    if (!value.startsWith(parts.first())) return false
    if (!value.endsWith(parts.last())) return false
    var pos = parts.first().length
    val end = value.length - parts.last().length
    if (pos > end) return false
    for (mid in parts.subList(1, parts.size - 1)) {
        val i = value.indexOf(mid, pos)
        if (i < 0 || i + mid.length > end) return false
        pos = i + mid.length
    }
    return true
}
