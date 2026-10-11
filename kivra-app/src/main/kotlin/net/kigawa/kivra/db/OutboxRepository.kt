package net.kigawa.kivra.db

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import net.kigawa.kivra.core.Lease
import net.kigawa.kivra.core.OutboxStatus
import net.kigawa.kivra.core.RunId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class OutboxEvent(
    val eventId: String,
    val aggregateId: String,
    val aggregateVersion: Long,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus,
    val publishAttempts: Int,
    val nextAttemptAt: LocalDateTime,
)

/**
 * Transactional Outbox (design #11 section 5). Events describe already-committed state changes and are
 * written in the same transaction as the change (at-least-once delivery; consumers dedupe on `event_id`).
 * A publisher worker is out of scope for M0; [fetchPending] / [markPublished] / [markFailed] are its building blocks.
 */
class OutboxRepository(private val database: Database, private val clock: Clock = Clock.systemUTC()) {

    /** Inserts an event inside the caller's transaction. `(run, version, type)` is unique. */
    fun append(
        tx: Transaction,
        runId: RunId,
        aggregateVersion: Long,
        eventType: String,
        payload: String,
        traceId: String? = null,
    ): String = with(tx) {
        val eventId = Ulid.generate(clock)
        val now = utcNow(clock)
        OutboxEventsTable.insert {
            it[OutboxEventsTable.eventId] = eventId
            it[aggregateType] = AGGREGATE_RUN
            it[aggregateId] = runId.value
            it[OutboxEventsTable.aggregateVersion] = aggregateVersion
            it[OutboxEventsTable.eventType] = eventType
            it[OutboxEventsTable.payload] = payload
            it[OutboxEventsTable.traceId] = traceId
            it[status] = OutboxStatus.PENDING.name
            it[nextAttemptAt] = now
            it[createdAt] = now
        }
        eventId
    }

    /** Standalone append that is rejected (and leaves nothing behind) when [lease] is no longer valid. */
    fun appendFenced(lease: Lease, aggregateVersion: Long, eventType: String, payload: String, traceId: String? = null): String =
        transaction(database) {
            requireLease(lease)
            append(this, lease.runId, aggregateVersion, eventType, payload, traceId)
        }

    /** Pending events that are due, ordered per run by version. */
    fun fetchPending(limit: Int): List<OutboxEvent> = transaction(database) {
        OutboxEventsTable.selectAll()
            .where { (OutboxEventsTable.status eq OutboxStatus.PENDING.name) and (OutboxEventsTable.nextAttemptAt lessEq utcNow(clock)) }
            .orderBy(OutboxEventsTable.aggregateId to SortOrder.ASC, OutboxEventsTable.aggregateVersion to SortOrder.ASC)
            .limit(limit)
            .map { it.toEvent() }
    }

    /** PENDING -> PUBLISHED. False if it was not PENDING (already published by another publisher). */
    fun markPublished(eventId: String): Boolean = transaction(database) {
        OutboxEventsTable.update({
            (OutboxEventsTable.eventId eq eventId) and (OutboxEventsTable.status eq OutboxStatus.PENDING.name)
        }) {
            it[status] = OutboxStatus.PUBLISHED.name
            it[publishedAt] = utcNow(clock)
        } == 1
    }

    /** Records a failed delivery: backoff, or DEAD once [maxAttempts] is reached. */
    fun markFailed(eventId: String, backoff: Duration, maxAttempts: Int): OutboxStatus? = transaction(database) {
        val row = OutboxEventsTable.selectAll().where { OutboxEventsTable.eventId eq eventId }.firstOrNull() ?: return@transaction null
        if (row[OutboxEventsTable.status] != OutboxStatus.PENDING.name) return@transaction null
        val attempts = row[OutboxEventsTable.publishAttempts] + 1
        val next = if (attempts >= maxAttempts) OutboxStatus.DEAD else OutboxStatus.PENDING
        OutboxEventsTable.update({
            (OutboxEventsTable.eventId eq eventId) and (OutboxEventsTable.status eq OutboxStatus.PENDING.name)
        }) {
            it[publishAttempts] = attempts
            it[status] = next.name
            it[nextAttemptAt] = utcNow(clock).plus(backoff)
        }
        next
    }

    private fun ResultRow.toEvent() = OutboxEvent(
        eventId = this[OutboxEventsTable.eventId],
        aggregateId = this[OutboxEventsTable.aggregateId],
        aggregateVersion = this[OutboxEventsTable.aggregateVersion],
        eventType = this[OutboxEventsTable.eventType],
        payload = this[OutboxEventsTable.payload],
        status = OutboxStatus.valueOf(this[OutboxEventsTable.status]),
        publishAttempts = this[OutboxEventsTable.publishAttempts],
        nextAttemptAt = this[OutboxEventsTable.nextAttemptAt],
    )

    companion object {
        const val AGGREGATE_RUN = "RUN"
    }
}
