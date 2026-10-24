package net.kigawa.kivra.selfdev.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuppressShadowSettingsTest {

    private val name = SuppressShadowSettings.ENV_ENABLED

    @Test
    fun `off unless the value is exactly true`() {
        assertFalse(SuppressShadowSettings.fromEnv(emptyMap()).enabled)
        assertFalse(SuppressShadowSettings.fromEnv(mapOf(name to "false")).enabled)
        assertFalse(SuppressShadowSettings.disabled().enabled)
        assertTrue(SuppressShadowSettings.fromEnv(mapOf(name to "true")).enabled)
        assertTrue(SuppressShadowSettings.fromEnv(mapOf(name to " true ")).enabled)
    }

    @Test
    fun `any other value is a startup failure that names the variable and not the value`() {
        for (bad in listOf("", "TRUE", "yes", "1", "on", "tru")) {
            val error = assertFailsWith<IllegalStateException> { SuppressShadowSettings.fromEnv(mapOf(name to bad)) }
            assertEquals("$name must be true or false", error.message)
        }
    }

    @Test
    fun `read only while the memory is on, so a bad value with the memory off does not fail the startup`() {
        val memoryOff = SelfDevMemorySettings.disabled()
        assertFalse(SuppressShadowSettings.fromStartupEnv(mapOf(name to "garbage"), memoryOff).enabled)
        assertFalse(SuppressShadowSettings.fromStartupEnv(mapOf(name to "true"), memoryOff).enabled)
        val memoryOn = SelfDevMemorySettings.fromEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "true"))
        assertTrue(SuppressShadowSettings.fromStartupEnv(mapOf(name to "true"), memoryOn).enabled)
        assertFailsWith<IllegalStateException> { SuppressShadowSettings.fromStartupEnv(mapOf(name to "garbage"), memoryOn) }
    }

    @Test
    fun `the switch reads only its own variable`() {
        assertEquals("SuppressShadowSettings(enabled=false)", SuppressShadowSettings.fromEnv(mapOf("KIVRA_OTHER" to "x")).toString())
    }
}
