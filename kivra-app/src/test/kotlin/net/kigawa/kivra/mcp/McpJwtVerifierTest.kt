package net.kigawa.kivra.mcp

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import net.kigawa.kivra.auth.OidcTestTokens
import net.kigawa.kivra.auth.RequiredRole
import net.kigawa.kivra.mcp.McpTestTokens.MCP_AUDIENCE

class McpJwtVerifierTest {
    private val verifier = McpJwtVerifier(McpTestTokens.config, OidcTestTokens.jwks())
    private val agentAwareVerifier = McpJwtVerifier(McpTestTokens.config, OidcTestTokens.jwks(), RequiredRole("kivra-agent", "agent"))

    private fun auth(headers: Headers, v: McpJwtVerifier = verifier): McpAuth = runBlocking { v.authenticate(headers) }

    private fun bearer(token: String): Headers = Headers.build { append(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun `a valid MCP token authenticates with the owner key and its scopes`() {
        val result = auth(bearer(McpTestTokens.token(scope = "kivra.runs.read kivra.selfdev.start")))
        val ok = assertIs<McpAuth.Authenticated>(result)
        assertEquals(McpTestTokens.aliceOwner, ok.principal)
        assertEquals(setOf("kivra.runs.read", "kivra.selfdev.start"), ok.scopes)
    }

    @Test
    fun `a missing scope claim gives no scopes but still authenticates`() {
        val ok = assertIs<McpAuth.Authenticated>(auth(bearer(McpTestTokens.token(scope = null))))
        assertEquals(emptySet(), ok.scopes)
    }

    @Test
    fun `no Authorization header is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(Headers.Empty))
    }

    @Test
    fun `X-Forwarded-Access-Token is not a credential for MCP`() {
        val headers = Headers.build { append("X-Forwarded-Access-Token", McpTestTokens.token()) }
        assertIs<McpAuth.Unauthenticated>(auth(headers))
    }

    @Test
    fun `a token for the REST audience is unauthenticated (wrong audience)`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(McpTestTokens.token(audience = OidcTestTokens.AUDIENCE))))
    }

    @Test
    fun `an agent token (audience kivra-agent) is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(McpTestTokens.token(audience = "kivra-agent", client = "kivra-agent", roles = listOf("agent")))))
    }

    @Test
    fun `a token from another issuer is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(McpTestTokens.token(issuer = "https://other.example/realms/kigawa-net"))))
    }

    @Test
    fun `an expired token is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(McpTestTokens.token(expiresInSeconds = -120))))
    }

    @Test
    fun `a token signed by another key is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(McpTestTokens.token(key = OidcTestTokens.otherKey))))
    }

    @Test
    fun `an alg none token is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(OidcTestTokens.unsignedToken())))
    }

    @Test
    fun `a token without the required operator role is unauthenticated`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(McpTestTokens.token(roles = listOf("viewer")))))
    }

    @Test
    fun `a token that carries the agent role is refused, even with the MCP audience and the operator role`() {
        // Two clients in one token: the operator client (admin-panel:admin) and the agent client (kivra-agent:agent).
        val token = McpTestTokens.token(client = "kivra-agent", roles = listOf("agent"))
        assertIs<McpAuth.AgentRefused>(auth(bearer(token), agentAwareVerifier))
    }

    @Test
    fun `the Bearer scheme is required`() {
        val headers = Headers.build { append(HttpHeaders.Authorization, "Basic ${McpTestTokens.token()}") }
        assertIs<McpAuth.Unauthenticated>(auth(headers))
    }

    @Test
    fun `the MCP audience constant matches the config`() {
        assertEquals(MCP_AUDIENCE, McpTestTokens.config.audience)
    }

    @Test
    fun `scopes are split on spaces and tabs and empties are dropped`() {
        assertEquals(setOf("a", "b"), McpJwtVerifier.scopesOf(" a  b\tb "))
        assertEquals(emptySet(), McpJwtVerifier.scopesOf(""))
        assertEquals(emptySet(), McpJwtVerifier.scopesOf(null))
    }

    private val memberRole = RequiredRole("kivra", "member")
    private val memberAware = McpJwtVerifier(
        McpTestTokens.config.copy(memberRole = memberRole), OidcTestTokens.jwks(), RequiredRole("kivra-agent", "agent"),
    )
    private val memberOnly = McpTestTokens.token(resourceAccess = mapOf("kivra" to mapOf("roles" to listOf("member"))))

    private fun memberAuth(headers: Headers, v: McpJwtVerifier = memberAware): McpAuth = runBlocking { v.authenticateMember(headers) }

    @Test
    fun `a member-only token is rejected by authenticate and is MEMBER_ONLY for authenticateMember`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(memberOnly), memberAware))
        val ok = assertIs<McpAuth.Authenticated>(memberAuth(bearer(memberOnly)))
        assertEquals(net.kigawa.kivra.auth.MemberKind.MEMBER_ONLY, ok.kind)
        assertEquals(McpTestTokens.aliceOwner, ok.principal)
    }

    @Test
    fun `an operator token is LEGACY_ADMIN as before`() {
        val ok = assertIs<McpAuth.Authenticated>(auth(bearer(McpTestTokens.token()), memberAware))
        assertEquals(net.kigawa.kivra.auth.MemberKind.LEGACY_ADMIN, ok.kind)
    }

    @Test
    fun `without a member role setting a member-only token is rejected by both`() {
        assertIs<McpAuth.Unauthenticated>(auth(bearer(memberOnly), agentAwareVerifier))
        assertIs<McpAuth.Unauthenticated>(memberAuth(bearer(memberOnly), agentAwareVerifier))
    }

    @Test
    fun `an agent token with the member role is refused as an agent by both`() {
        val both = McpTestTokens.token(
            resourceAccess = mapOf(
                "kivra" to mapOf("roles" to listOf("member")),
                "kivra-agent" to mapOf("roles" to listOf("agent")),
            ),
        )
        assertIs<McpAuth.AgentRefused>(auth(bearer(both), memberAware))
        assertIs<McpAuth.AgentRefused>(memberAuth(bearer(both)))
    }

    @Test
    fun `a bad aud, iss, exp or subject is rejected even with the member role`() {
        val ra = mapOf("kivra" to mapOf("roles" to listOf("member")))
        assertIs<McpAuth.Unauthenticated>(memberAuth(bearer(McpTestTokens.token(audience = "other", resourceAccess = ra))))
        assertIs<McpAuth.Unauthenticated>(memberAuth(bearer(McpTestTokens.token(issuer = "https://evil.example", resourceAccess = ra))))
        assertIs<McpAuth.Unauthenticated>(memberAuth(bearer(McpTestTokens.token(expiresInSeconds = -3600, resourceAccess = ra))))
        assertIs<McpAuth.Unauthenticated>(memberAuth(bearer(McpTestTokens.token(subject = "", resourceAccess = ra))))
    }
}
