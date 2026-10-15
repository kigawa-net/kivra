package net.kigawa.kivra.core.patch

/*
 * Self-development loop, part 1 (Issue #56). A PatchSet is the change proposal an LLM produces. It is plain data:
 * no I/O, no execution. [PatchPolicy] decides whether it may become a branch and a PR.
 *
 * Only whole-file writes and deletes exist, no renames and no unified diffs. This keeps every change easy to verify.
 */

/**
 * Proposed change set. Paths are relative to the repository root. [summary] is the human-readable explanation that
 * becomes the PR body; [rationale] is optional extra context for the reviewer.
 */
data class PatchSet(
    val files: List<FileChange>,
    val summary: String,
    val rationale: String? = null,
) {
    /** Does not print summary or rationale, so that model-written text does not end up in logs by accident. */
    override fun toString(): String =
        "PatchSet(files=${files.size}, summaryChars=${summary.length}, hasRationale=${rationale != null})"
}

sealed interface FileChange {
    val path: String
}

/** Creates the file at [path], or replaces it completely. */
data class Write(override val path: String, val content: String) : FileChange {
    /** Does not print the content (it may contain a secret the policy is about to reject). */
    override fun toString(): String = "Write(path=$path, contentChars=${content.length})"
}

/** Deletes the file at [path]. */
data class Delete(override val path: String) : FileChange

/**
 * Read-only view of the repository state. [PatchPolicy] uses it for the checks that need the current tree
 * (deleting a missing file, shrinking an existing file).
 */
interface RepoSnapshot {
    fun exists(path: String): Boolean

    /** UTF-8 byte size of the file at [path], or null when it is absent or unknown. */
    fun size(path: String): Long?

    /**
     * True when the file at [path] was shown to the LLM (ADR 0008 item 5). A Write or Delete of an existing file that is not in
     * the context is rejected as UNSEEN_OVERWRITE: the model never saw its content, so it cannot have meant to replace it.
     * The default is true, which keeps every existing caller's behaviour.
     */
    fun isInContext(path: String): Boolean = true
}
