package net.kigawa.kivra.mcp

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kigawa.kivra.auth.OidcJwtConfig
import net.kigawa.kivra.auth.OidcJwtVerifier
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.auth.oidcOwnerKey
import org.slf4j.LoggerFactory

/** The result of authenticating an MCP request. Only [Authenticated] carries a principal and scopes. */
sealed interface McpAuth {
    data class Authenticated(val principal: String, val scopes: Set<String>) : McpAuth

    /** No usable Bearer token, a wrong signature, issuer, audience, expiry, role or subject. Answered with 401. */
    data object Unauthenticated : McpAuth

    /** A valid token that carries the agent role. Answered with 403 (an agent is never an MCP caller). */
    data object AgentRefused : McpAuth
}

/**
 * Verifies the access token of an MCP request (ADR 0013 section 4). The checks are the REST verifier's ([OidcJwtVerifier]: RS256 only,
 * signature from the JWKS, `iss`, `exp`, `nbf`, the required operator role, the owner key from `iss` and `sub`) with two differences:
 *
 * - `aud` must contain the MCP audience. [config] carries it (`KIVRA_MCP_AUDIENCE`), so a token for the REST API (`kivra-dev`) or for an
 *   agent (`kivra-agent`) is rejected with 401.
 * - The Bearer token is read from `Authorization` only. The `X-Forwarded-Access-Token` fallback of the REST API is not used here: an MCP
 *   client sends a Bearer token, and a forwarded header is never a credential for `/mcp`.
 *
 * The `scope` claim (space-separated) is returned for the per-tool check. Failures are logged by a fixed reason only, never the token or a
 * claim value.
 */
class McpJwtVerifier(
    private val config: OidcJwtConfig,
    private val jwks: JwkProvider,
    private val agentRole: RequiredRole? = null,
) {
    private val logger = LoggerFactory.getLogger(McpJwtVerifier::class.java)

    suspend fun authenticate(headers: Headers): McpAuth {
        val token = bearerToken(headers)
        if (token == null) {
            logger.info("MCP request rejected: reason=NoBearerToken")
            return McpAuth.Unauthenticated
        }
        // JWKS may be fetched over the network, so keep the blocking work off the Netty event loop.
        return withContext(Dispatchers.IO) {
            try {
                verify(token)
            } catch (e: Exception) {
                logger.info("MCP request rejected: reason={}", e.javaClass.simpleName)
                McpAuth.Unauthenticated
            }
        }
    }

    internal fun verify(token: String): McpAuth {
        val decoded = JWT.decode(token)
        if (decoded.algorithm != "RS256") return reject("AlgorithmNotRs256")
        val key = jwks.get(decoded.keyId).publicKey as? RSAPublicKey ?: return reject("SigningKeyNotRsa")
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .acceptLeeway(OidcJwtVerifier.LEEWAY_SECONDS)
            .build()
            .verify(token)
        if (verified.expiresAt == null) return reject("MissingExp")
        val resourceAccess = verified.getClaim("resource_access").asMap()
        if (agentRole != null && hasRole(resourceAccess, agentRole)) {
            logger.info("MCP request refused: reason=AgentRoleRefused")
            return McpAuth.AgentRefused
        }
        if (!hasRole(resourceAccess, RequiredRole(config.requiredClient, config.requiredRole))) return reject("RequiredRoleMissing")
        val subject = verified.subject ?: return reject("MissingSubject")
        val principal = oidcOwnerKey(config.issuer, subject) ?: return reject("InvalidSubject")
        return McpAuth.Authenticated(principal, scopesOf(verified.getClaim("scope").asString()))
    }

    private fun reject(reason: String): McpAuth {
        logger.info("MCP request rejected: reason={}", reason)
        return McpAuth.Unauthenticated
    }

    private fun hasRole(resourceAccess: Map<String, Any?>?, role: RequiredRole): Boolean {
        val client = resourceAccess?.get(role.client) as? Map<*, *> ?: return false
        val roles = client["roles"] as? List<*> ?: return false
        return role.role in roles
    }

    companion object {
        /** Upper bound on the token length, checked before parsing. */
        const val MAX_TOKEN_LENGTH = 16 * 1024

        /** The `scope` claim as a set. Empty when the claim is absent. */
        internal fun scopesOf(scopeClaim: String?): Set<String> =
            scopeClaim?.split(' ', '\t')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        /** The access token from `Authorization: Bearer <token>` only. Null when missing, not Bearer, too long, or not JWT-shaped. */
        internal fun bearerToken(headers: Headers): String? {
            val authorization = headers[HttpHeaders.Authorization]?.trim() ?: return null
            if (!authorization.startsWith("Bearer ", ignoreCase = true)) return null
            val token = authorization.substring("Bearer ".length).trim()
            if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) return null
            return token.takeIf { it.count { c -> c == '.' } == 2 }
        }
    }
}
