package net.kigawa.kivra.auth

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `KIVRA_CODING_MONITOR_RETENTION_DAYS` (ADR 0012 section 3): default 30, whole days from 1 to 365, errors name the variable only. */
class AgentApiMonitorRetentionTest {
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

    @Test
    fun `the monitor retention defaults to 30 days and accepts whole days from 1 to 365`() {
        assertEquals(Duration.ofDays(30), AgentApiSettings.fromEnv(valid()).monitorRetention)
        assertEquals(Duration.ofDays(1), AgentApiSettings.fromEnv(valid("KIVRA_CODING_MONITOR_RETENTION_DAYS" to "1")).monitorRetention)
        assertEquals(Duration.ofDays(365), AgentApiSettings.fromEnv(valid("KIVRA_CODING_MONITOR_RETENTION_DAYS" to "365")).monitorRetention)
    }

    @Test
    fun `an out-of-range or non-integer value fails the startup and the message names only the variable`() {
        for (raw in listOf("0", "366", "-1", "1.5", "forty-two")) {
            val error = assertFailsWith<IllegalStateException> {
                AgentApiSettings.fromEnv(valid("KIVRA_CODING_MONITOR_RETENTION_DAYS" to raw))
            }
            assertEquals(true, error.message!!.contains("KIVRA_CODING_MONITOR_RETENTION_DAYS"))
            assertEquals(false, error.message!!.contains(raw), "the value must not be in the message: $raw")
        }
    }

    @Test
    fun `the retention is not read when the agent API is off`() {
        // A bad value is ignored while the switch is off: nothing beyond the switch is read.
        assertEquals(Duration.ofDays(30), AgentApiSettings.fromEnv(mapOf("KIVRA_CODING_MONITOR_RETENTION_DAYS" to "bad")).monitorRetention)
    }
}
