package net.kigawa.kivra.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The member role (`KIVRA_OIDC_MEMBER_ROLE`) authenticates a harmless user without the operator role. It adds no permission: the operator
 * [OidcJwtVerifier.authenticate] still rejects a member-only token, and [OidcJwtVerifier.authenticateMember] is not called by any route yet.
 */
class MemberRoleAuthTest {
    private val memberRole = RequiredRole("kivra", "member")
    private val agentRole = RequiredRole.parse(AgentTestTokens.REQUIRED_ROLE, "KIVRA_AGENT_REQUIRED_ROLE")
    private val withMember = OidcJwtVerifier(OidcTestTokens.config.copy(memberRole = memberRole), OidcTestTokens.jwks(), agentRole)
    private val withoutMember = OidcJwtVerifier(OidcTestTokens.config, OidcTestTokens.jwks(), agentRole)

    private fun bearer(token: String) = headersOf(HttpHeaders.Authorization, "Bearer $token")

    private fun memberToken(
        subject: String = "sub-ai",
        extra: Map<String, Map<String, List<String>>> = emptyMap(),
        issuer: String = OidcTestTokens.ISSUER,
        audience: String = OidcTestTokens.AUDIENCE,
        expiresInSeconds: Long = 300,
    ) = OidcTestTokens.token(
        subject = subject,
        issuer = issuer,
        audience = audience,
        expiresInSeconds = expiresInSeconds,
        resourceAccess = mapOf("kivra" to mapOf("roles" to listOf("member"))) + extra,
    )

    private fun member(v: OidcJwtVerifier, token: String): MemberAuth = runBlocking { v.authenticateMember(bearer(token)) }

    private fun operator(v: OidcJwtVerifier, token: String): OperatorAuth = runBlocking { v.authenticate(bearer(token)) }

    @Test
    fun `a member-only token is MEMBER_ONLY for authenticateMember and rejected by the operator authenticate`() {
        val token = memberToken()
        val ok = assertIs<MemberAuth.Authenticated>(member(withMember, token))
        assertEquals(MemberKind.MEMBER_ONLY, ok.kind)
        assertEquals(oidcOwnerKey(OidcTestTokens.ISSUER, "sub-ai"), ok.principal)
        assertEquals(OperatorAuth.Unauthenticated, operator(withMember, token))
        assertNull(runBlocking { withMember.principal(bearer(token)) })
        assertEquals(false, assertIs<TaskCaller.Member>(runBlocking { withMember.caller(bearer(token)) }).operator)
    }

    @Test
    fun `an operator token is LEGACY_ADMIN and behaves as before`() {
        val token = OidcTestTokens.token()
        assertEquals(MemberKind.LEGACY_ADMIN, assertIs<MemberAuth.Authenticated>(member(withMember, token)).kind)
        assertIs<OperatorAuth.Authenticated>(operator(withMember, token))
        assertEquals(MemberKind.LEGACY_ADMIN, assertIs<MemberAuth.Authenticated>(member(withoutMember, token)).kind)
        assertIs<OperatorAuth.Authenticated>(operator(withoutMember, token))
    }

    @Test
    fun `a token with both the operator and the member role is LEGACY_ADMIN`() {
        val token = memberToken(extra = mapOf(OidcTestTokens.CLIENT to mapOf("roles" to listOf(OidcTestTokens.ROLE))))
        assertEquals(MemberKind.LEGACY_ADMIN, assertIs<MemberAuth.Authenticated>(member(withMember, token)).kind)
    }

    @Test
    fun `a token with no known role keeps the previous result`() {
        val token = OidcTestTokens.token(roles = listOf("viewer"))
        assertEquals(MemberAuth.Unauthenticated, member(withMember, token))
        assertEquals(OperatorAuth.Unauthenticated, operator(withMember, token))
        assertEquals(MemberKind.NONE, assertIs<TaskCaller.Member>(runBlocking { withMember.caller(bearer(token)) }).kind)
    }

    @Test
    fun `without a member role setting the member role is ignored`() {
        val token = memberToken()
        assertEquals(MemberAuth.Unauthenticated, member(withoutMember, token))
        assertEquals(OperatorAuth.Unauthenticated, operator(withoutMember, token))
        assertEquals(MemberKind.NONE, assertIs<TaskCaller.Member>(runBlocking { withoutMember.caller(bearer(token)) }).kind)
    }

    @Test
    fun `an agent token is refused even when it also carries the member role, and before the member check`() {
        val both = AgentTestTokens.token(
            resourceAccess = mapOf(
                "kivra" to mapOf("roles" to listOf("member")),
                AgentTestTokens.CLIENT to mapOf("roles" to listOf(AgentTestTokens.ROLE)),
            ),
            audiences = listOf(OidcTestTokens.AUDIENCE),
        )
        assertEquals(MemberAuth.AgentRefused, member(withMember, both))
        assertEquals(OperatorAuth.AgentRefused, operator(withMember, both))
        assertEquals(TaskCaller.AgentRefused, runBlocking { withMember.caller(bearer(both)) })
    }

    @Test
    fun `a bad iss, aud or exp is rejected even with the member role`() {
        assertEquals(MemberAuth.Unauthenticated, member(withMember, memberToken(issuer = "https://evil.example")))
        assertEquals(MemberAuth.Unauthenticated, member(withMember, memberToken(audience = "other")))
        assertEquals(MemberAuth.Unauthenticated, member(withMember, memberToken(expiresInSeconds = -3600)))
    }

    @Test
    fun `an empty or control-character subject has no owner key and is rejected`() {
        assertNull(oidcOwnerKey(OidcTestTokens.ISSUER, ""))
        assertNull(oidcOwnerKey(OidcTestTokens.ISSUER, "a\u0007b"))
        assertEquals(MemberAuth.Unauthenticated, member(withMember, memberToken(subject = "")))
        assertEquals(MemberAuth.Unauthenticated, member(withMember, memberToken(subject = "a\nb")))
    }

    // Config

    private val env = mapOf(
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
    )

    @Test
    fun `the member role is unset by default and parsed when set`() {
        assertNull(OidcJwtConfig.fromEnv(env).memberRole)
        assertNull(OidcJwtConfig.fromEnv(env + ("KIVRA_OIDC_MEMBER_ROLE" to "  ")).memberRole)
        assertEquals(memberRole, OidcJwtConfig.fromEnv(env + ("KIVRA_OIDC_MEMBER_ROLE" to " kivra:member ")).memberRole)
    }

    @Test
    fun `a malformed or clashing member role fails startup naming only the variable`() {
        for (bad in listOf("member", "kivra:", ":member", "a:b:c", OidcTestTokens.REQUIRED_ROLE)) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { OidcJwtConfig.fromEnv(env + ("KIVRA_OIDC_MEMBER_ROLE" to bad)) }
            assertEquals(true, e.message!!.contains("KIVRA_OIDC_MEMBER_ROLE"))
            assertEquals(false, e.message!!.contains(bad))
        }
        val e = assertFailsWith<IllegalArgumentException> {
            OidcJwtConfig.fromEnv(env + mapOf("KIVRA_OIDC_MEMBER_ROLE" to "kivra-agent:agent", "KIVRA_AGENT_REQUIRED_ROLE" to " kivra-agent:agent"))
        }
        assertEquals(true, e.message!!.contains("KIVRA_OIDC_MEMBER_ROLE"))
        assertFailsWith<IllegalArgumentException> { OidcJwtVerifier(OidcTestTokens.config.copy(memberRole = agentRole), OidcTestTokens.jwks(), agentRole) }
    }
}
