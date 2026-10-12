package net.kigawa.kivra.policy

import java.util.UUID

/** Verifies a short-lived Run/Agent/scope-bound token. Returns null when invalid or expired. */
fun interface TokenVerifier { fun verify(token: String): Principal? }

/** Checks the Run and Agent are still active. Port so this module does not depend on the Run state machine. */
fun interface RunAgentStatus { fun isActive(runId: String, agentId: String): Boolean }

/**
 * Port to #33's Approval service: atomically validates and consumes an approval for this digest.
 * [decisionId] is the decision whose INTENT_ALLOWED record was written; an implementation must refuse (false) when that
 * decision has already been closed as ABANDONED by the orphan reaper, so the handler can never start for it.
 */
fun interface ApprovalConsumer { fun consume(approvalId: String, digest: OperationDigest, decisionId: String): Boolean }

/**
 * Port to #33's Audit log / decision journal. Returns true only when the record is DURABLY stored.
 * Implementations must not swallow failures: return false (or throw) when the record is not persisted.
 */
fun interface AuditSink { fun record(event: AuditRecord): Boolean }

enum class AuditOutcome {
    /** Written BEFORE any side effect (and before approval consumption): the durable decision intent. */
    INTENT_ALLOWED,
    DENIED,
    APPROVAL_REQUIRED,
    /** Handler returned normally. */
    EXECUTED,
    /** Handler reported a definite failure without side effect. */
    FAILED,
    /** Side effect may have happened; needs reconciliation (UNKNOWN -> RECONCILIATION_REQUIRED). */
    UNKNOWN,
}

data class AuditRecord(
    val decisionId: String,
    val runId: String?,
    val agentId: String?,
    val action: String,
    /** Canonical resource when decoded, otherwise the (untrusted) hint. */
    val resource: String,
    val outcome: AuditOutcome,
    val reason: String,
    val approvalId: String?,
    val digest: String? = null,
)

/** Context handed to handlers. Credentials are resolved by the handler/gateway side; Agents never hold them. */
data class ToolContext(val principal: Principal, val decisionId: String, val approvalId: String?)

/** Executes the canonical command. It never sees the raw request, so it cannot act on a target that was not authorized. */
fun interface ToolHandler { fun handle(context: ToolContext, command: CanonicalCommand): String }

/** One registered action: trusted decoder plus handler. */
class ToolBinding(val decoder: CommandDecoder, val handler: ToolHandler)

/**
 * Single mediation point for Agent external operations (design #1 section 6.2).
 * Only calls allowed by [PolicyEngine] for the CANONICAL target reach a registered [ToolHandler].
 *
 * Order: token -> run/agent active -> binding exists -> decode canonical command -> hint must equal canonical
 * resource -> policy -> (approval present?) -> durable INTENT record (fail-closed) -> consume approval -> execute
 * -> record result. Nothing irreversible (approval consumption, side effect) happens before the intent is durable
 * and before executability is verified.
 */
class ToolGateway(
    private val verifier: TokenVerifier,
    private val status: RunAgentStatus,
    private val engine: PolicyEngine,
    private val approvals: ApprovalConsumer,
    private val audit: AuditSink,
    bindings: Map<String, ToolBinding>,
) {
    // Exact action names only: an unregistered action cannot execute even if a rule allows it.
    private val bindings = bindings.toMap()

    fun call(request: ToolRequest): ToolResult {
        val decisionId = UUID.randomUUID().toString()
        var principal: Principal? = null
        var resource = request.resource
        var digest: String? = null
        fun rec(o: AuditOutcome, reason: String): Boolean =
            try { audit.record(AuditRecord(decisionId, principal?.runId, principal?.agentId, request.action, resource, o, reason, request.approvalId, digest)) }
            catch (e: Exception) { false }
        // A denial stays a denial even if its audit record cannot be written (nothing executes).
        fun deny(reason: String): ToolResult { rec(AuditOutcome.DENIED, reason); return ToolResult.Denied(reason, decisionId) }

        principal = try { verifier.verify(request.token) } catch (e: Exception) { null }
            ?: return deny("invalid or expired token")
        val active = try { status.isActive(principal.runId, principal.agentId) } catch (e: Exception) { false }
        if (!active) return deny("run or agent is not active (fail-closed)")

        val binding = bindings[request.action] ?: return deny("no handler registered for '${request.action}'")
        val command = try { binding.decoder.decode(request) } catch (e: Exception) { null }
            ?: return deny("request params could not be decoded to a canonical target (fail-closed)")
        if (command.action != request.action) return deny("decoded action does not match the requested action")
        // request.resource is only a hint: it must match the real target exactly.
        if (request.resource != command.resource) return deny("declared resource does not match the canonical target")
        resource = command.resource

        val decision = try { engine.evaluate(principal, command.action, command.resource) }
        catch (e: Exception) { Decision.Deny("policy evaluation failed (fail-closed)") }

        val opDigest = OperationDigest.of(principal, command)
        val needsApproval = when (decision) {
            is Decision.Deny -> return deny(decision.reason)
            is Decision.RequireApproval -> {
                if (request.approvalId == null) {
                    digest = opDigest.hex
                    rec(AuditOutcome.APPROVAL_REQUIRED, decision.reason)
                    return ToolResult.ApprovalRequired(opDigest, decision.risk, decision.reason, decisionId)
                }
                true
            }
            is Decision.Allow -> false
        }
        digest = opDigest.hex

        // Durable decision intent BEFORE any side effect or approval consumption; fail-closed when it cannot be stored.
        if (!rec(AuditOutcome.INTENT_ALLOWED, decision.reason)) {
            return ToolResult.Denied("audit unavailable: decision intent could not be durably recorded (fail-closed)", decisionId)
        }

        if (needsApproval) {
            val id = request.approvalId!!
            val ok = try { approvals.consume(id, opDigest, decisionId) } catch (e: Exception) { false }
            if (!ok) return deny("approval '$id' is not valid for this exact operation")
        }

        val out = try {
            binding.handler.handle(ToolContext(principal, decisionId, request.approvalId), command)
        } catch (e: ToolFailure) {
            rec(AuditOutcome.FAILED, "handler failed without side effect")
            return ToolResult.Failed("tool execution failed")
        } catch (e: Exception) {
            // Past the point of no return: the effect may have been applied.
            rec(AuditOutcome.UNKNOWN, "handler error after execution started; reconciliation required")
            return ToolResult.Unknown("tool outcome unknown; reconciliation required", decisionId)
        }
        if (!rec(AuditOutcome.EXECUTED, decision.reason)) {
            return ToolResult.Unknown("tool executed but its result could not be recorded; reconciliation required", decisionId)
        }
        return ToolResult.Success(out)
    }
}
