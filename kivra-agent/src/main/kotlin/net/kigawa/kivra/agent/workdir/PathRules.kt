package net.kigawa.kivra.agent.workdir

/**
 * The path rules of one attempt (Issue #231). A changed path must pass BOTH the built-in rules ([ChangeRules], a copy of kivra-core's
 * defaults) AND the patterns that `claim` sends for this attempt. The built-in denylist always applies, whatever the server sends.
 *
 * The server patterns are read with the same meaning as kivra-core's PatchPolicy, so the agent refuses at least what the server would:
 * - an allowed pattern is case-sensitive, a denied pattern is case-insensitive (PatchPolicy: `allowed` case-sensitive, `denied`
 *   case-insensitive);
 * - `*` is one segment, `?` one character, a double star followed by a slash zero or more directories, and a bare double star anything;
 * - a pattern starting with `/` is literal, so it never matches a repository-relative path (the server does the same);
 * - a denied match wins over an allowed match;
 * - an empty allowed list means "no server allowlist" (only the built-in allowlist applies). An empty denied list adds nothing.
 *
 * Invalid patterns are refused as a whole by [fromServer] (see [PathPatternException]). Nothing here logs a pattern.
 */
class PathRules private constructor(
    private val serverAllowed: List<Regex>,
    private val serverDenied: List<Regex>,
) {
    /** The first reason a changed path is refused, or null. The built-in rules are checked first, then the server's denylist and allowlist. */
    fun reason(path: String, maxChars: Int): DiffRejectReason? {
        ChangeRules.pathReason(path, maxChars)?.let { return it }
        if (serverDenied.any { it.matches(path) }) return DiffRejectReason.PATH_DENIED
        if (serverAllowed.isNotEmpty() && serverAllowed.none { it.matches(path) }) return DiffRejectReason.PATH_NOT_ALLOWED
        return null
    }

    companion object {
        /** The most patterns of each list. A claim with more is refused (NEEDS_HUMAN). The server's defaults are 3 and 20. */
        const val MAX_PATTERNS = 100

        /** The most characters of one pattern. Paths are at most 200 characters (the PatchPolicy limit), so a longer pattern is not useful. */
        const val MAX_PATTERN_CHARS = 200

        /** Only the built-in rules. This is what a claim with no patterns gets. */
        val BUILT_IN_ONLY: PathRules = PathRules(emptyList(), emptyList())

        /**
         * The rules for the patterns of one claim. Throws [PathPatternException] when a list is too long, a pattern is empty or too long, or
         * a pattern has a control character. The exception carries a fixed message only.
         */
        fun fromServer(allowed: List<String>, denied: List<String>): PathRules {
            checkList(allowed)
            checkList(denied)
            return PathRules(
                serverAllowed = allowed.map { ChangeRules.globToRegex(it, ignoreCase = false) },
                serverDenied = denied.map { ChangeRules.globToRegex(it, ignoreCase = true) },
            )
        }

        private fun checkList(patterns: List<String>) {
            if (patterns.size > MAX_PATTERNS) throw PathPatternException("too many path patterns")
            for (pattern in patterns) {
                if (pattern.isEmpty()) throw PathPatternException("a path pattern is empty")
                if (pattern.length > MAX_PATTERN_CHARS) throw PathPatternException("a path pattern is too long")
                if (pattern.any { Character.isISOControl(it) }) throw PathPatternException("a path pattern has a control character")
            }
        }
    }
}

/** A claim's path patterns are not usable. The message is fixed and never names a pattern. */
class PathPatternException(message: String) : IllegalArgumentException(message)
