package net.kigawa.kivra.policy

import java.security.MessageDigest

/**
 * Binds an approval to one exact operation (design #1 section 6.3): run, agent, action and the canonical target + args
 * (a [CanonicalCommand], never the raw self-declared request).
 * Any change to the operation changes the digest, so an approval cannot be reused for a different call.
 * The digest also binds the [policyHash] the operation was evaluated under (ADR 0007, TOCTOU): an approval granted
 * under one Policy does not match the same operation under another Policy.
 */
@JvmInline
value class OperationDigest(val hex: String) {
    companion object {
        /** Domain prefix and version of the operation digest encoding (v2 adds the policy hash; v1 is not accepted). */
        const val VERSION = "kivra-op-digest-v2"

        fun of(principal: Principal, command: CanonicalCommand, policyHash: String): OperationDigest {
            require(POLICY_HASH_PATTERN.matches(policyHash)) { "policy hash must be 64 lowercase hex characters" }
            val w = FieldWriter("$VERSION\n").field(policyHash)
            w.field(principal.runId).field(principal.agentId)
            w.field(command.action).field(command.resource)
            command.args.toSortedMap().forEach { (k, v) -> w.field(k).field(v) }
            return OperationDigest(w.sha256Hex())
        }
    }
}

/** Length-prefixed, unambiguous encoding of fields, hashed with SHA-256 (shared by [OperationDigest] and [PolicyHash]). */
internal class FieldWriter(prefix: String = "") {
    private val sb = StringBuilder(prefix)
    fun field(v: String): FieldWriter {
        sb.append(v.length).append(':').append(v).append('\n')
        return this
    }
    fun encoded(): String = sb.toString()
    fun sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
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

/** Domain prefix and version of the policy hash encoding. Also reported as [PolicyDescriptor.version]. */
const val POLICY_HASH_VERSION = "kivra-policy-v1"

/** Shape of a SHA-256 hex digest (the policy hash and the operation digest). */
internal val POLICY_HASH_PATTERN = Regex("[0-9a-f]{64}")

/**
 * Canonical SHA-256 of a rule set (ADR 0007: the policy hash fixed at load time).
 * Each rule is encoded field by field (roles sorted), the per-rule encodings are sorted, so the record order, JSON
 * whitespace and role order do not matter, while any change of action, resource, roles or risk does.
 */
internal object PolicyHash {
    fun of(rules: List<PolicyRule>): String {
        val ruleEncodings = rules.map { r ->
            val w = FieldWriter()
            w.field("action").field(r.action)
            w.field("resource").field(r.resource)
            w.field("risk").field(r.risk.name)
            w.field("roles").field(r.roles.size.toString())
            r.roles.map { it.name }.sorted().forEach { w.field(it) }
            w.encoded()
        }.sorted()
        val w = FieldWriter("$POLICY_HASH_VERSION\n").field(ruleEncodings.size.toString())
        ruleEncodings.forEach { w.field(it) }
        return w.sha256Hex()
    }
}
