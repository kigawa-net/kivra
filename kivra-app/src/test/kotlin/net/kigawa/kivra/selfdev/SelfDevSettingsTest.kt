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
private const val BOT_PR_AUTHOR_ID = "343288952"

private fun enabledEnv(vararg overrides: Pair<String, String?>): Map<String, String> {
    val base = mutableMapOf(
        "KIVRA_SELFDEV_ENABLED" to "true",
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_SELFDEV_GITHUB_TOKEN" to TOKEN,
        "KIVRA_SELFDEV_ANTHROPIC_API_KEY" to API_KEY,
        "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to BOT_PR_AUTHOR_ID,
        "KIVRA_SELFDEV_MODEL_REGISTRY_PATH" to "/etc/kivra/model-registry.yaml",
        "KIVRA_FX_USD_JPY" to "150",
        "KIVRA_FX_AS_OF" to "2026-10-01",
    )
    for ((k, v) in overrides) if (v == null) base.remove(k) else base[k] = v
    return base
}

class SelfDevSettingsTest {
    @Test
    fun `the pull request branch update is off by default, only true turns it on, and any other value is a startup error`() {
        assertFalse(SelfDevSettings.fromEnv(enabledEnv()).prRebaseEnabled)
        assertFalse(SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_PR_REBASE_ENABLED" to "false")).prRebaseEnabled)
        assertTrue(SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_PR_REBASE_ENABLED" to "true")).prRebaseEnabled)
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_PR_REBASE_ENABLED" to "yes")) }
    }

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
            "KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID",
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
    fun `the PR author's bot user id is required and must be a positive integer, and no value is echoed`() {
        assertEquals(BOT_PR_AUTHOR_ID, SelfDevSettings.fromEnv(enabledEnv()).githubBotUserId!!.reveal())
        for (bad in listOf("0", "-1", "12a", "+5", "", "99999999999999999999", "bot-$BOT_PR_AUTHOR_ID")) {
            val e = assertFailsWith<IllegalStateException>(bad) {
                SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to bad))
            }
            assertTrue("KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" in e.message!!, e.message)
            assertFalse(bad.isNotEmpty() && bad in e.message!!, e.message)
        }
        val unset = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv("KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" to null)) }
        assertTrue("KIVRA_SELFDEV_GITHUB_APP_BOT_USER_ID" in unset.message!!)
        assertFalse(SelfDevSettings.fromEnv(TrapEnv(emptyMap())).enabled)
        assertNull(SelfDevSettings.fromEnv(TrapEnv(emptyMap())).githubBotUserId)
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

    private val wifVars = mapOf(
        "KIVRA_SELFDEV_ANTHROPIC_FEDERATION_RULE_ID" to "fdrl_WIF_RULE_MUST_NOT_LEAK",
        "KIVRA_SELFDEV_ANTHROPIC_ORGANIZATION_ID" to "11111111-2222-3333-4444-555555555555",
        "KIVRA_SELFDEV_ANTHROPIC_SERVICE_ACCOUNT_ID" to "svac_WIF_SA_MUST_NOT_LEAK",
        "KIVRA_SELFDEV_ANTHROPIC_IDENTITY_TOKEN_FILE" to "/nonexistent/anthropic/token",
    )

    private fun withoutStaticKey(vararg overrides: Pair<String, String?>): Map<String, String> =
        enabledEnv("KIVRA_SELFDEV_ANTHROPIC_API_KEY" to null, *overrides.toList().toTypedArray())

    @Test
    fun `workload identity federation is accepted without reading the identity token file`() {
        // /nonexistent is not read at startup: the pod may start before the projected token appears.
        val s = SelfDevSettings.fromEnv(withoutStaticKey(*wifVars.map { it.key to it.value }.toTypedArray()))
        assertTrue(s.enabled)
        assertNull(s.anthropicApiKeyEnvVar)
        val wif = assertIs<AnthropicWifSettings>(s.anthropicWif)
        assertEquals("/nonexistent/anthropic/token", wif.identityTokenFile.reveal())
        assertNull(wif.workspaceId)
        assertFalse(s.toString().contains("fdrl_WIF"))
        assertFalse(wif.toString().contains("svac_WIF") || wif.toString().contains("fdrl_WIF"))
    }

    @Test
    fun `workspace id is optional and validated when set`() {
        val s = SelfDevSettings.fromEnv(withoutStaticKey(*(wifVars.map { it.key to it.value } + ("KIVRA_SELFDEV_ANTHROPIC_WORKSPACE_ID" to "wrkspc_WS")).toTypedArray()))
        assertEquals("wrkspc_WS", assertIs<AnthropicWifSettings>(s.anthropicWif).workspaceId!!.reveal())
        val bad = assertFailsWith<IllegalStateException> {
            SelfDevSettings.fromEnv(withoutStaticKey(*(wifVars.map { it.key to it.value } + ("KIVRA_SELFDEV_ANTHROPIC_WORKSPACE_ID" to "bad-ws-value")).toTypedArray()))
        }
        assertTrue("KIVRA_SELFDEV_ANTHROPIC_WORKSPACE_ID" in bad.message!!)
        assertFalse("bad-ws-value" in bad.message!!)
    }

    @Test
    fun `exactly one anthropic method is allowed`() {
        val both = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(enabledEnv(*wifVars.map { it.key to it.value }.toTypedArray())) }
        assertTrue("KIVRA_SELFDEV_ANTHROPIC_API_KEY" in both.message!!)
        assertFalse(API_KEY in both.message!!)
        val neither = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(withoutStaticKey()) }
        assertTrue("KIVRA_SELFDEV_ANTHROPIC_API_KEY" in neither.message!!)
    }

    @Test
    fun `a partial federation setup fails and names only the variable`() {
        for (missing in wifVars.keys) {
            val e = assertFailsWith<IllegalStateException>(missing) {
                SelfDevSettings.fromEnv(withoutStaticKey(*(wifVars - missing).map { it.key to it.value }.toTypedArray()))
            }
            assertTrue(missing in e.message!!, missing)
        }
        // A workspace id alone is still a partial federation setup.
        assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(withoutStaticKey("KIVRA_SELFDEV_ANTHROPIC_WORKSPACE_ID" to "wrkspc_WS")) }
    }

    @Test
    fun `federation id formats are checked without echoing the value`() {
        val e = assertFailsWith<IllegalStateException> {
            SelfDevSettings.fromEnv(withoutStaticKey(*(wifVars + ("KIVRA_SELFDEV_ANTHROPIC_ORGANIZATION_ID" to "not-a-uuid-VALUE")).map { it.key to it.value }.toTypedArray()))
        }
        assertTrue("KIVRA_SELFDEV_ANTHROPIC_ORGANIZATION_ID" in e.message!!)
        assertFalse("not-a-uuid-VALUE" in e.message!!)
    }
}
