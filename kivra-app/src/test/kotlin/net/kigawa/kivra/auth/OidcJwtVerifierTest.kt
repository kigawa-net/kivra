package net.kigawa.kivra.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.kigawa.kivra.auth.OidcTestTokens.AUDIENCE
import net.kigawa.kivra.auth.OidcTestTokens.ISSUER
import net.kigawa.kivra.auth.OidcTestTokens.token
import net.kigawa.kivra.auth.OidcTestTokens

class OidcJwtVerifierTest {
    private val verifier = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())

    private fun authWith(token: String): Headers = headersOf(HttpHeaders.Authorization, "Bearer $token")

    private fun principalOf(token: String): String? = runBlocking { verifier.principal(authWith(token)) }

    private val subjectOwner = "${ISSUER.toByteArray().size}:$ISSUER:sub-alice"

    @Test
    fun `a valid token's owner is the iss and sub key, not the email`() {
        assertEquals(subjectOwner, principalOf(token()))
    }

    @Test
    fun `changing the email keeps the same owner`() {
        assertEquals(principalOf(token(email = "alice@example.com")), principalOf(token(email = "alice.new@example.com")))
        assertEquals(subjectOwner, principalOf(token(email = "alice.new@example.com")))
    }

    @Test
    fun `changing the username keeps the same owner`() {
        assertEquals(principalOf(token(preferredUsername = "alice")), principalOf(token(preferredUsername = "alice2")))
        assertEquals(principalOf(token(emailVerified = false)), principalOf(token(emailVerified = true)))
        assertEquals(subjectOwner, principalOf(token(email = null, preferredUsername = null)))
    }

    @Test
    fun `an old email reused by another sub is a different owner`() {
        val oldEmailOwner = principalOf(token(subject = "sub-alice", email = "shared@example.com"))
        val reusedEmailOwner = principalOf(token(subject = "sub-bob", email = "shared@example.com"))
        assertNotNull(oldEmailOwner)
        assertNotNull(reusedEmailOwner)
        assertNotEquals(oldEmailOwner, reusedEmailOwner)
    }

    @Test
    fun `the same sub from a different issuer is a different owner`() {
        val otherIssuer = "https://other.example/realms/kigawa-net"
        val otherVerifier = OidcJwtVerifier(OidcTestTokens.config.copy(issuer = otherIssuer), OidcTestTokens.jwks())
        val fromOther = runBlocking { otherVerifier.principal(authWith(token(issuer = otherIssuer, subject = "sub-alice"))) }
        assertNotNull(fromOther)
        assertNotEquals(principalOf(token(subject = "sub-alice")), fromOther)
        assertEquals("${otherIssuer.toByteArray().size}:$otherIssuer:sub-alice", fromOther)
    }

    @Test
    fun `a token without a subject is 401`() {
        val noSub = JWT.create()
            .withKeyId(OidcTestTokens.KID)
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(300)))
            .withClaim("email", "alice@example.com")
            .withClaim("email_verified", true)
            .withClaim("resource_access", mapOf(OidcTestTokens.CLIENT to mapOf("roles" to listOf(OidcTestTokens.ROLE))))
            .sign(OidcTestTokens.rs256(OidcTestTokens.signingKey))
        assertNull(principalOf(noSub))
        assertNull(principalOf(token(subject = "")))
        assertNull(principalOf(token(subject = "   ")))
    }

    @Test
    fun `oidcOwnerKey encodes iss and sub without delimiter collisions`() {
        assertEquals("3:a:b:c", oidcOwnerKey("a:b", "c"))
        assertEquals("1:a:b:c", oidcOwnerKey("a", "b:c"))
        assertNotEquals(oidcOwnerKey("a:b", "c"), oidcOwnerKey("a", "b:c"))
        assertNotEquals(oidcOwnerKey("a", "b"), oidcOwnerKey("a:b", ""))
        assertNull(oidcOwnerKey("a", ""))
        assertNull(oidcOwnerKey("a", null))
        assertNull(oidcOwnerKey("a", "x\ny"))
        // The key must fit the owner column (MAX_PRINCIPAL_LENGTH, 256).
        assertNull(oidcOwnerKey(ISSUER, "s".repeat(256)))
        assertNotNull(oidcOwnerKey(ISSUER, "s".repeat(200)))
    }

    @Test
    fun `a token signed by another key is 401`() {
        assertNull(principalOf(token(key = OidcTestTokens.otherKey)))
    }

    @Test
    fun `an expired token is 401, but a clock skew within 30 seconds is tolerated`() {
        assertNull(principalOf(token(expiresInSeconds = -60)))
        assertEquals(subjectOwner, principalOf(token(expiresInSeconds = -20)))
    }

    @Test
    fun `a token with nbf in the future is 401, nbf within the skew is accepted`() {
        assertNull(principalOf(token(notBeforeInSeconds = 3600)))
        assertEquals(subjectOwner, principalOf(token(notBeforeInSeconds = 20)))
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
        assertEquals(subjectOwner, runBlocking { verifier.principal(headersOf("X-Forwarded-Access-Token", valid)) })
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
