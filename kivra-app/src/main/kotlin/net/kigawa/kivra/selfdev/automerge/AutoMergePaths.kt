package net.kigawa.kivra.selfdev.automerge

/*
 * Path rules of ADR 0014 section 2 (C3) and the denylist of section 2.1. Pure string work: no normalization, no file access.
 *
 * The scope is matched case-sensitively, so `Kivra-app/...` is out of scope. The denylist is matched case-insensitively, so it
 * still wins on a name that differs only by case (fail-closed). A path is in scope only when it is safe, matches the scope and
 * matches no denylist pattern.
 */
internal object AutoMergePaths {
    /** U-1 (ADR 0014): the test sources of the kivra modules only. docs and README.md are out for the first stage (section 2.2). */
    val DEFAULT_SCOPE: List<String> = listOf("kivra-*/src/test/kotlin/**/*.kt")

    /** The denylist of section 2.1. Always wins over the scope. */
    val DENYLIST: List<String> = listOf(
        // CI, build and container wiring
        ".github/**",
        "gradle/**",
        "**/gradlew*",
        "**/*.gradle.kts",
        "**/gradle.properties",
        "**/Dockerfile*",
        "**/compose*.yml",
        // policy, authentication and budget cores
        "kivra-policy/**",
        "kivra-*/src/main/kotlin/**/auth/**",
        "kivra-model-gateway/**/Budget.kt",
        // migrations are written by people
        "kivra-*/src/main/resources/db/migration/**",
        // agent, design-governance and key files
        "docs/adr/**",
        "docs/provider-compatibility-matrix.md",
        "**/CLAUDE.md",
        "**/.env*",
        "**/*.pem",
        "**/*.key",
        // the merge decision, the GitHub port and the operator screen themselves
        "kivra-github/**",
        "kivra-core/src/main/kotlin/net/kigawa/kivra/core/**",
        "kivra-app/src/main/kotlin/net/kigawa/kivra/selfdev/**",
        "kivra-app/src/main/resources/ui/**",
        // test resources can be read as configuration
        "kivra-*/src/test/resources/**",
        // git internals and dotfiles that carry configuration
        "**/.git*",
        // The gate's own tests: an auto-approved change must not weaken the assertions that guard the gate (ADR 0014 section 2.1, applied to tests).
        "kivra-*/src/test/kotlin/**/selfdev/automerge/**",
        "kivra-*/src/test/kotlin/**/*PatchPolicy*.kt",
        "kivra-*/src/test/kotlin/**/*AutoScope*.kt",
        "kivra-*/src/test/kotlin/**/*PathRules*.kt",
        "kivra-*/src/test/kotlin/**/*DiffCollector*.kt",
        "kivra-*/src/test/kotlin/**/*DiffLimits*.kt",
        "kivra-*/src/test/kotlin/**/*ArchitectureTest*.kt",
        "kivra-*/src/test/kotlin/**/*Invariant*Test*.kt",
        // tests of the policy, GitHub, core, authentication and selfdev cores
        "kivra-policy/src/test/**",
        "kivra-github/src/test/**",
        "kivra-core/src/test/kotlin/net/kigawa/kivra/core/**",
        "kivra-*/src/test/kotlin/**/auth/**",
        "kivra-app/src/test/kotlin/net/kigawa/kivra/selfdev/**",
        // tests of the MCP endpoint and the routes, whose permission checks must not be weakened
        "kivra-app/src/test/kotlin/net/kigawa/kivra/mcp/**",
        "kivra-app/src/test/kotlin/net/kigawa/kivra/routes/**",
    )

    /**
     * True when [path] is a plain repository-relative path: not empty, no `..`, no `\`, no leading `/`, no empty or `.` segment, no NUL,
     * no control character and no format character (this includes the bidirectional controls).
     */
    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith('/') || path.contains("..") || path.contains('\\')) return false
        if (path.split('/').any { it.isEmpty() || it == "." }) return false
        return path.codePoints().allMatch { cp ->
            cp != 0 && !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT.toInt()
        }
    }

    /** True when [path] is safe, matches one of [scope] (case-sensitive) and matches no denylist pattern (case-insensitive). */
    fun isInScope(path: String, scope: List<String>): Boolean =
        isSafe(path) &&
            scope.any { matchesGlob(it, path, ignoreCase = false) } &&
            DENYLIST.none { matchesGlob(it, path, ignoreCase = true) }

    /** Glob match on `/`-separated segments. `**` matches zero or more whole segments; `*` and `?` stay inside one segment. */
    internal fun matchesGlob(pattern: String, path: String, ignoreCase: Boolean): Boolean =
        matchSegments(pattern.split('/'), 0, path.split('/'), 0, ignoreCase)

    private fun matchSegments(pattern: List<String>, pi: Int, path: List<String>, si: Int, ignoreCase: Boolean): Boolean {
        if (pi == pattern.size) return si == path.size
        if (pattern[pi] == "**") {
            return (si..path.size).any { matchSegments(pattern, pi + 1, path, it, ignoreCase) }
        }
        if (si == path.size) return false
        return wildcard(pattern[pi], path[si], ignoreCase) && matchSegments(pattern, pi + 1, path, si + 1, ignoreCase)
    }

    /** Matches one segment with `*` (any run of characters) and `?` (one character). Linear-space greedy matching. */
    private fun wildcard(pattern: String, text: String, ignoreCase: Boolean): Boolean {
        var p = 0
        var t = 0
        var starP = -1
        var starT = 0
        while (t < text.length) {
            if (p < pattern.length && (pattern[p] == '?' || pattern[p].equals(text[t], ignoreCase))) {
                p++
                t++
            } else if (p < pattern.length && pattern[p] == '*') {
                starP = p
                starT = t
                p++
            } else if (starP >= 0) {
                p = starP + 1
                starT++
                t = starT
            } else {
                return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
