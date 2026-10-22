package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order switch is fail-closed (ADR 0015 stage 1): off reads nothing else, and on needs the autopilot and a root approver. */
class SelfDevOrderSettingsTest {

    private val approver = "1:i:kigawa"
    private val ai = "1:i:ai"

    private val enabledAutopilot: AutopilotSettings by lazy { AutopilotSettings.fromEnv(autopilotEnv(), selfDev()) }

    @Test
    fun `off by default and reads no other variable`() {
        assertFalse(OrderSettings.fromEnv(emptyMap(), AutopilotSettings.disabled(), null).enabled)
        // A broken approver value is ignored while the switch is off: nothing beyond the switch is read.
        val settings = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "false", OrderSettings.ENV_ROOT_APPROVER to "!!"),
            AutopilotSettings.disabled(),
            null,
        )
        assertFalse(settings.enabled)
        assertNull(settings.rootApprover)
    }

    @Test
    fun `a value other than true or false is a startup failure`() {
        assertFailsWith<IllegalStateException> {
            OrderSettings.fromEnv(mapOf(OrderSettings.ENV_ENABLED to "yes"), enabledAutopilot, null)
        }
    }

    @Test
    fun `with the autopilot off the switch is not read at all, so even a true value does nothing`() {
        // The default-off wiring reads only the two existing switches: a bad value here must not fail, and nothing is read.
        val settings = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "yes", OrderSettings.ENV_ROOT_APPROVER to "!!"),
            AutopilotSettings.disabled(),
            null,
        )
        assertFalse(settings.enabled)
        val trap = object : Map<String, String> by mapOf(OrderSettings.ENV_ENABLED to "true") {
            override fun get(key: String): String? = throw AssertionError("read $key while the autopilot is off")
        }
        assertFalse(OrderSettings.fromEnv(trap, AutopilotSettings.disabled(), null).enabled)
    }

    @Test
    fun `on needs a root approver, and the failure never shows a value`() {
        val e = assertFailsWith<IllegalStateException> {
            OrderSettings.fromEnv(mapOf(OrderSettings.ENV_ENABLED to "true"), enabledAutopilot, null)
        }
        assertTrue(e.message!!.contains(OrderSettings.ENV_ROOT_APPROVER))
        val bad = assertFailsWith<IllegalStateException> {
            OrderSettings.fromEnv(
                mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to "kigawa-secret-subject"),
                enabledAutopilot,
                null,
            )
        }
        assertFalse(bad.message!!.contains("kigawa-secret-subject"))
    }

    @Test
    fun `the AI principal must differ from the root approver`() {
        val e = assertFailsWith<IllegalStateException> {
            OrderSettings.fromEnv(
                mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to approver),
                enabledAutopilot,
                approver,
            )
        }
        assertFalse(e.message!!.contains(approver))
    }

    @Test
    fun `on with a valid approver is enabled, and the AI principal is optional`() {
        val without = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to approver),
            enabledAutopilot,
            null,
        )
        assertTrue(without.enabled)
        assertEquals(approver, without.rootApprover)
        val with = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to approver),
            enabledAutopilot,
            ai,
        )
        assertTrue(with.enabled)
    }

    // The AI principal is read on its own, whatever the order switch says (#311).

    @Test
    fun `the AI principal is read while the orders are off, so the Goal refusals hold`() {
        assertEquals(ai, aiPrincipalFromEnv(mapOf(OrderSettings.ENV_AI_PRINCIPAL to ai), enabledAutopilot))
        assertEquals(ai, aiPrincipalFromEnv(mapOf(OrderSettings.ENV_AI_PRINCIPAL to " $ai "), enabledAutopilot))
        assertEquals(
            ai,
            aiPrincipalFromEnv(mapOf(OrderSettings.ENV_ENABLED to "false", OrderSettings.ENV_AI_PRINCIPAL to ai), enabledAutopilot),
        )
    }

    @Test
    fun `with the autopilot off the AI principal is not read at all, as the orders are not`() {
        val trap = object : Map<String, String> by mapOf(OrderSettings.ENV_AI_PRINCIPAL to "!!") {
            override fun get(key: String): String? = throw AssertionError("read $key while the autopilot is off")
        }
        assertNull(aiPrincipalFromEnv(trap, AutopilotSettings.disabled()))
    }

    @Test
    fun `an unset or blank AI principal is null, and nothing else is read for it`() {
        assertNull(aiPrincipalFromEnv(emptyMap(), enabledAutopilot))
        assertNull(aiPrincipalFromEnv(mapOf(OrderSettings.ENV_AI_PRINCIPAL to "   "), enabledAutopilot))
    }

    @Test
    fun `a malformed AI principal is a startup failure even with the orders off, and the failure never shows the value`() {
        for (bad in listOf("kigawa-secret-subject", "1:i", "1:i:", "1:i:ai\u0007")) {
            val e = assertFailsWith<IllegalStateException> {
                aiPrincipalFromEnv(mapOf(OrderSettings.ENV_ENABLED to "false", OrderSettings.ENV_AI_PRINCIPAL to bad), enabledAutopilot)
            }
            assertTrue(e.message!!.contains(OrderSettings.ENV_AI_PRINCIPAL))
            assertFalse(e.message!!.contains("kigawa-secret-subject"))
        }
    }

    private fun selfDev(): SelfDevSettings = SelfDevSettings.fromEnv(
        mapOf(
            "KIVRA_SELFDEV_ENABLED" to "true",
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            "KIVRA_SELFDEV_GITHUB_TOKEN" to "test-token-not-used",
            "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "test-key-not-used",
            "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "1",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-18",
            "KIVRA_SELFDEV_RUNTIME" to "cli",
            "KIVRA_AGENT_API_ENABLED" to "true",
        ),
    )

    private fun autopilotEnv(): Map<String, String> = mapOf(
        AutopilotSettings.ENV_ENABLED to "true",
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
    )
}
