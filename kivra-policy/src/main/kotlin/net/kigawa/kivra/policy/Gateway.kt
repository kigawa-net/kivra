package net.kigawa.kivra.policy

import java.util.UUID

/** Verifies a short-lived Run/Agent/scope-bound token. Returns null when invalid or expired. */
fun interface TokenVerifier { fun verify(token: String): Principal? }

/** Checks the Run and Agent are still active. Port so this module does not depend on the Run state machine. */
fun interface RunAgentStatus { fun isActive(runId: String, agentId: String): Boolean }

/** Port to #33's Approval service: atomically validates and consumes an approval for this digest. */
fun interface ApprovalConsumer { fun consume(approvalId: String, digest: OperationDigest): Boolean }

/** Port to #33's Audit log. Implemented there; secrets must not be placed in the record. */
fun interface AuditSink { fun record(event: AuditRecord) }

data class AuditRecord(
    val decisionId: String,
    val runId: String?,
    val agentId: String?,
    val action: String,
    val resource: String,
    val outcome: String, // ALLOWED_EXECUTED | DENIED | APPROVAL_REQUIRED | FAILED
    val reason: String,
    val approvalId: String?,
)

/** Context handed to handlers. Credentials are resolved by the handler/gateway side; Agents never hold them. */
data class ToolContext(val principal: Principal, val decisionId: String, val approvalId: String?)

fun interface ToolHandler { fun handle(context: ToolContext, request: ToolRequest): String }

/**
 * Single mediation point for Agent external operations (design #1 section 6.2).
 * Only calls allowed by [PolicyEngine] reach a registered [ToolHandler]; everything is audited.
 */
class ToolGateway(
    private val verifier: TokenVerifier,
    private val status: RunAgentStatus,
    private val engine: PolicyEngine,
    private val approvals: ApprovalConsumer,
    private val audit: AuditSink,
    handlers: Map<String, ToolHandler>,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    // Exact action names only: an unregistered action cannot execute even if a rule allows it.
    private val handlers = handlers.toMap()

    fun call(request: ToolRequest): ToolResult {
        val decisionId = UUID.randomUUID().toString()
        fun deny(p: Principal?, reason: String): ToolResult {
            safeAudit(AuditRecord(decisionId, p?.runId, p?.agentId, request.action, request.resource, "DENIED", reason, request.approvalId))
            return ToolResult.Denied(reason, decisionId)
        }
        val principal = try { verifier.verify(request.token) } catch (e: Exception) { null }
            ?: return deny(null, "invalid or expired token")
        val active = try { status.isActive(principal.runId, principal.agentId) } catch (e: Exception) { false }
        if (!active) return deny(principal, "run or agent is not active (fail-closed)")

        val decision = try { engine.evaluate(principal, request.action, request.resource) }
        catch (e: Exception) { Decision.Deny("policy evaluation failed (fail-closed)") }

        when (decision) {
            is Decision.Deny -> return deny(principal, decision.reason)
            is Decision.RequireApproval -> {
                val digest = OperationDigest.of(principal, request)
                val id = request.approvalId
                if (id == null) {
                    safeAudit(AuditRecord(decisionId, principal.runId, principal.agentId, request.action, request.resource, "APPROVAL_REQUIRED", decision.reason, null))
                    return ToolResult.ApprovalRequired(digest, decision.risk, decision.reason, decisionId)
                }
                val ok = try { approvals.consume(id, digest) } catch (e: Exception) { false }
                if (!ok) return deny(principal, "approval '$id' is not valid for this exact operation")
            }
            is Decision.Allow -> Unit
        }

        val handler = handlers[request.action] ?: return deny(principal, "no handler registered for '${request.action}'")
        return try {
            val out = handler.handle(ToolContext(principal, decisionId, request.approvalId), request)
            safeAudit(AuditRecord(decisionId, principal.runId, principal.agentId, request.action, request.resource, "ALLOWED_EXECUTED", decision.reason, request.approvalId))
            ToolResult.Success(out)
        } catch (e: Exception) {
            safeAudit(AuditRecord(decisionId, principal.runId, principal.agentId, request.action, request.resource, "FAILED", "handler error", request.approvalId))
            ToolResult.Failed("tool execution failed")
        }
    }

    // Audit failure must not turn into silent success of a denial; for executed calls it is best-effort here
    // (durable fail-closed audit semantics belong to #33).
    private fun safeAudit(e: AuditRecord) { try { audit.record(e) } catch (_: Exception) { } }
}
