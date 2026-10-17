package net.kigawa.kivra.auth

import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The agent authenticator (PR 4a). Every row of the authorization table is pinned here: missing or forged credentials are 401,
 * a valid token of the wrong client, audience, role or with no agent role is 403, and the operator's authenticator refuses agent
 * tokens. The headers used are only `Authorization: Bearer`.
 */
class AgentIdentityTest {
    private val agents = AgentIdentity(AgentTestTokens.config(), OidcTestTokens.jwks())
    private val operators = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())

    private fun bearer(token: String) = headersOf(HttpHeaders.Authorization, "Bearer $token")

    private fun authenticate(headers: io.ktor.http.Headers): AgentAuth = runBlocking { agents.authenticate(headers) }

    private fun authenticateWith(token: String): AgentAuth = authenticate(bearer(token))

    @Test
    fun `a valid agent token authenticates, and the identity is the token's sub`() {
        assertEquals(AgentAuth.Authenticated(AgentTestTokens.SUBJECT), authenticateWith(AgentTestTokens.token()))
    }

    @Test
    fun `an agent token with the operator role as well still authenticates, because the agent role is what matters`() {
        val both = mapOf(
            "admin-panel" to mapOf("roles" to listOf("admin")),
            AgentTestTokens.CLIENT to mapOf("roles" to listOf(AgentTestTokens.ROLE)),
        )
        assertEquals(AgentAuth.Authenticated(AgentTestTokens.SUBJECT), authenticateWith(AgentTestTokens.token(resourceAccess = both)))
    }

    @Test
    fun `no credential, a non-Bearer scheme or a forwarded token alone is unauthenticated`() {
        assertEquals(AgentAuth.Unauthenticated, authenticate(headersOf()))
        val token = AgentTestTokens.token()
        assertEquals(AgentAuth.Unauthenticated, authenticate(headersOf(HttpHeaders.Authorization, "Basic $token")))
        // oauth2-proxy's header is never trusted, even with a valid agent token in it.
        assertEquals(AgentAuth.Unauthenticated, authenticate(headersOf("X-Forwarded-Access-Token", token)))
        assertEquals(AgentAuth.Unauthenticated, authenticate(headersOf(HttpHeaders.Cookie, "_oauth2_proxy=$token")))
        assertEquals(AgentAuth.Unauthenticated, authenticate(headersOf("X-Auth-Request-Email", "alice@example.com")))
    }

    @Test
    fun `a Bearer header with a forwarded header next to it authenticates only from Authorization`() {
        // The forwarded header carries an operator token. It is ignored, so the agent token decides.
        val headers = headersOf(
            HttpHeaders.Authorization to listOf("Bearer ${AgentTestTokens.token()}"),
            "X-Forwarded-Access-Token" to listOf(AgentTestTokens.operatorToken()),
        )
        assertEquals(AgentAuth.Authenticated(AgentTestTokens.SUBJECT), authenticate(headers))
        // A forged Authorization value next to a valid forwarded agent token: the forwarded one is not read.
        val forged = headersOf(
            HttpHeaders.Authorization to listOf("Bearer not.a.token"),
            "X-Forwarded-Access-Token" to listOf(AgentTestTokens.token()),
        )
        assertEquals(AgentAuth.Unauthenticated, authenticate(forged))
    }

    @Test
    fun `two Authorization headers are unauthenticated`() {
        val headers = headersOf(
            HttpHeaders.Authorization to listOf("Bearer ${AgentTestTokens.token()}", "Bearer ${AgentTestTokens.token()}"),
        )
        assertEquals(AgentAuth.Unauthenticated, authenticate(headers))
    }

    @Test
    fun `a bad signature, an unknown key, alg none, an expired or not-yet-valid token are unauthenticated`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(key = other)))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(kid = "unknown-kid")))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(algorithm = Algorithm.none())))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(expiresInSeconds = -120)))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(notBeforeInSeconds = 300)))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(expiresInSeconds = null)))
    }

    @Test
    fun `a token for another issuer or another audience is unauthenticated (the Anthropic audience alone is not kivra)`() {
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(issuer = "https://evil.example/realms/x")))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(audiences = listOf(AgentTestTokens.ANTHROPIC_AUDIENCE))))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(audiences = listOf("other-service"))))
    }

    @Test
    fun `a token whose sub is not an agent identifier is unauthenticated`() {
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(subject = null)))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(subject = "has space")))
        assertEquals(AgentAuth.Unauthenticated, authenticateWith(AgentTestTokens.token(subject = "x".repeat(65))))
    }

    @Test
    fun `azp must equal the configured value exactly`() {
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(azp = null)))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(azp = "admin-panel")))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(azp = "kivra-agent-2")))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(azp = "Kivra-Agent")))
    }

    @Test
    fun `the agent role is required, and a role under another client does not count`() {
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(resourceAccess = mapOf("kivra-agent" to mapOf("roles" to emptyList<String>())))))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(resourceAccess = emptyMap())))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(resourceAccess = mapOf("admin-panel" to mapOf("roles" to listOf("agent"))))))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.token(resourceAccess = mapOf("kivra-agent" to mapOf("roles" to listOf("admin"))))))
    }

    @Test
    fun `an operator token is refused by the agent API`() {
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.operatorToken()))
        assertEquals(AgentAuth.Forbidden, authenticateWith(AgentTestTokens.operatorToken(azp = null)))
        // An operator token from the test helper (no azp at all) is refused too.
        assertEquals(AgentAuth.Forbidden, authenticateWith(OidcTestTokens.token()))
    }

    @Test
    fun `the operator authenticator refuses every agent token, in the header and in the forwarded header`() {
        val agentToken = AgentTestTokens.token()
        assertNull(runBlocking { operators.principal(bearer(agentToken)) })
        assertNull(runBlocking { operators.principal(headersOf("X-Forwarded-Access-Token", agentToken)) })
        // The agent token has no admin-panel:admin role, so it is refused even when the audience and issuer match.
        assertNull(runBlocking { operators.principal(bearer(AgentTestTokens.token(audiences = listOf(AgentTestTokens.AUDIENCE)))) })
    }

    @Test
    fun `the operator token is refused by the agent authenticator, and the operator still accepts its own token`() {
        val operator = AgentTestTokens.operatorToken()
        assertEquals(AgentAuth.Forbidden, authenticateWith(operator))
        assertTrue(runBlocking { operators.principal(bearer(OidcTestTokens.token())) } != null)
    }

    @Test
    fun `the agent config and its identity never print a value`() {
        assertFalse(AgentTestTokens.config().toString().contains(AgentTestTokens.AZP))
        assertFalse(AgentTestTokens.config().toString().contains(OidcTestTokens.ISSUER))
        assertFalse(AgentIdentity(AgentTestTokens.config(), OidcTestTokens.jwks()).toString().contains(AgentTestTokens.SUBJECT))
    }
}
