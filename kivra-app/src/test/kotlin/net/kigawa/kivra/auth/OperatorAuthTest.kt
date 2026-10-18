package net.kigawa.kivra.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The operator authenticator of the autopilot routes (ADR 0011 section 1): an agent token is refused with a result of its own
 * ([OperatorAuth.AgentRefused], 403), not confused with an unauthenticated caller (401). Without an agent role the verifier behaves as
 * the Task API's (unchanged).
 */
class OperatorAuthTest {
    private val agentRole = RequiredRole.parse(AgentTestTokens.REQUIRED_ROLE, "KIVRA_AGENT_REQUIRED_ROLE")
    private val guarded = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks(), agentRole)
    private val unguarded = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks())

    private fun bearer(token: String) = headersOf(HttpHeaders.Authorization, "Bearer $token")

    private fun authenticate(verifier: OidcJwtVerifier, token: String): OperatorAuth =
        runBlocking { verifier.authenticate(bearer(token)) }

    @Test
    fun `an operator token authenticates, and the principal is the owner key`() {
        val auth = authenticate(guarded, OidcTestTokens.token())
        assertIs<OperatorAuth.Authenticated>(auth)
        assertEquals(runBlocking { unguarded.principal(bearer(OidcTestTokens.token())) }, auth.principal)
    }

    @Test
    fun `an agent token alone is refused as an agent, not as unauthenticated`() {
        assertEquals(OperatorAuth.AgentRefused, authenticate(guarded, AgentTestTokens.token()))
    }

    @Test
    fun `an agent token that also carries the operator role is refused as an agent`() {
        val both = AgentTestTokens.token(
            resourceAccess = mapOf(
                OidcTestTokens.CLIENT to mapOf("roles" to listOf(OidcTestTokens.ROLE)),
                AgentTestTokens.CLIENT to mapOf("roles" to listOf(AgentTestTokens.ROLE)),
            ),
            audiences = listOf(OidcTestTokens.AUDIENCE),
        )
        assertEquals(OperatorAuth.AgentRefused, authenticate(guarded, both))
    }

    @Test
    fun `without an agent role, an agent token is unauthenticated, as before`() {
        assertEquals(OperatorAuth.Unauthenticated, authenticate(unguarded, AgentTestTokens.token()))
    }

    @Test
    fun `a forged or unsigned token is unauthenticated even when it carries the agent role`() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertEquals(OperatorAuth.Unauthenticated, authenticate(guarded, AgentTestTokens.token(key = other)))
        assertEquals(OperatorAuth.Unauthenticated, authenticate(guarded, OidcTestTokens.token(issuer = "https://evil.example")))
        assertEquals(OperatorAuth.Unauthenticated, runBlocking { guarded.authenticate(headersOf(HttpHeaders.Authorization, "Basic x")) })
    }

    @Test
    fun `the agent role is parsed as client colon role, and the message names the variable only`() {
        assertEquals(RequiredRole("kivra-agent", "agent"), RequiredRole.parse(" kivra-agent : agent ", "KIVRA_AGENT_REQUIRED_ROLE"))
        for (bad in listOf("", "agent", ":agent", "kivra-agent:", "a:b:c")) {
            val e = assertFailsWith<IllegalArgumentException> { RequiredRole.parse(bad, "KIVRA_AGENT_REQUIRED_ROLE") }
            assertEquals("KIVRA_AGENT_REQUIRED_ROLE must be <client>:<role>", e.message)
        }
        assertEquals("RequiredRole(***)", agentRole.toString())
    }
}
