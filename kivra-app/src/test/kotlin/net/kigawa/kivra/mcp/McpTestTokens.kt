package net.kigawa.kivra.mcp

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.security.KeyPair
import java.time.Instant
import java.util.Date
import net.kigawa.kivra.auth.OidcJwtConfig
import net.kigawa.kivra.auth.OidcTestTokens

/** Test-only MCP access tokens: RS256 with the OIDC test key, the MCP audience and an optional `scope` claim. */
object McpTestTokens {
    const val MCP_AUDIENCE = "https://kivra-dev.kigawa.net/mcp"
    const val HOST = "kivra-dev.kigawa.net"
    const val ORIGIN = "https://kivra-dev.kigawa.net"
    const val READ_SCOPE = "kivra.runs.read"

    /** The MCP verifier's config: the REST test config with the MCP audience. */
    val config: OidcJwtConfig = OidcTestTokens.config.copy(audience = MCP_AUDIENCE)

    /** The owner key of the default subject, as the verifier builds it. */
    val aliceOwner: String = "${OidcTestTokens.ISSUER.toByteArray().size}:${OidcTestTokens.ISSUER}:sub-alice"

    fun token(
        audience: String = MCP_AUDIENCE,
        issuer: String = OidcTestTokens.ISSUER,
        subject: String = "sub-alice",
        roles: List<String> = listOf(OidcTestTokens.ROLE),
        client: String = OidcTestTokens.CLIENT,
        scope: String? = READ_SCOPE,
        expiresInSeconds: Long = 300,
        key: KeyPair = OidcTestTokens.signingKey,
        algorithm: Algorithm? = null,
        extraClaims: Map<String, Any> = emptyMap(),
        resourceAccess: Map<String, Map<String, List<String>>>? = null,
    ): String {
        val now = Instant.now()
        val builder = JWT.create()
            .withKeyId(OidcTestTokens.KID)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withExpiresAt(Date.from(now.plusSeconds(expiresInSeconds)))
            .withClaim("resource_access", resourceAccess ?: mapOf(client to mapOf("roles" to roles)))
        scope?.let { builder.withClaim("scope", it) }
        extraClaims.forEach { (k, v) -> builder.withClaim(k, v.toString()) }
        return builder.sign(algorithm ?: OidcTestTokens.rs256(key))
    }

    fun bearerHeaders(token: String = token(), host: String = HOST, origin: String? = null): Headers = Headers.build {
        append(HttpHeaders.Authorization, "Bearer $token")
        append(HttpHeaders.Host, host)
        origin?.let { append(HttpHeaders.Origin, it) }
    }
}
