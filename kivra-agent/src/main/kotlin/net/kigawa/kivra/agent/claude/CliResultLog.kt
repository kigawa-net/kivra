package net.kigawa.kivra.agent.claude

import java.util.Locale

/**
 * The fields of the CLI's final `result` event that are logged: numbers, booleans and enum-like names only. The result text, the
 * tool input, the paths and the permission denial entries are never part of it. A string that does not have the shape of an enum
 * name is logged as `unrecognized`, so a value from the output cannot carry text into the log.
 */
object CliResultLog {
    fun describe(result: CliResult?): String {
        if (result == null) return "hasResult=false"
        return listOf(
            "hasResult=true",
            "subtype=${name(result.subtype)}",
            "isError=${result.isError}",
            "numTurns=${result.numTurns}",
            "totalCostUsd=${result.usage.costUsd?.let { String.format(Locale.ROOT, "%.4f", it) }}",
            "permissionDenials=${result.permissionDenials}",
            "stopReason=${name(result.stopReason)}",
            "terminalReason=${name(result.terminalReason)}",
        ).joinToString(" ")
    }

    private fun name(value: String?): String = when {
        value == null -> "none"
        StreamJsonParser.ENUM_NAME.matches(value) -> value
        else -> "unrecognized"
    }
}
