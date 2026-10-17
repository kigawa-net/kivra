package net.kigawa.kivra.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPair
import java.time.Instant
import java.util.Date

/**
 * Test-only agent tokens (client `kivra-agent`, role `agent`). Same keys and JWKS as [OidcTestTokens]: no network. The builder can
 * produce every claim shape the agent check must refuse.
 */
object AgentTestTokens {
    const val ISSUER = OidcTestTokens.ISSUER
    const val AUDIENCE = OidcTestTokens.AUDIENCE
    const val ANTHROPIC_AUDIENCE = "https://api.anthropic.com"
    const val AZP = "kivra-agent"
    const val CLIENT = "kivra-agent"
    const val ROLE = "agent"
    const val REQUIRED_ROLE = "$CLIENT:$ROLE"
    /** The service account's `sub` (a UUID), as Keycloak issues it. */
    const val SUBJECT = "6c655ef9-d120-46e2-8216-e3356d80fa5f"

    fun config(): AgentAuthConfig = AgentAuthConfig(
        issuer = ISSUER,
        jwksUrl = "$ISSUER/protocol/openid-connect/certs",
        audience = AUDIENCE,
        azp = AZP,
        requiredClient = CLIENT,
        requiredRole = ROLE,
    )

    /** The default is a valid agent token. Each parameter changes one property so a test can break exactly that property. */
    fun token(
        key: KeyPair = OidcTestTokens.signingKey,
        kid: String = OidcTestTokens.KID,
        issuer: String = ISSUER,
        audiences: List<String> = listOf(AUDIENCE, ANTHROPIC_AUDIENCE),
        subject: String? = SUBJECT,
        azp: String? = AZP,
        resourceAccess: Map<String, Any> = mapOf(CLIENT to mapOf("roles" to listOf(ROLE))),
        expiresInSeconds: Long? = 300,
        notBeforeInSeconds: Long? = null,
        algorithm: Algorithm? = null,
    ): String {
        val now = Instant.now()
        val builder = JWT.create().withKeyId(kid).withIssuer(issuer).withAudience(*audiences.toTypedArray())
        subject?.let { builder.withSubject(it) }
        expiresInSeconds?.let { builder.withExpiresAt(Date.from(now.plusSeconds(it))) }
        notBeforeInSeconds?.let { builder.withNotBefore(Date.from(now.plusSeconds(it))) }
        azp?.let { builder.withClaim("azp", it) }
        builder.withClaim("resource_access", resourceAccess)
        return builder.sign(algorithm ?: OidcTestTokens.rs256(key))
    }

    /** An operator token as the Task API expects it: `admin-panel:admin`, no agent role. */
    fun operatorToken(azp: String? = "admin-panel"): String = token(
        azp = azp,
        resourceAccess = mapOf("admin-panel" to mapOf("roles" to listOf("admin"))),
        audiences = listOf(AUDIENCE),
        subject = "sub-operator",
    )
}
