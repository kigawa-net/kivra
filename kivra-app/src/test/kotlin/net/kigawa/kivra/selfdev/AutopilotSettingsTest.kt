package net.kigawa.kivra.selfdev

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

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

    /** The switch, plus the CLI cost ledger's settings, which the autopilot needs (ADR 0011 section 5.3). [pairs] override them. */
    private fun autopilotEnv(vararg pairs: Pair<String, String>): Map<String, String> = mapOf(
        "KIVRA_SELFDEV_AUTOPILOT_ENABLED" to "true",
    ) + costLedgerEnv() + pairs

    private fun costLedgerEnv(): Map<String, String> = mapOf(
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-18",
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/nonexistent/registry.json",
    )

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

    // ---- the CLI cost ledger (ADR 0011 section 5.3) ---------------------------------------------------------------------------

    @Test
    fun `enabling without the cost ledger is refused, and the message names each missing variable but no value`() {
        val settingsVars = listOf(
            "KIVRA_AGENT_API_ENABLED", "KIVRA_FX_USD_JPY", "KIVRA_FX_AS_OF", "KIVRA_SELFDEV_MODEL_REGISTRY_PATH",
        )
        for (name in settingsVars) {
            val env = autopilotEnv().filterKeys { it != name }
            val refused = assertFailsWith<IllegalStateException>(name) { AutopilotSettings.fromEnv(env, cliSelfDev) }
            assertTrue(refused.message!!.contains(name), refused.message)
            assertFalse(refused.message!!.contains("150"), refused.message)
            assertFalse(refused.message!!.contains("2026-10-18"), refused.message)
            assertFalse(refused.message!!.contains("/nonexistent"), refused.message)
        }
        // Every missing one is named together, so the operator fixes them in one go.
        val none = assertFailsWith<IllegalStateException> {
            AutopilotSettings.fromEnv(mapOf(AutopilotSettings.ENV_ENABLED to "true"), cliSelfDev)
        }
        for (name in settingsVars) assertTrue(none.message!!.contains(name), none.message)
    }

    @Test
    fun `agent API off or set to a value other than true is refused as a missing cost ledger`() {
        for (value in listOf("false", "TRUE", "yes")) {
            val env = autopilotEnv("KIVRA_AGENT_API_ENABLED" to value)
            val refused = assertFailsWith<IllegalStateException>(value) { AutopilotSettings.fromEnv(env, cliSelfDev) }
            assertTrue(refused.message!!.contains("KIVRA_AGENT_API_ENABLED=true"), refused.message)
            assertFalse(refused.message!!.contains(value), refused.message)
        }
    }

    @Test
    fun `an expired FX rate is not refused at startup`() {
        val settings = AutopilotSettings.fromEnv(autopilotEnv("KIVRA_FX_AS_OF" to "2000-01-01"), cliSelfDev)
        assertTrue(settings.enabled)
    }

    @Test
    fun `the limits are logged once as numbers, and a PR limit that fits under the concurrent limit gives no WARN`() {
        val events = logEvents { AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev) }
        val info = events.single { it.level == Level.INFO && it.formattedMessage.startsWith("autopilot limits:") }
        assertEquals(
            "autopilot limits: maxActiveRuns=1 maxPendingPrs=3 maxConsecutiveFailures=3 maxRunsPerDay=5 budgetSharePercent=50",
            info.formattedMessage,
        )
        assertEquals(1, events.count { it.level == Level.WARN }, "the default PR limit (3) is above the default Run limit (1)")

        val fits = logEvents {
            AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_MAX_PENDING_PRS to "1"), cliSelfDev)
        }
        assertEquals(0, fits.count { it.level == Level.WARN })
    }

    @Test
    fun `a WARN when the pending PR limit is above the concurrent Run limit, and not when it is at or below it`() {
        // The default concurrent limit is 1, so the default PR limit of 3 can never be reached.
        val warned = logEvents { AutopilotSettings.fromEnv(autopilotEnv(), cliSelfDev) }.filter { it.level == Level.WARN }
        assertEquals(1, warned.size)
        assertTrue(warned.single().formattedMessage.contains("maxPendingPrs=3"), warned.single().formattedMessage)
        assertTrue(warned.single().formattedMessage.contains("maxActiveRuns=1"), warned.single().formattedMessage)

        val raised = SelfDevSettings.fromEnv(selfDevEnv(runtime = "cli") + ("KIVRA_SELFDEV_MAX_ACTIVE_RUNS" to "3"))
        val equal = logEvents { AutopilotSettings.fromEnv(autopilotEnv(), raised) }
        assertEquals(0, equal.count { it.level == Level.WARN }, "maxPendingPrs=3 equals maxActiveRuns=3: no WARN")
        val above = logEvents { AutopilotSettings.fromEnv(autopilotEnv(AutopilotSettings.ENV_MAX_PENDING_PRS to "4"), raised) }
        assertEquals(1, above.count { it.level == Level.WARN })
    }

    /** Every event of the autopilot settings logger while [block] runs. */
    private fun logEvents(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(AutopilotSettings::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previous = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
        return appender.list
    }
}
