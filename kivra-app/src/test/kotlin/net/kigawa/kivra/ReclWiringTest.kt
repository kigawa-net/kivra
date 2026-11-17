package net.kigawa.kivra

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.kigawa.kivra.memory.*
import net.kigawa.kivra.memory.recl.ReclAdapter
import net.kigawa.kivra.memory.recl.ReclConfig
import net.kigawa.kivra.recl.M5MemoryFixtures as F
import java.time.Duration
import kotlin.test.*

class ReclWiringTest {
    private val env = mapOf(
        "KIVRA_RECL_ENABLED" to "true", "KIVRA_RECL_BASE_URL" to "https://recl.example",
        "KIVRA_RECL_TOKEN" to "test-bearer",
    )

    @Test
    fun `disabled by default and does not construct a provider`() {
        assertNull(ReclWiring.fromEnvironment(emptyMap()))
        assertNull(ReclWiring.fromEnvironment(ReclSettings.fromEnv(mapOf(
            "KIVRA_RECL_ENABLED" to "false", "KIVRA_RECL_READ_TIMEOUT_MS" to "invalid",
        ))) { error("disabled wiring must not create provider") })
    }

    @Test
    fun `enabled settings construct adapter and memory service against mock transport`() = runBlocking {
        val requests = mutableListOf<String>()
        val settings = ReclSettings.fromEnv(env + mapOf(
            "KIVRA_RECL_READ_TIMEOUT_MS" to "2000",
            "KIVRA_RECL_CIRCUIT_BREAKER_THRESHOLD" to "2",
            "KIVRA_RECL_CIRCUIT_BREAKER_COOLDOWN_MS" to "100",
        ))
        assertEquals(Duration.ofMillis(2000), settings.readTimeout)
        assertEquals(2, settings.circuitBreakerThreshold)
        assertEquals(Duration.ofMillis(100), settings.circuitBreakerCooldown)
        val engine = MockEngine { request ->
            requests += request.url.encodedPath
            assertEquals("Bearer test-bearer", request.headers[HttpHeaders.Authorization])
            respond(if (request.url.encodedPath.endsWith("/health")) "{}" else "{\"results\":[]}",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val service = assertNotNull(ReclWiring.fromEnvironment(settings) {
            ReclAdapter(ReclConfig(assertNotNull(it.baseUrl)), engine, it.credentials())
        })
        service.use {
            assertFalse(it.recall(MemoryQuery("Gradle", listOf(F.scope)), F.ctx).degraded)
        }
        assertEquals(listOf("/api/v1/health", "/api/v1/contexts/search"), requests)
        assertFalse(settings.toString().contains("test-bearer"))
    }

    @Test
    fun `invalid enabled settings fail at startup`() {
        for ((key, value) in listOf(
            "KIVRA_RECL_ENABLED" to "yes", "KIVRA_RECL_READ_TIMEOUT_MS" to "-1",
            "KIVRA_RECL_READ_TIMEOUT_MS" to "zero", "KIVRA_RECL_READ_TIMEOUT_MS" to "0",
            "KIVRA_RECL_CIRCUIT_BREAKER_THRESHOLD" to "0",
            "KIVRA_RECL_CIRCUIT_BREAKER_THRESHOLD" to "2147483648",
            "KIVRA_RECL_CIRCUIT_BREAKER_COOLDOWN_MS" to "-1",
            "KIVRA_RECL_BASE_URL" to "http://recl.example", "KIVRA_RECL_TOKEN" to " ",
        )) assertFailsWith<IllegalArgumentException>(key) { ReclSettings.fromEnv(env + (key to value)) }
        for (key in listOf("KIVRA_RECL_BASE_URL", "KIVRA_RECL_TOKEN")) {
            assertFailsWith<IllegalArgumentException>(key) { ReclSettings.fromEnv(env - key) }
        }
    }
}
