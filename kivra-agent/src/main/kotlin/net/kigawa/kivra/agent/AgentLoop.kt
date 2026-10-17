package net.kigawa.kivra.agent

import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Runs one claimed attempt and returns the error code to report with `fail`. PR 5 has only the stub [NotImplementedExecutor];
 * PR 6-8 add the docker run, the CLI and the diff behind this interface.
 */
fun interface AttemptExecutor {
    suspend fun execute(attempt: ClaimedAttempt): AgentErrorCode
}

/**
 * PR 5 stub: nothing is executed. The attempt is failed with NEEDS_HUMAN. kivra maps NEEDS_HUMAN to UNKNOWN, so an operator decides
 * and nothing is retried automatically. There is no NOT_IMPLEMENTED code on the server.
 */
object NotImplementedExecutor : AttemptExecutor {
    override suspend fun execute(attempt: ClaimedAttempt): AgentErrorCode = AgentErrorCode.NEEDS_HUMAN
}

/**
 * The agent's main loop (design #187 section 9, PR 5). One attempt at a time (fixed concurrency 1). The loop:
 *
 * - does not claim while the STOP file exists (the kill switch);
 * - claims, and while an attempt runs it sends a heartbeat every [AgentConfig.heartbeatInterval] and reads its status;
 * - stops the work at once on 409 or 404 (the generation is stale or the attempt is gone), and does not report a fail;
 * - stops the work when kivra sets cancelRequested, and reports CANCELLED;
 * - on cancellation of the loop (SIGTERM), reports CANCELLED for the running attempt, then ends;
 * - backs off after a failed claim (exponential, capped), so a failing kivra or a bad token is never a hot loop.
 *
 * Nothing here logs a token, a secret, the task text or a path. The log lines carry fixed codes plus runId and attemptId.
 */
class AgentLoop(
    config: AgentConfig,
    private val client: KivraAgentClient,
    private val executor: AttemptExecutor = NotImplementedExecutor,
) {
    private val logger = LoggerFactory.getLogger(AgentLoop::class.java)
    private val stopFile = config.stopFile
    private val pollInterval = config.pollInterval
    private val heartbeatInterval = config.heartbeatInterval

    /** Runs until cancelled. Returns only through cancellation. */
    suspend fun run() {
        var failures = 0
        var stopLogged = false
        while (true) {
            if (Files.exists(stopFile)) {
                if (!stopLogged) {
                    logger.warn("STOP file present; not claiming")
                    stopLogged = true
                }
                delay(pollInterval.toMillis())
                continue
            }
            stopLogged = false
            try {
                when (val result = client.claim()) {
                    is ClaimResult.Assigned -> {
                        failures = 0
                        runAttempt(result.attempt)
                    }
                    ClaimResult.NoAssignment -> {
                        failures = 0
                        delay(pollInterval.toMillis())
                    }
                }
            } catch (e: AgentApiException) {
                failures++
                logger.warn("claim failed: kind={} consecutiveFailures={}", e.kind, failures)
                delay(backoff(failures).toMillis())
            }
        }
    }

    private suspend fun runAttempt(attempt: ClaimedAttempt) {
        logger.info("attempt claimed: runId={} attemptId={}", attempt.runId, attempt.attemptId)
        val code = try {
            coroutineScope {
                val heartbeats = launch { heartbeats(attempt) }
                try {
                    executeOrNeedsHuman(attempt)
                } finally {
                    heartbeats.cancel()
                }
            }
        } catch (e: StaleAttemptException) {
            logger.warn("attempt is stale or gone; stopping without fail: runId={} attemptId={}", attempt.runId, attempt.attemptId)
            return
        } catch (e: CancelRequestedException) {
            AgentErrorCode.CANCELLED
        } catch (e: CancellationException) {
            // The loop is being stopped (for example SIGTERM). Report the cancel, bounded in time, then end.
            withContext(NonCancellable) {
                withTimeoutOrNull(FAIL_TIMEOUT_MILLIS) { reportFail(attempt, AgentErrorCode.CANCELLED) }
            }
            throw e
        }
        reportFail(attempt, code)
    }

    private suspend fun executeOrNeedsHuman(attempt: ClaimedAttempt): AgentErrorCode =
        try {
            executor.execute(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("executor failed: class={} runId={} attemptId={}", e.javaClass.simpleName, attempt.runId, attempt.attemptId)
            AgentErrorCode.NEEDS_HUMAN
        }

    /** Sends a heartbeat each interval and reads the status. Ends the attempt by throwing when kivra says it is stale or cancelled. */
    private suspend fun heartbeats(attempt: ClaimedAttempt) {
        while (true) {
            delay(heartbeatInterval.toMillis())
            try {
                client.heartbeat(attempt.attemptId, attempt.generation, progress = null)
            } catch (e: AgentApiException) {
                if (e.kind == AgentApiErrorKind.CONFLICT || e.kind == AgentApiErrorKind.NOT_FOUND) throw StaleAttemptException()
                logger.warn("heartbeat failed: kind={} runId={} attemptId={}", e.kind, attempt.runId, attempt.attemptId)
                continue
            }
            val status = try {
                client.status(attempt.attemptId)
            } catch (e: AgentApiException) {
                if (e.kind == AgentApiErrorKind.CONFLICT || e.kind == AgentApiErrorKind.NOT_FOUND) throw StaleAttemptException()
                logger.warn("status failed: kind={} runId={} attemptId={}", e.kind, attempt.runId, attempt.attemptId)
                continue
            }
            if (status.cancelRequested) throw CancelRequestedException()
        }
    }

    private suspend fun reportFail(attempt: ClaimedAttempt, code: AgentErrorCode) {
        try {
            client.fail(attempt.attemptId, attempt.generation, code)
            logger.info("attempt failed: runId={} attemptId={} code={}", attempt.runId, attempt.attemptId, code)
        } catch (e: AgentApiException) {
            logger.warn("fail not accepted: kind={} runId={} attemptId={}", e.kind, attempt.runId, attempt.attemptId)
        }
    }

    /** Exponential backoff from the poll interval, capped at [MAX_BACKOFF]. */
    internal fun backoff(failures: Int): Duration {
        val factor = 1L shl (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        val millis = pollInterval.toMillis() * factor
        return Duration.ofMillis(minOf(millis, MAX_BACKOFF.toMillis()))
    }

    private class StaleAttemptException : Exception("attempt is stale")

    private class CancelRequestedException : Exception("cancel requested")

    private companion object {
        val MAX_BACKOFF: Duration = Duration.ofMinutes(5)
        const val MAX_BACKOFF_SHIFT = 16
        const val FAIL_TIMEOUT_MILLIS = 10_000L
    }
}
