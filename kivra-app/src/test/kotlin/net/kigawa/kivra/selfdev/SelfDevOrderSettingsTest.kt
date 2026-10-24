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

    // ---- child limits (ADR 0015 section 2.7, stage 2) ----------------------------------------------------------------------

    private val onEnv = mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to approver)

    @Test
    fun `the child limits default to no children (depth 0), 5 per parent and 15 per tree`() {
        val settings = OrderSettings.fromEnv(onEnv, enabledAutopilot, null)
        assertEquals(0, settings.maxDepth)
        assertEquals(5, settings.maxChildren)
        assertEquals(15, settings.maxTree)
    }

    @Test
    fun `the child limits are read when the orders are on, within their ranges`() {
        val settings = OrderSettings.fromEnv(
            onEnv + mapOf(
                OrderSettings.ENV_MAX_DEPTH to "2",
                OrderSettings.ENV_MAX_CHILDREN to "20",
                OrderSettings.ENV_MAX_TREE to "100",
            ),
            enabledAutopilot,
            null,
        )
        assertEquals(2, settings.maxDepth)
        assertEquals(20, settings.maxChildren)
        assertEquals(100, settings.maxTree)
    }

    @Test
    fun `a child limit out of its range or not an integer is a startup failure, and the failure never shows the value`() {
        val outOfRange = listOf(
            OrderSettings.ENV_MAX_DEPTH to listOf("-1", "3", "not-a-number"),
            OrderSettings.ENV_MAX_CHILDREN to listOf("0", "21", "not-a-number"),
            OrderSettings.ENV_MAX_TREE to listOf("0", "101", "not-a-number"),
        )
        for ((name, values) in outOfRange) {
            for (bad in values) {
                val e = assertFailsWith<IllegalStateException> {
                    OrderSettings.fromEnv(onEnv + (name to bad), enabledAutopilot, null)
                }
                assertTrue(e.message!!.contains(name))
            }
        }
        // The value itself is never in the message. (A short number can be in the range text, so only a word is checked here.)
        val secret = assertFailsWith<IllegalStateException> {
            OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_MAX_TREE to "tree-secret-value"), enabledAutopilot, null)
        }
        assertFalse(secret.message!!.contains("tree-secret-value"))
    }

    @Test
    fun `the child limits are not read while the orders are off`() {
        val off = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "false", OrderSettings.ENV_MAX_DEPTH to "not-a-number", OrderSettings.ENV_MAX_TREE to "0"),
            enabledAutopilot,
            null,
        )
        assertFalse(off.enabled)
        assertEquals(0, off.maxDepth)
    }

    @Test
    fun `the automatic child stop is off by default, and on only with exactly true`() {
        assertFalse(OrderSettings.fromEnv(onEnv, enabledAutopilot, null).autoStopEnabled)
        assertFalse(OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_AUTO_STOP to "false"), enabledAutopilot, null).autoStopEnabled)
        assertTrue(OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_AUTO_STOP to "true"), enabledAutopilot, null).autoStopEnabled)
    }

    @Test
    fun `an automatic child stop switch other than true or false is a startup failure, and the failure never shows the value`() {
        for (bad in listOf("yes", "1", "", "TRUE", "auto-stop-secret-value")) {
            val e = assertFailsWith<IllegalStateException> {
                OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_AUTO_STOP to bad), enabledAutopilot, null)
            }
            assertTrue(e.message!!.contains(OrderSettings.ENV_AUTO_STOP))
            assertFalse(e.message!!.contains("auto-stop-secret-value"))
        }
    }

    @Test
    fun `the automatic child stop switch is not read while the orders are off`() {
        val off = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "false", OrderSettings.ENV_AUTO_STOP to "not-a-switch"),
            enabledAutopilot,
            null,
        )
        assertFalse(off.autoStopEnabled)
    }

    @Test
    fun `the cost counting switch is off by default and accepts only true or false`() {
        assertFalse(OrderSettings.fromEnv(onEnv, enabledAutopilot, null).spendEnabled)
        assertFalse(OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_SPEND to "false"), enabledAutopilot, null).spendEnabled)
        assertTrue(OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_SPEND to "true"), enabledAutopilot, null).spendEnabled)
        for (bad in listOf("yes", "1", "", "TRUE", "spend-secret-value")) {
            val e = assertFailsWith<IllegalStateException> {
                OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_SPEND to bad), enabledAutopilot, null)
            }
            assertTrue(e.message!!.contains(OrderSettings.ENV_SPEND))
            assertFalse(e.message!!.contains("spend-secret-value"))
        }
    }

    @Test
    fun `the cost counting switch is not read while the orders are off`() {
        val off = OrderSettings.fromEnv(
            mapOf(OrderSettings.ENV_ENABLED to "false", OrderSettings.ENV_SPEND to "not-a-switch"),
            enabledAutopilot,
            null,
        )
        assertFalse(off.spendEnabled)
    }

    @Test
    fun `the cycle cap defaults to 1 and accepts 1 to 10`() {
        assertEquals(1, OrderSettings.fromEnv(onEnv, enabledAutopilot, null).maxCyclesCap)
        for (ok in listOf(1, 3, 10)) {
            assertEquals(ok, OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_MAX_CYCLES_CAP to "$ok"), enabledAutopilot, null).maxCyclesCap)
        }
    }

    @Test
    fun `a cycle cap out of range or not an integer is a startup failure naming only the variable`() {
        for (bad in listOf("0", "11", "-1", "abc", "2.5", "cap-secret-value")) {
            val e = assertFailsWith<IllegalStateException> {
                OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_MAX_CYCLES_CAP to bad), enabledAutopilot, null)
            }
            assertTrue(e.message!!.contains(OrderSettings.ENV_MAX_CYCLES_CAP))
            assertFalse(e.message!!.contains("cap-secret-value"))
        }
    }

    @Test
    fun `the automatic cycle start is off by default and accepts only true or false`() {
        assertFalse(OrderSettings.fromEnv(onEnv, enabledAutopilot, null).autoCycleEnabled)
        assertFalse(OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_AUTO_CYCLE to "false"), enabledAutopilot, null).autoCycleEnabled)
        assertTrue(OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_AUTO_CYCLE to "true"), enabledAutopilot, null).autoCycleEnabled)
        for (bad in listOf("yes", "1", "", "TRUE", "auto-cycle-secret-value")) {
            val e = assertFailsWith<IllegalStateException> {
                OrderSettings.fromEnv(onEnv + (OrderSettings.ENV_AUTO_CYCLE to bad), enabledAutopilot, null)
            }
            assertTrue(e.message!!.contains(OrderSettings.ENV_AUTO_CYCLE))
            assertFalse(e.message!!.contains("auto-cycle-secret-value"))
        }
    }

    @Test
    fun `the cycle cap and the automatic start are not read while the orders are off`() {
        val off = OrderSettings.fromEnv(
            mapOf(
                OrderSettings.ENV_ENABLED to "false",
                OrderSettings.ENV_MAX_CYCLES_CAP to "not-a-number",
                OrderSettings.ENV_AUTO_CYCLE to "not-a-switch",
            ),
            enabledAutopilot,
            null,
        )
        assertFalse(off.autoCycleEnabled)
        assertEquals(1, off.maxCyclesCap)
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
