package net.kigawa.kivra.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSettingsTest {
    /** Fails if any MCP variable other than the switch is read. Used for the off case. */
    private class TrapEnv(private val base: Map<String, String>) : Map<String, String> by base {
        override fun get(key: String): String? {
            if ((key.startsWith("KIVRA_MCP_") || key == McpSettings.ENV_AGENT_ROLE) && key != McpSettings.ENV_ENABLED) {
                throw AssertionError("an MCP variable was read while the endpoint is off")
            }
            return base[key]
        }
    }

    private val enabledEnv = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        McpSettings.ENV_ENABLED to "true",
        McpSettings.ENV_AUDIENCE to "https://kivra-dev.kigawa.net/mcp",
        McpSettings.ENV_ALLOWED_HOSTS to "kivra-dev.kigawa.net",
    )

    @Test
    fun `unset, false and empty switches are off and read nothing beyond the switch`() {
        for (value in listOf(null, "", "false", "  ")) {
            val env = (value?.let { mapOf(McpSettings.ENV_ENABLED to it) } ?: emptyMap()) + (McpSettings.ENV_AUDIENCE to "x")
            val settings = McpSettings.fromEnv(TrapEnv(env))
            assertFalse(settings.enabled)
        }
    }

    @Test
    fun `off ignores a broken MCP configuration`() {
        val settings = McpSettings.fromEnv(TrapEnv(mapOf(McpSettings.ENV_ENABLED to "false")))
        assertFalse(settings.enabled)
        assertNull(settings.audience)
    }

    @Test
    fun `an unknown switch value is refused`() {
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(mapOf(McpSettings.ENV_ENABLED to "yes")) }
    }

    @Test
    fun `enabled needs oidc-jwt`() {
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(enabledEnv - "KIVRA_AUTH_MODE") }
        assertFailsWith<Exception> { McpSettings.fromEnv(enabledEnv + ("KIVRA_AUTH_MODE" to "none")) }
    }

    @Test
    fun `enabled reads the audience, hosts and origins, and derives the metadata URL from the audience origin`() {
        val settings = McpSettings.fromEnv(
            enabledEnv + (McpSettings.ENV_ALLOWED_HOSTS to " Kivra-Dev.Kigawa.net ") +
                (McpSettings.ENV_ALLOWED_ORIGINS to "https://kivra-dev.kigawa.net, http://localhost:3000"),
        )
        assertTrue(settings.enabled)
        assertEquals(setOf("kivra-dev.kigawa.net"), settings.allowedHosts)
        assertEquals(setOf("https://kivra-dev.kigawa.net", "http://localhost:3000"), settings.allowedOrigins)
        assertEquals("https://kivra-dev.kigawa.net", settings.publicOrigin)
        assertEquals("https://kivra-dev.kigawa.net/.well-known/oauth-protected-resource", settings.resourceMetadataUrl)
        assertEquals(McpSettings.DEFAULT_RATE_PER_MINUTE, settings.ratePerMinute)
    }

    @Test
    fun `the audience must be https and end in slash mcp`() {
        for (bad in listOf("http://kivra-dev.kigawa.net/mcp", "https://kivra-dev.kigawa.net/", "https://kivra-dev.kigawa.net/mcp?x=1", "not a url")) {
            assertFailsWith<Exception>(bad) { McpSettings.fromEnv(enabledEnv + (McpSettings.ENV_AUDIENCE to bad)) }
        }
    }

    @Test
    fun `the audience is required when enabled`() {
        assertFailsWith<IllegalStateException> { McpSettings.fromEnv(enabledEnv - McpSettings.ENV_AUDIENCE) }
    }

    @Test
    fun `allowed hosts are required and must have the host shape`() {
        assertFailsWith<IllegalArgumentException> { McpSettings.fromEnv(enabledEnv - McpSettings.ENV_ALLOWED_HOSTS) }
        assertFailsWith<IllegalArgumentException> {
            McpSettings.fromEnv(enabledEnv + (McpSettings.ENV_ALLOWED_HOSTS to "kivra-dev.kigawa.net/mcp"))
        }
    }

    @Test
    fun `origins must have the origin shape`() {
        assertFailsWith<IllegalArgumentException> {
            McpSettings.fromEnv(enabledEnv + (McpSettings.ENV_ALLOWED_ORIGINS to "kivra-dev.kigawa.net"))
        }
    }

    @Test
    fun `the rate must be a whole number from 1 to 6000`() {
        assertEquals(5, McpSettings.fromEnv(enabledEnv + (McpSettings.ENV_RATE_PER_MINUTE to "5")).ratePerMinute)
        for (bad in listOf("0", "6001", "x", "1.5")) {
            assertFailsWith<Exception>(bad) { McpSettings.fromEnv(enabledEnv + (McpSettings.ENV_RATE_PER_MINUTE to bad)) }
        }
    }

    @Test
    fun `an invalid setting names the variable and never its value`() {
        val secret = "SECRET-VALUE-MARKER"
        val error = assertFailsWith<Exception> {
            McpSettings.fromEnv(enabledEnv + (McpSettings.ENV_RATE_PER_MINUTE to secret))
        }
        assertFalse(error.message.orEmpty().contains(secret))
        assertTrue(error.message.orEmpty().contains(McpSettings.ENV_RATE_PER_MINUTE))
    }
}
