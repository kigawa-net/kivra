package net.kigawa.kivra.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Settings for `KIVRA_AUTH_MODE=oidc-jwt`. All of them are required except [jwksUrl], which defaults to
 * `<issuer>/protocol/openid-connect/certs`.
 *
 * - `KIVRA_OIDC_ISSUER`: the `iss` a token must carry, e.g. `https://user.kigawa.net/realms/kigawa-net`.
 * - `KIVRA_OIDC_JWKS_URL`: optional JWKS URL.
 * - `KIVRA_OIDC_AUDIENCE`: the `aud` a token must contain, e.g. `kivra-dev`.
 * - `KIVRA_OIDC_REQUIRED_ROLE`: `<client>:<role>`, e.g. `admin-panel:admin`. The token must list the role in
 *   `resource_access.<client>.roles`.
 */
data class OidcJwtConfig(
    val issuer: String,
    val jwksUrl: String,
    val audience: String,
    val requiredClient: String,
    val requiredRole: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): OidcJwtConfig {
            val issuer = required(env, "KIVRA_OIDC_ISSUER")
            val jwksUrl = env["KIVRA_OIDC_JWKS_URL"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${issuer.trimEnd('/')}/protocol/openid-connect/certs"
            val audience = required(env, "KIVRA_OIDC_AUDIENCE")
            val role = required(env, "KIVRA_OIDC_REQUIRED_ROLE")
            val parts = role.split(":")
            require(parts.size == 2 && parts.all { it.isNotBlank() }) {
                "KIVRA_OIDC_REQUIRED_ROLE must be <client>:<role>"
            }
            return OidcJwtConfig(issuer, jwksUrl, audience, parts[0].trim(), parts[1].trim())
        }

        private fun required(env: Map<String, String>, name: String): String =
            env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("$name is required for KIVRA_AUTH_MODE=oidc-jwt")
    }
}

/**
 * Verifies a Keycloak access token and returns its principal, or null (-> 401) for any failure. The failure reason is
 * logged by exception class only, and is never sent to the client.
 *
 * Checks: `alg` must be RS256 (`none` and HS256 are rejected before any key lookup); signature against the key with the
 * token's `kid` from [jwks]; `iss` equals [OidcJwtConfig.issuer]; `aud` contains [OidcJwtConfig.audience]; `exp` is
 * present and not passed; `nbf` is not in the future. Clock skew is tolerated for [LEEWAY_SECONDS] seconds. The token
 * must contain the required client role.
 *
 * Principal: `email` when `email_verified` is true, else `preferred_username`, else `sub`.
 */
class OidcJwtVerifier(
    private val config: OidcJwtConfig,
    private val jwks: JwkProvider,
) {
    private val logger = LoggerFactory.getLogger(OidcJwtVerifier::class.java)

    fun asIdentity(): TaskApiIdentity = TaskApiIdentity { headers -> principal(headers) }

    suspend fun principal(headers: Headers): String? {
        val token = bearerToken(headers) ?: return null
        // JWKS may be fetched over the network, so keep the blocking work off the Netty event loop.
        return withContext(Dispatchers.IO) {
            try {
                verify(token)
            } catch (e: Exception) {
                logRejection(e.javaClass.simpleName)
                null
            }
        }
    }

    /**
     * Logs why a token was rejected: a fixed reason or the exception class name only, never the token, a claim value or
     * the exception message. At WARN so that it shows at the default production level, rate limited per reason so that
     * unauthenticated traffic cannot flood the log. Without this a 401 cannot be told apart (expired token, wrong
     * audience, JWKS unreachable, missing role).
     */
    private fun logRejection(reason: String) {
        val now = System.currentTimeMillis()
        val last = lastRejectionLog.put(reason, now)
        if (last == null || now - last >= REJECTION_LOG_INTERVAL_MILLIS) {
            logger.warn("OIDC access token rejected: reason={}", reason)
        } else {
            lastRejectionLog[reason] = last
        }
    }

    private val lastRejectionLog = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun reject(reason: String): String? {
        logRejection(reason)
        return null
    }

    private fun verify(token: String): String? {
        val decoded = JWT.decode(token)
        if (decoded.algorithm != "RS256") return reject("AlgorithmNotRs256")
        val key = jwks.get(decoded.keyId).publicKey as? RSAPublicKey ?: return reject("SigningKeyNotRsa")
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .acceptLeeway(LEEWAY_SECONDS)
            .build()
            .verify(token)
        if (verified.expiresAt == null) return reject("MissingExp")
        if (!hasRole(verified.getClaim("resource_access").asMap())) return reject("RequiredRoleMissing")

        val emailVerified = verified.getClaim("email_verified").asBoolean() == true
        val email = verified.getClaim("email").asString()?.takeIf { emailVerified && it.isNotBlank() }
        val username = verified.getClaim("preferred_username").asString()?.takeIf { it.isNotBlank() }
        return validPrincipal(email ?: username ?: verified.subject) ?: reject("InvalidPrincipal")
    }

    private fun hasRole(resourceAccess: Map<String, Any?>?): Boolean {
        val client = resourceAccess?.get(config.requiredClient) as? Map<*, *> ?: return false
        val roles = client["roles"] as? List<*> ?: return false
        return config.requiredRole in roles
    }

    companion object {
        /** Clock-skew tolerance for exp / nbf. Must stay within 30 seconds. */
        const val LEEWAY_SECONDS = 30L

        /** The same rejection reason is logged at most once per minute. */
        const val REJECTION_LOG_INTERVAL_MILLIS = 60_000L

        /** Upper bound on the token length, checked before parsing. */
        const val MAX_TOKEN_LENGTH = 16 * 1024

        /**
         * The access token from `Authorization: Bearer <token>`. When there is no Authorization header, from
         * `X-Forwarded-Access-Token`. Null when the value is missing, is not a Bearer credential, or is not shaped like a
         * JWT (three dot-separated parts).
         */
        internal fun bearerToken(headers: Headers): String? {
            val authorization = headers[HttpHeaders.Authorization]
            val token = if (authorization != null) {
                val trimmed = authorization.trim()
                if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
                trimmed.substring("Bearer ".length).trim()
            } else {
                headers["X-Forwarded-Access-Token"]?.trim()
            }
            if (token.isNullOrEmpty() || token.length > MAX_TOKEN_LENGTH) return null
            return token.takeIf { it.count { c -> c == '.' } == 2 }
        }
    }
}
