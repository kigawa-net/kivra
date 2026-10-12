package net.kigawa.kivra.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskPollerConfigLoaderTest {
    private val appConfig = HoconApplicationConfig(ConfigFactory.load())

    @Test
    fun `unscoped defaults fail fast so the poller never lists the whole visible set`() {
        val ex = runCatching { TaskPollerConfigLoader.from(appConfig) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(ex)
    }

    @Test
    fun `scoped config loads with defaults for the rest`() {
        val scoped = HoconApplicationConfig(
            ConfigFactory.parseString("kivra.fusha.poller.assigneeId = kigawa").withFallback(ConfigFactory.load()),
        )
        val poller = TaskPollerConfigLoader.from(scoped)
        assertEquals("dev", poller.environment)
        assertEquals("kigawa", poller.filter.assigneeId)
        assertNull(poller.filter.viewId)
        assertEquals(Duration.ofSeconds(30), poller.interval)
    }

    @Test
    fun `status mapping defaults to disabled`() {
        val mapping = TaskPollerConfigLoader.statusMappingFrom(appConfig)
        assertFalse(mapping.writeEnabled)
        assertFalse(mapping.writesAllowed())
        assertEquals(emptyMap(), mapping.kivraToFusha)
    }

    @Test
    fun `configured status mapping is read and stays disabled without writeEnabled`() {
        val mapped = HoconApplicationConfig(
            ConfigFactory.parseString(
                """
                kivra.fusha.statusMapping.statusFieldKey = status
                kivra.fusha.statusMapping.kivraToFusha.READY = Ready
                kivra.fusha.statusMapping.kivraToFusha.RUNNING = InProgress
                """.trimIndent(),
            ).withFallback(ConfigFactory.load()),
        )
        val mapping = TaskPollerConfigLoader.statusMappingFrom(mapped)
        assertEquals(mapOf("READY" to "Ready", "RUNNING" to "InProgress"), mapping.kivraToFusha)
        assertEquals("status", mapping.statusFieldKey)
        assertFalse(mapping.writesAllowed())
    }
}
