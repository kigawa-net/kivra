package net.kigawa.kivra.selfdev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KIVRA_SELFDEV_RUNTIME` (ADR 0010): `api` by default, `cli` refused until the agent API is wired. */
class SelfDevRuntimeSettingTest {
    private fun enabledEnv(vararg overrides: Pair<String, String?>): Map<String, String> {
        val base = mutableMapOf(
            "KIVRA_SELFDEV_ENABLED" to "true",
            "KIVRA_AUTH_MODE" to "oidc-jwt",
            "KIVRA_SELFDEV_GITHUB_TOKEN" to "ghp_RUNTIME_SETTING_TEST_TOKEN_1234",
            "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to "sk-ant-RUNTIME_SETTING_TEST_KEY_5678",
            "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to "343288952",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/etc/kivra/model-registry.yaml",
            "KIVRA_FX_USD_JPY" to "150",
            "KIVRA_FX_AS_OF" to "2026-10-01",
        )
        for ((k, v) in overrides) if (v == null) base.remove(k) else base[k] = v
        return base
    }

    @Test
    fun `the runtime is api when the variable is unset or empty or api`() {
        assertEquals(SelfDevRuntime.API, SelfDevSettings.fromEnv(enabledEnv()).config!!.runtime)
        assertEquals(SelfDevRuntime.API, SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_RUNTIME" to "")).config!!.runtime)
        assertEquals(SelfDevRuntime.API, SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_RUNTIME" to " api ")).config!!.runtime)
    }

    @Test
    fun `cli is refused at startup, with a message that names the variable and not the value`() {
        val error = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_RUNTIME" to "cli")) }
        assertTrue("KIVRA_SELFDEV_RUNTIME" in error.message!!, error.message)
        assertTrue("ADR 0010" in error.message!!, error.message)
    }

    @Test
    fun `any other value is a startup error that names the variable only`() {
        val error = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_RUNTIME" to "bogus-value")) }
        assertTrue("KIVRA_SELFDEV_RUNTIME" in error.message!!, error.message)
        assertFalse("bogus-value" in error.message!!, error.message)
    }

    @Test
    fun `while the feature is off the runtime variable is not read, so it cannot refuse anything`() {
        val off = SelfDevSettings.fromEnv(mapOf("KIVRA_SELFDEV_ENABLED" to "false", "KIVRA_SELFDEV_RUNTIME" to "cli"))
        assertFalse(off.enabled)
        assertEquals(null, off.config)
    }
}
