package net.kigawa.kivra.coding

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingUsageRow
import net.kigawa.kivra.db.StoredMonitorEvent

/*
 * The read model of the run execution monitor (ADR 0012 section 4, PR 3). Pure: no database, no clock, no logging. The routes read the
 * rows and pass them in, so every rule here is tested without a database.
 *
 * Only fixed codes, enumerations, counts, money and times leave this file. Nothing free-text is in any type here: no task body, no path,
 * no prompt, no CLI output, no goal text. The Run's state reason is not read at all.
 */

/** LIVE: the last receipt is newer than the stale threshold. STALE: older than that, or nothing was ever received. */
enum class ExecutionFreshness { LIVE, STALE }

/** The stop stages (ADR 0012 section 6). The stage is the furthest one reached; each stage is also shown as its own flag. */
enum class StopStage { NONE, REQUESTED, STOPPING, EXIT_CONFIRMED, CANCELLED }

/** Default stale threshold: twice the coding lease TTL (ADR 0012 section 4). */
val DEFAULT_MONITOR_STALE_AFTER: Duration = CodingRuntimeConfig().leaseTtl.multipliedBy(2)

/** Last receipt in [lastReceivedAt]; STALE when nothing was received. The boundary belongs to STALE (elapsed >= [staleAfter]). */
fun freshnessOf(lastReceivedAt: LocalDateTime?, now: Instant, staleAfter: Duration): ExecutionFreshness {
    if (lastReceivedAt == null) return ExecutionFreshness.STALE
    val elapsed = Duration.between(lastReceivedAt.toInstant(ZoneOffset.UTC), now)
    return if (elapsed < staleAfter) ExecutionFreshness.LIVE else ExecutionFreshness.STALE
}

@Serializable
data class ExecutionRevision(
    /** Sum of the attempts' version counters (each transition raises one). */
    val attemptVersion: Long,
    /** Stored monitor rows of the run, including the TRUNCATED mark. */
    val eventCount: Long,
)

@Serializable
data class ExecutionAttempt(
    val attemptId: String,
    val attemptNo: Int,
    val state: String,
    val leaseGeneration: Long,
    val lastReceivedAt: String?,
)

@Serializable
data class ExecutionStop(
    val stage: String,
    val runCancelRequested: Boolean,
    /** The agent saw the stop request (STOP_REQUESTED). This is the agent's report, not a verified fact. */
    val agentAcknowledged: Boolean,
    /** The agent reported the container's exit as confirmed (PROCESS_EXIT_CONFIRMED). */
    val processExitConfirmed: Boolean,
)

@Serializable
data class ExecutionTools(
    val total: Int,
    /** Calls per tool name (the fixed list only; anything else is OTHER). */
    val byName: Map<String, Int>,
    /** Failed tool results (TOOL_RESULT is_error). */
    val errors: Int,
)

@Serializable
data class ExecutionCost(
    /** Money the usage ledger has recorded from the CLI's own report. Exact, but only after the attempt's result is in. */
    val confirmedUsd: String,
    /** Money not yet confirmed: the ledger's reserve estimates, and the reserve of active attempts that have no report yet. */
    val estimatedUsd: String,
    val confirmedBasis: String = "REPORTED_USAGE",
    val estimatedBasis: String = "RESERVE_ESTIMATE",
)

@Serializable
data class RunExecutionSnapshot(
    val runId: String,
    val runState: String,
    val snapshotAt: String,
    val revision: ExecutionRevision,
    val freshness: String,
    val lastReceivedAt: String?,
    val attempts: List<ExecutionAttempt>,
    val stop: ExecutionStop,
    val tools: ExecutionTools,
    val turns: Int,
    val permissionDenied: Int,
    val warnings: Int,
    val dropped: Int,
    val truncated: Boolean,
    /** The last PHASE the agent reported (a fixed code), or null. */
    val lastPhase: String?,
    /** Whether the agent ever reported the TESTING phase. Shown as "reported", never as a percentage. */
    val testingReported: Boolean,
    /** API_RETRY counts per fixed error kind. */
    val apiRetries: Map<String, Int>,
    val cost: ExecutionCost,
)

/** One stored event as the API shows it. Only fixed fields; [receivedAt] is the server's receive time. */
@Serializable
data class ExecutionEventView(
    val attemptId: String,
    val leaseGeneration: Long,
    val seq: Int,
    val kind: String,
    val phase: String? = null,
    val toolName: String? = null,
    val code: String? = null,
    val count: Int? = null,
    val costUsd: String? = null,
    val receivedAt: String,
)

@Serializable
data class ExecutionEventsPage(
    val attemptId: String,
    val events: List<ExecutionEventView>,
    /** The cursor of the last returned row; the next page starts after it. Null when no row was returned. */
    val nextAfterGeneration: Long?,
    val nextAfterSeq: Int?,
    val hasMore: Boolean,
)

/** Everything the snapshot is built from. [events] maps an attempt id to its stored rows, oldest first. */
data class ExecutionInput(
    val runId: String,
    val runState: String,
    val runCancelRequested: Boolean,
    val attempts: List<CodingAttempt>,
    val events: Map<String, List<StoredMonitorEvent>>,
    val usage: List<CodingUsageRow>,
    val now: Instant,
    val staleAfter: Duration,
    val reserveUsd: BigDecimal,
)

private val ACTIVE_STATES = setOf("QUEUED", "CLAIMED", "RUNNING")
private const val CANCELLED_RUN_STATE = "CANCELLED"
private const val REPORTED = "REPORTED"
private const val ESTIMATED = "ESTIMATED_RESERVE"
private val MONEY_SCALE = 6

fun buildRunExecutionSnapshot(input: ExecutionInput): RunExecutionSnapshot {
    val attempts = input.attempts.sortedBy { it.attemptNo }
    val rows = attempts.flatMap { input.events[it.attemptId].orEmpty() }
    val observed = rows.map { it.event }
    val lastReceivedAt = rows.maxOfOrNull { it.receivedAt }

    val toolCounts = observed.filter { it.kind == MonitorKind.TOOL_USE }
        .groupingBy { it.toolName.orEmpty() }.eachCount()
        .filterKeys { it.isNotEmpty() }
    val byName = linkedMapOf<String, Int>()
    for (name in MonitorEventSchema.TOOL_NAMES) toolCounts[name]?.let { byName[name] = it }

    val lastPhase = observed.lastOrNull { it.kind == MonitorKind.PHASE }?.phase
    val exitConfirmed = observed.any { it.kind == MonitorKind.PROCESS_EXIT_CONFIRMED }
    val acknowledged = observed.any { it.kind == MonitorKind.STOP_REQUESTED }
    val stage = when {
        input.runState == CANCELLED_RUN_STATE -> StopStage.CANCELLED
        exitConfirmed -> StopStage.EXIT_CONFIRMED
        acknowledged -> StopStage.STOPPING
        input.runCancelRequested -> StopStage.REQUESTED
        else -> StopStage.NONE
    }

    val confirmed = input.usage.filter { it.source == REPORTED }.sumOf { it.costUsd }
    val ledgerEstimates = input.usage.filter { it.source == ESTIMATED }.sumOf { it.costUsd }
    // An active attempt with no usage row yet is shown at the reserve it was claimed with: an estimate, labelled as one.
    val openReserves = attempts.count { attempt ->
        attempt.state.name in ACTIVE_STATES && input.usage.none { it.attemptId == attempt.attemptId }
    }
    val estimated = ledgerEstimates + input.reserveUsd.multiply(BigDecimal(openReserves))

    return RunExecutionSnapshot(
        runId = input.runId,
        runState = input.runState,
        snapshotAt = input.now.toString(),
        revision = ExecutionRevision(
            attemptVersion = attempts.sumOf { it.version },
            eventCount = rows.size.toLong(),
        ),
        freshness = freshnessOf(lastReceivedAt, input.now, input.staleAfter).name,
        lastReceivedAt = lastReceivedAt?.let { it.toInstant(ZoneOffset.UTC).toString() },
        attempts = attempts.map { attempt ->
            ExecutionAttempt(
                attemptId = attempt.attemptId,
                attemptNo = attempt.attemptNo,
                state = attempt.state.name,
                leaseGeneration = attempt.leaseGeneration,
                lastReceivedAt = input.events[attempt.attemptId].orEmpty().maxOfOrNull { it.receivedAt }
                    ?.let { it.toInstant(ZoneOffset.UTC).toString() },
            )
        },
        stop = ExecutionStop(
            stage = stage.name,
            runCancelRequested = input.runCancelRequested,
            agentAcknowledged = acknowledged,
            processExitConfirmed = exitConfirmed,
        ),
        tools = ExecutionTools(
            total = observed.count { it.kind == MonitorKind.TOOL_USE },
            byName = byName,
            errors = observed.filter { it.kind == MonitorKind.TOOL_RESULT }.sumOf { it.count ?: 0 },
        ),
        turns = observed.filter { it.kind == MonitorKind.TURN }.sumOf { it.count ?: 0 },
        permissionDenied = observed.filter { it.kind == MonitorKind.PERMISSION_DENIED }.sumOf { it.count ?: 0 },
        warnings = observed.filter { it.kind == MonitorKind.WARNING }.sumOf { it.count ?: 0 },
        dropped = observed.filter { it.kind == MonitorKind.DROPPED }.sumOf { it.count ?: 0 },
        truncated = observed.any { it.kind == MonitorKind.TRUNCATED },
        lastPhase = lastPhase,
        testingReported = observed.any { it.kind == MonitorKind.PHASE && it.phase == "TESTING" },
        apiRetries = observed.filter { it.kind == MonitorKind.API_RETRY }
            .groupingBy { it.code.orEmpty() }.eachCount()
            .filterKeys { it.isNotEmpty() }
            .toSortedMap(),
        cost = ExecutionCost(
            confirmedUsd = confirmed.money(),
            estimatedUsd = estimated.money(),
        ),
    )
}

/** The API form of one stored row. */
fun executionEventView(attemptId: String, stored: StoredMonitorEvent): ExecutionEventView {
    val event = stored.event
    return ExecutionEventView(
        attemptId = attemptId,
        leaseGeneration = stored.leaseGeneration,
        seq = event.seq,
        kind = event.kind.name,
        phase = event.phase,
        toolName = event.toolName,
        code = event.code,
        count = event.count,
        costUsd = event.costUsd?.money(),
        receivedAt = stored.receivedAt.toInstant(ZoneOffset.UTC).toString(),
    )
}

private fun BigDecimal.money(): String = setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString()
