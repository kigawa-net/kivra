package net.kigawa.kivra.agent.claude

import java.math.BigDecimal
import java.math.RoundingMode
import net.kigawa.kivra.agent.ProgressPhase
import net.kigawa.kivra.agent.docker.DockerRunOutcome

/**
 * The tools the monitor names (ADR 0012 §1). The CLI's tool name is matched against this list; any other name, including an MCP
 * name, is [OTHER]. The tool's input is never read.
 */
enum class MonitorTool {
    READ,
    EDIT,
    WRITE,
    BASH,
    GREP,
    GLOB,
    TASK,
    TODO_WRITE,
    OTHER,
    ;

    companion object {
        fun ofCliName(name: String?): MonitorTool = when (name) {
            "Read" -> READ
            "Edit" -> EDIT
            "Write" -> WRITE
            "Bash" -> BASH
            "Grep" -> GREP
            "Glob" -> GLOB
            "Task" -> TASK
            "TodoWrite" -> TODO_WRITE
            else -> OTHER
        }
    }
}

/** A warning the monitor records by a fixed code. The count of warnings is the only thing kept. */
enum class MonitorWarning { OVERSIZED_LINE, BLOCK_LIMIT }

/**
 * A model id from the CLI's init event, kept only when it has the allowed shape (ADR 0012 U-5). [toString] shows no value.
 */
class ModelId private constructor(val value: String) {
    override fun toString(): String = "ModelId(***)"

    companion object {
        private val PATTERN = Regex("^[a-z0-9][a-z0-9.-]{0,63}$")

        fun of(raw: String?): ModelId? = raw?.takeIf { PATTERN.matches(it) }?.let { ModelId(it) }
    }
}

/** The CLI version from the init event, kept only as `major.minor.patch` digits. [toString] shows no value. */
class CliVersion private constructor(val value: String) {
    override fun toString(): String = "CliVersion(***)"

    companion object {
        private val PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        private const val MAX_LENGTH = 32

        fun of(raw: String?): CliVersion? =
            raw?.takeIf { it.length <= MAX_LENGTH && PATTERN.matches(it) }?.let { CliVersion(it) }
    }
}

/**
 * One event of the run monitor (ADR 0012 §1). Every field is a fixed code, an enum, a number or a time-free shape: there is no free
 * text, so a value that comes from the CLI can only reach the wire as one of these. The wire form is in `MonitorWire`.
 */
sealed interface MonitorEvent {
    /** The CLI is about to start (the agent's own event). */
    data object AttemptStarted : MonitorEvent

    data class CliInit(val model: ModelId?, val cliVersion: CliVersion?) : MonitorEvent

    /** A phase from the agent's own progress (the agent reports FINALIZING before the result). */
    data class Phase(val phase: ProgressPhase) : MonitorEvent

    data class ToolUse(val tool: MonitorTool) : MonitorEvent

    data class ToolResult(val isError: Boolean) : MonitorEvent

    /** One assistant message. */
    data object Turn : MonitorEvent

    /** The number of tool calls the permission mode refused, from the final result (0 to [MAX_COUNT]). */
    data class PermissionDenied(val count: Int) : MonitorEvent

    data class ApiRetry(val kind: ApiErrorKind) : MonitorEvent

    /** The CLI's cost in micro-dollars (USD times 10^6), from 0 to 100 dollars. */
    data class Cost(val micros: Long) : MonitorEvent

    data object StopRequested : MonitorEvent

    /** The container has ended, with the runner's outcome. */
    data class ProcessExitConfirmed(val outcome: DockerRunOutcome) : MonitorEvent

    data class Warning(val code: MonitorWarning) : MonitorEvent

    /** The number of events the sender could not queue since the last one (0 to [MAX_COUNT]). */
    data class Dropped(val count: Int) : MonitorEvent

    /** The per-attempt limit was reached; no later event is recorded. */
    data object Truncated : MonitorEvent

    companion object {
        const val MAX_COUNT = 10_000
        const val MAX_COST_MICROS = 100_000_000L
    }
}

/** Shared conversions from a CLI cost to micro-dollars. A plain decimal in 0 to 100, at most six decimals, else null. */
internal object MonitorCost {
    private val MAX = BigDecimal("100")

    fun micros(text: String?): Long? {
        if (text == null) return null
        val value = runCatching { BigDecimal(text) }.getOrNull() ?: return null
        if (value.signum() < 0 || value > MAX) return null
        return value.setScale(6, RoundingMode.HALF_UP).movePointRight(6).toLong()
    }
}
