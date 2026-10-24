package net.kigawa.kivra.selfdev

import net.kigawa.kivra.auth.PermissionMode
import net.kigawa.kivra.auth.RolesDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RolesSettingsTest {
    private val approver = "1:i:kigawa"
    private val ai = "1:i:ai"

    private fun orders(): OrderSettings = OrderSettings.fromEnv(
        mapOf(OrderSettings.ENV_ENABLED to "true", OrderSettings.ENV_ROOT_APPROVER to approver),
        enabledAutopilot,
        ai,
    )

    private val enabledAutopilot: AutopilotSettings by lazy { AutopilotSettings.fromEnv(autopilotEnv(), selfDev()) }

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

    private fun load(env: Map<String, String>, aiKey: String? = ai) = RolesSettings.fromEnv(env, orders(), aiKey)

    @Test
    fun `off by default, and reads nothing while the orders are off`() {
        assertEquals(PermissionMode.OFF, load(emptyMap()).mode)
        assertEquals(PermissionMode.OFF, load(mapOf(RolesSettings.ENV_MODE to "off", RolesSettings.ENV_DEFAULT to "!!")).mode)
        val trap = object : Map<String, String> by emptyMap() {
            override fun get(key: String): String? = throw AssertionError("read $key")
        }
        assertEquals(PermissionMode.OFF, RolesSettings.fromEnv(trap, OrderSettings.disabled(), null).mode)
    }

    @Test
    fun `valid values`() {
        val s = load(mapOf(RolesSettings.ENV_MODE to "shadow"))
        assertEquals(PermissionMode.SHADOW, s.mode)
        assertEquals(RolesDefault.OPERATOR, s.default)
        val e = load(mapOf(RolesSettings.ENV_MODE to "enforce", RolesSettings.ENV_DEFAULT to "none"))
        assertEquals(PermissionMode.ENFORCE, e.mode)
        assertEquals(RolesDefault.NONE, e.default)
    }

    @Test
    fun `a bad value fails and names only the variable`() {
        val m = assertFailsWith<IllegalStateException> { load(mapOf(RolesSettings.ENV_MODE to "SECRETVALUE")) }
        assertTrue(m.message!!.contains(RolesSettings.ENV_MODE))
        assertFalse(m.message!!.contains("SECRETVALUE"))
        val d = assertFailsWith<IllegalStateException> {
            load(mapOf(RolesSettings.ENV_MODE to "shadow", RolesSettings.ENV_DEFAULT to "SECRETVALUE"))
        }
        assertTrue(d.message!!.contains(RolesSettings.ENV_DEFAULT))
        assertFalse(d.message!!.contains("SECRETVALUE"))
    }

    @Test
    fun `the AI principal must not be the root approver`() {
        assertFailsWith<IllegalStateException> { load(mapOf(RolesSettings.ENV_MODE to "shadow"), aiKey = approver) }
    }

    @Test
    fun `none and oauth2-proxy authentication need the insecure flag`() {
        for (mode in listOf("none", "oauth2-proxy")) {
            val base = mapOf(RolesSettings.ENV_MODE to "enforce", "KIVRA_AUTH_MODE" to mode, "KIVRA_RUNTIME_ENV" to "development")
            assertFailsWith<IllegalStateException> { load(base) }
            assertFailsWith<IllegalStateException> { load(base + (RolesSettings.ENV_ALLOW_INSECURE_AUTH to "false")) }
            assertEquals(PermissionMode.ENFORCE, load(base + (RolesSettings.ENV_ALLOW_INSECURE_AUTH to "true")).mode)
            // off is not affected
            assertEquals(PermissionMode.OFF, load(base - RolesSettings.ENV_MODE).mode)
        }
        assertEquals(PermissionMode.ENFORCE, load(mapOf(RolesSettings.ENV_MODE to "enforce", "KIVRA_AUTH_MODE" to "oidc-jwt")).mode)
    }
}
