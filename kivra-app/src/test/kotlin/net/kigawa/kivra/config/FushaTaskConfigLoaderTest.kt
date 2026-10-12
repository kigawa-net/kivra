package net.kigawa.kivra.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import com.typesafe.config.ConfigFactory
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FushaTaskConfigLoaderTest {
    @Test
    fun `defaults from application conf load`() {
        val c: ApplicationConfig = HoconApplicationConfig(ConfigFactory.load())
        val f = FushaTaskConfigLoader.from(c)
        assertEquals("dev", f.environment)
        assertNull(f.staticToken)
        assertEquals(Duration.ofSeconds(10), f.requestTimeout)
        assertEquals(3, f.maxRetries)
    }
}
