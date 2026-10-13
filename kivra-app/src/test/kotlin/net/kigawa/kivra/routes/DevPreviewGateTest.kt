package net.kigawa.kivra.routes

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevPreviewGateTest {
    @Test
    fun `preview disabled by default`() {
        assertFalse(isDevPreviewEnabled(emptyMap()))
    }

    @Test
    fun `preview requires explicit development environment`() {
        assertFailsWith<IllegalArgumentException> {
            isDevPreviewEnabled(mapOf("KIVRA_DEV_DEMO_ENABLED" to "true"))
        }
        assertFailsWith<IllegalArgumentException> {
            isDevPreviewEnabled(mapOf("KIVRA_DEV_DEMO_ENABLED" to "true", "KIVRA_RUNTIME_ENV" to "production"))
        }
    }

    @Test
    fun `preview allowed only for explicit dev pair`() {
        assertTrue(isDevPreviewEnabled(mapOf("KIVRA_DEV_DEMO_ENABLED" to "true", "KIVRA_RUNTIME_ENV" to "development")))
    }
}
