package net.kigawa.kivra.db

import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kigawa.kivra.core.RunId
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll

/** Outbox aggregate kind for audit head anchors. The aggregate version is the audit `seq` of the anchored head. */
const val AUDIT_ANCHOR_AGGREGATE = "AUDIT_ANCHOR"
const val AUDIT_HEAD_EVENT = "AUDIT_HEAD"

/**
 * Transaction side of the external audit anchor. Snapshots the current head of [runId] into the outbox, inside the
 * caller's transaction. It performs no external I/O, so a slow or failing anchor cannot hold DB locks or roll back the
 * caller. The run row is locked first, like every other audit writer, so the snapshot is consistent.
 *
 * Idempotent per `(run, seq)`: enqueuing the same head again returns it without a second event. A different hash at an
 * already-enqueued seq means the chain was rewritten; that is refused.
 *
 * Returns null when the run is unknown or has no events.
 */
fun enqueueAuditHeadAnchor(tx: Transaction, audit: AuditRepository, outbox: OutboxRepository, runId: RunId): AuditHead? = with(tx) {
    DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId.value }.forUpdate().firstOrNull() ?: return null
    val head = audit.head(tx, runId) ?: return null
    val existing = outbox.findEvent(tx, AUDIT_ANCHOR_AGGREGATE, runId.value, head.seq, AUDIT_HEAD_EVENT)
    if (existing != null) {
        val enqueuedHash = Json.parseToJsonElement(existing.payload).jsonObject.getValue("hash").jsonPrimitive.content
        check(enqueuedHash == head.hash) { "audit head conflict at seq ${head.seq}: the chain was rewritten after it was anchored" }
        return head
    }
    val payload = buildJsonObject {
        put("runId", head.runId)
        put("seq", head.seq)
        put("hash", head.hash)
    }.toString()
    outbox.append(tx, runId, head.seq, AUDIT_HEAD_EVENT, payload, aggregateKind = AUDIT_ANCHOR_AGGREGATE)
    return head
}

/**
 * Publishes queued audit head anchors through the outbox, after the enqueueing transaction has committed. A failure
 * only leaves the event PENDING (with backoff) and never touches the enqueueing transaction. Delivery is at-least-once:
 * a redelivered head carries the same idempotency key (the event id), which the [AuditHeadAnchor] must dedupe on.
 */
class AuditAnchorPublisher(
    private val outbox: OutboxRepository,
    private val anchor: AuditHeadAnchor,
    private val publisherId: String,
    private val claimTtl: Duration = Duration.ofMinutes(1),
    private val backoff: Duration = Duration.ofSeconds(30),
    private val maxAttempts: Int = 10,
) {
    /** Publishes up to [limit] due anchors and returns how many were delivered and marked PUBLISHED. */
    fun publishDue(limit: Int = 50): Int {
        var published = 0
        for (event in outbox.claim(publisherId, limit, claimTtl, aggregateKind = AUDIT_ANCHOR_AGGREGATE)) {
            val token = event.claimToken ?: continue
            val delivered = try {
                anchor.publish(parseHead(event.payload), event.eventId)
                true
            } catch (e: Exception) {
                false
            }
            if (delivered) {
                if (outbox.markPublished(event.eventId, publisherId, token)) published++
            } else {
                outbox.markFailed(event.eventId, publisherId, token, backoff, maxAttempts)
            }
        }
        return published
    }

    private fun parseHead(payload: String): AuditHead {
        val obj = Json.parseToJsonElement(payload).jsonObject
        return AuditHead(
            runId = obj.getValue("runId").jsonPrimitive.content,
            seq = obj.getValue("seq").jsonPrimitive.content.toLong(),
            hash = obj.getValue("hash").jsonPrimitive.content,
        )
    }
}
