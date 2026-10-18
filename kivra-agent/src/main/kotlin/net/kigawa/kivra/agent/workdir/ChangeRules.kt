package net.kigawa.kivra.agent.workdir

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/*
 * Path, content and result rules for a change set. They COPY the rules of kivra-core (PatchPolicy, PatchPolicyDefaults, PatchSupport)
 * because kivra-agent does not depend on kivra-core. A change to either side must be made to the other. ChangeRulesTest pins the
 * copied lists. The agent is the stricter side: it adds an NFC check that the server does not have.
 */
internal object ChangeRules {
    /** Copy of kivra-core PatchPolicyDefaults.ALLOWED_PATH_PATTERNS. */
    val ALLOWED_PATH_PATTERNS: List<String> = listOf(
        "kivra-*/src/**",
        "docs/**",
        "README.md",
    )

    /** Copy of kivra-core PatchPolicyDefaults.DENIED_PATH_PATTERNS. */
    val DENIED_PATH_PATTERNS: List<String> = listOf(
        ".github/**",
        "gradle/**",
        "**/gradlew*",
        "**/*.gradle.kts",
        "**/*.gradle",
        "**/gradle.properties",
        "**/Dockerfile*",
        "**/compose*.yml",
        "**/compose*.yaml",
        "kivra-*/src/main/resources/db/migration/**",
        "**/.git*",
        "**/.env*",
        "**/*.pem",
        "**/*.key",
        "**/CLAUDE.md",
        "docs/adr/**",
        "docs/provider-compatibility-matrix.md",
        "kivra-policy/**",
        "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/**",
        "kivra-model-gateway/src/main/kotlin/**/Budget.kt",
    )

    /** Copy of the secret shapes in kivra-core PatchPolicy. */
    private val SECRET_PATTERNS: List<Regex> = listOf(
        Regex("-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----"),
        Regex("ghp_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("sk-ant-[A-Za-z0-9_-]{20,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("(?i)(password|passwd|secret|api[_-]?key|token)\\s*[=:]\\s*\"[^\"\\n]{8,}\""),
    )

    private val allowed: List<Regex> = ALLOWED_PATH_PATTERNS.map { globToRegex(it, ignoreCase = false) }
    private val denied: List<Regex> = DENIED_PATH_PATTERNS.map { globToRegex(it, ignoreCase = true) }

    /** The first reason a changed path is refused, or null. Used only for paths that are changed (added, modified or deleted). */
    fun pathReason(path: String, maxChars: Int): DiffRejectReason? {
        shapeReason(path, maxChars)?.let { return it }
        if (denied.any { it.matches(path) }) return DiffRejectReason.PATH_DENIED
        if (allowed.none { it.matches(path) }) return DiffRejectReason.PATH_NOT_ALLOWED
        return null
    }

    /** Text that contains a secret shape. The text itself is never returned. */
    fun containsSecretLike(text: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(text) }

    /** The content of a file as text, or the reasons it is refused. [text] is null when refused. */
    class Content(val reasons: Set<DiffRejectReason>, val text: String?)

    fun contentOf(bytes: ByteArray, maxLineChars: Int): Content {
        if (bytes.any { it == 0.toByte() }) return Content(setOf(DiffRejectReason.BINARY_FILE), null)
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return Content(setOf(DiffRejectReason.INVALID_UTF8), null)
        }
        val reasons = mutableSetOf<DiffRejectReason>()
        if (longestLineChars(text) > maxLineChars) reasons.add(DiffRejectReason.LINE_TOO_LONG)
        if (containsSecretLike(text)) reasons.add(DiffRejectReason.SECRET_LIKE_CONTENT)
        return Content(reasons, if (reasons.isEmpty()) text else null)
    }

    /** The same key kivra-core uses to find colliding paths: NFC, then lower case. */
    fun collisionKey(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun shapeReason(path: String, maxChars: Int): DiffRejectReason? {
        if (path.isEmpty()) return DiffRejectReason.PATH_EMPTY
        if (path.length > maxChars) return DiffRejectReason.PATH_TOO_LONG
        if (path.contains('\uFFFD')) return DiffRejectReason.PATH_NOT_UTF8
        if (path.any { isForbiddenPathChar(it) }) return DiffRejectReason.PATH_CONTROL_CHARACTER
        if (path.contains('\\')) return DiffRejectReason.PATH_BACKSLASH
        if (path.contains(':')) return DiffRejectReason.PATH_COLON
        if (path.startsWith('/')) return DiffRejectReason.PATH_ABSOLUTE
        if (path.endsWith('/')) return DiffRejectReason.PATH_TRAILING_SLASH
        val segments = path.split('/')
        if (segments.any { it.isEmpty() }) return DiffRejectReason.PATH_EMPTY_SEGMENT
        if (segments.any { it == "." || it == ".." }) return DiffRejectReason.PATH_DOT_SEGMENT
        if (segments.any { it.startsWith('~') }) return DiffRejectReason.PATH_TILDE
        if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)) return DiffRejectReason.PATH_NOT_NFC
        return null
    }

    /** Copy of kivra-core isForbiddenPathChar: C0/C1 controls, DEL, format and separator characters, surrogates. */
    private fun isForbiddenPathChar(ch: Char): Boolean {
        if (ch.code < 0x20 || ch.code == 0x7F) return true
        val type = Character.getType(ch)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.SURROGATE.toInt()
    }

    /** Copy of kivra-core globToRegex: a double-star segment with a slash matches zero or more directories, a bare double-star anything. */
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

    /** Copy of kivra-core longestLineChars: UTF-16 units of the longest line, excluding the newline. */
    private fun longestLineChars(s: String): Int {
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
}
