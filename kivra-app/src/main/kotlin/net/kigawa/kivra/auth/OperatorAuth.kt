package net.kigawa.kivra.auth

import io.ktor.http.Headers

/** A client role as `<client>:<role>` (for example `kivra-agent:agent`). */
data class RequiredRole(val client: String, val role: String) {
    companion object {
        /** Parses `<client>:<role>`. Both parts must be non-blank. Throws with a message that names no value. */
        fun parse(raw: String, variable: String): RequiredRole {
            val parts = raw.split(":")
            require(parts.size == 2 && parts.all { it.isNotBlank() }) { "$variable must be <client>:<role>" }
            return RequiredRole(parts[0].trim(), parts[1].trim())
        }
    }

    override fun toString(): String = "RequiredRole(***)"
}

/**
 * The result of authenticating an operator request on the self-development autopilot routes (ADR 0011 section 1). Only
 * [Authenticated] carries a principal. [AgentRefused] is a valid operator token that also carries the agent role: the agent
 * principal is refused with 403, the same rule as `AgentIdentity` (an agent is never an operator).
 */
sealed interface OperatorAuth {
    data class Authenticated(val principal: String) : OperatorAuth
    data object Unauthenticated : OperatorAuth
    data object AgentRefused : OperatorAuth
}

/** Authenticates an operator request from its headers. [OidcJwtVerifier] is the production implementation. */
interface OperatorAuthenticator {
    suspend fun authenticate(headers: Headers): OperatorAuth
}

/**
 * The result of authenticating a member request ([OidcJwtVerifier.authenticateMember]): an operator or a member-only token. Only
 * [Authenticated] carries a principal. Authorization is a separate decision.
 */
sealed interface MemberAuth {
    data class Authenticated(val principal: String, val kind: MemberKind) : MemberAuth
    data object Unauthenticated : MemberAuth
    data object AgentRefused : MemberAuth
}

/** Whether `resource_access.<client>.roles` lists the role. Shared by every verifier, so the rule cannot drift. */
internal fun hasRole(resourceAccess: Map<String, Any?>?, role: RequiredRole): Boolean {
    val client = resourceAccess?.get(role.client) as? Map<*, *> ?: return false
    val roles = client["roles"] as? List<*> ?: return false
    return role.role in roles
}

/** The role kind of a token's `resource_access`: the operator role wins, then the optional member role, else none. */
internal fun memberKindOf(resourceAccess: Map<String, Any?>?, config: OidcJwtConfig): MemberKind = when {
    hasRole(resourceAccess, RequiredRole(config.requiredClient, config.requiredRole)) -> MemberKind.LEGACY_ADMIN
    config.memberRole != null && hasRole(resourceAccess, config.memberRole) -> MemberKind.MEMBER_ONLY
    else -> MemberKind.NONE
}
