package net.kigawa.kivra.capability

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.ktor.server.config.HoconApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.selfdev.SelfDevSettings

/** Self-development is a production capability (ADR 0008). It is on by default; only an explicit `false` switches it off. */
class SelfDevCapabilityDefaultsTest {
    @Test
    fun `the shipped configuration has no self-development switch, so the environment decides`() {
        val shipped = HoconApplicationConfig(
            ConfigFactory.parseResources("application.conf").resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false)),
        )
        assertNull(shipped.propertyOrNull("kivra.selfdev.enabled"))
    }

    @Test
    fun `an unset switch is on, so without oidc-jwt the start fails and names the requirement, and false switches it off`() {
        val unset = assertFailsWith<IllegalStateException> { SelfDevSettings.fromEnv(emptyMap()) }
        assertTrue("KIVRA_AUTH_MODE" in unset.message.orEmpty())
        val off = SelfDevSettings.fromEnv(mapOf("KIVRA_SELFDEV_ENABLED" to "false"))
        assertFalse(off.enabled)
        assertNull(off.config)
    }
}
