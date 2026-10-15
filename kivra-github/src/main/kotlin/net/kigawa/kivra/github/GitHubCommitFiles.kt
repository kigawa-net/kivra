package net.kigawa.kivra.github

/**
 * Input of [GitHubPort.commitFiles]. Deliberately independent of kivra-core's `PatchSet`: the Runner converts
 * between the two. [toString] never prints the message, the paths or the contents.
 */
class CommitFilesRequest(
    val branch: String,
    /** The branch head the caller based its changes on. A different head is `CONFLICT` and nothing is written. */
    val expectedHeadSha: String,
    val message: String,
    val changes: List<FileWrite>,
) {
    override fun toString() = "CommitFilesRequest(branch=<redacted>, changes=${changes.size}, message=<redacted>)"
}

/** One change in a commit. [path] is repository-relative. [toString] never prints the path or the content. */
sealed interface FileWrite {
    val path: String

    /** Creates or replaces a regular file (mode `100644` only). */
    class Write(override val path: String, val content: ByteArray) : FileWrite {
        constructor(path: String, text: String) : this(path, text.toByteArray(Charsets.UTF_8))

        override fun toString() = "FileWrite.Write(path=<redacted>, bytes=${content.size})"
    }

    /** Removes a file from the tree. */
    class Delete(override val path: String) : FileWrite {
        override fun toString() = "FileWrite.Delete(path=<redacted>)"
    }
}

/** Result of [GitHubPort.commitFiles]. [created] is true when the commit was made and the ref moved by this call. */
data class CommittedFiles(val commitSha: String, val treeSha: String, val created: Boolean)

/** Safety ceilings, checked before any request is sent. Values are inclusive. */
data class CommitFilesLimits(
    val maxFiles: Int = 50,
    val maxFileBytes: Int = 1_048_576,
    val maxTotalBytes: Long = 5_242_880L,
    val maxPathChars: Int = 255,
    val maxMessageChars: Int = 4_000,
)

/**
 * Pre-request checks for [GitHubPort.commitFiles]. Each check returns a fixed reason string (never the offending input),
 * so a rejection cannot leak a path, a message or a body into logs or results.
 */
internal object CommitFilesPolicy {
    /** Returns the first violated rule, or null when the request may be sent. */
    fun violation(request: CommitFilesRequest, limits: CommitFilesLimits): String? {
        if (!RunBranch.isRunBranch(request.branch)) return "branch"
        if (!SHA_40.matches(request.expectedHeadSha)) return "expected_head"
        if (request.changes.isEmpty()) return "no_changes"
        if (request.changes.size > limits.maxFiles) return "too_many_files"
        val message = request.message
        if (message.isBlank() || message.length > limits.maxMessageChars) return "message_length"
        if (message.any { isForbiddenMessageChar(it) }) return "message_control_char"
        val seen = HashSet<String>()
        var total = 0L
        for (change in request.changes) {
            pathViolation(change.path, limits)?.let { return it }
            if (!seen.add(change.path)) return "duplicate_path"
            if (change is FileWrite.Write) {
                if (change.content.size > limits.maxFileBytes) return "file_too_large"
                total += change.content.size
            }
        }
        if (total > limits.maxTotalBytes) return "total_too_large"
        return null
    }

    /** Git-level path rules, independent of PatchPolicy (defence in depth). */
    fun pathViolation(path: String, limits: CommitFilesLimits): String? {
        if (path.isEmpty() || path.length > limits.maxPathChars) return "path_length"
        if (path.startsWith('/') || path.contains('\\')) return "path_format"
        if (path.any { it.code < 0x20 || it.code == 0x7f }) return "path_control_char"
        val segments = path.split('/')
        if (segments.any { it.isEmpty() }) return "path_format"
        if (segments.any { it == "." || it == ".." }) return "path_dot_segment"
        if (segments.any { it.equals(".git", ignoreCase = true) }) return "path_git_dir"
        return null
    }

    private fun isForbiddenMessageChar(c: Char): Boolean = (c.code < 0x20 && c != '\n') || c.code == 0x7f

    private val SHA_40 = Regex("[0-9a-f]{40}")
}
