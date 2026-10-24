package net.kigawa.kivra.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `KIVRA_AGENT_API_ENABLED` and its required settings (PR 4a). Fail-closed; no error message carries a value. */
class AgentApiSettingsTest {
    private val secret = "SECRET-VALUE-MARKER-7f3"

    private fun valid(vararg overrides: Pair<String, String?>): Map<String, String> {
        val base = mutableMapOf(
            "KIVRA_AGENT_API_ENABLED" to "true",
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            "KIVRA_OIDC_ISSUER" to "https://user.kigawa.net/realms/kigawa-net",
            "KIVRA_OIDC_AUDIENCE" to "kivra-dev",
            "KIVRA_OIDC_REQUIRED_ROLE" to "admin-panel:admin",
            "KIVRA_AGENT_AZP" to "kivra-agent",
            "KIVRA_AGENT_REQUIRED_ROLE" to "kivra-agent:agent",
        )
        for ((k, v) in overrides) if (v == null) base.remove(k) else base[k] = v
        return base
    }

    /** Fails the test when any agent variable other than the switch is read while the feature is off. */
    private class TrapEnv(private val base: Map<String, String>) : Map<String, String> by base {
        override fun get(key: String): String? {
            if (key.startsWith("KIVRA_AGENT_") && key != "KIVRA_AGENT_API_ENABLED") {
                throw AssertionError("an agent variable was read while the agent API is off")
            }
            return base[key]
        }
    }

    @Test
    fun `unset or false is off, and reads no other agent variable`() {
        assertFalse(AgentApiSettings.fromEnv(TrapEnv(emptyMap())).enabled)
        assertFalse(AgentApiSettings.fromEnv(TrapEnv(mapOf("KIVRA_AGENT_API_ENABLED" to "false"))).enabled)
        assertFalse(AgentApiSettings.fromEnv(TrapEnv(mapOf("KIVRA_AGENT_API_ENABLED" to "  "))).enabled)
    }

    @Test
    fun `any value other than true or false is a startup error that names no value`() {
        for (raw in listOf("TRUE", "1", "yes", secret)) {
            val e = assertFailsWith<IllegalStateException> { AgentApiSettings.fromEnv(mapOf("KIVRA_AGENT_API_ENABLED" to raw)) }
            assertFalse(e.message!!.contains(raw), "the message must not carry the value")
        }
    }

    @Test
    fun `enabled with the full settings builds the agent auth config`() {
        val settings = AgentApiSettings.fromEnv(valid())
        assertTrue(settings.enabled)
        val auth = settings.auth!!
        assertEquals("kivra-agent", auth.azp)
        assertEquals("kivra-agent", auth.requiredClient)
        assertEquals("agent", auth.requiredRole)
        assertEquals("kivra-dev", auth.audience)
        assertEquals("https://user.kigawa.net/realms/kigawa-net/protocol/openid-connect/certs", auth.jwksUrl)
    }

    @Test
    fun `enabled requires oidc-jwt, and an operator auth mode is refused`() {
        val e = assertFailsWith<IllegalStateException> { AgentApiSettings.fromEnv(valid("KIVRA_AUTH_MODE" to null)) }
        assertTrue(e.message!!.contains("KIVRA_AUTH_MODE"))
        assertFailsWith<IllegalStateException> {
            AgentApiSettings.fromEnv(
                valid("KIVRA_AUTH_MODE" to "oauth2-proxy", "KIVRA_RUNTIME_ENV" to "development"),
            )
        }
        assertFailsWith<IllegalArgumentException> { AgentApiSettings.fromEnv(valid("KIVRA_AUTH_MODE" to "none")) }
    }

    @Test
    fun `each required setting is refused when it is missing, and the message names only the variable`() {
        for (name in listOf("KIVRA_OIDC_ISSUER", "KIVRA_OIDC_AUDIENCE", "KIVRA_AGENT_AZP", "KIVRA_AGENT_REQUIRED_ROLE")) {
            val env = valid(name to null, "KIVRA_OIDC_AUDIENCE" to (if (name == "KIVRA_OIDC_AUDIENCE") null else secret))
            val e = assertFailsWith<Exception>(name) { AgentApiSettings.fromEnv(env) }
            assertTrue(e.message!!.contains(name), name)
            assertFalse(e.message!!.contains(secret), "the message must not carry another value: $name")
        }
        // A blank value is missing too.
        assertFailsWith<IllegalStateException> { AgentApiSettings.fromEnv(valid("KIVRA_AGENT_AZP" to "   ")) }
    }

    @Test
    fun `the required role must be client colon role, and must differ from the operator role`() {
        for (bad in listOf("kivra-agent", "kivra-agent:", ":agent", "a:b:c", secret)) {
            val e = assertFailsWith<IllegalArgumentException> { AgentApiSettings.fromEnv(valid("KIVRA_AGENT_REQUIRED_ROLE" to bad)) }
            assertFalse(e.message!!.contains(bad), "the message must not carry the value")
        }
        val same = assertFailsWith<IllegalArgumentException> {
            AgentApiSettings.fromEnv(valid("KIVRA_AGENT_REQUIRED_ROLE" to "admin-panel:admin"))
        }
        assertTrue(same.message!!.contains("KIVRA_AGENT_REQUIRED_ROLE"))
        assertFalse(same.message!!.contains("admin-panel"))
    }

    @Test
    fun `the agent role must differ from the member role, and a member role alone makes the disabled API read nothing`() {
        val same = assertFailsWith<IllegalArgumentException> {
            AgentApiSettings.fromEnv(valid("KIVRA_OIDC_MEMBER_ROLE" to " kivra-agent:agent"))
        }
        assertTrue(same.message!!.contains("KIVRA_OIDC_MEMBER_ROLE"))
        assertFalse(same.message!!.contains("kivra-agent"))
        assertTrue(AgentApiSettings.fromEnv(valid("KIVRA_OIDC_MEMBER_ROLE" to "kivra:member")).enabled)
        assertFalse(AgentApiSettings.fromEnv(TrapEnv(mapOf("KIVRA_OIDC_MEMBER_ROLE" to "kivra:member"))).enabled)
    }

    @Test
    fun `the azp must be a plain identifier of bounded length`() {
        assertFailsWith<IllegalArgumentException> { AgentApiSettings.fromEnv(valid("KIVRA_AGENT_AZP" to "x".repeat(129))) }
        assertFailsWith<IllegalArgumentException> { AgentApiSettings.fromEnv(valid("KIVRA_AGENT_AZP" to "a\nb")) }
    }

    @Test
    fun `the disabled settings expose no auth config`() {
        assertNull(AgentApiSettings.fromEnv(emptyMap()).auth)
    }

    @Test
    fun `the reconcile interval and the result retention default to 60 seconds and 30 days, and take their bounds`() {
        val defaults = AgentApiSettings.fromEnv(valid())
        assertEquals(java.time.Duration.ofSeconds(60), defaults.reconcileInterval)
        assertEquals(java.time.Duration.ofDays(30), defaults.resultRetention)

        val bounds = AgentApiSettings.fromEnv(
            valid("KIVRA_CODING_RECONCILE_INTERVAL_SECONDS" to "10", "KIVRA_CODING_RESULT_RETENTION_DAYS" to "365"),
        )
        assertEquals(java.time.Duration.ofSeconds(10), bounds.reconcileInterval)
        assertEquals(java.time.Duration.ofDays(365), bounds.resultRetention)
        val upper = AgentApiSettings.fromEnv(
            valid("KIVRA_CODING_RECONCILE_INTERVAL_SECONDS" to "3600", "KIVRA_CODING_RESULT_RETENTION_DAYS" to "1"),
        )
        assertEquals(java.time.Duration.ofSeconds(3600), upper.reconcileInterval)
        assertEquals(java.time.Duration.ofDays(1), upper.resultRetention)
    }

    @Test
    fun `an out-of-range or non-integer interval or retention is a startup error that names only the variable`() {
        val cases = listOf(
            "KIVRA_CODING_RECONCILE_INTERVAL_SECONDS" to "9",
            "KIVRA_CODING_RECONCILE_INTERVAL_SECONDS" to "3601",
            "KIVRA_CODING_RECONCILE_INTERVAL_SECONDS" to "1.5",
            "KIVRA_CODING_RESULT_RETENTION_DAYS" to "-7",
            "KIVRA_CODING_RESULT_RETENTION_DAYS" to "366",
            "KIVRA_CODING_RESULT_RETENTION_DAYS" to secret,
        )
        for ((name, value) in cases) {
            val error = assertFailsWith<IllegalStateException> { AgentApiSettings.fromEnv(valid(name to value)) }
            assertTrue(error.message!!.contains(name), name)
            assertFalse(error.message!!.contains(value), "the value is never named: $name")
        }
    }
}
