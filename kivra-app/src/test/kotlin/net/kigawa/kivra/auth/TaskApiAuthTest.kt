package net.kigawa.kivra.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TaskApiAuthTest {
    @Test
    fun `unset auth mode registers no Task API`() {
        assertNull(resolveTaskApiAuthMode(emptyMap()))
        assertNull(resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to " ")))
    }

    @Test
    fun `oauth2-proxy mode resolves in any runtime`() {
        assertEquals(TaskApiAuthMode.OAUTH2_PROXY, resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy")))
        assertEquals(TaskApiAuthMode.OAUTH2_PROXY, resolveTaskApiAuthMode(mapOf("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "server")))
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
