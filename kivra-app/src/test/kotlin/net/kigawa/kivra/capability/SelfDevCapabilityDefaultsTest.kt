package net.kigawa.kivra.capability

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.ktor.server.config.HoconApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import net.kigawa.kivra.selfdev.SelfDevSettings

/** Self-development is a production capability (ADR 0008): it is off in the shipped configuration and without its switch. */
class SelfDevCapabilityDefaultsTest {
    @Test
    fun `self-development is off in the shipped configuration and when the switch is unset`() {
        val shipped = HoconApplicationConfig(
            ConfigFactory.parseResources("application.conf").resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false)),
        )
        assertFalse(shipped.propertyOrNull("kivra.selfdev.enabled")?.getString()?.toBoolean() ?: false)
        val settings = SelfDevSettings.fromEnv(emptyMap())
        assertFalse(settings.enabled)
        assertNull(settings.config)
    }
}
