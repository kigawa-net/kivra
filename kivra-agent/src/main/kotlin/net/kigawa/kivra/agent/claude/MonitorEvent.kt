package net.kigawa.kivra.agent.claude

import java.math.BigDecimal
import java.math.RoundingMode
import net.kigawa.kivra.agent.ProgressPhase
import net.kigawa.kivra.agent.docker.DockerRunOutcome

/**
 * The tools the monitor names (ADR 0012 §1). The CLI's tool name is matched against this list; any other name, including an MCP
 * name, is [OTHER]. The tool's input is never read. [wire] is the value kivra accepts (its `TOOL_NAMES`), which is the CLI's own
 * spelling, not the enum name.
 */
enum class MonitorTool(val wire: String) {
    READ("Read"),
    EDIT("Edit"),
    WRITE("Write"),
    BASH("Bash"),
    GREP("Grep"),
    GLOB("Glob"),
    TASK("Task"),
    TODO_WRITE("TodoWrite"),
    OTHER("OTHER"),
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

/**
 * A warning of the agent's own reading (an oversized line, or too many blocks in one message). Kept inside the agent only: the wire
 * carries a count of warnings, not the kind (kivra's WARNING has a count field only).
 */
enum class MonitorWarning { OVERSIZED_LINE, BLOCK_LIMIT }

/**
 * One event of the run monitor (ADR 0012 §1). Every field is a fixed code, an enum or a number. There is no free text, and no value from
 * the CLI reaches an event except as one of these. The wire form is in `MonitorWire`, and matches kivra's `MonitorEventSchema`.
 */
sealed interface MonitorEvent {
    /** The CLI is about to start (the agent's own event). */
    data object AttemptStarted : MonitorEvent

    /** The CLI's init. Nothing from it is kept: kivra stores no model id or version yet (ADR 0012 U-5). */
    data object CliInit : MonitorEvent

    /** A phase from the agent's own progress (the agent reports FINALIZING before the result). */
    data class Phase(val phase: ProgressPhase) : MonitorEvent

    data class ToolUse(val tool: MonitorTool) : MonitorEvent

    /** One tool result that was an error. Successful results are not recorded. */
    data object ToolError : MonitorEvent

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

    /** The number of events the agent could not send since the last one (0 to [MAX_COUNT]). */
    data class Dropped(val count: Int) : MonitorEvent

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
