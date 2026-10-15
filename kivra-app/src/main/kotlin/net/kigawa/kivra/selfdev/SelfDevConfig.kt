package net.kigawa.kivra.selfdev

import java.time.Duration
import net.kigawa.kivra.github.RepoRef

/**
 * Self-development settings (ADR 0008). A plain value: it is built by the wiring (part 6), never read from the environment here.
 *
 * [enabled] is the kill switch. The default is off, and while it is off [SelfDevRunner.advance] does nothing at all: no database
 * write, no GitHub call, no model call.
 *
 * [contextPathPrefixes] selects the repository files the LLM is shown. An entry ending in `/` is a directory prefix; any other
 * entry must match a path exactly. Files larger than [maxContextFileBytes] are skipped, and selection stops at
 * [maxContextFiles] files or [maxContextTotalBytes] bytes. Files outside the context are never overwritten (UNSEEN_OVERWRITE).
 *
 * [prReviewTimeout]: an open pull request older than this (measured from the last run update) is closed by abandoning the run,
 * never by closing the pull request. kivra never merges and never closes a pull request.
 */
data class SelfDevConfig(
    val enabled: Boolean = false,
    /** The only repository the runner may touch (ADR 0008 item 4). */
    val repo: RepoRef = RepoRef("kigawa-net", "kivra"),
    val baseBranch: String = "main",
    /** Lease owner name for this worker. Generations, not names, fence the writes. */
    val workerId: String = "selfdev-runner",
    val leaseTtl: Duration = Duration.ofMinutes(5),
    val prReviewTimeout: Duration = Duration.ofDays(7),
    val contextPathPrefixes: List<String> = listOf("README.md", "docs/"),
    val maxContextFiles: Int = 10,
    val maxContextFileBytes: Long = 32L * 1024,
    val maxContextTotalBytes: Long = 128L * 1024,
) {
    init {
        require(baseBranch.isNotBlank()) { "baseBranch must not be blank" }
        require(workerId.isNotBlank() && workerId.length <= 64) { "workerId must be 1..64 characters" }
        require(!leaseTtl.isNegative && !leaseTtl.isZero) { "leaseTtl must be positive" }
        require(!prReviewTimeout.isNegative && !prReviewTimeout.isZero) { "prReviewTimeout must be positive" }
        require(maxContextFiles >= 0) { "maxContextFiles must not be negative" }
        require(maxContextFileBytes > 0) { "maxContextFileBytes must be positive" }
        require(maxContextTotalBytes > 0) { "maxContextTotalBytes must be positive" }
    }
}
