package net.kigawa.kivra.policy

import java.security.MessageDigest

/**
 * Binds an approval to one exact operation (design #1 section 6.3): run, agent, action, resource and params.
 * Any change to the operation changes the digest, so an approval cannot be reused for a different call.
 */
@JvmInline
value class OperationDigest(val hex: String) {
    companion object {
        fun of(principal: Principal, request: ToolRequest): OperationDigest {
            val sb = StringBuilder()
            fun field(v: String) { sb.append(v.length).append(':').append(v).append('\n') }
            field(principal.runId); field(principal.agentId)
            field(request.action); field(request.resource)
            request.params.toSortedMap().forEach { (k, v) -> field(k); field(v) }
            val bytes = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
            return OperationDigest(bytes.joinToString("") { "%02x".format(it) })
        }
    }
}

/**
 * In-memory shape of an approval bound to a digest (no persistence; #33 owns storage).
 * Valid only for the same digest, before [expiresAt], once, and when the approver is not the requester (four-eyes).
 */
data class ApprovalBinding(
    val approvalId: String,
    val digest: OperationDigest,
    val requesterAgentId: String,
    val approverId: String,
    val expiresAtEpochSec: Long,
    val used: Boolean = false,
) {
    fun isValidFor(digest: OperationDigest, nowEpochSec: Long): Boolean =
        !used && this.digest == digest && nowEpochSec < expiresAtEpochSec && approverId != requesterAgentId
}
