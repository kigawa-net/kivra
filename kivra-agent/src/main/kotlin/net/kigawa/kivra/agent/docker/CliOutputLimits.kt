package net.kigawa.kivra.agent.docker

/**
 * The size limits of the CLI's standard output (ADR 0010 decision 9, runbook §6.7). Every stage that reads that output takes its
 * limit from here: the container reader ([DockerRunner]), the stream-json parser, and the debug copy (`AttemptDebugKeep`).
 *
 * - [MAX_TOTAL_BYTES] is the most stdout the agent keeps in memory and parses for one run. The server's `maxOutputBytes` can only
 *   lower it for one attempt (`RealAttemptExecutor` takes the smaller of the two).
 * - [MAX_LINE_BYTES] is the most one line may hold. A longer line is skipped and counted as oversized.
 */
object CliOutputLimits {
    const val MAX_TOTAL_BYTES: Long = 8L * 1024 * 1024
    const val MAX_LINE_BYTES: Int = 1024 * 1024
}
