package net.kigawa.kivra.auth

import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class TaskApiAuthTest {
    private val oidcEnv = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to OidcTestTokens.ISSUER,
        "KIVRA_OIDC_AUDIENCE" to OidcTestTokens.AUDIENCE,
        "KIVRA_OIDC_REQUIRED_ROLE" to OidcTestTokens.REQUIRED_ROLE,
    )

    @Test
    fun `unset auth mode registers no Task API`() {
        assertNull(resolveTaskApiAuthMode(emptyMap()))
        assertNull(resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to " ")))
        assertNull(resolveTaskApiIdentity(emptyMap()))
    }

    @Test
    fun `oidc-jwt resolves in any runtime`() {
        assertEquals(TaskApiAuthMode.OIDC_JWT, resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oidc-jwt")))
        assertEquals(TaskApiAuthMode.OIDC_JWT, resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oidc-jwt", "KIVRA_RUNTIME_ENV" to "server")))
    }

    @Test
    fun `oauth2-proxy is allowed only in development`() {
        assertEquals(TaskApiAuthMode.OAUTH2_PROXY, resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "development")))
        assertFailsWith<IllegalArgumentException> { resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "server")) }
        assertFailsWith<IllegalArgumentException> { resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy")) }
    }

    @Test
    fun `none is allowed only in development`() {
        assertEquals(TaskApiAuthMode.NONE, resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development")))
        assertFailsWith<IllegalArgumentException> { resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "server")) }
        assertFailsWith<IllegalArgumentException> { resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "none")) }
    }

    @Test
    fun `unknown auth mode fails`() {
        assertFailsWith<IllegalArgumentException> { resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "basic")) }
    }

    @Test
    fun `oidc-jwt with a missing required setting fails at startup`() {
        for (missing in listOf("KIVRA_OIDC_ISSUER", "KIVRA_OIDC_AUDIENCE", "KIVRA_OIDC_REQUIRED_ROLE")) {
            assertFailsWith<IllegalArgumentException>(missing) { resolveTaskApiIdentity(oidcEnv - missing) }
        }
        assertFailsWith<IllegalArgumentException> { resolveTaskApiIdentity(oidcEnv + ("KIVRA_OIDC_AUDIENCE" to "  ")) }
    }

    @Test
    fun `oidc-jwt required role must be client colon role`() {
        for (bad in listOf("admin", "admin-panel:", ":admin", "a:b:c")) {
            assertFailsWith<IllegalArgumentException>(bad) { resolveTaskApiIdentity(oidcEnv + ("KIVRA_OIDC_REQUIRED_ROLE" to bad)) }
        }
    }

    @Test
    fun `oidc-jwt config defaults the JWKS URL to the realm certs endpoint`() {
        val config = OidcJwtConfig.fromEnv(oidcEnv)
        assertEquals("${OidcTestTokens.ISSUER}/protocol/openid-connect/certs", config.jwksUrl)
        assertEquals("admin-panel", config.requiredClient)
        assertEquals("admin", config.requiredRole)
        assertEquals("https://example.test/jwks", OidcJwtConfig.fromEnv(oidcEnv + ("KIVRA_OIDC_JWKS_URL" to "https://example.test/jwks")).jwksUrl)
    }

    @Test
    fun `oidc-jwt identity resolves without fetching the JWKS`() {
        // No network is touched here: the JWKS is fetched only on the first token it has to check.
        assertNotNull(resolveTaskApiIdentity(oidcEnv))
    }

    @Test
    fun `oidc-jwt identity ignores the forged identity headers`() = runBlocking {
        val identity = assertNotNull(resolveTaskApiIdentity(oidcEnv, OidcTestTokens.jwks()))
        val forged = headersOf("X-Auth-Request-Email" to listOf("admin@example.com"), "X-Auth-Request-User" to listOf("admin"))
        assertNull(identity.principal(forged))
    }

    @Test
    fun `principal is the email, else the user, and invalid values are rejected`() {
        val mode = TaskApiAuthMode.OAUTH2_PROXY
        assertEquals("alice@example.com", principalFromHeaders(mode, "alice@example.com", "alice"))
        assertEquals("alice", principalFromHeaders(mode, null, "alice"))
        assertEquals("alice", principalFromHeaders(mode, "  ", "alice"))
        assertNull(principalFromHeaders(mode, null, null))
        assertNull(principalFromHeaders(mode, "", ""))
        assertNull(principalFromHeaders(mode, "bad\u0007name", null))
        assertNull(principalFromHeaders(mode, "a".repeat(MAX_PRINCIPAL_LENGTH + 1), null))
        assertEquals("a".repeat(MAX_PRINCIPAL_LENGTH), principalFromHeaders(mode, "a".repeat(MAX_PRINCIPAL_LENGTH), null))
    }

    @Test
    fun `none mode ignores headers and uses the development principal`() {
        assertEquals(DEVELOPMENT_ANONYMOUS_PRINCIPAL, principalFromHeaders(TaskApiAuthMode.NONE, null, null))
    }
}
