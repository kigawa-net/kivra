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
