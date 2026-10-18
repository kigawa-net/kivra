package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutopilotSettingsTest {

    /** Self-development on the cli runtime, with the agent API switched on (ADR 0010). Its other settings are not under test here. */
    private val cliSelfDev: SelfDevSettings by lazy { SelfDevSettings.fromEnv(selfDevEnv(runtime = "cli")) }
    private val apiSelfDev: SelfDevSettings by lazy { SelfDevSettings.fromEnv(selfDevEnv(runtime = "api")) }

    private fun selfDevEnv(runtime: String?): Map<String, String> = buildMap {
        put("KIVRA_SELFDEV_ENABLED", "true")
        put("KIVRA_AUTH_MODE", "oidc-jwt")
        put("KIVRA_SELFDEV_GITHUB_TOKEN", "test-token-not-used")
        put("KIVRA_SELFDEV_ANTHROPIC_API_KEY", "test-key-not-used")
        put("KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID", "1")
        put("KIVRA_SELFDEV_MODEL_REGISTRY_PATH", "/nonexistent/registry.json")
        put("KIVRA_FX_USD_JPY", "150")
        put("KIVRA_FX_AS_OF", "2026-10-18")
        if (runtime != null) put("KIVRA_SELFDEV_RUNTIME", runtime)
        if (runtime == "cli") put("KIVRA_AGENT_API_ENABLED", "true")
    }

    private fun autopilotEnv(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(
        "KIVRA_SELFDEV_AUTOPILOT_ENABLED" to "true",
    ) + pairs

    @Test
    fun `off by default, and off when the switch is false or blank, with no other variable read`() {
        for (env in listOf(emptyMap(), mapOf(AutopilotSettings.ENV_ENABLED to "false"), mapOf(AutopilotSettings.ENV_ENABLED to " "))) {
            val settings = AutopilotSettings.fromEnv(env, apiSelfDev)
            assertFalse(settings.enabled)
            assertEquals(null, settings.config)
        }
        // Invalid numbers and values are not read while off: the switch alone decides.
        val settings = AutopilotSettings.fromEnv(
            mapOf(AutopilotSettings.ENV_ENABLED to "false", AutopilotSettings.ENV_MAX_RUNS_PER_DAY to "not-a-number"),
            apiSelfDev,
        )
        assertFalse(settings.enabled)
    }

    @Test
    fun `a switch value other than true or false is refused`() {
        for (value in listOf("TRUE", "yes", "1", "on")) {
            assertFailsWith<IllegalStateException>(value) {
                AutopilotSettings.fromEnv(mapOf(AutopilotSettings.ENV_ENABLED to value), cliSelfDev)
            }
        }
    }

    @Test
    fun `enabling needs self-development on the cli runtime, and names the variable it lacks`() {
        val refused = assertFailsWith<IllegalStateException> {
            AutopilotSettings.fromEnv(autopilotEnv(), apiSelfDev)
        }
        assertTrue(refused.message!!.contains("KIVRA_SELFDEV_RUNTIME=cli"), refused.message)
        assertFailsWith<IllegalStateException> {
            AutopilotSettings.fromEnv(autopilotEnv(), SelfDevSettings.disabled())
        }.also { assertTrue(it.message!!.contains("KIVRA_SELFDEV_ENABLED=true"), it.message) }
    }

    @Test
    fun `enabled on the cli runtime, the defaults are required, auto is not the default, and the numbers are the ADR defaults`() {
        val settings = AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev)
        assertTrue(settings.enabled)
        val config = settings.config!!
        assertEquals(AutopilotApproval.REQUIRED, config.approval)
        assertEquals(3, config.maxPendingPrs)
        assertEquals(3, config.maxConsecutiveFailures)
        assertEquals(5, config.maxRunsPerDay)
        assertEquals(50, config.budgetSharePercent)
    }

    @Test
    fun `the approval values are required and auto, and anything else is refused by name`() {
        assertEquals(
            AutopilotApproval.AUTO,
            AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_APPROVAL to "auto"), cliSelfDev).config!!.approval,
        )
        assertEquals(
            AutopilotApproval.REQUIRED,
            AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_APPROVAL to " required "), cliSelfDev).config!!.approval,
        )
        for (bad in listOf("always", "sometimes", "Maybe")) {
            val refused = assertFailsWith<IllegalStateException> {
                AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_APPROVAL to bad), cliSelfDev)
            }
            assertTrue(refused.message!!.contains(AutopilotSettings.ENV_APPROVAL), refused.message)
            assertFalse(refused.message!!.contains(bad), "the message must not echo the value")
        }
    }

    @Test
    fun `a denylisted task needs approval under auto too, and an allowlisted one needs it only under required`() {
        val auto = AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_APPROVAL to "auto"), cliSelfDev).config!!
        assertTrue(auto.requiresApproval(denylisted = true))
        assertFalse(auto.requiresApproval(denylisted = false))
        val required = AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev).config!!
        assertTrue(required.requiresApproval(denylisted = true))
        assertTrue(required.requiresApproval(denylisted = false))
    }

    @Test
    fun `the numbers accept their bounds and refuse values outside them, naming the variable`() {
        val config = AutopilotSettings.fromEnv(
            autopilotEnv(
                AutopilotSettings.ENV_MAX_PENDING_PRS to "20",
                AutopilotSettings.ENV_MAX_CONSECUTIVE_FAILURES to "1",
                AutopilotSettings.ENV_MAX_RUNS_PER_DAY to "1",
                AutopilotSettings.ENV_BUDGET_SHARE_PERCENT to "100",
            ),
            cliSelfDev,
        ).config!!
        assertEquals(20, config.maxPendingPrs)
        assertEquals(1, config.maxConsecutiveFailures)
        assertEquals(1, config.maxRunsPerDay)
        assertEquals(100, config.budgetSharePercent)

        val cases = listOf(
            AutopilotSettings.ENV_MAX_PENDING_PRS to "0",
            AutopilotSettings.ENV_MAX_PENDING_PRS to "21",
            AutopilotSettings.ENV_MAX_CONSECUTIVE_FAILURES to "0",
            AutopilotSettings.ENV_MAX_RUNS_PER_DAY to "101",
            AutopilotSettings.ENV_BUDGET_SHARE_PERCENT to "0",
            AutopilotSettings.ENV_BUDGET_SHARE_PERCENT to "101",
            AutopilotSettings.ENV_BUDGET_SHARE_PERCENT to "fifty",
        )
        for ((name, value) in cases) {
            val refused = assertFailsWith<IllegalStateException>("$name=$value") {
                AutopilotSettings.fromEnv(autopilotEnv(name to value), cliSelfDev)
            }
            assertTrue(refused.message!!.contains(name), refused.message)
        }
    }

    @Test
    fun `the settings never print their values`() {
        assertEquals("AutopilotSettings(enabled=false)", AutopilotSettings.disabled().toString())
    }
}
