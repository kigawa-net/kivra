package net.kigawa.kivra.core.patch

import java.util.EnumSet

/*
 * Self-development loop, part 1 (Issue #56). PatchPolicy is the gate between an LLM-proposed PatchSet and any branch
 * or PR. It is fail-closed: a change it cannot classify is rejected. It never reads, writes or executes content.
 *
 * Limits of the secret check: it catches only the listed token and key shapes and quoted password-like assignments.
 * It is one defence layer, not a guarantee. Obfuscated or split secrets pass it. GitHub secret scanning and human
 * review remain necessary.
 *
 * Reasons are a fixed enum. They never carry paths, file contents or task text.
 */

/** Why a PatchSet was rejected. Fixed codes only. */
enum class RejectReason {
    EMPTY_PATCH,
    TOO_MANY_FILES,
    TOO_MANY_DELETES,

    PATH_EMPTY,
    PATH_TOO_LONG,
    PATH_CONTROL_CHARACTER,
    PATH_BACKSLASH,
    PATH_COLON,
    PATH_ABSOLUTE,
    PATH_TILDE,
    PATH_TRAILING_SLASH,
    PATH_EMPTY_SEGMENT,
    PATH_DOT_SEGMENT,
    DUPLICATE_PATH,
    CASE_COLLISION,
    NORMALIZATION_COLLISION,
    PATH_NOT_ALLOWED,
    PATH_DENIED,

    FILE_TOO_LARGE,
    TOTAL_TOO_LARGE,
    BINARY_CONTENT,
    INVALID_UTF8,
    LINE_TOO_LONG,
    SECRET_LIKE_CONTENT,

    DELETE_MISSING_FILE,
    LARGE_SHRINK,
    /** A Write or Delete of an existing file that was not in the LLM's context (see [RepoSnapshot.isInContext]). */
    UNSEEN_OVERWRITE,

    SUMMARY_EMPTY,
    SUMMARY_TOO_LONG,
    RATIONALE_TOO_LONG,
}

sealed interface PatchVerdict {
    data class Accepted(val patch: PatchSet) : PatchVerdict

    /** [reasons] is distinct and sorted by enum order, so the same input always gives the same list. */
    data class Rejected(val reasons: List<RejectReason>) : PatchVerdict
}

/** Default path patterns. Pinned by tests: changing them is a deliberate review decision. */
object PatchPolicyDefaults {
    /** Glob patterns (`*`, `**`, `?`). Matched case-sensitively. A path must match at least one. */
    val ALLOWED_PATH_PATTERNS: List<String> = listOf(
        "kivra-*/src/**",
        "docs/**",
        "README.md",
    )

    /** Glob patterns. Matched case-insensitively and always win over the allowlist. */
    val DENIED_PATH_PATTERNS: List<String> = listOf(
        // CI workflows and build wiring
        ".github/**",
        "gradle/**",
        "**/gradlew*",
        "**/*.gradle.kts",
        "**/*.gradle",
        "**/gradle.properties",
        // container and compose definitions
        "**/Dockerfile*",
        "**/compose*.yml",
        "**/compose*.yaml",
        // Flyway migrations are written by humans
        "kivra-*/src/main/resources/db/migration/**",
        // git internals and dotfiles that carry configuration or hooks
        "**/.git*",
        "**/.env*",
        // key material
        "**/*.pem",
        "**/*.key",
        // agent and design-governance files
        "**/CLAUDE.md",
        "docs/adr/**",
        "docs/provider-compatibility-matrix.md",
        // policy, authentication and budget cores
        "kivra-policy/**",
        "kivra-app/src/main/kotlin/net/kigawa/kivra/auth/**",
        "kivra-model-gateway/src/main/kotlin/**/Budget.kt",
    )
}

/**
 * Limits and patterns. The defaults are the strict set. Relaxing a limit is an explicit, reviewable change.
 * [shrinkThresholdPercent]: a Write that makes an existing file smaller than this percentage of its old size is rejected.
 */
data class PatchPolicyConfig(
    val allowedPathPatterns: List<String> = PatchPolicyDefaults.ALLOWED_PATH_PATTERNS,
    val deniedPathPatterns: List<String> = PatchPolicyDefaults.DENIED_PATH_PATTERNS,
    val maxFiles: Int = 20,
    val maxFileBytes: Long = 200L * 1024,
    val maxTotalBytes: Long = 1024L * 1024,
    val maxDeletes: Int = 5,
    val maxPathChars: Int = 200,
    val maxLineChars: Int = 10_000,
    val maxSummaryChars: Int = 4_000,
    val maxRationaleChars: Int = 4_000,
    val shrinkThresholdPercent: Int = 20,
) {
    init {
        require(maxFiles > 0) { "maxFiles must be positive" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(maxDeletes >= 0) { "maxDeletes must not be negative" }
        require(maxPathChars > 0) { "maxPathChars must be positive" }
        require(maxLineChars > 0) { "maxLineChars must be positive" }
        require(maxSummaryChars > 0) { "maxSummaryChars must be positive" }
        require(maxRationaleChars > 0) { "maxRationaleChars must be positive" }
        require(shrinkThresholdPercent in 0..100) { "shrinkThresholdPercent must be in 0..100" }
    }
}

/** Secret-shaped text. Test values are built by concatenation, so no real-looking secret sits in source. */
private val SECRET_PATTERNS: List<Regex> = listOf(
    Regex("-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----"),
    Regex("ghp_[A-Za-z0-9]{20,}"),
    Regex("github_pat_[A-Za-z0-9_]{20,}"),
    Regex("sk-ant-[A-Za-z0-9_-]{20,}"),
    Regex("AKIA[0-9A-Z]{16}"),
    Regex("(?i)(password|passwd|secret|api[_-]?key|token)\\s*[=:]\\s*\"[^\"\\n]{8,}\""),
)

private fun containsSecretLike(text: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(text) }

class PatchPolicy(private val config: PatchPolicyConfig = PatchPolicyConfig()) {
    private val allowed: List<Regex> = config.allowedPathPatterns.map { globToRegex(it, ignoreCase = false) }
    private val denied: List<Regex> = config.deniedPathPatterns.map { globToRegex(it, ignoreCase = true) }

    /**
     * Checks [patch] against the policy. [current] is optional. Without it, the checks that need the repository
     * state (missing delete targets, shrinking writes) are skipped.
     */
    fun validate(patch: PatchSet, current: RepoSnapshot? = null): PatchVerdict {
        val reasons = EnumSet.noneOf(RejectReason::class.java)
        if (patch.files.isEmpty()) reasons.add(RejectReason.EMPTY_PATCH)
        if (patch.files.size > config.maxFiles) reasons.add(RejectReason.TOO_MANY_FILES)
        checkCollisions(patch.files, reasons)

        var totalBytes = 0L
        var deletes = 0
        for (file in patch.files) {
            checkPath(file.path, reasons)
            when (file) {
                is Write -> {
                    totalBytes += checkWrite(file, current, reasons)
                }

                is Delete -> {
                    deletes++
                    if (current != null && !current.exists(file.path)) reasons.add(RejectReason.DELETE_MISSING_FILE)
                    if (current != null && current.exists(file.path) && !current.isInContext(file.path)) {
                        reasons.add(RejectReason.UNSEEN_OVERWRITE)
                    }
                }
            }
        }
        if (deletes > config.maxDeletes) reasons.add(RejectReason.TOO_MANY_DELETES)
        if (totalBytes > config.maxTotalBytes) reasons.add(RejectReason.TOTAL_TOO_LARGE)

        checkSummary(patch.summary, reasons)
        patch.rationale?.let { checkRationale(it, reasons) }

        return if (reasons.isEmpty()) PatchVerdict.Accepted(patch) else PatchVerdict.Rejected(reasons.toList())
    }

    /**
     * True when [path] has a valid shape, is not denied and is allowed, which are the path rules a write must pass. Used to choose
     * which repository files the model is shown. It never permits a write: the write check is still [validate].
     */
    fun allowsPath(path: String): Boolean {
        if (pathShapeReason(path, config.maxPathChars) != null) return false
        val key = nfc(path)
        return allowed.any { it.matches(key) } && denied.none { it.matches(key) }
    }

    private fun checkPath(path: String, reasons: MutableSet<RejectReason>) {
        val shape = pathShapeReason(path, config.maxPathChars)
        if (shape != null) {
            reasons.add(shape)
            return
        }
        val key = nfc(path)
        when {
            denied.any { it.matches(key) } -> reasons.add(RejectReason.PATH_DENIED)
            allowed.none { it.matches(key) } -> reasons.add(RejectReason.PATH_NOT_ALLOWED)
        }
    }

    /** Two entries that refer to the same file, in any spelling, are rejected. Reasons never include the paths. */
    private fun checkCollisions(files: List<FileChange>, reasons: MutableSet<RejectReason>) {
        val seen = HashMap<String, String>()
        for (file in files) {
            val raw = file.path
            val key = collisionKey(raw)
            val previous = seen[key]
            if (previous == null) {
                seen[key] = raw
                continue
            }
            reasons.add(
                when {
                    previous == raw -> RejectReason.DUPLICATE_PATH
                    nfc(previous) == nfc(raw) -> RejectReason.NORMALIZATION_COLLISION
                    else -> RejectReason.CASE_COLLISION
                },
            )
        }
    }

    /** Returns the UTF-8 size of the content, which is added to the total. */
    private fun checkWrite(file: Write, current: RepoSnapshot?, reasons: MutableSet<RejectReason>): Long {
        val content = file.content
        if (!isWellFormedUtf16(content)) reasons.add(RejectReason.INVALID_UTF8)
        if (content.indexOf('\u0000') >= 0) reasons.add(RejectReason.BINARY_CONTENT)
        if (longestLineChars(content) > config.maxLineChars) reasons.add(RejectReason.LINE_TOO_LONG)
        if (containsSecretLike(content)) reasons.add(RejectReason.SECRET_LIKE_CONTENT)

        val bytes = utf8Size(content)
        if (bytes > config.maxFileBytes) reasons.add(RejectReason.FILE_TOO_LARGE)

        if (current != null && current.exists(file.path)) {
            if (!current.isInContext(file.path)) reasons.add(RejectReason.UNSEEN_OVERWRITE)
            val old = current.size(file.path)
            // bytes < old * percent / 100, computed without division to keep it exact
            if (old != null && old > 0 && bytes * 100 < old * config.shrinkThresholdPercent) {
                reasons.add(RejectReason.LARGE_SHRINK)
            }
        }
        return bytes
    }

    private fun checkSummary(summary: String, reasons: MutableSet<RejectReason>) {
        if (summary.isBlank()) reasons.add(RejectReason.SUMMARY_EMPTY)
        if (summary.length > config.maxSummaryChars) reasons.add(RejectReason.SUMMARY_TOO_LONG)
        if (!isWellFormedUtf16(summary)) reasons.add(RejectReason.INVALID_UTF8)
        if (containsSecretLike(summary)) reasons.add(RejectReason.SECRET_LIKE_CONTENT)
    }

    private fun checkRationale(rationale: String, reasons: MutableSet<RejectReason>) {
        if (rationale.length > config.maxRationaleChars) reasons.add(RejectReason.RATIONALE_TOO_LONG)
        if (!isWellFormedUtf16(rationale)) reasons.add(RejectReason.INVALID_UTF8)
        if (containsSecretLike(rationale)) reasons.add(RejectReason.SECRET_LIKE_CONTENT)
    }
}
