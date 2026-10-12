package net.kigawa.kivra.observability

/**
 * Value-level guard applied to every label, span attribute and log field.
 *
 * Key allowlists decide *which* keys may exist; this decides whether an allowed key's *value*
 * looks like a secret or a body (prompt, response, token). Rejected values are never emitted.
 */
object Redaction {
    const val REDACTED = "_redacted"
    const val MAX_VALUE_LENGTH = 256

    private val secretPatterns = listOf(
        Regex("""(?i)\bbearer\s+\S+"""),
        Regex("""\b(sk|pk|rk)-[A-Za-z0-9_-]{8,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{10,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{10,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        Regex("""(?i)\b(password|passwd|secret|token|api[_-]?key|authorization|credential)s?\s*[=:]"""),
        // Opaque blobs (base64 / base64url / long hex-like strings). Trace/Run IDs are far shorter.
        Regex("""[A-Za-z0-9+/_-]{64,}={0,2}"""),
    )

    /** True when [value] may be emitted: short, single-line and not matching any secret pattern. */
    fun isSafeValue(value: String, maxLength: Int = MAX_VALUE_LENGTH): Boolean {
        if (value.length > maxLength) return false
        if (value.any { it == '\n' || it == '\r' }) return false
        return secretPatterns.none { it.containsMatchIn(value) }
    }
}
