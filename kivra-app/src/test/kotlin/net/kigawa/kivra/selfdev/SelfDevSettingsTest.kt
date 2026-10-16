package net.kigawa.kivra.selfdev

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.selfdev.SelfDevSettings.Companion.ENV_ENABLED

/** Fails the test if any self-development variable other than the switch, or any FX variable, is read while the feature is off. */
private class TrapEnv(private val base: Map<String, String>) : Map<String, String> by base {
    override fun get(key: String): String? {
        if ((key.startsWith("KIVRA_SELFDEV_") && key != ENV_ENABLED) || key.startsWith("KIVRA_FX_")) {
            throw AssertionError("a self-development variable was read while the feature is off")
        }
        return base[key]
    }
}

private const val TOKEN = "ghp_TOKEN_VALUE_MUST_NOT_LEAK_1234"
private const val API_KEY = "sk-ant-KEY_VALUE_MUST_NOT_LEAK_5678"

private fun enabledEnv(vararg overrides: Pair<String, String?>): Map<String, String> {
    val base = mutableMapOf(
        "KIVRA_SELFDEV_ENABLED" to "true",
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_SELFDEV_GITHUB_TOKEN" to TOKEN,
        "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to API_KEY,
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/etc/kivra/model-registry.yaml",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-01",
    )
    for ((k, v) in overrides) if (v == null) base.remove(k) else base[k] = v
    return base
}

class SelfDevSettingsTest {
    @Test
    fun `unset or false keeps the feature off and reads no self-development variable`() {
        assertFalse(SelfDevSettings.fromEnv(TrapEnv(emptyMap())).enabled)
        assertFalse(SelfDevSettings.fromEnv(TrapEnv(mapOf("KIVRA_SELFDEV_ENABLED" to "false"))).enabled)
        assertFalse(SelfDevSettings.fromEnv(TrapEnv(mapOf("KIVRA_SELFDEV_ENABLED" to ""))).enabled)
        val off = SelfDevSettings.fromEnv(TrapEnv(emptyMap()))
        assertNull(off.config)
        assertNull(off.githubToken)
        assertNull(off.anthropicApiKeyEnvVar)
    }

    @Test
    fun `a switch value other than true or false is a startup error`() {
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(mapOf("KIVRA_SELFDEV_ENABLED" to "yes")) }
    }

    @Test
    fun `enabled requires oidc-jwt, and a development environment is refused`() {
        val none = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_AUTH_MODE" to "none")) }
        assertTrue("KIVRA_AUTH_MODE" in none.message!!)
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_AUTH_MODE" to null)) }
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_AUTH_MODE" to "oauth2-proxy")) }
        val dev = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_RUNTIME_ENV" to "development")) }
        assertTrue("KIVRA_RUNTIME_ENV" in dev.message!!)
    }

    @Test
    fun `each required setting is named in the failure, and no value is`() {
        for (name in listOf(
            "KIVRA_SELFDEV_GITHUB_TOKEN",
            "KIVRA_SELFDEV_ANTHROPIC_API_KEY",
            "KIVRA_SELFDEV_MODEL_REGISTRY_PATH",
            "KIVRA_FX_USD_JPY",
            "KIVRA_FX_AS_OF",
        )) {
            val e = assertFailsWith<Exception>(name) { SelfDevSettings.fromEnv(enabledEnv(name to null)) }
            val message = e.message.orEmpty()
            assertFalse(TOKEN in message || API_KEY in message, name)
            assertTrue(name in message, "$name not named in: $message")
        }
    }

    @Test
    fun `the target repository and the interval are validated, and the defaults are fixed`() {
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_TARGET_REPO" to "not a repo")) }
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_ADVANCE_INTERVAL_SECONDS" to "5")) }
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_MAX_ACTIVE_RUNS" to "0")) }

        val s = SelfDevSettings.fromEnv(enabledEnv())
        assertTrue(s.enabled)
        assertEquals("kigawa-net", s.config!!.repo.owner)
        assertEquals("kivra", s.config!!.repo.repo)
        assertEquals("main", s.config!!.baseBranch)
        assertEquals(listOf("README.md", "docs/"), s.config!!.contextPathPrefixes)
        assertEquals(102_400L, s.config!!.maxContextFileBytes)
        assertEquals(Duration.ofDays(7), s.config!!.prReviewTimeout)
        assertEquals(Duration.ofSeconds(30), s.advanceInterval)
        assertEquals(1, s.maxActiveRuns)
    }

    @Test
    fun `secrets never appear in the settings text`() {
        val s = SelfDevSettings.fromEnv(enabledEnv())
        assertFalse(TOKEN in s.toString() || API_KEY in s.toString())
        assertFalse(TOKEN in s.githubToken.toString())
        assertFalse(TOKEN in s.config.toString())
        assertEquals("Secret(***)", s.githubToken.toString())
    }
}
