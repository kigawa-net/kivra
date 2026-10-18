package net.kigawa.kivra.db

import java.time.Clock
import java.time.LocalDateTime
import net.kigawa.kivra.coding.CodingMonitorPurge
import net.kigawa.kivra.coding.MonitorEvent
import net.kigawa.kivra.coding.MonitorKind
import net.kigawa.kivra.core.DevelopmentRunState
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * The run-execution monitor's event log (V20 `coding_monitor_events`, ADR 0012 section 2.2). Only the agent API writes it, through
 * [append], and only for the holder of the current lease generation. Nothing here logs, and no value of an event is ever echoed in an
 * error: the results are fixed codes and counts.
 *
 * The monitor is separate from the attempt's own transitions (`coding_events`). A failure here never changes an attempt: the caller
 * answers the monitor request alone.
 */

/** Exposed mirror of V20 `coding_monitor_events` (the migration is the source of truth). */
internal object CodingMonitorEventsTable : Table("coding_monitor_events") {
    val attemptId = char("attempt_id", 26)
    val leaseGeneration = long("lease_generation")
    val seq = integer("seq")
    val kind = varchar("kind", 32)
    val phase = varchar("phase", 32).nullable()
    val toolName = varchar("tool_name", 32).nullable()
    val code = varchar("code", 32).nullable()
    val countValue = integer("count_value").nullable()
    val costUsd = decimal("cost_usd", 12, 6).nullable()
    val receivedAt = datetime("received_at")
    override val primaryKey = PrimaryKey(attemptId, leaseGeneration, seq)
}

/** An event as stored, with the generation it was sent under and the server's receive time. */
data class StoredMonitorEvent(val leaseGeneration: Long, val event: MonitorEvent, val receivedAt: LocalDateTime)

/** What [CodingMonitorEventRepository.append] did. Only the outcomes below; no value is returned. */
sealed interface MonitorAppendResult {
    /** Stored (or already stored, for a resend). [lastAcceptedSeq] is the last seq of the generation; [truncated] is true at the cap. */
    data class Accepted(val lastAcceptedSeq: Int, val truncated: Boolean) : MonitorAppendResult

    /** The attempt is missing or held by another agent (the caller answers 404), not active, or of an older generation. */
    data class Refused(val reason: CodingRefusal) : MonitorAppendResult

    /** The batch starts after a gap: the next seq must be [lastAcceptedSeq] + 1. Nothing is stored. */
    data class Gap(val lastAcceptedSeq: Int) : MonitorAppendResult

    /** A seq that is already stored has different content. Nothing is stored. */
    data class Conflict(val lastAcceptedSeq: Int) : MonitorAppendResult
}

class CodingMonitorEventRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
    /** The cap per attempt, over all its generations (ADR 0012 U-2 / U-10). The server's TRUNCATED mark is not counted. */
    private val maxEventsPerAttempt: Int = DEFAULT_MAX_EVENTS_PER_ATTEMPT,
) : CodingMonitorPurge {

    init {
        require(maxEventsPerAttempt > 0) { "maxEventsPerAttempt must be positive" }
    }

    /**
     * Appends one batch of consecutive events for [generation] of [attemptId], held by [owner]. The attempt row is locked for the whole
     * call, so two batches for one attempt are serialized and the seq checks cannot race.
     *
     * Order of the checks: the holder, the state, the generation (fencing), then seq. A seq that is already stored is a resend: it is
     * accepted when the content is the same and it is a conflict when it differs. The first new seq must be the next one (a gap is
     * refused). Past the cap, the new events are dropped and the attempt gets one TRUNCATED mark (seq 0 of the generation that reached
     * the cap).
     */
    fun append(attemptId: String, owner: String, generation: Long, events: List<MonitorEvent>): MonitorAppendResult {
        require(events.isNotEmpty() && events.zipWithNext().all { (a, b) -> b.seq == a.seq + 1 }) { "events must be consecutive" }
        return transaction(database) {
            val attempt = CodingAttemptsTable.selectAll()
                .where { CodingAttemptsTable.attemptId eq attemptId }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction MonitorAppendResult.Refused(CodingRefusal.NOT_FOUND)
            // Another agent's attempt is reported as missing, the same as one that does not exist.
            if (attempt[CodingAttemptsTable.claimedBy] != owner) return@transaction MonitorAppendResult.Refused(CodingRefusal.NOT_FOUND)
            val state = attempt[CodingAttemptsTable.state]
            if (state != CodingAttemptState.CLAIMED.name && state != CodingAttemptState.RUNNING.name) {
                return@transaction MonitorAppendResult.Refused(CodingRefusal.NOT_ACTIVE)
            }
            if (attempt[CodingAttemptsTable.leaseGeneration] != generation) {
                return@transaction MonitorAppendResult.Refused(CodingRefusal.STALE_GENERATION)
            }

            val last = lastSeq(attemptId, generation)
            val overlap = events.filter { it.seq <= last }
            if (overlap.isNotEmpty()) {
                val stored = storedBySeq(attemptId, generation, overlap.first().seq, overlap.last().seq)
                if (overlap.any { stored[it.seq] != it }) return@transaction MonitorAppendResult.Conflict(last)
            }
            val fresh = events.filter { it.seq > last }
            if (fresh.isNotEmpty() && fresh.first().seq != last + 1) return@transaction MonitorAppendResult.Gap(last)

            val used = countEvents(attemptId)
            val room = (maxEventsPerAttempt - used).coerceAtLeast(0)
            val toStore = fresh.take(room)
            val now = utcNow(clock)
            for (event in toStore) insertEvent(attemptId, generation, event, now)
            val total = used + toStore.size
            val truncated = total >= maxEventsPerAttempt
            if ((fresh.size > toStore.size || truncated) && !hasTruncatedMark(attemptId)) {
                insertEvent(attemptId, generation, MonitorEvent(seq = 0, kind = MonitorKind.TRUNCATED), now)
            }
            MonitorAppendResult.Accepted(lastAcceptedSeq = last + toStore.size, truncated = truncated)
        }
    }

    /**
     * Stored events of [attemptId] after the cursor (generation, seq), oldest first, at most [limit]. The server's TRUNCATED mark (seq 0)
     * is included. Read-only; nothing is changed.
     */
    fun read(attemptId: String, afterGeneration: Long, afterSeq: Int, limit: Int): List<StoredMonitorEvent> {
        require(limit in 1..MAX_READ_LIMIT) { "limit must be from 1 to $MAX_READ_LIMIT" }
        return transaction(database) {
            CodingMonitorEventsTable.selectAll()
                .where {
                    (CodingMonitorEventsTable.attemptId eq attemptId) and (
                        (CodingMonitorEventsTable.leaseGeneration greater afterGeneration) or
                            ((CodingMonitorEventsTable.leaseGeneration eq afterGeneration) and (CodingMonitorEventsTable.seq greater afterSeq))
                        )
                }
                .orderBy(CodingMonitorEventsTable.leaseGeneration to SortOrder.ASC, CodingMonitorEventsTable.seq to SortOrder.ASC)
                .limit(limit)
                .map { it.toStored() }
        }
    }

    /**
     * Every stored event of [attemptId], oldest first. At most the cap plus the TRUNCATED mark, so this is bounded. Read-only. Used by the
     * run execution snapshot (ADR 0012 section 4), which needs the whole attempt for its counts.
     */
    fun all(attemptId: String): List<StoredMonitorEvent> = transaction(database) {
        CodingMonitorEventsTable.selectAll()
            .where { CodingMonitorEventsTable.attemptId eq attemptId }
            .orderBy(CodingMonitorEventsTable.leaseGeneration to SortOrder.ASC, CodingMonitorEventsTable.seq to SortOrder.ASC)
            .map { it.toStored() }
    }

    /** True when the row (attempt, generation, seq) is still stored. A stream cursor whose row is gone was purged (ADR 0012 section 4, resync). */
    fun exists(attemptId: String, generation: Long, seq: Int): Boolean = transaction(database) {
        CodingMonitorEventsTable.selectAll()
            .where {
                (CodingMonitorEventsTable.attemptId eq attemptId) and
                    (CodingMonitorEventsTable.leaseGeneration eq generation) and
                    (CodingMonitorEventsTable.seq eq seq)
            }
            .count() > 0
    }

    /**
     * Deletes the events of at most [maxAttempts] attempts that are no longer active and whose Run is terminal, and whose last change
     * ([CodingAttemptsTable] updated_at) is before [before]. Active attempts and non-terminal Runs keep their events. The attempt rows stay.
     * Returns the number of event rows deleted.
     */
    override fun purgeBefore(before: LocalDateTime, maxAttempts: Int): Int {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        val terminal = DevelopmentRunState.terminalNames.toList()
        val terminalMarks = terminal.joinToString(",") { "?" }
        return transaction(database) {
            val ids = execQuery(
                """SELECT DISTINCT e.attempt_id FROM coding_monitor_events e
                   JOIN coding_attempts a ON a.attempt_id = e.attempt_id
                   JOIN development_runs d ON d.run_id = a.run_id
                   WHERE a.updated_at < ?
                     AND a.state NOT IN ('QUEUED', 'CLAIMED', 'RUNNING')
                     AND d.state IN ($terminalMarks)
                   LIMIT ?""",
                *(listOf<Any?>(before) + terminal + listOf<Any?>(maxAttempts)).toTypedArray(),
            ) { it.getString(1) }
            if (ids.isEmpty()) {
                0
            } else {
                val idMarks = ids.joinToString(",") { "?" }
                execUpdate("DELETE FROM coding_monitor_events WHERE attempt_id IN ($idMarks)", *ids.toTypedArray())
            }
        }
    }

    private fun Transaction.lastSeq(attemptId: String, generation: Long): Int =
        execQuery(
            "SELECT COALESCE(MAX(seq), 0) FROM coding_monitor_events WHERE attempt_id = ? AND lease_generation = ? AND seq > 0",
            attemptId, generation,
        ) { it.getInt(1) }.single()

    private fun Transaction.countEvents(attemptId: String): Int =
        execQuery(
            "SELECT COUNT(*) FROM coding_monitor_events WHERE attempt_id = ? AND kind <> ?",
            attemptId, MonitorKind.TRUNCATED.name,
        ) { it.getLong(1) }.single().toInt()

    private fun Transaction.hasTruncatedMark(attemptId: String): Boolean =
        execQuery(
            "SELECT COUNT(*) FROM coding_monitor_events WHERE attempt_id = ? AND kind = ?",
            attemptId, MonitorKind.TRUNCATED.name,
        ) { it.getLong(1) }.single() > 0

    private fun Transaction.storedBySeq(attemptId: String, generation: Long, from: Int, to: Int): Map<Int, MonitorEvent> =
        CodingMonitorEventsTable.selectAll()
            .where {
                (CodingMonitorEventsTable.attemptId eq attemptId) and
                    (CodingMonitorEventsTable.leaseGeneration eq generation) and
                    (CodingMonitorEventsTable.seq greaterEq from) and
                    (CodingMonitorEventsTable.seq lessEq to)
            }
            .associate { row -> row[CodingMonitorEventsTable.seq] to row.toStored().event }

    private fun Transaction.insertEvent(attemptId: String, generation: Long, event: MonitorEvent, now: LocalDateTime) {
        execUpdate(
            """INSERT INTO coding_monitor_events
                   (attempt_id, lease_generation, seq, kind, phase, tool_name, code, count_value, cost_usd, received_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            attemptId, generation, event.seq, event.kind.name, event.phase, event.toolName, event.code,
            event.count, event.costUsd, now,
        )
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toStored(): StoredMonitorEvent {
        val kindName = this[CodingMonitorEventsTable.kind]
        val event = MonitorEvent(
            seq = this[CodingMonitorEventsTable.seq],
            kind = MonitorKind.valueOf(kindName),
            phase = this[CodingMonitorEventsTable.phase],
            toolName = this[CodingMonitorEventsTable.toolName],
            code = this[CodingMonitorEventsTable.code],
            count = this[CodingMonitorEventsTable.countValue],
            costUsd = this[CodingMonitorEventsTable.costUsd]?.setScale(6),
        )
        return StoredMonitorEvent(this[CodingMonitorEventsTable.leaseGeneration], event, this[CodingMonitorEventsTable.receivedAt])
    }

    companion object {
        const val DEFAULT_MAX_EVENTS_PER_ATTEMPT = 2_000
        const val MAX_READ_LIMIT = 200
    }
}
