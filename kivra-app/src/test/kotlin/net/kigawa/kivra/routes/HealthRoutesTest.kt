package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.kigawa.kivra.module

class HealthRoutesTest {
    @Test
    fun `health returns ok and the app boots against a migrated database`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_health_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "kivra.database.user" to "sa",
                "kivra.database.password" to "",
                "kivra.lease.ttlSeconds" to "30",
                "kivra.lease.graceSeconds" to "5",
            )
        }
        application { module() }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }
}
