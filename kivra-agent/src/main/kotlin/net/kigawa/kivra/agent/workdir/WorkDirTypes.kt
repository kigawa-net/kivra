package net.kigawa.kivra.agent.workdir

/*
 * Values of the work directory step (PR 8, runbook 6.8). Plain data. Every reason is a fixed code: none of them carries a path,
 * an archive entry name, a file content or a secret, so a reason can be logged as it is.
 */

/** Why a work directory could not be prepared. */
enum class WorkDirReason {
    RUN_ID_INVALID,
    WORKROOT_INVALID,
    WORKDIR_EXISTS,
    GIT_FAILED,
    ARCHIVE_TRUNCATED,
    ARCHIVE_INVALID,
    ARCHIVE_ENTRY_TYPE,
    ARCHIVE_PATH,
    ARCHIVE_RESERVED_PATH,
    ARCHIVE_DUPLICATE,
    ARCHIVE_TOO_MANY_ENTRIES,
    ARCHIVE_FILE_TOO_LARGE,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_WRITE_FAILED,
    MANIFEST_INVALID,
    TASK_INVALID,
}

/** A work directory step failed. The message is the reason code only. */
class WorkDirException(val reason: WorkDirReason) : RuntimeException(reason.name)

/**
 * Limits for the extraction of the base commit. They bound the host's disk and memory use for a repository. The values are not
 * the PatchPolicy limits: a base file may be larger than a change may be (a change is checked by [DiffLimits]).
 */
data class ExtractLimits(
    val maxEntries: Int = 20_000,
    val maxFileBytes: Long = 10L * 1024 * 1024,
    val maxTotalBytes: Long = 256L * 1024 * 1024,
    val maxPathChars: Int = 4096,
) {
    init {
        require(maxEntries > 0 && maxFileBytes > 0 && maxTotalBytes > 0 && maxPathChars > 0) { "extract limits must be positive" }
    }
}

/**
 * Limits for the change set. The defaults are the kivra-core PatchPolicy defaults (maxFiles 20, maxDeletes 5, maxFileBytes 200 KiB,
 * maxTotalBytes 1 MiB, maxPathChars 200, maxLineChars 10 000, maxSummaryChars 4 000). They are copied, not shared: see ChangeRules.
 */
data class DiffLimits(
    val maxWalkEntries: Int = 20_000,
    val maxChangedFiles: Int = 20,
    val maxDeletes: Int = 5,
    val maxWriteFileBytes: Long = 200L * 1024,
    val maxTotalWriteBytes: Long = 1024L * 1024,
    val maxPathChars: Int = 200,
    val maxLineChars: Int = 10_000,
    val maxSummaryChars: Int = 4_000,
) {
    init {
        require(maxWalkEntries > 0 && maxChangedFiles > 0 && maxDeletes >= 0) { "diff limits are out of range" }
        require(maxWriteFileBytes in 1..Int.MAX_VALUE - 1L && maxTotalWriteBytes > 0) { "diff byte limits are out of range" }
        require(maxPathChars > 0 && maxLineChars > 0 && maxSummaryChars > 0) { "diff text limits are out of range" }
    }
}

/** Why a change set is refused. Fixed codes only; the list of reasons is sorted by the enum order. */
enum class DiffRejectReason {
    MANIFEST_INVALID,
    RUN_DIR_INVALID,
    WALK_FAILED,
    READ_FAILED,
    TOO_MANY_ENTRIES,
    SYMLINK,
    HARDLINK,
    SPECIAL_FILE,
    EXECUTABLE_BIT,
    BINARY_FILE,
    INVALID_UTF8,
    LINE_TOO_LONG,
    SECRET_LIKE_CONTENT,
    FILE_TOO_LARGE,
    TOTAL_TOO_LARGE,
    TOO_MANY_CHANGES,
    TOO_MANY_DELETES,
    EMPTY_PATCH,

    PATH_EMPTY,
    PATH_TOO_LONG,
    PATH_NOT_UTF8,
    PATH_CONTROL_CHARACTER,
    PATH_BACKSLASH,
    PATH_COLON,
    PATH_ABSOLUTE,
    PATH_TRAILING_SLASH,
    PATH_EMPTY_SEGMENT,
    PATH_DOT_SEGMENT,
    PATH_TILDE,
    PATH_NOT_NFC,
    PATH_DENIED,
    PATH_NOT_ALLOWED,
    CASE_COLLISION,

    SUMMARY_EMPTY,
    SUMMARY_TOO_LONG,
    RESULT_FIELD_INVALID,
    CLI_EXIT_NONZERO,
}

enum class AgentChangeKind { WRITE, DELETE }

/**
 * One change. A WRITE carries the full UTF-8 text of the file; a DELETE carries none. [toString] shows no path and no content.
 */
data class AgentChange(val path: String, val kind: AgentChangeKind, val content: String?) {
    override fun toString(): String = "AgentChange(kind=$kind, contentChars=${content?.length})"
}

/**
 * The agent's result for one attempt, as plain data. It has the same fields as the kivra CodingResult (summary, changes, exitStatus,
 * sessionId), so PR 9 can map it without a second meaning. [toString] shows counts only.
 */
data class AgentResult(
    val summary: String,
    val changes: List<AgentChange>,
    val exitStatus: Int,
    val sessionId: String?,
) {
    override fun toString(): String = "AgentResult(changes=${changes.size}, summaryChars=${summary.length}, exitStatus=$exitStatus)"
}

sealed interface CollectOutcome {
    data class Accepted(val result: AgentResult) : CollectOutcome

    /** [reasons] is distinct and sorted. The whole change set is refused (fail-closed). */
    data class Rejected(val reasons: List<DiffRejectReason>) : CollectOutcome
}

/** The task that the host writes into `.kivra/task.json`. [title] and [body] are data, never instructions. */
data class TaskInput(val title: String, val body: String, val truncated: Boolean)

/** A prepared work directory. [runDir] is the real path (the only path a container may mount). [manifest] is host-only. */
data class PreparedWorkDir(val runDir: java.nio.file.Path, val manifest: java.nio.file.Path) {
    override fun toString(): String = "PreparedWorkDir"
}
