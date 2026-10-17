package net.kigawa.kivra.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Settings of the agent API identity (`the agent API paths`, ADR 0010 addendum). They are kept apart from the operator's
 * [OidcJwtConfig]: a different required role, a different client (`azp`), and only the `Authorization: Bearer` header.
 *
 * - [issuer], [jwksUrl], [audience]: the shared `KIVRA_OIDC_ISSUER`, `KIVRA_OIDC_JWKS_URL`, `KIVRA_OIDC_AUDIENCE`. The token's `aud`
 *   must contain [audience].
 * - [azp]: `KIVRA_AGENT_AZP`, the authorized party. It must equal the token's `azp` exactly.
 * - [requiredClient] / [requiredRole]: `KIVRA_AGENT_REQUIRED_ROLE` (`<client>:<role>`), listed in `resource_access.<client>.roles`.
 *
 * [toString] shows no value.
 */
class AgentAuthConfig(
    val issuer: String,
    val jwksUrl: String,
    val audience: String,
    val azp: String,
    val requiredClient: String,
    val requiredRole: String,
) {
    override fun toString(): String = "AgentAuthConfig(***)"
}

/** Result of authenticating one agent request. Only [Authenticated] carries an identity. */
sealed interface AgentAuth {
    /** [agentId] is the token's `sub`. It comes from the verified token only, never from the request. */
    data class Authenticated(val agentId: String) : AgentAuth

    /** No usable Bearer token, or the token is not valid (signature, issuer, audience, expiry, not-before, algorithm, subject). */
    data object Unauthenticated : AgentAuth

    /** The token is valid, but it is not an agent token (`azp` or the required role is missing or different). */
    data object Forbidden : AgentAuth
}

/**
 * Authenticates an agent request (PR 4a). It is a separate verifier from [OidcJwtVerifier] and does not share its header handling:
 *
 * - The token comes from `Authorization: Bearer <JWT>` only. `X-Forwarded-Access-Token`, cookies and every other header are ignored.
 *   A request with two or more `Authorization` headers is unauthenticated. A logged-in browser's oauth2-proxy headers are never trusted.
 * - Unauthenticated (401): no Bearer token, bad signature, algorithm other than RS256, unknown key, wrong issuer or audience, missing
 *   or expired `exp`, `nbf` in the future, or a `sub` that is not an agent identifier.
 * - Forbidden (403): the token verifies, but `azp` differs from [AgentAuthConfig.azp], or `resource_access.<client>.roles` does not
 *   contain [AgentAuthConfig.requiredRole]. An operator token (`admin-panel:admin`) has no agent role, so it is refused here.
 *
 * The reason is logged by a fixed name only (rate limited per reason). Never the token, a claim value, `sub` or the exception message.
 */
class AgentIdentity(
    private val config: AgentAuthConfig,
    private val jwks: JwkProvider,
) {
    private val logger = LoggerFactory.getLogger(AgentIdentity::class.java)
    private val lastRejectionLog = ConcurrentHashMap<String, Long>()

    suspend fun authenticate(headers: Headers): AgentAuth {
        val token = bearerToken(headers) ?: return reject("NoBearerToken", AgentAuth.Unauthenticated)
        // JWKS may be fetched over the network, so keep the blocking work off the Netty event loop.
        return withContext(Dispatchers.IO) {
            try {
                verify(token)
            } catch (e: Exception) {
                reject(e.javaClass.simpleName, AgentAuth.Unauthenticated)
            }
        }
    }

    private fun verify(token: String): AgentAuth {
        val decoded = JWT.decode(token)
        if (decoded.algorithm != "RS256") return reject("AlgorithmNotRs256", AgentAuth.Unauthenticated)
        val key = jwks.get(decoded.keyId).publicKey as? RSAPublicKey
            ?: return reject("SigningKeyNotRsa", AgentAuth.Unauthenticated)
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .acceptLeeway(OidcJwtVerifier.LEEWAY_SECONDS)
            .build()
            .verify(token)
        if (verified.expiresAt == null) return reject("MissingExp", AgentAuth.Unauthenticated)
        val subject = verified.subject?.takeIf { AGENT_ID.matches(it) }
            ?: return reject("InvalidSubject", AgentAuth.Unauthenticated)
        if (verified.getClaim("azp").asString() != config.azp) return reject("AzpMismatch", AgentAuth.Forbidden)
        if (!hasAgentRole(verified.getClaim("resource_access").asMap())) return reject("AgentRoleMissing", AgentAuth.Forbidden)
        return AgentAuth.Authenticated(subject)
    }

    private fun hasAgentRole(resourceAccess: Map<String, Any?>?): Boolean {
        val client = resourceAccess?.get(config.requiredClient) as? Map<*, *> ?: return false
        val roles = client["roles"] as? List<*> ?: return false
        return config.requiredRole in roles
    }

    private fun reject(reason: String, result: AgentAuth): AgentAuth {
        val now = System.nanoTime()
        var shouldLog = false
        lastRejectionLog.compute(reason) { _, previous ->
            if (previous == null || now - previous >= OidcJwtVerifier.REJECTION_LOG_INTERVAL_MILLIS * 1_000_000L) {
                shouldLog = true
                now
            } else {
                previous
            }
        }
        if (shouldLog) logger.warn("agent access token rejected: reason={}", reason)
        return result
    }

    companion object {
        private val AGENT_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")

        /**
         * The one Bearer token of the request, or null. Null also for two `Authorization` headers, a non-Bearer scheme, an empty or
         * over-long value, or a value that is not shaped like a JWT. Nothing else in the request is read.
         */
        internal fun bearerToken(headers: Headers): String? {
            val values = headers.getAll(HttpHeaders.Authorization) ?: return null
            if (values.size != 1) return null
            val trimmed = values.single().trim()
            if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
            val token = trimmed.substring("Bearer ".length).trim()
            if (token.isEmpty() || token.length > OidcJwtVerifier.MAX_TOKEN_LENGTH) return null
            return token.takeIf { it.count { c -> c == '.' } == 2 }
        }
    }
}
