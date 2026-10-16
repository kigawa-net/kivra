package net.kigawa.kivra.selfdev

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        assertNull(off.githubAuth)
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
        val auth = assertIs<GitHubAuth.Pat>(s.githubAuth)
        assertFalse(TOKEN in auth.toString())
        assertFalse(TOKEN in s.config.toString())
        assertEquals("GitHubAuth.Pat(***)", auth.toString())
        assertEquals("Secret(***)", auth.token.toString())
    }

    private val appKeyPem: String by lazy {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
    }

    private fun appEnv(vararg overrides: Pair<String, String?>): Map<String, String> = enabledEnv(
        "KIVRA_SELFDEV_GITHUB_TOKEN" to null,
        "KIVRA_SELFDEV_GITHUB_APP_ID" to "123456",
        "KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID" to "987654",
        "KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY" to appKeyPem,
        *overrides,
    )

    @Test
    fun `a GitHub App configuration is accepted and carries no key text in its string forms`() {
        val s = SelfDevSettings.fromEnv(appEnv())
        val auth = assertIs<GitHubAuth.App>(s.githubAuth)
        assertEquals("123456", auth.appId)
        assertEquals(987654L, auth.installationId)
        assertEquals(appKeyPem.trim(), auth.privateKeyPem.reveal())
        assertEquals("GitHubAuth.App(***)", auth.toString())
        assertFalse("PRIVATE KEY" in s.toString() || "PRIVATE KEY" in auth.toString())
    }

    @Test
    fun `the App private key may be given as a file path, which is read`() {
        val file = Files.createTempFile("app-key", ".pem").toFile()
        try {
            file.writeText(appKeyPem)
            val auth = assertIs<GitHubAuth.App>(SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY" to file.absolutePath)).githubAuth)
            assertEquals(appKeyPem, auth.privateKeyPem.reveal())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a PAT and any App variable together is a startup failure, and so is neither`() {
        val both = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_TOKEN" to TOKEN)) }
        assertTrue("KIVRA_SELFDEV_GITHUB_TOKEN" in both.message!!)
        assertFalse(TOKEN in both.message!!)
        assertFailsWith<IllegalStateException> {
            SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_TOKEN" to TOKEN, "KIVRA_SELFDEV_GITHUB_APP_ID" to null))
        }
        val neither = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_GITHUB_TOKEN" to null)) }
        assertTrue("KIVRA_SELFDEV_GITHUB_TOKEN" in neither.message!!)
        assertTrue("KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY" in neither.message!!)
    }

    @Test
    fun `a partial App configuration names the missing variable and no value`() {
        for (name in listOf("KIVRA_SELFDEV_GITHUB_APP_ID", "KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID", "KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY")) {
            val e = assertFailsWith<IllegalStateException>(name) { SelfDevSettings.fromEnv(appEnv(name to null)) }
            assertTrue(name in e.message!!, e.message)
            assertFalse("123456" in e.message!! || "BEGIN" in e.message!!)
        }
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_APP_ID" to "12a")) }
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_APP_INSTALLATION_ID" to "0")) }
    }

    @Test
    fun `an unusable private key is refused without echoing it`() {
        val bogus = "-----BEGIN PRIVATE KEY-----\nQUJDREVGR0hJSktMTU5PUA==\n-----END PRIVATE KEY-----"
        val e = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY" to bogus)) }
        assertTrue("KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY" in e.message!!)
        assertFalse("QUJD" in e.message!!)
        val missingFile = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(appEnv("KIVRA_SELFDEV_GITHUB_APP_PRIVATE_KEY" to "/nonexistent/key.pem")) }
        assertFalse("/nonexistent" in missingFile.message!!)
    }
}
