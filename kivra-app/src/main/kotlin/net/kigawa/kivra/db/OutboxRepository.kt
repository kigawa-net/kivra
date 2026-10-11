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
 * A publisher worker is out of scope for M0; [claim] / [markPublished] / [markFailed] are its building blocks.
 *
 * Delivery contract (README "Outbox 配信契約"):
 * - **At-least-once.** [claim] gives one publisher a time-limited claim (lease, DB clock). If the publisher crashes or
 *   is slow the claim expires and another publisher delivers the event again, so a consumer can see an event more
 *   than once and MUST dedupe on `event_id` (see `inbox_events`). Exactly-once is not promised.
 * - **Per-run order.** Only the lowest-version PENDING event of a run is claimable. Across runs there is no order.
 * - `markPublished` / `markFailed` succeed only for the claim holder with a live claim; `markFailed` is CAS on
 *   `publish_attempts` under a row lock (no lost update), becomes DEAD at `maxAttempts`, and DEAD never blocks later events.
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

    /**
     * Claims up to [limit] due events for [publisherId] for [claimTtl] (DB clock). Each event is claimed with a
     * conditional UPDATE, so two concurrent publishers never both get the same event while its claim is live.
     * Per run only the lowest-version PENDING event is claimable (a higher version waits until the lower one is
     * PUBLISHED or DEAD), so a run's events are delivered in order. Events of different runs are independent.
     */
    fun claim(publisherId: String, limit: Int, claimTtl: Duration): List<OutboxEvent> = transaction(database) {
        val candidates = execQuery(
            """
            SELECT o.event_id FROM outbox_events o
             WHERE o.status = 'PENDING' AND o.next_attempt_at <= ${DbTime.NOW}
               AND (o.claim_expires_at IS NULL OR o.claim_expires_at <= ${DbTime.NOW})
               AND NOT EXISTS (SELECT 1 FROM outbox_events p
                                WHERE p.aggregate_type = o.aggregate_type AND p.aggregate_id = o.aggregate_id
                                  AND p.status = 'PENDING' AND p.aggregate_version < o.aggregate_version)
             ORDER BY o.aggregate_id, o.aggregate_version
             LIMIT ?
            """.trimIndent(),
            limit,
        ) { it.getString(1) }
        candidates.mapNotNull { id ->
            val won = execUpdate(
                """
                UPDATE outbox_events
                   SET claimed_by = ?, claim_expires_at = ${DbTime.nowPlusSeconds()}
                 WHERE event_id = ? AND status = 'PENDING'
                   AND (claim_expires_at IS NULL OR claim_expires_at <= ${DbTime.NOW})
                """.trimIndent(),
                publisherId, claimTtl.seconds, id,
            ) == 1
            if (won) OutboxEventsTable.selectAll().where { OutboxEventsTable.eventId eq id }.single().toEvent() else null
        }
    }

    /**
     * PENDING -> PUBLISHED, only for the publisher whose claim is still live. False otherwise (claim expired and
     * possibly taken over, or already published). A false result after a successful send is expected under
     * at-least-once delivery: the event may be delivered again, which is why consumers dedupe on `event_id`.
     */
    fun markPublished(eventId: String, publisherId: String): Boolean = transaction(database) {
        execUpdate(
            """
            UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?, claimed_by = NULL, claim_expires_at = NULL
             WHERE event_id = ? AND status = 'PENDING' AND claimed_by = ? AND claim_expires_at > ${DbTime.NOW}
            """.trimIndent(),
            utcNow(clock), eventId, publisherId,
        ) == 1
    }

    /**
     * Records a failed delivery for the claim holder: backoff, or DEAD once [maxAttempts] is reached.
     * The row is locked and updated with CAS on `publish_attempts`, so concurrent callers cannot lose an
     * increment. Returns null if the caller does not hold a live claim or the event is no longer PENDING.
     */
    fun markFailed(eventId: String, publisherId: String, backoff: Duration, maxAttempts: Int): OutboxStatus? = transaction(database) {
        val row = OutboxEventsTable.selectAll().where { OutboxEventsTable.eventId eq eventId }.forUpdate().firstOrNull()
            ?: return@transaction null
        val expires = row[OutboxEventsTable.claimExpiresAt]
        if (row[OutboxEventsTable.status] != OutboxStatus.PENDING.name || row[OutboxEventsTable.claimedBy] != publisherId || expires == null) {
            return@transaction null
        }
        val attempts = row[OutboxEventsTable.publishAttempts]
        val next = if (attempts + 1 >= maxAttempts) OutboxStatus.DEAD else OutboxStatus.PENDING
        val updated = execUpdate(
            """
            UPDATE outbox_events
               SET status = ?, next_attempt_at = ${DbTime.nowPlusSeconds()}, claimed_by = NULL, claim_expires_at = NULL, publish_attempts = ?
             WHERE event_id = ? AND status = 'PENDING' AND claimed_by = ? AND publish_attempts = ?
               AND claim_expires_at > ${DbTime.NOW}
            """.trimIndent(),
            next.name, backoff.seconds, attempts + 1, eventId, publisherId, attempts,
        )
        if (updated == 1) next else null
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
