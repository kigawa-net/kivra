package net.kigawa.kivra.selfdev.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SelfDevMemorySettingsTest {

    @Test
    fun `off unless the switch is exactly true`() {
        assertFalse(SelfDevMemorySettings.fromEnv(emptyMap()).enabled)
        assertFalse(SelfDevMemorySettings.fromEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "")).enabled)
        assertFalse(SelfDevMemorySettings.fromEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "false")).enabled)
        assertFalse(SelfDevMemorySettings.disabled().enabled)
        assertTrue(SelfDevMemorySettings.fromEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to "true")).enabled)
        assertTrue(SelfDevMemorySettings.fromEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to " true ")).enabled)
    }

    @Test
    fun `any other value is a startup failure that names the variable and not the value`() {
        for (bad in listOf("TRUE", "yes", "1", "on", "tru")) {
            val error = assertFailsWith<IllegalStateException> {
                SelfDevMemorySettings.fromEnv(mapOf(SelfDevMemorySettings.ENV_ENABLED to bad))
            }
            assertEquals("${SelfDevMemorySettings.ENV_ENABLED} must be true or false", error.message)
        }
    }

    @Test
    fun `the switch reads only its own variable`() {
        assertEquals("SelfDevMemorySettings(enabled=false)", SelfDevMemorySettings.fromEnv(mapOf("KIVRA_OTHER" to "x")).toString())
    }
}
