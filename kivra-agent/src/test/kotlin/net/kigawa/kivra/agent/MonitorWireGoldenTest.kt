package net.kigawa.kivra.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.agent.claude.ApiErrorKind
import net.kigawa.kivra.agent.claude.MonitorEvent
import net.kigawa.kivra.agent.claude.MonitorTool
import net.kigawa.kivra.agent.claude.MonitorWarning
import net.kigawa.kivra.agent.docker.DockerRunOutcome

/**
 * The golden test of the wire form (ADR 0012 §2.1). The expected lists below are kivra's own: `MonitorEventSchema` in kivra-app
 * (`MonitorKind` and its fields, `PHASES`, `TOOL_NAMES`, `API_ERROR_KINDS`, `DOCKER_RUN_OUTCOMES`). They are copied here by hand, so that a
 * change on either side fails a test. Each kind's JSON is compared as a whole string, so an extra or a missing field fails too.
 */
class MonitorWireGoldenTest {
    /** kivra's `MonitorKind` without TRUNCATED (the server writes that one itself): kind to its fields. */
    private val kivraKinds: Map<String, Set<String>> = mapOf(
        "ATTEMPT_STARTED" to emptySet(),
        "CLI_INIT" to emptySet(),
        "PHASE" to setOf("phase"),
        "TOOL_USE" to setOf("toolName"),
        "TOOL_RESULT" to setOf("count"),
        "TURN" to setOf("count"),
        "PERMISSION_DENIED" to setOf("count"),
        "API_RETRY" to setOf("code"),
        "COST" to setOf("costUsd"),
        "STOP_REQUESTED" to emptySet(),
        "PROCESS_EXIT_CONFIRMED" to setOf("code"),
        "WARNING" to setOf("count"),
        "DROPPED" to setOf("count"),
    )

    private val kivraPhases = setOf("STARTED", "EDITING", "TESTING", "ROUND_COMPLETED", "FINALIZING")
    private val kivraToolNames = setOf("Read", "Edit", "Write", "Bash", "Grep", "Glob", "Task", "TodoWrite", "OTHER")
    private val kivraApiErrorKinds = setOf("AUTHENTICATION", "RATE_LIMIT", "OVERLOADED", "BILLING", "OTHER")
    private val kivraDockerRunOutcomes = setOf("EXITED", "TIMEOUT", "CANCELLED", "OUTPUT_LIMIT")

    private fun wire(event: MonitorEvent): String = MonitorWire.event(7L, event).toString()

    /** One event of every kind, with the values that the schema allows. The expected JSON is written out in full. */
    private val golden: List<Pair<MonitorEvent, String>> = listOf(
        MonitorEvent.AttemptStarted to """{"seq":7,"kind":"ATTEMPT_STARTED"}""",
        MonitorEvent.CliInit to """{"seq":7,"kind":"CLI_INIT"}""",
        MonitorEvent.Phase(ProgressPhase.FINALIZING) to """{"seq":7,"kind":"PHASE","phase":"FINALIZING"}""",
        MonitorEvent.ToolUse(MonitorTool.READ) to """{"seq":7,"kind":"TOOL_USE","toolName":"Read"}""",
        MonitorEvent.ToolUse(MonitorTool.TODO_WRITE) to """{"seq":7,"kind":"TOOL_USE","toolName":"TodoWrite"}""",
        MonitorEvent.ToolUse(MonitorTool.OTHER) to """{"seq":7,"kind":"TOOL_USE","toolName":"OTHER"}""",
        MonitorEvent.ToolError to """{"seq":7,"kind":"TOOL_RESULT","count":1}""",
        MonitorEvent.Turn to """{"seq":7,"kind":"TURN","count":1}""",
        MonitorEvent.PermissionDenied(2) to """{"seq":7,"kind":"PERMISSION_DENIED","count":2}""",
        MonitorEvent.ApiRetry(ApiErrorKind.RATE_LIMIT) to """{"seq":7,"kind":"API_RETRY","code":"RATE_LIMIT"}""",
        MonitorEvent.Cost(1_234_567) to """{"seq":7,"kind":"COST","costUsd":1.234567}""",
        MonitorEvent.Cost(0) to """{"seq":7,"kind":"COST","costUsd":0.000000}""",
        MonitorEvent.StopRequested to """{"seq":7,"kind":"STOP_REQUESTED"}""",
        MonitorEvent.ProcessExitConfirmed(DockerRunOutcome.OUTPUT_LIMIT) to
            """{"seq":7,"kind":"PROCESS_EXIT_CONFIRMED","code":"OUTPUT_LIMIT"}""",
        MonitorEvent.Warning(MonitorWarning.OVERSIZED_LINE) to """{"seq":7,"kind":"WARNING","count":1}""",
        MonitorEvent.Dropped(5) to """{"seq":7,"kind":"DROPPED","count":5}""",
    )

    @Test
    fun eachKindIsWrittenExactlyAsKivraExpects() {
        for ((event, expected) in golden) {
            assertEquals(expected, wire(event), event.toString())
        }
    }

    @Test
    fun everyKindCarriesExactlyTheFieldsKivraListsForIt() {
        for ((event, _) in golden) {
            val obj = MonitorWire.event(7L, event)
            val kind = (obj["kind"] as kotlinx.serialization.json.JsonPrimitive).content
            val fields = obj.keys - setOf("seq", "kind")
            assertEquals(kivraKinds.getValue(kind), fields, "fields of $kind")
        }
    }

    @Test
    fun theAgentPinsTheSameKindsAsKivra() {
        assertEquals(kivraKinds, MonitorWire.KIND_FIELDS)
        assertEquals(kivraKinds.keys, golden.map { (wire(it.first).let { w -> Regex("\"kind\":\"([A-Z_]+)\"").find(w)!!.groupValues[1] }) }.toSet())
    }

    @Test
    fun theValueSetsMatchKivra() {
        assertEquals(kivraPhases, ProgressPhase.entries.map { it.name }.toSet(), "ProgressPhase")
        assertEquals(kivraToolNames, MonitorTool.entries.map { it.wire }.toSet(), "MonitorTool")
        assertEquals(kivraApiErrorKinds, ApiErrorKind.entries.map { it.name }.toSet(), "ApiErrorKind")
        assertEquals(kivraDockerRunOutcomes, DockerRunOutcome.entries.map { it.name }.toSet(), "DockerRunOutcome")
    }

    @Test
    fun everyWireEventIsWithinTheSizeLimit() {
        for ((event, _) in golden) {
            assertTrue(wire(event).toByteArray().size <= 256, event.toString())
        }
    }
}
