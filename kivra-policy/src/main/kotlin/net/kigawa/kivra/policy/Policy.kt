package net.kigawa.kivra.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PolicyLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Actions no rule may ever allow, even with approval (design #1 section 6.3 Forbidden). */
internal val FORBIDDEN_ACTION_PATTERNS = listOf(
    "policy.*",        // policy changes
    "safety.*",        // disabling safety controls
    "privilege.*",     // self privilege escalation / production promotion of self
    "approval.grant", "approval.issue", "approval.approve", // approval self-issue
    "audit.*",         // audit log tampering
)

@Serializable
data class PolicyRule(
    val action: String,
    val resource: String = "*",
    val roles: List<Role>,
    val risk: RiskClass,
)

@Serializable
data class PolicyDocument(val version: Int = 1, val rules: List<PolicyRule>)

/**
 * Static policy engine. Deny by default and fail-closed: anything not explicitly allowed, or that cannot
 * be evaluated, is denied. Rule files are declared as JSON; see [parse] / [loadOrDenyAll].
 */
class PolicyEngine private constructor(private val rules: List<PolicyRule>, private val loadError: String?) {

    fun evaluate(principal: Principal, action: String, resource: String): Decision {
        if (loadError != null) return Decision.Deny("policy unavailable (fail-closed): $loadError")
        if (action.isBlank() || resource.isBlank()) return Decision.Deny("malformed request")
        if (FORBIDDEN_ACTION_PATTERNS.any { globMatches(it, action) }) {
            return Decision.Deny("forbidden action '$action': cannot be allowed, even with approval")
        }
        if (principal.scopes.none { globMatches(it, action) }) {
            return Decision.Deny("action '$action' is outside the token scope")
        }
        val matched = rules.filter {
            principal.role in it.roles && globMatches(it.action, action) && globMatches(it.resource, resource)
        }
        if (matched.isEmpty()) return Decision.Deny("no rule allows '$action' on '$resource' for ${principal.role} (deny by default)")
        // Most restrictive matching rule wins.
        val risk = matched.maxOf { it.risk }
        return when (risk) {
            RiskClass.FORBIDDEN -> Decision.Deny("'$action' on '$resource' is classified Forbidden")
            RiskClass.HIGH -> Decision.RequireApproval(risk, "'$action' on '$resource' is High risk: human approval required")
            RiskClass.LOW, RiskClass.MEDIUM -> Decision.Allow(risk, "allowed by static rule ($risk)")
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /** Strict parse. Throws [PolicyLoadException] on any invalid content. */
        fun parse(text: String): PolicyEngine {
            val doc = try {
                json.decodeFromString(PolicyDocument.serializer(), text)
            } catch (e: Exception) {
                throw PolicyLoadException("invalid policy document: ${e.message}", e)
            }
            if (doc.version != 1) throw PolicyLoadException("unsupported policy version ${doc.version}")
            for (r in doc.rules) {
                if (r.action.isBlank() || r.resource.isBlank()) throw PolicyLoadException("rule with blank action/resource")
                if (r.roles.isEmpty()) throw PolicyLoadException("rule for '${r.action}' has no roles")
                if (r.risk != RiskClass.FORBIDDEN && FORBIDDEN_ACTION_PATTERNS.any { overlaps(it, r.action) }) {
                    throw PolicyLoadException("rule for '${r.action}' would allow a forbidden action")
                }
            }
            return PolicyEngine(doc.rules, null)
        }

        /** Fail-closed loader: invalid policy yields an engine that denies everything. */
        fun loadOrDenyAll(text: String): PolicyEngine =
            try { parse(text) } catch (e: PolicyLoadException) { PolicyEngine(emptyList(), e.message ?: "invalid policy") }

        fun denyAll(reason: String = "no policy loaded"): PolicyEngine = PolicyEngine(emptyList(), reason)

        // Conservative overlap: a rule pattern overlaps a forbidden pattern if either matches the other's literal prefix.
        private fun overlaps(forbidden: String, ruleAction: String): Boolean {
            val fp = forbidden.substringBefore('*'); val rp = ruleAction.substringBefore('*')
            return fp.startsWith(rp) || rp.startsWith(fp) && (forbidden.contains('*') || fp == rp)
        }
    }
}
