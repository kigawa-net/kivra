package net.kigawa.kivra.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.kigawa.kivra.auth.OidcTestTokens.AUDIENCE
import net.kigawa.kivra.auth.OidcTestTokens.ISSUER
import net.kigawa.kivra.auth.OidcTestTokens.token

class OidcJwtVerifierTest {
    private val verifier = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())

    private fun authWith(token: String): Headers = headersOf(HttpHeaders.Authorization, "Bearer $token")

    private fun principalOf(token: String): String? = runBlocking { verifier.principal(authWith(token)) }

    @Test
    fun `a valid token gives the verified email as principal`() {
        assertEquals("alice@example.com", principalOf(token()))
    }

    @Test
    fun `email is ignored when email_verified is false or missing, and preferred_username is used`() {
        assertEquals("alice", principalOf(token(emailVerified = false)))
        assertEquals("alice", principalOf(token(emailVerified = null)))
    }

    @Test
    fun `without a verified email or username the subject is the principal`() {
        assertEquals("sub-alice", principalOf(token(emailVerified = false, preferredUsername = null)))
        assertEquals("sub-alice", principalOf(token(email = null, preferredUsername = null)))
    }

    @Test
    fun `a token signed by another key is 401`() {
        assertNull(principalOf(token(key = OidcTestTokens.otherKey)))
    }

    @Test
    fun `an expired token is 401, but a clock skew within 30 seconds is tolerated`() {
        assertNull(principalOf(token(expiresInSeconds = -60)))
        assertEquals("alice@example.com", principalOf(token(expiresInSeconds = -20)))
    }

    @Test
    fun `a token with nbf in the future is 401, nbf within the skew is accepted`() {
        assertNull(principalOf(token(notBeforeInSeconds = 3600)))
        assertEquals("alice@example.com", principalOf(token(notBeforeInSeconds = 20)))
    }

    @Test
    fun `a different issuer is 401`() {
        assertNull(principalOf(token(issuer = "https://evil.example/realms/kigawa-net")))
        assertNull(principalOf(token(issuer = "$ISSUER/")))
    }

    @Test
    fun `a different audience is 401`() {
        assertNull(principalOf(token(audience = "admin-panel")))
        assertNull(principalOf(token(audience = "${AUDIENCE}-other")))
    }

    @Test
    fun `a token without the required client role is 401`() {
        assertNull(principalOf(token(roles = listOf("viewer"))))
        assertNull(principalOf(token(roles = emptyList())))
        // Same role name under another client does not count.
        assertNull(principalOf(token(client = "other-client")))
    }

    @Test
    fun `alg none and HS256 are 401`() {
        assertNull(principalOf(OidcTestTokens.unsignedToken()))
        assertNull(principalOf(token(algorithm = Algorithm.HMAC256("shared-secret"))))
    }

    @Test
    fun `a missing bearer credential is 401`() {
        assertNull(runBlocking { verifier.principal(headersOf()) })
        assertNull(runBlocking { verifier.principal(headersOf(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")) })
        assertNull(runBlocking { verifier.principal(headersOf(HttpHeaders.Authorization, "Bearer ")) })
        assertNull(runBlocking { verifier.principal(headersOf(HttpHeaders.Authorization, "Bearer not-a-jwt")) })
    }

    @Test
    fun `X-Forwarded-Access-Token is used only when there is no Authorization header`() {
        val valid = token()
        assertEquals("alice@example.com", runBlocking { verifier.principal(headersOf("X-Forwarded-Access-Token", valid)) })
        // Authorization wins: a bad Bearer does not fall back to the forwarded token.
        val forwardedOnly = headersOf(
            HttpHeaders.Authorization to listOf("Bearer ${token(key = OidcTestTokens.otherKey)}"),
            "X-Forwarded-Access-Token" to listOf(valid),
        )
        assertNull(runBlocking { verifier.principal(forwardedOnly) })
    }

    @Test
    fun `a forged identity header without a token is not authentication`() {
        val forged = headersOf("X-Auth-Request-Email" to listOf("admin@example.com"), "X-Auth-Request-User" to listOf("admin"))
        assertNull(runBlocking { verifier.principal(forged) })
    }

    @Test
    fun `a JWKS failure is 401, not an exception`() {
        val failing = OidcJwtVerifier(OidcTestTokens.config, object : JwkProvider {
            override fun get(keyId: String?) = throw SigningKeyNotFoundException("JWKS down", null)
        })
        assertNull(runBlocking { failing.principal(authWith(token())) })
    }

    @Test
    fun `a token with an unknown kid is 401`() {
        assertNull(principalOf(token(kid = "rotated-away")))
    }
}
