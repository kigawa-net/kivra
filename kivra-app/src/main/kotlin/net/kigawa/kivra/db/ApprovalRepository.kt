package net.kigawa.kivra.db

import java.time.Clock
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.policy.ApprovalConsumer
import net.kigawa.kivra.policy.OperationDigest
import net.kigawa.kivra.policy.Principal
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

enum class ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, CONSUMED }

data class Approval(
    val approvalId: String,
    val runId: String,
    val requesterAgentId: String,
    val digest: String,
    val action: String,
    val resource: String,
    val status: ApprovalStatus,
    val decidedBy: String?,
    val decisionReason: String?,
)

/**
 * The only identity that may approve or reject: a human subject (e.g. a Keycloak OIDC `sub`).
 * There is deliberately no conversion from a [Principal] (Agent / Orchestrator token), so an Agent cannot
 * obtain one and the approval API cannot be reached with an Agent identity (design #1 section 6.3, no self-escalation).
 */
class HumanApprover private constructor(val subject: String) {
    companion object {
        /** Throws for blank subjects and for anything that looks like an agent / system identity. */
        fun of(subject: String): HumanApprover {
            require(subject.isNotBlank() && subject.length <= 128 && subject.none { it.isISOControl() }) { "invalid approver subject" }
            val s = subject.lowercase()
            require(listOf("agent:", "orchestrator:", "system:", "gateway").none { s.startsWith(it) }) { "approver must be a human subject" }
            return HumanApprover(subject)
        }
    }
}

/**
 * Approval Gate persistence (#33). Approvals are bound to an [OperationDigest], expire on the DB clock and can be
 * consumed exactly once. Every state change is one conditional UPDATE (status + expiry + digest in the WHERE), so
 * concurrent consumers race on the row and exactly one wins. Request / decide events are written to the hash-chained
 * audit log in the same transaction.
 *
 * Implements [ApprovalConsumer] for the Tool Gateway. [consume] never throws for an invalid approval: it returns false.
 */
class ApprovalRepository(
    private val database: Database,
    private val audit: AuditRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalConsumer {

    /**
     * Creates a PENDING approval request for [digest] on behalf of [principal]'s Agent. Idempotent: an unexpired
     * PENDING/APPROVED request for the same run + digest is returned instead of creating a duplicate.
     */
    fun request(principal: Principal, digest: OperationDigest, action: String, resource: String, ttlSeconds: Long): Approval {
        require(ttlSeconds in 1..7 * 24 * 3600L) { "ttlSeconds out of range" }
        return transaction(database) {
            lockRun(principal.runId) ?: throw NoSuchElementException("run ${principal.runId} not found")
            val existing = find("run_id = ? AND digest = ? AND status IN ('PENDING','APPROVED') AND expires_at > ${DbTime.NOW} AND requester_agent_id = ?",
                principal.runId, digest.hex, principal.agentId)
            if (existing != null) return@transaction existing
            val id = Ulid.generate(clock)
            execUpdate(
                """
                INSERT INTO approvals (approval_id, run_id, requester_agent_id, digest, action, resource, status, requested_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ${DbTime.nowPlusSeconds()})
                """.trimIndent(),
                id, principal.runId, principal.agentId, digest.hex, action, resource, utcNow(clock), ttlSeconds,
            )
            audit.append(this, RunId(principal.runId), AuditEntry(
                actor = "agent:${principal.agentId}", agentId = principal.agentId.takeIf { it.length <= 26 }, action = "approval.request",
                result = "REQUESTED", resource = resource, approvalId = id, payload = """{"digest":"${digest.hex}","action":"$action"}""",
            ))
            get(id)!!
        }
    }

    /** PENDING -> APPROVED. False when not pending, expired, or the approver is the requester (four-eyes). */
    fun approve(approvalId: String, approver: HumanApprover): Boolean = decide(approvalId, approver, "APPROVED", null)

    /** PENDING/APPROVED -> REJECTED. A rejected approval can never be consumed. */
    fun reject(approvalId: String, approver: HumanApprover, reason: String? = null): Boolean = decide(approvalId, approver, "REJECTED", reason)

    private fun decide(approvalId: String, approver: HumanApprover, target: String, reason: String?): Boolean = transaction(database) {
        val current = get(approvalId) ?: return@transaction false
        lockRun(current.runId)
        val allowedFrom = if (target == "APPROVED") "status = 'PENDING' AND expires_at > ${DbTime.NOW}" else "status IN ('PENDING','APPROVED')"
        val n = execUpdate(
            """
            UPDATE approvals SET status = ?, decided_by = ?, decided_at = ${DbTime.NOW}, decision_reason = ?
             WHERE approval_id = ? AND $allowedFrom AND requester_agent_id <> ?
            """.trimIndent(),
            target, approver.subject, reason?.take(255), approvalId, approver.subject,
        )
        if (n != 1) return@transaction false
        audit.append(this, RunId(current.runId), AuditEntry(
            actor = "human:${approver.subject}", action = "approval.decide", result = target,
            resource = current.resource, approvalId = approvalId, payload = """{"digest":"${current.digest}"}""",
        ))
        true
    }

    /**
     * Atomically consumes an APPROVED, unexpired approval whose digest equals [digest]. Exactly one concurrent
     * caller gets true; mismatching digest, expiry, rejection, an unknown id or reuse give false.
     */
    override fun consume(approvalId: String, digest: OperationDigest): Boolean = try {
        transaction(database) {
            execUpdate(
                """
                UPDATE approvals SET status = 'CONSUMED', consumed_at = ${DbTime.NOW}
                 WHERE approval_id = ? AND status = 'APPROVED' AND digest = ? AND expires_at > ${DbTime.NOW}
                   AND decided_by IS NOT NULL AND decided_by <> requester_agent_id
                """.trimIndent(),
                approvalId, digest.hex,
            ) == 1
        }
    } catch (e: Exception) { false }

    /** Marks PENDING/APPROVED approvals past their expiry as EXPIRED. Returns how many. (consume/approve already check expiry.) */
    fun expireDue(): Int = transaction(database) {
        execUpdate("UPDATE approvals SET status = 'EXPIRED' WHERE status IN ('PENDING','APPROVED') AND expires_at <= ${DbTime.NOW}")
    }

    fun get(approvalId: String): Approval? = transaction(database) { find("approval_id = ?", approvalId) }

    private fun org.jetbrains.exposed.sql.Transaction.find(where: String, vararg p: Any?): Approval? =
        execQuery(
            "SELECT approval_id, run_id, requester_agent_id, digest, action, resource, status, decided_by, decision_reason FROM approvals WHERE $where ORDER BY approval_id LIMIT 1",
            *p,
        ) {
            Approval(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6),
                ApprovalStatus.valueOf(it.getString(7)), it.getString(8), it.getString(9))
        }.firstOrNull()

    private fun org.jetbrains.exposed.sql.Transaction.lockRun(runId: String) =
        DevelopmentRunsTable.selectAll().where { DevelopmentRunsTable.runId eq runId }.forUpdate().firstOrNull()
}
