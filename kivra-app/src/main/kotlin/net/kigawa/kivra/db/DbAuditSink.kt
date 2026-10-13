package net.kigawa.kivra.db

import java.time.Clock
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.policy.AuditOutcome
import net.kigawa.kivra.policy.AuditRecord
import net.kigawa.kivra.policy.AuditSink
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [AuditSink] backed by the hash-chained `audit_events` table. `record` returns true only after the row is committed;
 * any failure (DB down, unknown run, constraint violation) returns false so the Gateway fails closed on INTENT_ALLOWED.
 * Records without a run (e.g. invalid token) cannot join a per-run chain and are reported as not stored (false):
 * that only affects denials, which never execute anything.
 * Append-only: this class and [AuditRepository] expose no update / delete.
 */
class DbAuditSink(private val database: Database, private val audit: AuditRepository) : AuditSink {
    override fun record(event: AuditRecord): Boolean {
        val runId = event.runId ?: return false
        return try {
            transaction(database) {
                // Same row lock as every other chain writer: serializes seq assignment for this run.
                DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId }.forUpdate().firstOrNull()
                    ?: return@transaction false
                audit.append(this, RunId(runId), AuditEntry(
                    actor = event.agentId?.let { "agent:$it" } ?: "gateway",
                    agentId = event.agentId?.takeIf { it.length <= 26 },
                    action = event.action.take(64),
                    result = event.outcome.name,
                    resource = event.resource.take(255),
                    decisionId = event.decisionId,
                    approvalId = event.approvalId,
                    payload = buildJsonObject {
                        put("reason", event.reason)
                        event.digest?.let { put("digest", it) }
                        event.agentId?.let { put("agentId", it) }
                    }.toString(),
                ))
                true
            }
        } catch (e: Exception) { false }
    }
}

/** An INTENT_ALLOWED record that has no terminal record for the same decision. */
data class OrphanIntent(val runId: String, val decisionId: String, val approvalId: String?, val action: String, val resource: String)

/** Execution-side phase of one decision, as reported by the authoritative store of the executing side. */
enum class ExecutionPhase { NOT_STARTED, STARTED }

/**
 * Authoritative query of the execution side (Gateway / handler / coordinator state, not the audit log) for one decision.
 * Return [ExecutionPhase.NOT_STARTED] only when the handler has provably not begun for [decisionId]; return null when that
 * cannot be confirmed. Implementations must be read-only and must not call back into the Gateway: the reaper calls them
 * while holding the run row lock.
 */
fun interface ExecutionPhaseAuthority {
    fun phaseOf(decisionId: String): ExecutionPhase?
}

/**
 * Orphan intent policy. The Gateway writes INTENT_ALLOWED before any side effect; a crash afterwards leaves an intent with
 * no EXECUTED / FAILED / UNKNOWN / DENIED / ABANDONED record. After [graceSeconds] such an intent is closed by:
 *  - ABANDONED only when it needed an approval that was never consumed AND [phases] confirms NOT_STARTED. The
 *    [ApprovalRepository.consume] fence refuses an ABANDONED decision, so the handler cannot start for it afterwards;
 *  - UNKNOWN otherwise: no approval needed, approval consumed, the phase is STARTED, unknown, or the query failed. The
 *    effect may have happened, so it goes through reconciliation (OperationStatus UNKNOWN -> RECONCILIATION_REQUIRED) and
 *    is never retried on a guess.
 * Closing is idempotent (a closed decision is no longer an orphan) and runs under the run row lock.
 */
class OrphanIntentReaper(
    private val database: Database,
    private val audit: AuditRepository,
    private val phases: ExecutionPhaseAuthority,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun findOrphans(graceSeconds: Long): List<OrphanIntent> = transaction(database) {
        execQuery(
            """
            SELECT i.run_id, i.decision_id, i.approval_id, i.action, i.resource FROM audit_events i
             WHERE i.result = 'INTENT_ALLOWED' AND i.run_id IS NOT NULL AND i.decision_id IS NOT NULL AND i.occurred_at < ?
               AND NOT EXISTS (SELECT 1 FROM audit_events t WHERE t.decision_id = i.decision_id
                               AND t.result IN ('EXECUTED','FAILED','UNKNOWN','DENIED','ABANDONED'))
             ORDER BY i.occurred_at
            """.trimIndent(),
            utcNow(clock).minusSeconds(graceSeconds),
        ) { OrphanIntent(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }
    }

    /** Closes every orphan older than [graceSeconds]; returns the closed ones with the outcome written. */
    fun reap(graceSeconds: Long): List<Pair<OrphanIntent, String>> =
        findOrphans(graceSeconds).mapNotNull { o ->
            transaction(database) {
                DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq o.runId }.forUpdate().firstOrNull()
                    ?: return@transaction null
                val stillOrphan = execQuery(
                    "SELECT COUNT(*) FROM audit_events WHERE decision_id = ? AND result IN ('EXECUTED','FAILED','UNKNOWN','DENIED','ABANDONED')",
                    o.decisionId,
                ) { it.getInt(1) }.single() == 0
                if (!stillOrphan) return@transaction null
                val consumed = o.approvalId != null && execQuery(
                    "SELECT COUNT(*) FROM approvals WHERE approval_id = ? AND status = 'CONSUMED'", o.approvalId,
                ) { it.getInt(1) }.single() == 1
                // Authoritative check of the execution side; any failure or unknown answer keeps the intent UNKNOWN.
                val phase = try { phases.phaseOf(o.decisionId) } catch (e: Exception) { null }
                // ABANDONED is not a gateway outcome; it is stored directly as the audit result string.
                val abandon = o.approvalId != null && !consumed && phase == ExecutionPhase.NOT_STARTED
                val result = if (abandon) "ABANDONED" else "UNKNOWN"
                audit.append(this, RunId(o.runId), AuditEntry(
                    actor = "system:orphan-reaper", action = o.action.take(64), result = result, resource = o.resource,
                    decisionId = o.decisionId, approvalId = o.approvalId,
                    payload = buildJsonObject {
                        put("reason", "orphan intent: no terminal record")
                        put("phase", phase?.name ?: "UNCONFIRMED")
                        put("reconciliationRequired", result == "UNKNOWN")
                    }.toString(),
                ))
                o to result
            }
        }
}
