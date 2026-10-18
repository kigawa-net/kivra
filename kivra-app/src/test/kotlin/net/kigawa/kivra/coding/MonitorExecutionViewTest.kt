package net.kigawa.kivra.coding

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptState
import net.kigawa.kivra.db.CodingUsageRow
import net.kigawa.kivra.db.StoredMonitorEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The run execution read model (ADR 0012 section 4): the counts, the cost split, the stop stages, and the freshness boundary. */
class MonitorExecutionViewTest {
    private val staleAfter = Duration.ofMinutes(4)
    private val base: LocalDateTime = LocalDateTime.of(2026, 10, 18, 10, 0, 0)
    private val now: Instant = base.plusMinutes(1).toInstant(ZoneOffset.UTC)

    private fun attempt(id: String, no: Int, state: CodingAttemptState, gen: Long = 1, version: Long = 3) = CodingAttempt(
        attemptId = id, runId = "RUN", attemptNo = no, state = state, claimedBy = "agent", leaseGeneration = gen,
        leaseExpiresAt = null, queuedExpiresAt = base, baseSha = "a".repeat(40), resultDigest = null, exitStatus = null,
        errorCode = null, sessionId = null, version = version, createdAt = base, updatedAt = base,
    )

    private fun row(gen: Long, event: MonitorEvent, at: LocalDateTime = base) = StoredMonitorEvent(gen, event, at)

    private fun input(
        attempts: List<CodingAttempt>,
        events: Map<String, List<StoredMonitorEvent>> = emptyMap(),
        usage: List<CodingUsageRow> = emptyList(),
        runState: String = "DEVELOPING",
        cancel: Boolean = false,
    ) = ExecutionInput(
        runId = "RUN", runState = runState, runCancelRequested = cancel, attempts = attempts, events = events, usage = usage,
        now = now, staleAfter = staleAfter, reserveUsd = BigDecimal("0.50"),
    )

    private fun ev(seq: Int, kind: MonitorKind, phase: String? = null, tool: String? = null, code: String? = null, count: Int? = null) =
        MonitorEvent(seq = seq, kind = kind, phase = phase, toolName = tool, code = code, count = count)

    @Test
    fun `freshness is LIVE just before the threshold and STALE from it on, and STALE when nothing was received`() {
        val last = base
        assertEquals(ExecutionFreshness.LIVE, freshnessOf(last, last.plus(staleAfter).minusNanos(1).toInstant(ZoneOffset.UTC), staleAfter))
        assertEquals(ExecutionFreshness.STALE, freshnessOf(last, last.plus(staleAfter).toInstant(ZoneOffset.UTC), staleAfter))
        assertEquals(ExecutionFreshness.STALE, freshnessOf(null, now, staleAfter))
        // A receive time later than the server's clock (skew between the two) is not stale.
        assertEquals(ExecutionFreshness.LIVE, freshnessOf(base.plusMinutes(5), now, staleAfter))
    }

    @Test
    fun `the default stale threshold is twice the coding lease TTL`() {
        assertEquals(CodingRuntimeConfig().leaseTtl.multipliedBy(2), DEFAULT_MONITOR_STALE_AFTER)
    }

    @Test
    fun `counts tools by fixed name, errors, turns, denials, warnings, drops, and the truncation mark`() {
        val attempts = listOf(attempt("A1", 1, CodingAttemptState.RUNNING))
        val events = listOf(
            row(1, ev(1, MonitorKind.ATTEMPT_STARTED)),
            row(1, ev(2, MonitorKind.TOOL_USE, tool = "Read")),
            row(1, ev(3, MonitorKind.TOOL_USE, tool = "Read")),
            row(1, ev(4, MonitorKind.TOOL_USE, tool = "Bash")),
            row(1, ev(5, MonitorKind.TOOL_RESULT, count = 1)),
            row(1, ev(6, MonitorKind.TOOL_RESULT, count = 1)),
            row(1, ev(7, MonitorKind.TURN, count = 1)),
            row(1, ev(8, MonitorKind.TURN, count = 1)),
            row(1, ev(9, MonitorKind.TURN, count = 1)),
            row(1, ev(10, MonitorKind.PERMISSION_DENIED, count = 2)),
            row(1, ev(11, MonitorKind.WARNING, count = 4)),
            row(1, ev(12, MonitorKind.DROPPED, count = 7)),
            row(1, ev(13, MonitorKind.API_RETRY, code = "RATE_LIMIT")),
            row(1, ev(14, MonitorKind.API_RETRY, code = "RATE_LIMIT")),
            row(1, ev(15, MonitorKind.API_RETRY, code = "OVERLOADED")),
            row(1, MonitorEvent(seq = 0, kind = MonitorKind.TRUNCATED)),
        )
        val snapshot = buildRunExecutionSnapshot(input(attempts, mapOf("A1" to events)))
        assertEquals(3, snapshot.tools.total)
        assertEquals(mapOf("Read" to 2, "Bash" to 1), snapshot.tools.byName)
        assertEquals(2, snapshot.tools.errors)
        assertEquals(3, snapshot.turns)
        assertEquals(2, snapshot.permissionDenied)
        assertEquals(4, snapshot.warnings)
        assertEquals(7, snapshot.dropped)
        assertEquals(mapOf("OVERLOADED" to 1, "RATE_LIMIT" to 2), snapshot.apiRetries)
        assertTrue(snapshot.truncated)
        assertEquals(events.size.toLong(), snapshot.revision.eventCount)
    }

    @Test
    fun `counts are summed over the generations of one attempt, and over the attempts of the run`() {
        val attempts = listOf(attempt("A1", 1, CodingAttemptState.ORPHANED, gen = 2), attempt("A2", 2, CodingAttemptState.RUNNING, gen = 1))
        val events = mapOf(
            "A1" to listOf(row(1, ev(1, MonitorKind.TURN, count = 2)), row(2, ev(1, MonitorKind.TURN, count = 3))),
            "A2" to listOf(row(1, ev(1, MonitorKind.TURN, count = 1))),
        )
        val snapshot = buildRunExecutionSnapshot(input(attempts, events))
        assertEquals(6, snapshot.turns)
        assertEquals(listOf(1, 2), snapshot.attempts.map { it.attemptNo })
    }

    @Test
    fun `the last phase and whether TESTING was reported are read from PHASE events only`() {
        val attempts = listOf(attempt("A1", 1, CodingAttemptState.RUNNING))
        val events = mapOf("A1" to listOf(row(1, ev(1, MonitorKind.PHASE, phase = "TESTING")), row(1, ev(2, MonitorKind.PHASE, phase = "EDITING"))))
        val snapshot = buildRunExecutionSnapshot(input(attempts, events))
        assertEquals("EDITING", snapshot.lastPhase)
        assertTrue(snapshot.testingReported)
        val none = buildRunExecutionSnapshot(input(attempts))
        assertEquals(null, none.lastPhase)
        assertFalse(none.testingReported)
    }

    @Test
    fun `the cost splits what the ledger recorded from the CLI from what is only an estimate`() {
        val attempts = listOf(
            attempt("DONE", 1, CodingAttemptState.RESULT_RECEIVED),
            attempt("RUN", 2, CodingAttemptState.RUNNING),
            attempt("QUEUED", 3, CodingAttemptState.QUEUED),
            attempt("OLD", 4, CodingAttemptState.FAILED),
        )
        val usage = listOf(
            CodingUsageRow("DONE", 1, BigDecimal("0.123456"), "REPORTED"),
            CodingUsageRow("OLD", 1, BigDecimal("0.200000"), "ESTIMATED_RESERVE"),
        )
        val snapshot = buildRunExecutionSnapshot(input(attempts, usage = usage))
        assertEquals("0.123456", snapshot.cost.confirmedUsd)
        // The estimate is the ledger's reserve for OLD, plus the claim reserve of the two active attempts that have no usage row yet.
        assertEquals("1.200000", snapshot.cost.estimatedUsd)
        assertEquals("REPORTED_USAGE", snapshot.cost.confirmedBasis)
        assertEquals("RESERVE_ESTIMATE", snapshot.cost.estimatedBasis)
    }

    @Test
    fun `a reported cost of an active attempt is not counted again as an estimate`() {
        val attempts = listOf(attempt("RUN", 1, CodingAttemptState.RUNNING))
        val usage = listOf(CodingUsageRow("RUN", 1, BigDecimal("0.300000"), "REPORTED"))
        val snapshot = buildRunExecutionSnapshot(input(attempts, usage = usage))
        assertEquals("0.300000", snapshot.cost.confirmedUsd)
        assertEquals("0.000000", snapshot.cost.estimatedUsd)
    }

    @Test
    fun `the stop stage is the furthest one reached`() {
        val running = listOf(attempt("A1", 1, CodingAttemptState.RUNNING))
        fun stage(runState: String = "DEVELOPING", cancel: Boolean = false, events: List<StoredMonitorEvent> = emptyList()) =
            buildRunExecutionSnapshot(input(running, mapOf("A1" to events), runState = runState, cancel = cancel)).stop.stage

        assertEquals("NONE", stage())
        assertEquals("REQUESTED", stage(cancel = true))
        assertEquals("STOPPING", stage(cancel = true, events = listOf(row(1, ev(1, MonitorKind.STOP_REQUESTED)))))
        assertEquals(
            "EXIT_CONFIRMED",
            stage(cancel = true, events = listOf(row(1, ev(1, MonitorKind.STOP_REQUESTED)), row(1, ev(2, MonitorKind.PROCESS_EXIT_CONFIRMED, code = "EXITED")))),
        )
        assertEquals("CANCELLED", stage(runState = "CANCELLED", cancel = true))
    }

    @Test
    fun `the snapshot exposes the stop flags and the freshness, and its revision moves with the attempts`() {
        val attempts = listOf(attempt("A1", 1, CodingAttemptState.RUNNING, version = 5))
        val snapshot = buildRunExecutionSnapshot(input(attempts, mapOf("A1" to listOf(row(1, ev(1, MonitorKind.TURN, count = 1), base)))))
        assertEquals("LIVE", snapshot.freshness)
        assertEquals(base.toInstant(ZoneOffset.UTC).toString(), snapshot.lastReceivedAt)
        assertEquals(base.toInstant(ZoneOffset.UTC).toString(), snapshot.attempts.single().lastReceivedAt)
        assertEquals(5L, snapshot.revision.attemptVersion)
        assertEquals(now.toString(), snapshot.snapshotAt)
        assertFalse(snapshot.stop.agentAcknowledged)
        assertFalse(snapshot.stop.processExitConfirmed)
    }

    @Test
    fun `a run with no received event is STALE and has no last receive time`() {
        val snapshot = buildRunExecutionSnapshot(input(listOf(attempt("A1", 1, CodingAttemptState.CLAIMED))))
        assertEquals("STALE", snapshot.freshness)
        assertEquals(null, snapshot.lastReceivedAt)
        assertEquals(0L, snapshot.revision.eventCount)
    }

    @Test
    fun `the event view carries the fixed fields and the server's receive time only`() {
        val view = executionEventView("A1", row(2, ev(7, MonitorKind.TOOL_USE, tool = "Edit"), base))
        assertEquals("A1", view.attemptId)
        assertEquals(2L, view.leaseGeneration)
        assertEquals(7, view.seq)
        assertEquals("TOOL_USE", view.kind)
        assertEquals("Edit", view.toolName)
        assertEquals(base.toInstant(ZoneOffset.UTC).toString(), view.receivedAt)
        assertEquals(null, view.costUsd)
    }

    /** The JSON of a snapshot has only the names this API defines. A new free-text field would show up here as a new name. */
    @Test
    fun `the snapshot JSON carries only the allow-listed field names`() {
        val attempts = listOf(attempt("A1", 1, CodingAttemptState.RUNNING))
        val events = mapOf("A1" to listOf(row(1, ev(1, MonitorKind.TOOL_USE, tool = "Read"), base)))
        val text = Json.encodeToString(RunExecutionSnapshot.serializer(), buildRunExecutionSnapshot(input(attempts, events)))
        val names = mutableSetOf<String>()
        collectNames(Json.parseToJsonElement(text), names)
        val allowed = setOf(
            "runId", "runState", "snapshotAt", "revision", "attemptVersion", "eventCount", "freshness", "lastReceivedAt", "attempts",
            "attemptId", "attemptNo", "state", "leaseGeneration", "stop", "stage", "runCancelRequested", "agentAcknowledged",
            "processExitConfirmed", "tools", "total", "byName", "errors", "turns", "permissionDenied", "warnings", "dropped", "truncated",
            "lastPhase", "testingReported", "apiRetries", "cost", "confirmedUsd", "estimatedUsd", "confirmedBasis", "estimatedBasis",
        )
        assertTrue(names.all { it in allowed }, "unexpected names: ${names - allowed}")
        // The names that must never appear, whatever is added later.
        for (forbidden in listOf("taskTitle", "taskBody", "taskSource", "stateReason", "path", "prompt", "stdout", "payload", "goal")) {
            assertFalse(names.contains(forbidden), forbidden)
        }
    }

    private val ENUM_MAPS = setOf("byName", "apiRetries")

    private fun collectNames(element: kotlinx.serialization.json.JsonElement, out: MutableSet<String>) {
        when (element) {
            // The keys of these two maps are enumerations (tool names, error codes), not field names; their values are counts.
            is JsonObject -> element.forEach { (k, v) -> out += k; if (k !in ENUM_MAPS) collectNames(v, out) }
            is JsonArray -> element.forEach { collectNames(it, out) }
            is JsonPrimitive -> Unit
        }
    }
}
