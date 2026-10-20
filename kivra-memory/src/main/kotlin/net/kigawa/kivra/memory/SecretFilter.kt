package net.kigawa.kivra.memory

import kotlin.math.ln

/** Kinds of secret detected. Only the kind is ever reported: never the matched value (design #6 section 7.1). */
enum class SecretKind {
    PRIVATE_KEY, CLOUD_KEY, VCS_TOKEN, CHAT_TOKEN, JWT, BEARER, URL_CREDENTIALS, ENV_ASSIGNMENT, HIGH_ENTROPY,
    /** `sk-ant-...` (Anthropic) and `sk-...` style API keys (added with ADR 0016 P0). */
    API_KEY,
    /** A Keycloak / OAuth client secret written as `client_secret = ...`, also in JSON (added with ADR 0016 P0). */
    CLIENT_SECRET,
    /** `Authorization: Basic ...` (added with ADR 0016 P0). */
    BASIC_AUTH,
}

data class SecretFinding(val kind: SecretKind, val start: Int, val end: Int) {
    // No value here on purpose: a finding must be safe to log.
    override fun toString(): String = "SecretFinding(kind=$kind)"
}

/**
 * Pattern + entropy based secret detector (design #6 section 7.1: known token formats, private key headers,
 * `.env` assignments, entropy check). Detection is best-effort and NOT complete: it is a last line of defence,
 * the primary rule is that callers never pass credentials in the first place.
 */
object SecretFilter {
    private val patterns: List<Pair<SecretKind, Regex>> = listOf(
        SecretKind.PRIVATE_KEY to Regex("-----(?:BEGIN|END) [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----"),
        SecretKind.CLOUD_KEY to Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
        SecretKind.CLOUD_KEY to Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
        SecretKind.VCS_TOKEN to Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{16,})"),
        SecretKind.CHAT_TOKEN to Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
        SecretKind.JWT to Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        SecretKind.API_KEY to Regex("\\bsk-ant-[A-Za-z0-9_-]{16,}"),
        SecretKind.API_KEY to Regex("\\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}"),
        SecretKind.BEARER to Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"),
        SecretKind.BASIC_AUTH to Regex("(?i)\\bauthorization\\s*[=:]\\s*\"?basic\\s+[A-Za-z0-9+/=]{8,}"),
        SecretKind.CLIENT_SECRET to Regex("(?i)\\bclient[_-]?secret[\"']?\\s*[=:]\\s*[\"']?[A-Za-z0-9._~+/=-]{16,}"),
        SecretKind.URL_CREDENTIALS to Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/?#@:]+:[^\\s/?#@]+@"),
        SecretKind.ENV_ASSIGNMENT to Regex(
            "(?im)^\\s*(?:export\\s+)?[A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|PRIVATE_?KEY|CREDENTIAL)[A-Z0-9_]*\\s*[=:]\\s*['\"]?[^\\s'\"]{6,}",
        ),
    )

    private val candidate = Regex("[A-Za-z0-9+=_-]{32,}")

    /** Base64 runs with `+` and `/`, padded or not. Accepted only when they mix upper case, lower case and digits. */
    private val base64Candidate = Regex("(?:[A-Za-z0-9+/]{4}){10,}(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")

    /** Entropy threshold in bits/char above which a long token-like run is treated as a secret. */
    private const val ENTROPY_THRESHOLD = 4.0

    /** A run of lower-case letters that is long enough to be a word (see [isWordLike]). */
    private val LOWER_WORD = Regex("[a-z]{3,}")

    fun scan(text: String): List<SecretFinding> {
        val out = ArrayList<SecretFinding>()
        for ((kind, re) in patterns) {
            for (m in re.findAll(text)) out += SecretFinding(kind, m.range.first, m.range.last + 1)
        }
        for (m in candidate.findAll(text)) {
            val v = m.value
            if (out.any { it.start <= m.range.first && it.end >= m.range.last + 1 }) continue
            if (looksLikeSecret(v)) out += SecretFinding(SecretKind.HIGH_ENTROPY, m.range.first, m.range.last + 1)
        }
        for (m in base64Candidate.findAll(text)) {
            if (out.any { it.start <= m.range.first && it.end >= m.range.last + 1 }) continue
            if (looksLikeBase64Secret(m.value)) out += SecretFinding(SecretKind.HIGH_ENTROPY, m.range.first, m.range.last + 1)
        }
        return out.sortedBy { it.start }
    }

    fun containsSecret(text: String): Boolean = scan(text).isNotEmpty()

    /** Replaces every finding with `[REDACTED:KIND]`. Overlapping findings are merged. */
    fun redact(text: String): String {
        val findings = scan(text)
        if (findings.isEmpty()) return text
        val sb = StringBuilder()
        var pos = 0
        var i = 0
        while (i < findings.size) {
            val f = findings[i]
            var end = f.end
            var j = i + 1
            while (j < findings.size && findings[j].start < end) {
                end = maxOf(end, findings[j].end)
                j++
            }
            if (f.start >= pos) {
                sb.append(text, pos, f.start).append("[REDACTED:").append(f.kind.name).append(']')
                pos = end
            }
            i = j
        }
        sb.append(text, pos, text.length)
        return sb.toString()
    }

    private fun looksLikeSecret(v: String): Boolean {
        val classes = listOf(
            v.any { it in 'a'..'z' }, v.any { it in 'A'..'Z' }, v.any { it in '0'..'9' },
        ).count { it }
        // Pure lowercase/uppercase words, long identifiers separated by '-' / '_' and plain paths are not secrets.
        if (classes < 2) return false
        // A digit-free run that reads as identifier words (CamelCase or snake_case) is not a random token.
        // Only word-like runs are exempt: a digit-free random token has nothing else to hide behind.
        if (v.none { it in '0'..'9' } && isWordLike(v)) return false
        return entropy(v) >= ENTROPY_THRESHOLD
    }

    /** Lower-case words of 3+ letters cover at least half of the run. Identifiers have such words; random tokens rarely do. */
    private fun isWordLike(v: String): Boolean {
        val covered = LOWER_WORD.findAll(v).sumOf { it.value.length }
        return covered * 2 >= v.length
    }

    /** A base64 run of 40+ characters that mixes upper case, lower case and digits, with enough entropy. Paths and words do not match. */
    private fun looksLikeBase64Secret(v: String): Boolean {
        if (v.length < 40) return false
        val mixed = v.any { it in 'A'..'Z' } && v.any { it in 'a'..'z' } && v.any { it in '0'..'9' }
        return mixed && entropy(v) >= ENTROPY_THRESHOLD
    }

    private fun entropy(s: String): Double {
        val counts = HashMap<Char, Int>()
        for (c in s) counts.merge(c, 1, Int::plus)
        var h = 0.0
        for (n in counts.values) {
            val p = n.toDouble() / s.length
            h -= p * (ln(p) / ln(2.0))
        }
        return h
    }
}
