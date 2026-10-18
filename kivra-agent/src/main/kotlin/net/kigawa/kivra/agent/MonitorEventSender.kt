package net.kigawa.kivra.agent

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.kigawa.kivra.agent.claude.MonitorEvent
import net.kigawa.kivra.agent.claude.MonitorEventExtractor
import net.kigawa.kivra.agent.claude.MonitorWarning
import net.kigawa.kivra.agent.docker.StdoutLine
import org.slf4j.LoggerFactory

/**
 * The wire form of the monitor events (ADR 0012 §2.1): fixed field names, fixed codes and numbers. Every value is ASCII, so the length
 * of a JSON text is its size in bytes.
 */
internal object MonitorWire {
    const val MAX_EVENTS_PER_REQUEST = 50
    const val MAX_BODY_BYTES = 8 * 1024

    /**
     * The wire form of one event. Field names and values are kivra's (`MonitorEventSchema`): each kind carries exactly the fields listed
     * in [KIND_FIELDS], and no other. Kinds kivra writes itself (TRUNCATED) are never sent.
     */
    fun event(seq: Long, event: MonitorEvent): JsonObject = buildJsonObject {
        put("seq", seq)
        when (event) {
            MonitorEvent.AttemptStarted -> put("kind", "ATTEMPT_STARTED")
            MonitorEvent.CliInit -> put("kind", "CLI_INIT")
            is MonitorEvent.Phase -> {
                put("kind", "PHASE")
                put("phase", event.phase.name)
            }
            is MonitorEvent.ToolUse -> {
                put("kind", "TOOL_USE")
                put("toolName", event.tool.wire)
            }
            MonitorEvent.ToolError -> {
                put("kind", "TOOL_RESULT")
                put("count", 1)
            }
            MonitorEvent.Turn -> {
                put("kind", "TURN")
                put("count", 1)
            }
            is MonitorEvent.PermissionDenied -> {
                put("kind", "PERMISSION_DENIED")
                put("count", event.count)
            }
            is MonitorEvent.ApiRetry -> {
                put("kind", "API_RETRY")
                put("code", event.kind.name)
            }
            is MonitorEvent.Cost -> {
                put("kind", "COST")
                put("costUsd", BigDecimal.valueOf(event.micros, 6))
            }
            MonitorEvent.StopRequested -> put("kind", "STOP_REQUESTED")
            is MonitorEvent.ProcessExitConfirmed -> {
                put("kind", "PROCESS_EXIT_CONFIRMED")
                put("code", event.outcome.name)
            }
            is MonitorEvent.Warning -> {
                // kivra's WARNING carries a count of warnings; the kind of warning is not sent.
                put("kind", "WARNING")
                put("count", 1)
            }
            is MonitorEvent.Dropped -> {
                put("kind", "DROPPED")
                put("count", event.count)
            }
        }
    }

    /** The kinds kivra accepts, and the fields each one carries (kivra's `MonitorKind.fields`). Pinned by a test. */
    val KIND_FIELDS: Map<String, Set<String>> = mapOf(
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

    fun body(generation: Long, events: List<JsonObject>): String = buildJsonObject {
        put("generation", generation)
        putJsonArray("events") {
            events.forEach { add(it) }
        }
    }.toString()
}

/** Posts one batch. The attempt id is bound by the caller. Never throws for a status code; see [EventsReply]. */
fun interface MonitorTransport {
    suspend fun post(body: String): EventsReply
}

/**
 * Sends the monitor events of one attempt (ADR 0012 §2.1). Events are queued without blocking ([enqueue] is safe from any thread),
 * and sent in order by [runLoop] in batches of at most 50 events and 8 KiB. Each event gets a `seq` when it enters a batch, counting
 * from 1 for each generation, so a dropped event never leaves a gap.
 *
 * - The queue holds at most [queueCapacity] events. A later event is dropped, and the count goes into the next batch as one DROPPED.
 * - At most [maxEvents] events are queued in all. The last one is TRUNCATED, and nothing after it is queued.
 * - 200 accepts the batch. 409 with `lastAcceptedSeq` resends the rest of the batch from there; a 409 without it (the lease is stale)
 *   stops the sender. 429 waits for `Retry-After`. 400 discards the batch and its seqs are reused, with one WARNING for the attempt. 5xx,
 *   network errors and timeouts retry the same batch a few times, then stop. Any other refusal (401, 403, 404) stops the sender.
 * - Requests are paced by [pacingMillis] (5 per second by default).
 * - Nothing here logs a value: only the ids, counts and fixed codes.
 *
 * A stopped sender never sends again. A failure here does not change the attempt: the caller does not wait for a result from it,
 * except in [finish], which is bounded.
 */
class MonitorEventSender(
    private val attemptId: String,
    private val generation: Long,
    private val transport: MonitorTransport,
    private val pacingMillis: Long = PACING_MILLIS,
    private val retryBackoffMillis: List<Long> = RETRY_BACKOFF_MILLIS,
    private val maxEvents: Int = MAX_EVENTS,
    private val queueCapacity: Int = QUEUE_CAPACITY,
) {
    private val logger = LoggerFactory.getLogger(MonitorEventSender::class.java)

    /** Guards the queue and the flags. Held only briefly, never across a suspension. */
    private val lock = Any()

    /** Serializes the requests. Held across a request. */
    private val sendLock = Mutex()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Guarded by lock.
    private val queue = ArrayDeque<MonitorEvent>()
    private var dropped = 0
    /** Events accepted into the queue, ever. Together with [markersIssued] it counts the seqs this attempt has used or will use. */
    private var queuedTotal = 0
    /** DROPPED markers put into a batch. Each one takes a seq. */
    private var markersIssued = 0
    private var closed = false
    private var abandoned = false
    private var nextSeq = 1L

    // Guarded by sendLock.
    private var inFlight: List<Item>? = null
    private var unavailableRetries = 0
    private var conflictResumes = 0
    private var rateLimitRetries = 0
    private var rejectWarned = false

    private class Item(val seq: Long, val json: JsonObject, val size: Int)

    /** Queues [event]. Never blocks and never throws. Dropped when the queue is full, the sender has stopped or the attempt is capped. */
    fun enqueue(event: MonitorEvent) {
        synchronized(lock) {
            if (closed || abandoned) return
            // One seq stays free for the last DROPPED summary, so the attempt never goes past maxEvents (kivra's cap).
            if (queuedTotal + markersIssued >= maxEvents - 1 || queue.size >= queueCapacity) {
                dropped++
                return
            }
            queue.addLast(event)
            queuedTotal++
        }
        wake.trySend(Unit)
    }

    /** Sends queued events as they come, until the coroutine is cancelled. Started by [MonitorSession]. */
    suspend fun runLoop() {
        for (signal in wake) {
            sendLock.withLock {
                while (pumpOne()) {
                    // Keep sending while there is something to send.
                }
            }
        }
    }

    /**
     * Stops accepting events and sends what is queued, for at most [timeoutMillis]. Whatever is left after that is given up. No request
     * is sent after this returns. Safe to call more than once.
     */
    suspend fun finish(timeoutMillis: Long = FLUSH_TIMEOUT_MILLIS) {
        synchronized(lock) { closed = true }
        val completed = withTimeoutOrNull(timeoutMillis) {
            sendLock.withLock {
                while (hasPending() && pumpOne()) {
                    // Keep sending while there is something to send.
                }
            }
            true
        }
        stop(if (completed == null) "FLUSH_TIMEOUT" else "FLUSH_INCOMPLETE")
    }

    /** One request, or one decision that needs no request. Returns false when there is nothing more to do now. Holds [sendLock]. */
    private suspend fun pumpOne(): Boolean {
        if (isAbandoned()) return false
        val batch = inFlight ?: buildBatch()?.also { inFlight = it } ?: run {
            discardUnsendable()
            return false
        }
        return when (val reply = post(batch)) {
            EventsReply.Accepted -> {
                inFlight = null
                unavailableRetries = 0
                rateLimitRetries = 0
                conflictResumes = 0
                true
            }
            is EventsReply.Conflict -> resumeAfterConflict(batch, reply.lastAcceptedSeq)
            is EventsReply.RateLimited -> {
                rateLimitRetries++
                if (rateLimitRetries > MAX_RATE_LIMIT_RETRIES) {
                    stop("RATE_LIMITED")
                    false
                } else {
                    delay((reply.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS) * 1_000)
                    true
                }
            }
            EventsReply.Rejected -> {
                inFlight = null
                synchronized(lock) { nextSeq = batch.first().seq }
                if (!rejectWarned) {
                    rejectWarned = true
                    logger.warn("monitor batch refused as invalid, discarded: attemptId={} generation={}", attemptId, generation)
                }
                true
            }
            EventsReply.Refused -> {
                stop("REFUSED")
                false
            }
            EventsReply.Unavailable -> {
                unavailableRetries++
                if (unavailableRetries > retryBackoffMillis.size) {
                    stop("UNAVAILABLE")
                    false
                } else {
                    delay(retryBackoffMillis[unavailableRetries - 1])
                    true
                }
            }
        }
    }

    /** A 409: resend the part of [batch] after `lastAcceptedSeq` when the events are still held, else stop. */
    private fun resumeAfterConflict(batch: List<Item>, lastAccepted: Long?): Boolean {
        if (lastAccepted == null) {
            stop("CONFLICT")
            return false
        }
        if (lastAccepted >= batch.last().seq) {
            inFlight = null
            return true
        }
        conflictResumes++
        if (lastAccepted + 1 < batch.first().seq || conflictResumes > MAX_CONFLICT_RESUMES) {
            stop("SEQ_GAP")
            return false
        }
        inFlight = batch.filter { it.seq > lastAccepted }
        return true
    }

    private suspend fun post(batch: List<Item>): EventsReply {
        val body = MonitorWire.body(generation, batch.map { it.json })
        val reply = try {
            transport.post(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EventsReply.Unavailable
        }
        delay(pacingMillis)
        return reply
    }

    /**
     * Takes the next batch from the queue, with fresh seqs. Null when there is nothing to send (or the sender has stopped). Holds [lock]
     * only for this, so enqueue never waits for a request.
     */
    private fun buildBatch(): List<Item>? = synchronized(lock) { buildBatchLocked() }

    private fun buildBatchLocked(): List<Item>? {
        if (abandoned) return null
        val items = ArrayList<Item>()
        var bytes = MonitorWire.body(generation, emptyList()).length
        fun add(event: MonitorEvent): Boolean {
            if (items.size >= MonitorWire.MAX_EVENTS_PER_REQUEST) return false
            val seq = nextSeq + items.size
            if (seq > maxEvents) return false
            val json = MonitorWire.event(seq, event)
            val size = json.toString().length + 1
            if (bytes + size > MonitorWire.MAX_BODY_BYTES) return false
            bytes += size
            items += Item(seq, json, size)
            return true
        }
        while (queue.isNotEmpty() && add(queue.first())) queue.removeFirst()
        // The DROPPED summary goes after the queued events. A mid-run summary keeps the last seq free, so the final one always fits.
        if (dropped > 0 && (closed || queuedTotal + markersIssued < maxEvents - 1)) {
            if (add(MonitorEvent.Dropped(minOf(dropped, MonitorEvent.MAX_COUNT)))) {
                markersIssued++
                dropped = 0
            }
        }
        if (items.isEmpty()) return null
        nextSeq += items.size
        return items
    }

    /** The queue holds events that no seq can take any more (the attempt's cap). They are given up. */
    private fun discardUnsendable() {
        val lost = synchronized(lock) {
            val n = queue.size
            queue.clear()
            n
        }
        if (lost > 0) stop("SEQ_CAP")
    }

    private fun hasPending(): Boolean = inFlight != null || synchronized(lock) { queue.isNotEmpty() || dropped > 0 }

    private fun isAbandoned(): Boolean = synchronized(lock) { abandoned }

    /**
     * Stops the sender for good: nothing more is queued or sent. When events are still unsent, the reason is logged with their count.
     * The lock is taken here, not the send lock, so a timed-out [finish] can call it.
     */
    private fun stop(reason: String) {
        val lost = synchronized(lock) {
            if (abandoned) return
            abandoned = true
            val n = queue.size + dropped
            queue.clear()
            dropped = 0
            n
        }
        if (lost > 0) logger.warn("monitor events stopped: attemptId={} reason={} notSent={}", attemptId, reason, lost)
    }

    companion object {
        /** The most seqs one attempt uses, events and DROPPED summaries together. kivra's own cap is the same (ADR 0012 U-10). */
        const val MAX_EVENTS = 2_000

        /** The most events the queue holds before it drops (ADR 0012 U-10). */
        const val QUEUE_CAPACITY = 500

        /** The pause after each request: 5 requests per second (ADR 0012 U-10). */
        const val PACING_MILLIS = 200L

        /** The waits before the second, third and fourth try of a batch that failed with a server error or a network error. */
        val RETRY_BACKOFF_MILLIS = listOf(200L, 500L, 1_000L)

        /** The longest wait for the last events before a result or a fail is sent (ADR 0012 §2.1). */
        const val FLUSH_TIMEOUT_MILLIS = 3_000L

        const val MAX_RATE_LIMIT_RETRIES = 5
        const val MAX_CONFLICT_RESUMES = 3
        const val DEFAULT_RETRY_AFTER_SECONDS = 1L
    }
}

/**
 * The monitor of one attempt: the sender, its loop, and the reader of the CLI's stdout lines. [DISABLED] does nothing and sends nothing,
 * which is the default (`KIVRA_AGENT_MONITOR_EVENTS` unset). With it, the attempt runs as it did before the monitor existed.
 */
class MonitorSession private constructor(
    private val sender: MonitorEventSender?,
    private val scope: CoroutineScope?,
    private val loop: Job?,
) {
    fun record(event: MonitorEvent) {
        sender?.enqueue(event)
    }

    /**
     * The listener of the CLI's stdout lines, or null when the monitor is disabled. Each line is read with the extractor, and nothing
     * else from it is kept.
     */
    fun stdoutListener(): ((StdoutLine) -> Unit)? {
        val s = sender ?: return null
        return { line ->
            when (line) {
                is StdoutLine.Text -> MonitorEventExtractor.extract(line.value).forEach { s.enqueue(it) }
                StdoutLine.Oversized -> s.enqueue(MonitorEvent.Warning(MonitorWarning.OVERSIZED_LINE))
            }
        }
    }

    /** Sends what is still queued (at most [MonitorEventSender.FLUSH_TIMEOUT_MILLIS]), then stops the monitor. Safe to call twice. */
    suspend fun finish() {
        val s = sender ?: return
        s.finish()
        loop?.cancel()
        scope?.cancel()
    }

    companion object {
        val DISABLED = MonitorSession(null, null, null)

        fun start(client: KivraAgentClient, attemptId: String, generation: Long): MonitorSession {
            val sender = MonitorEventSender(
                attemptId = attemptId,
                generation = generation,
                transport = MonitorTransport { body -> client.postEvents(attemptId, body) },
            )
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val loop = scope.launch { sender.runLoop() }
            return MonitorSession(sender, scope, loop)
        }
    }
}
