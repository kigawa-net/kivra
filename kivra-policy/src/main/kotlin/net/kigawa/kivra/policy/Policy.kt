package net.kigawa.kivra.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.time.Clock
import java.time.Instant

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

/** Report of a loaded policy: hash and counts only. Rule contents (resource patterns etc.) are deliberately not included. */
data class PolicyDescriptor(
    val version: String,
    val policyHash: String,
    val ruleCount: Int,
    val loadedAt: Instant,
)

/**
 * Static policy engine. Deny by default and fail-closed: anything not explicitly allowed, or that cannot
 * be evaluated, is denied. Rule files are declared as JSON; see [parse] / [loadOrDenyAll].
 *
 * The rules are copied on construction and exposed only as a read-only list. [policyHash] is computed once at
 * load time (ADR 0007). Policy changes take effect only by restarting with a new policy (no hot reload).
 */
class PolicyEngine private constructor(rules: List<PolicyRule>, private val loadError: String?, private val loadedAt: Instant) {
    private val rules: List<PolicyRule> = rules.map { it.copy(roles = it.roles.toList()) }

    /** Canonical SHA-256 (hex) of the rule set, fixed at load time. Independent of rule order and JSON formatting. */
    val policyHash: String = PolicyHash.of(this.rules)

    /** True when the rules still hash to [expectedHash]. Used by [ToolGateway] on every call (fail-closed on mismatch). */
    fun verifyIntegrity(expectedHash: String): Boolean = PolicyHash.of(rules) == expectedHash

    fun describe(): PolicyDescriptor = PolicyDescriptor(POLICY_HASH_VERSION, policyHash, rules.size, loadedAt)

    /**
     * Logs one INFO line with the first 12 hex digits of the hash and the rule count (never the rule contents).
     * Intended to be called once at startup, right after loading. Not wired into the application yet, because the
     * Policy Gateway is not in production.
     */
    fun logLoaded(logger: Logger) {
        logger.info("policy loaded: hash={} rules={}", policyHash.take(12), rules.size)
    }

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
        fun parse(text: String, clock: Clock = Clock.systemUTC()): PolicyEngine {
            val doc = try {
                json.decodeFromString(PolicyDocument.serializer(), text)
            } catch (e: Exception) {
                throw PolicyLoadException("invalid policy document: ${e.message}", e)
            }
            if (doc.version != 1) throw PolicyLoadException("unsupported policy version ${doc.version}")
            return fromRules(doc.rules, clock)
        }

        /** Strict construction from already-decoded rules. The rules are copied, so later changes by the caller have no effect. */
        fun fromRules(rules: List<PolicyRule>, clock: Clock = Clock.systemUTC()): PolicyEngine {
            for (r in rules) {
                if (r.action.isBlank() || r.resource.isBlank()) throw PolicyLoadException("rule with blank action/resource")
                if (r.roles.isEmpty()) throw PolicyLoadException("rule for '${r.action}' has no roles")
                if (r.risk != RiskClass.FORBIDDEN && FORBIDDEN_ACTION_PATTERNS.any { overlaps(it, r.action) }) {
                    throw PolicyLoadException("rule for '${r.action}' would allow a forbidden action")
                }
            }
            return PolicyEngine(rules, null, clock.instant())
        }

        /** Fail-closed loader: invalid policy yields an engine that denies everything. */
        fun loadOrDenyAll(text: String, clock: Clock = Clock.systemUTC()): PolicyEngine =
            try { parse(text, clock) } catch (e: PolicyLoadException) { PolicyEngine(emptyList(), e.message ?: "invalid policy", clock.instant()) }

        fun denyAll(reason: String = "no policy loaded", clock: Clock = Clock.systemUTC()): PolicyEngine =
            PolicyEngine(emptyList(), reason, clock.instant())

        // Conservative overlap: a rule pattern overlaps a forbidden pattern if either matches the other's literal prefix.
        private fun overlaps(forbidden: String, ruleAction: String): Boolean {
            val fp = forbidden.substringBefore('*'); val rp = ruleAction.substringBefore('*')
            return fp.startsWith(rp) || rp.startsWith(fp) && (forbidden.contains('*') || fp == rp)
        }
    }
}
