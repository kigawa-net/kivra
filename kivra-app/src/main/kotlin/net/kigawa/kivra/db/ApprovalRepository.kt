package net.kigawa.kivra.db

import java.time.Clock
import java.time.Instant
import net.kigawa.kivra.core.RunId
import net.kigawa.kivra.policy.ApprovalConsumer
import net.kigawa.kivra.policy.OperationDigest
import net.kigawa.kivra.policy.Principal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Proof that a human was authenticated by a trusted identity provider (for example an OIDC ID token whose signature,
 * `iss`, `aud` and `exp` were checked). It is the only identity accepted by [ApprovalRepository.approve] / [reject].
 *
 * There is deliberately no factory that takes a plain subject string, and the constructor is private: a value can only
 * be created by [issue], which must be called from a [HumanAssertionVerifier] implementation after cryptographic
 * verification. Agent / Orchestrator tokens, tool inputs and the Gateway never reference that path, so an Agent cannot
 * approve by naming a human subject. Note this is an in-process boundary; the integrity of a human's identity still
 * rests on the verifier that produces it (the OIDC verifier adapter is not part of this change).
 */
class VerifiedHuman private constructor(val subject: String, val issuer: String, val expiresAt: Instant) {
    /** The assertion must still be valid when it is used to decide an approval. */
    fun isValidAt(now: Instant): Boolean = now.isBefore(expiresAt)

    companion object {
        /** Trusted verifiers only: call after the credential has been verified. */
        internal fun issue(subject: String, issuer: String, expiresAt: Instant): VerifiedHuman {
            require(subject.isNotBlank() && subject.length <= 128 && subject.none { it.isISOControl() }) { "invalid subject" }
            require(issuer.isNotBlank() && issuer.length <= 255) { "invalid issuer" }
            return VerifiedHuman(subject, issuer, expiresAt)
        }
    }
}

/**
 * Port for the authentication adapter: turns a raw human credential (e.g. a bearer ID token presented to the approval
 * API) into a [VerifiedHuman], or null when it is invalid, expired or not from a trusted issuer.
 */
fun interface HumanAssertionVerifier {
    fun verify(credential: String): VerifiedHuman?
}

/**
 * Approval Gate persistence (#33). Approvals are bound to an [OperationDigest], expire on the DB clock and can be
 * consumed exactly once. Every state change is one conditional UPDATE (status + expiry + digest in the WHERE), so
 * concurrent consumers race on the row and exactly one wins.
 *
 * Every state change and its audit event are written in ONE transaction under the run row lock: if the audit row cannot
 * be appended the state change is rolled back too (fail-closed). For [consume] that means no approval is spent without
 * a durable record.
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

    /** PENDING -> APPROVED. False when not pending, expired, the assertion is stale, or the approver is the requester. */
    fun approve(approvalId: String, approver: VerifiedHuman): Boolean = decide(approvalId, approver, "APPROVED", null)

    /** PENDING/APPROVED -> REJECTED. A rejected approval can never be consumed. */
    fun reject(approvalId: String, approver: VerifiedHuman, reason: String? = null): Boolean = decide(approvalId, approver, "REJECTED", reason)

    private fun decide(approvalId: String, approver: VerifiedHuman, target: String, reason: String?): Boolean {
        if (!approver.isValidAt(clock.instant())) return false
        return transaction(database) {
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
                resource = current.resource, approvalId = approvalId,
                payload = buildJsonObject { put("digest", current.digest); put("issuer", approver.issuer) }.toString(),
            ))
            true
        }
    }

    /**
     * Atomically consumes an APPROVED, unexpired approval whose digest equals [digest], and writes an `approval.consume`
     * audit event in the same transaction. Refused (false) when the decision [decisionId] was already closed as ABANDONED,
     * so a handler can never start for a decision the reaper has abandoned. Exactly one concurrent caller gets true.
     */
    override fun consume(approvalId: String, digest: OperationDigest, decisionId: String): Boolean = try {
        transaction(database) {
            val row = execQuery(
                "SELECT run_id, resource FROM approvals WHERE approval_id = ?", approvalId,
            ) { it.getString(1) to it.getString(2) }.firstOrNull() ?: return@transaction false
            val (runId, resource) = row
            lockRun(runId) ?: return@transaction false
            val abandoned = execQuery(
                "SELECT COUNT(*) FROM audit_events WHERE decision_id = ? AND result = 'ABANDONED'", decisionId,
            ) { it.getInt(1) }.single() > 0
            if (abandoned) return@transaction false
            val n = execUpdate(
                """
                UPDATE approvals SET status = 'CONSUMED', consumed_at = ${DbTime.NOW}
                 WHERE approval_id = ? AND status = 'APPROVED' AND digest = ? AND expires_at > ${DbTime.NOW}
                   AND decided_by IS NOT NULL AND decided_by <> requester_agent_id
                """.trimIndent(),
                approvalId, digest.hex,
            )
            if (n != 1) return@transaction false
            // Same transaction: if this record cannot be appended, the consumption above is rolled back (fail-closed).
            audit.append(this, RunId(runId), AuditEntry(
                actor = "gateway", action = "approval.consume", result = "CONSUMED", resource = resource,
                decisionId = decisionId, approvalId = approvalId, payload = """{"digest":"${digest.hex}"}""",
            ))
            true
        }
    } catch (e: Exception) { false }

    /**
     * Marks PENDING/APPROVED approvals past their expiry as EXPIRED and writes one `approval.expire` audit event per row,
     * in the same transaction as the update. If any audit append fails the whole sweep is rolled back and throws.
     * Runs are locked in run_id order so concurrent sweepers cannot deadlock.
     */
    fun expireDue(): Int = transaction(database) {
        val due = execQuery(
            "SELECT approval_id, run_id, resource FROM approvals WHERE status IN ('PENDING','APPROVED') AND expires_at <= ${DbTime.NOW} ORDER BY run_id, approval_id",
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        var expired = 0
        for ((approvalId, runId, resource) in due) {
            lockRun(runId) ?: continue
            val n = execUpdate(
                "UPDATE approvals SET status = 'EXPIRED' WHERE approval_id = ? AND status IN ('PENDING','APPROVED') AND expires_at <= ${DbTime.NOW}",
                approvalId,
            )
            if (n != 1) continue
            audit.append(this, RunId(runId), AuditEntry(
                actor = "system:approval-gate", action = "approval.expire", result = "EXPIRED", resource = resource,
                approvalId = approvalId,
            ))
            expired++
        }
        expired
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
