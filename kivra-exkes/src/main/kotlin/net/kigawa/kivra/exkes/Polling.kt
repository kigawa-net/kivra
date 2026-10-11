package net.kigawa.kivra.exkes

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class PollConfig(val interval: Duration = 1.seconds, val timeout: Duration = 60.seconds)

/** Result of polling an asynchronously created resource. */
sealed interface PollOutcome<out T> {
    data class Reached<T>(val value: T) : PollOutcome<T>

    /** The deadline passed while the resource was still in a non-target state. Last observation attached. */
    data class TimedOut<T>(val last: T?) : PollOutcome<T>

    /** A GET failed in a way polling cannot continue through (NotFound / IdentityMismatch / Rejected ...). */
    data class Failed(val result: PortResult<*>) : PollOutcome<Nothing>
}

private suspend fun <T> poll(config: PollConfig, get: suspend () -> PortResult<T>, done: (T) -> Boolean): PollOutcome<T> {
    val mark = TimeSource.Monotonic.markNow()
    var last: T? = null
    while (true) {
        when (val r = get()) {
            is PortResult.Ok -> {
                last = r.value
                if (done(r.value)) return PollOutcome.Reached(r.value)
            }
            is PortResult.Transient, is PortResult.Unknown -> Unit // GET only: safe to keep polling
            else -> return PollOutcome.Failed(r)
        }
        if (mark.elapsedNow() >= config.timeout) return PollOutcome.TimedOut(last)
        delay(config.interval.coerceAtLeast(1.milliseconds))
    }
}

/** Polls until the workspace is READY; ERROR / DELETING / DELETED also stop the poll (returned as Reached for the caller to inspect). */
suspend fun ExecutionPort.awaitWorkspace(
    id: WorkspaceId,
    config: PollConfig = PollConfig(),
    until: (Workspace) -> Boolean = { it.state != WorkspaceState.CREATING },
) = poll(config, { getWorkspace(id) }, until)

suspend fun ExecutionPort.awaitRuntime(
    id: RuntimeId,
    config: PollConfig = PollConfig(),
    until: (Runtime) -> Boolean = { it.state != RuntimeState.CREATING && it.state != RuntimeState.STARTING },
) = poll(config, { getRuntime(id) }, until)

suspend fun ExecutionPort.awaitExecution(
    id: ExecutionId,
    config: PollConfig = PollConfig(),
    until: (Execution) -> Boolean = { it.state.isFinal },
) = poll(config, { getExecution(id) }, until)
