package net.kigawa.kivra.db

import java.security.MessageDigest
import java.time.Clock
import net.kigawa.kivra.core.RunId
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

data class AuditEntry(
    val actor: String,
    val action: String,
    val result: String,
    val resource: String? = null,
    val agentId: String? = null,
    val decisionId: String? = null,
    val approvalId: String? = null,
    val traceId: String? = null,
    val payload: String? = null,
)

/** The newest event of a run's chain. Copy it out of the database to detect truncation (see [AuditHeadAnchor]). */
data class AuditHead(val runId: String, val seq: Long, val hash: String)

/**
 * Port for an external anchor: a copy of the chain head delivered to a store that the application database's
 * administrators cannot rewrite silently (WORM storage, a separate log account, a signed note). Without such an anchor
 * the chain only detects modification of events that remain; it cannot detect deletion of the newest events.
 */
fun interface AuditHeadAnchor {
    fun publish(head: AuditHead)
}

/**
 * Append-only audit log, hash-chained per run (design #1 section 6.5).
 * [append] must run inside the same transaction as the state change it records
 * (design #1 section 5.4). The caller holds the run row lock, which serializes `seq` assignment.
 * There is intentionally no update / delete API.
 */
open class AuditRepository(private val clock: Clock = Clock.systemUTC()) {

    open fun append(tx: Transaction, runId: RunId, entry: AuditEntry): String = with(tx) {
        val last = AuditEventsTable
            .selectAll()
            .where { AuditEventsTable.runId eq runId.value }
            .orderBy(AuditEventsTable.seq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
        val seq = (last?.get(AuditEventsTable.seq) ?: 0L) + 1
        val prevHash = last?.get(AuditEventsTable.hash)
        val occurredAt = utcNow(clock)
        val eventId = Ulid.generate(clock)
        val hash = computeHash(prevHash, runId.value, seq, occurredAt.toString(), entry)
        AuditEventsTable.insert {
            it[AuditEventsTable.eventId] = eventId
            it[AuditEventsTable.runId] = runId.value
            it[AuditEventsTable.seq] = seq
            it[AuditEventsTable.occurredAt] = occurredAt
            it[actor] = entry.actor
            it[agentId] = entry.agentId
            it[action] = entry.action
            it[resource] = entry.resource
            it[decisionId] = entry.decisionId
            it[approvalId] = entry.approvalId
            it[result] = entry.result
            it[traceId] = entry.traceId
            it[payload] = entry.payload
            it[AuditEventsTable.prevHash] = prevHash
            it[AuditEventsTable.hash] = hash
        }
        eventId
    }

    /** Latest event of [runId] as (seq, hash), or null when the run has no events. */
    fun head(tx: Transaction, runId: RunId): AuditHead? = with(tx) {
        AuditEventsTable.selectAll()
            .where { AuditEventsTable.runId eq runId.value }
            .orderBy(AuditEventsTable.seq, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { AuditHead(runId.value, it[AuditEventsTable.seq], it[AuditEventsTable.hash]) }
    }

    /** Recomputes the chain of a run and returns true when no event was altered, removed from the middle or reordered. */
    fun verifyChain(tx: Transaction, runId: RunId): Boolean = with(tx) {
        var prev: String? = null
        var expectedSeq = 1L
        AuditEventsTable.selectAll().where { AuditEventsTable.runId eq runId.value }
            .orderBy(AuditEventsTable.seq, SortOrder.ASC)
            .forEach { row ->
                val entry = AuditEntry(
                    actor = row[AuditEventsTable.actor],
                    action = row[AuditEventsTable.action],
                    result = row[AuditEventsTable.result],
                    resource = row[AuditEventsTable.resource],
                    agentId = row[AuditEventsTable.agentId],
                    decisionId = row[AuditEventsTable.decisionId],
                    approvalId = row[AuditEventsTable.approvalId],
                    traceId = row[AuditEventsTable.traceId],
                    payload = row[AuditEventsTable.payload],
                )
                val seq = row[AuditEventsTable.seq]
                val expected = computeHash(prev, runId.value, seq, row[AuditEventsTable.occurredAt].toString(), entry)
                if (seq != expectedSeq || row[AuditEventsTable.prevHash] != prev || row[AuditEventsTable.hash] != expected) return false
                prev = expected
                expectedSeq++
            }
        true
    }

    private fun computeHash(prev: String?, runId: String, seq: Long, occurredAt: String, e: AuditEntry): String {
        val canonical = listOf(
            prev.orEmpty(), runId, seq.toString(), occurredAt, e.actor, e.agentId.orEmpty(), e.action, e.resource.orEmpty(),
            e.decisionId.orEmpty(), e.approvalId.orEmpty(), e.result, e.traceId.orEmpty(), e.payload.orEmpty(),
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
