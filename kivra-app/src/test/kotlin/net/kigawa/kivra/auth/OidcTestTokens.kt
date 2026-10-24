package net.kigawa.kivra.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/** Test-only RS256 keys and JWT builder. No network: the JWKS is an in-memory [JwkProvider]. */
object OidcTestTokens {
    const val ISSUER = "https://user.kigawa.net/realms/kigawa-net"
    const val AUDIENCE = "kivra-dev"
    const val CLIENT = "admin-panel"
    const val ROLE = "admin"
    const val REQUIRED_ROLE = "$CLIENT:$ROLE"
    const val KID = "test-kid"

    val config = OidcJwtConfig(
        issuer = ISSUER,
        jwksUrl = "$ISSUER/protocol/openid-connect/certs",
        audience = AUDIENCE,
        requiredClient = CLIENT,
        requiredRole = ROLE,
    )

    val signingKey: KeyPair by lazy { newRsaKeyPair() }
    val otherKey: KeyPair by lazy { newRsaKeyPair() }

    /** JWKS holding only [signingKey] under [KID]. */
    fun jwks(): JwkProvider = InMemoryJwks(mapOf(KID to jwkOf(KID, signingKey)))

    /** The `Authorization` header value for [token] with default claims for [email]. */
    fun bearer(email: String): String = "Bearer ${token(email = email)}"

    /**
     * An RS256 token. Defaults are a valid token for alice. Pass [algorithm] to sign differently (for `alg` tests).
     * [expiresAt] / [notBefore] are relative to now.
     */
    fun token(
        key: KeyPair = signingKey,
        kid: String = KID,
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        subject: String = "sub-alice",
        email: String? = "alice@example.com",
        emailVerified: Boolean? = true,
        preferredUsername: String? = "alice",
        roles: List<String> = listOf(ROLE),
        client: String = CLIENT,
        expiresInSeconds: Long = 300,
        notBeforeInSeconds: Long? = null,
        algorithm: Algorithm? = null,
        resourceAccess: Map<String, Map<String, List<String>>>? = null,
    ): String {
        val now = Instant.now()
        val builder = JWT.create()
            .withKeyId(kid)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withExpiresAt(Date.from(now.plusSeconds(expiresInSeconds)))
        notBeforeInSeconds?.let { builder.withNotBefore(Date.from(now.plusSeconds(it))) }
        email?.let { builder.withClaim("email", it) }
        emailVerified?.let { builder.withClaim("email_verified", it) }
        preferredUsername?.let { builder.withClaim("preferred_username", it) }
        builder.withClaim("resource_access", resourceAccess ?: mapOf(client to mapOf("roles" to roles)))
        return builder.sign(algorithm ?: rs256(key))
    }

    /** A token with `alg: none` and an empty signature, carrying the claims of a valid token. */
    fun unsignedToken(): String {
        val valid = token()
        val payload = valid.split(".")[1]
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none","typ":"JWT","kid":"$KID"}""".toByteArray())
        return "$header.$payload."
    }

    fun rs256(key: KeyPair): Algorithm = Algorithm.RSA256(key.public as RSAPublicKey, key.private as RSAPrivateKey)

    private fun newRsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun jwkOf(kid: String, key: KeyPair): Jwk {
        val pub = key.public as RSAPublicKey
        val enc = Base64.getUrlEncoder().withoutPadding()
        return Jwk.fromValues(
            mapOf(
                "kid" to kid,
                "kty" to "RSA",
                "alg" to "RS256",
                "use" to "sig",
                "n" to enc.encodeToString(unsigned(pub.modulus.toByteArray())),
                "e" to enc.encodeToString(unsigned(pub.publicExponent.toByteArray())),
            )
        )
    }

    private fun unsigned(bytes: ByteArray): ByteArray = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

    private class InMemoryJwks(private val keys: Map<String, Jwk>) : JwkProvider {
        override fun get(keyId: String?): Jwk =
            keys[keyId] ?: throw SigningKeyNotFoundException("unknown kid", null)
    }
}
