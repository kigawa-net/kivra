package net.kigawa.kivra.routes

import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.util.getOrFail
import io.ktor.sse.ServerSentEvent
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.TaskApiIdentity
import net.kigawa.kivra.auth.MemberKind
import net.kigawa.kivra.auth.TaskCaller
import net.kigawa.kivra.coding.DEFAULT_MONITOR_STALE_AFTER
import net.kigawa.kivra.coding.ExecutionEventView
import net.kigawa.kivra.coding.ExecutionEventsPage
import net.kigawa.kivra.coding.ExecutionInput
import net.kigawa.kivra.coding.RunExecutionSnapshot
import net.kigawa.kivra.coding.buildRunExecutionSnapshot
import net.kigawa.kivra.coding.executionEventView
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.db.CodingAttempt
import net.kigawa.kivra.db.CodingAttemptRepository
import net.kigawa.kivra.db.CodingAttemptUsageRepository
import net.kigawa.kivra.db.CodingMonitorEventRepository
import net.kigawa.kivra.db.DevelopmentRun
import net.kigawa.kivra.db.DevelopmentRunRepository
import net.kigawa.kivra.db.StoredMonitorEvent
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

/*
 * The run execution read API (ADR 0012 section 4, PR 3): the snapshot, the event page, and the SSE stream. Read only; nothing here
 * changes a Run or an attempt (the stop is PR 5).
 *
 * Authorization: the caller must be authenticated (401). An agent token is refused (403) before the Run is looked at. Any other valid
 * caller gets the Run only when the caller is an operator (the required role, `admin-panel:admin`) or the Run's own creator. A Run that
 * the caller may not read is a 404, the same as a Run that does not exist. The check runs again before each stream send, so a lost right
 * ends the stream (ADR 0012 section 7).
 *
 * Responses carry fixed codes, enumerations, numbers and times only. The Run's state reason, the task body, the paths, the prompts and the
 * CLI output are never read into these types. Logs carry the run id, a short operator tag and counts, never a value of an event.
 */

/** The outcome of checking a caller for one Run. Only [Allowed] carries the Run. */
internal sealed interface RunAccess {
    data class Allowed(val principal: String, val run: DevelopmentRun) : RunAccess
    data object Unauthenticated : RunAccess
    data object AgentRefused : RunAccess
    data object NotFound : RunAccess
}

/** Checks who may read one Run. Production: [DefaultRunExecutionGate]. Tests inject their own. */
internal fun interface RunExecutionGate {
    suspend fun check(headers: Headers, runId: String): RunAccess
}

/**
 * Production gate. With [verifier] (`KIVRA_AUTH_MODE=oidc-jwt`) the caller is verified as a JWT, and the operator role is read from the
 * token. Without it (the development header modes) the caller is the identity's principal and is never an operator, so only the
 * creator can read.
 */
internal class DefaultRunExecutionGate(
    private val runs: DevelopmentRunRepository,
    private val verifier: OidcJwtVerifier?,
    private val identity: TaskApiIdentity,
) : RunExecutionGate {
    override suspend fun check(headers: Headers, runId: String): RunAccess {
        val caller = if (verifier != null) {
            verifier.caller(headers)
        } else {
            identity.principal(headers)?.let { TaskCaller.Member(it, MemberKind.NONE) } ?: TaskCaller.Unauthenticated
        }
        return when (caller) {
            TaskCaller.Unauthenticated -> RunAccess.Unauthenticated
            TaskCaller.AgentRefused -> RunAccess.AgentRefused
            is TaskCaller.Member -> {
                val run = runs.getAnyTask(RunId(runId)) ?: return RunAccess.NotFound
                if (caller.operator || run.principal == caller.principal) {
                    RunAccess.Allowed(caller.principal, run)
                } else {
                    RunAccess.NotFound
                }
            }
        }
    }
}

/**
 * Limits the open streams: [perPrincipal] for one caller and [total] for the process (ADR 0012 U-13). The limit is per process, so with
 * several pods the total is multiplied by the pod count. A lease is released once; releasing it twice does nothing.
 */
internal class MonitorStreamLimiter(
    private val perPrincipal: Int = DEFAULT_STREAMS_PER_PRINCIPAL,
    private val total: Int = DEFAULT_STREAMS_TOTAL,
) {
    init {
        require(perPrincipal > 0 && total >= perPrincipal) { "limits must be positive, and the total at least the per-principal limit" }
    }

    private val lock = Any()
    private val byPrincipal = HashMap<String, Int>()
    private var open = 0

    /** A lease for one open stream, or null when a limit is reached. */
    fun tryAcquire(principal: String): Lease? = synchronized(lock) {
        val mine = byPrincipal[principal] ?: 0
        if (mine >= perPrincipal || open >= total) return null
        byPrincipal[principal] = mine + 1
        open += 1
        Lease(principal)
    }

    private fun release(principal: String) = synchronized(lock) {
        val mine = byPrincipal[principal] ?: return
        if (mine <= 1) byPrincipal.remove(principal) else byPrincipal[principal] = mine - 1
        open -= 1
    }

    inner class Lease internal constructor(private val principal: String) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) this@MonitorStreamLimiter.release(principal)
        }
    }

    companion object {
        const val DEFAULT_STREAMS_PER_PRINCIPAL = 5
        const val DEFAULT_STREAMS_TOTAL = 50
    }
}

/**
 * The stream's time source: the monotonic clock and the pause between two database reads. Production uses the real ones. The tests
 * replace them, because the test scheduler's virtual `delay` runs faster than the real clock, and the two must agree.
 */
internal open class StreamTimer {
    open fun nanos(): Long = System.nanoTime()

    open suspend fun pause(duration: Duration) {
        delay(duration.toMillis())
    }
}

/** Everything the routes read or decide with. The defaults are the production values; tests shorten the times. */
internal class RunExecutionDeps(
    val runs: DevelopmentRunRepository,
    val attempts: CodingAttemptRepository,
    val monitor: CodingMonitorEventRepository,
    val usage: CodingAttemptUsageRepository,
    val gate: RunExecutionGate,
    val reserveUsd: BigDecimal,
    val staleAfter: Duration = DEFAULT_MONITOR_STALE_AFTER,
    val limiter: MonitorStreamLimiter = MonitorStreamLimiter(),
    /** The stream reads the database this often (ADR 0012 section 4). */
    val streamTick: Duration = Duration.ofSeconds(2),
    /** A comment line is sent this often when nothing else was sent. */
    val heartbeat: Duration = Duration.ofSeconds(15),
    val clock: Clock = Clock.systemUTC(),
    val timer: StreamTimer = StreamTimer(),
)

/**
 * The read API's dependencies, built from the Task API identity. With [verifier] (`oidc-jwt`) the caller is verified as a JWT and the
 * operator role is read from it; without it (the development header modes) only the creator can read a Run.
 */
internal fun runExecutionDeps(
    runs: DevelopmentRunRepository,
    database: Database,
    identity: TaskApiIdentity,
    verifier: OidcJwtVerifier?,
    reserveUsd: BigDecimal,
) = RunExecutionDeps(
    runs = runs,
    attempts = CodingAttemptRepository(database),
    monitor = CodingMonitorEventRepository(database),
    usage = CodingAttemptUsageRepository(database),
    gate = DefaultRunExecutionGate(runs, verifier, identity),
    reserveUsd = reserveUsd,
)

@Serializable
private data class ExecutionError(val error: String)

private const val RUN_NOT_FOUND = "run not found"
private const val UNAUTHORIZED = "unauthorized"
private const val FORBIDDEN = "forbidden"
private const val INVALID_QUERY = "invalid query"
private const val INVALID_LAST_EVENT_ID = "invalid Last-Event-ID"
private const val TOO_MANY_STREAMS = "too many open streams"
private const val ATTEMPT_NOT_FOUND = "attempt not found"

private const val DEFAULT_PAGE = 100
private const val MAX_PAGE = 200
private const val MAX_SEQ = Int.MAX_VALUE.toLong()
private const val MAX_GENERATION = 999_999_999_999L
private const val SSE_EVENT = "monitor"
private const val SSE_RESYNC = "resync"
private const val SSE_HEARTBEAT = "heartbeat"

private val RUN_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val ATTEMPT_ID_SHAPE = Regex("[0-9A-Z]{26}")
private val LAST_EVENT_ID = Regex("^([0-9A-Z]{26}):([1-9][0-9]{0,18}):(0|[1-9][0-9]{0,9})$")

private val monitorStreamLog = LoggerFactory.getLogger("net.kigawa.kivra.routes.run_execution")
private val json = Json

/** A position in the stream: the attempt, the lease generation and the seq. The order is attempt number, then generation, then seq. */
internal data class StreamPosition(val attemptId: String, val generation: Long, val seq: Int)

private class StreamAdmission(
    val principal: String,
    val runId: String,
    val cursor: StreamPosition?,
    val lease: MonitorStreamLimiter.Lease,
)

internal fun Route.configureRunExecutionRoutes(deps: RunExecutionDeps) {
    get("/api/admin/runs/{runId}/execution") {
        val access = deps.admit(call) ?: return@get
        call.respond(deps.snapshot(access.run))
    }

    get("/api/admin/runs/{runId}/execution/events") {
        val access = deps.admit(call) ?: return@get
        val params = call.request.queryParameters
        val attemptId = params["attemptId"]?.takeIf { ATTEMPT_ID_SHAPE.matches(it) }
        // An absent parameter takes its default; a present but invalid one is refused (null), never replaced by the default.
        val afterGeneration = params["afterGeneration"]?.let { parseBounded(it, 0L, MAX_GENERATION) } ?: if ("afterGeneration" in params) null else 0L
        val afterSeq = params["afterSeq"]?.let { parseBounded(it, -1L, MAX_SEQ)?.toInt() } ?: if ("afterSeq" in params) null else -1
        val limit = params["limit"]?.let { parseBounded(it, 1L, MAX_PAGE.toLong())?.toInt() } ?: if ("limit" in params) null else DEFAULT_PAGE
        if (attemptId == null || afterGeneration == null || afterSeq == null || limit == null) {
            call.respond(HttpStatusCode.BadRequest, ExecutionError(INVALID_QUERY))
            return@get
        }
        val attempt = deps.attempts.get(attemptId)?.takeIf { it.runId == access.run.runId.value }
        if (attempt == null) {
            call.respond(HttpStatusCode.NotFound, ExecutionError(ATTEMPT_NOT_FOUND))
            return@get
        }
        call.respond(deps.page(attempt, afterGeneration, afterSeq, limit))
    }

    get("/api/admin/runs/{runId}/execution/stream") {
        // The refusals are answered before the stream starts, so they are plain status codes. An admitted stream holds its lease until it ends.
        val admission = deps.admitStream(call) ?: return@get
        call.respond(
            SSEServerContent(call) {
                try {
                    deps.stream(this, admission)
                } finally {
                    admission.lease.release()
                }
            },
        )
    }
}

/** The gate, the Last-Event-ID and the stream limit, in that order. Null means the call was answered. */
private suspend fun RunExecutionDeps.admitStream(call: ApplicationCall): StreamAdmission? {
    val access = admit(call) ?: return null
    val cursorHeader = call.request.header("Last-Event-ID")
    val cursor = if (cursorHeader == null) {
        null
    } else {
        parseCursor(cursorHeader) ?: run {
            call.respond(HttpStatusCode.BadRequest, ExecutionError(INVALID_LAST_EVENT_ID))
            return null
        }
    }
    val lease = limiter.tryAcquire(access.principal) ?: run {
        call.respond(HttpStatusCode.TooManyRequests, ExecutionError(TOO_MANY_STREAMS))
        return null
    }
    return StreamAdmission(access.principal, access.run.runId.value, cursor, lease)
}

/** Checks the caller for the Run in the path and answers the refusal itself. Null means the call was answered. */
private suspend fun RunExecutionDeps.admit(call: ApplicationCall): RunAccess.Allowed? {
    val runId = call.parameters.getOrFail("runId").takeIf { RUN_ID_SHAPE.matches(it) }
    if (runId == null) {
        call.respond(HttpStatusCode.NotFound, ExecutionError(RUN_NOT_FOUND))
        return null
    }
    return when (val access = gate.check(call.request.headers, runId)) {
        is RunAccess.Allowed -> access
        RunAccess.Unauthenticated -> {
            call.respond(HttpStatusCode.Unauthorized, ExecutionError(UNAUTHORIZED))
            null
        }
        RunAccess.AgentRefused -> {
            call.respond(HttpStatusCode.Forbidden, ExecutionError(FORBIDDEN))
            null
        }
        RunAccess.NotFound -> {
            call.respond(HttpStatusCode.NotFound, ExecutionError(RUN_NOT_FOUND))
            null
        }
    }
}

private fun RunExecutionDeps.snapshot(run: DevelopmentRun): RunExecutionSnapshot {
    val attempts = attempts.listOfRun(run.runId.value)
    return buildRunExecutionSnapshot(
        ExecutionInput(
            runId = run.runId.value,
            runState = run.state.name,
            runCancelRequested = run.cancelRequested,
            attempts = attempts,
            events = attempts.associate { it.attemptId to monitor.all(it.attemptId) },
            usage = usage.costRowsFor(attempts.map { it.attemptId }),
            now = Instant.now(clock),
            staleAfter = staleAfter,
            reserveUsd = reserveUsd,
        ),
    )
}

private fun RunExecutionDeps.page(attempt: CodingAttempt, afterGeneration: Long, afterSeq: Int, limit: Int): ExecutionEventsPage {
    val rows = monitor.read(attempt.attemptId, afterGeneration, afterSeq, limit)
    val last = rows.lastOrNull()
    // hasMore is decided by one more row, so a full page at the end of the log says false.
    val hasMore = last != null && rows.size == limit &&
        monitor.read(attempt.attemptId, last.leaseGeneration, last.event.seq, 1).isNotEmpty()
    return ExecutionEventsPage(
        attemptId = attempt.attemptId,
        events = rows.map { executionEventView(attempt.attemptId, it) },
        nextAfterGeneration = last?.leaseGeneration,
        nextAfterSeq = last?.event?.seq,
        hasMore = hasMore,
    )
}

/**
 * One stream: reads the rows after the position (from the database, never from memory), sends them, and checks the right before each
 * send. It ends when the right is lost, the cursor was purged (`resync`), the run is gone, or the client has gone.
 */
private suspend fun RunExecutionDeps.stream(session: ServerSSESession, admission: StreamAdmission) {
    var cursor = admission.cursor
    var lastSentNanos = timer.nanos()
    var frames = 0
    var reason = "CLIENT_CLOSED"
    try {
        while (true) {
            val access = gate.check(session.call.request.headers, admission.runId)
            if (access !is RunAccess.Allowed) {
                reason = "ACCESS_ENDED"
                break
            }
            val pumped = pump(admission.runId, cursor)
            if (pumped == null) {
                session.send(ServerSentEvent(data = """{"action":"refetch_snapshot"}""", event = SSE_RESYNC))
                reason = "RESYNC"
                break
            }
            for (row in pumped.rows) {
                session.send(
                    ServerSentEvent(
                        data = json.encodeToString(ExecutionEventView.serializer(), executionEventView(row.attemptId, row.stored)),
                        event = SSE_EVENT,
                        id = "${row.attemptId}:${row.stored.leaseGeneration}:${row.stored.event.seq}",
                    ),
                )
                frames += 1
                lastSentNanos = timer.nanos()
            }
            cursor = pumped.cursor ?: cursor
            if (timer.nanos() - lastSentNanos >= heartbeat.toNanos()) {
                session.send(ServerSentEvent(comments = SSE_HEARTBEAT))
                lastSentNanos = timer.nanos()
            }
            timer.pause(streamTick)
        }
    } finally {
        monitorStreamLog.info("run execution stream closed runId={} frames={} reason={}", admission.runId, frames, reason)
    }
}

/** One stored row with the attempt it belongs to. */
private data class PumpedRow(val attemptId: String, val stored: StoredMonitorEvent)

/** The rows sent in one pass and the position after them. [cursor] is null when nothing was sent. */
private class Pumped(val rows: List<PumpedRow>, val cursor: StreamPosition?)

/**
 * Reads the rows of the run after [cursor], in order, one page at a time. Returns null when the cursor no longer names a stored row (its
 * rows were purged, or it belongs to another Run): the caller then sends `resync`.
 */
private fun RunExecutionDeps.pump(runId: String, cursor: StreamPosition?): Pumped? {
    val attempts = attempts.listOfRun(runId)
    if (cursor != null && !monitor.exists(cursor.attemptId, cursor.generation, cursor.seq)) return null
    val startIndex = if (cursor == null) {
        0
    } else {
        attempts.indexOfFirst { it.attemptId == cursor.attemptId }.takeIf { it >= 0 } ?: return null
    }
    val out = mutableListOf<PumpedRow>()
    var position = cursor
    for (index in startIndex until attempts.size) {
        val attemptId = attempts[index].attemptId
        var afterGeneration = if (cursor != null && index == startIndex) cursor.generation else 0L
        var afterSeq = if (cursor != null && index == startIndex) cursor.seq else -1
        while (true) {
            val rows = monitor.read(attemptId, afterGeneration, afterSeq, MAX_PAGE)
            for (row in rows) {
                out += PumpedRow(attemptId, row)
                position = StreamPosition(attemptId, row.leaseGeneration, row.event.seq)
            }
            if (rows.size < MAX_PAGE) break
            val last = rows.last()
            afterGeneration = last.leaseGeneration
            afterSeq = last.event.seq
        }
    }
    return Pumped(out, position)
}

/** `<attemptId>:<generation>:<seq>` from the Last-Event-ID header, or null when malformed. */
internal fun parseCursor(raw: String): StreamPosition? {
    val match = LAST_EVENT_ID.matchEntire(raw.trim()) ?: return null
    val generation = match.groupValues[2].toLongOrNull() ?: return null
    val seq = match.groupValues[3].toLongOrNull()?.takeIf { it <= MAX_SEQ }?.toInt() ?: return null
    return StreamPosition(match.groupValues[1], generation, seq)
}

/** A whole number in [min, max], or null. Plain digits only, with an optional leading minus (the cursor floor -1 is allowed). */
private fun parseBounded(raw: String, min: Long, max: Long): Long? {
    if (!Regex("^-?[0-9]{1,12}$").matches(raw)) return null
    return raw.toLongOrNull()?.takeIf { it in min..max }
}
