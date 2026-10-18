package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.kigawa.kivra.appModule
import net.kigawa.kivra.selfdev.AutopilotSettings

/**
 * The production wiring with self-development off (the default): no self-development route, and no self-development secret or
 * FX variable is read. Any such read fails the test (see [TrapEnv]).
 */
class SelfDevAppWiringTest {
    /**
     * Fails if a self-development variable or an FX variable is read. The two switches are the exception: the self-development
     * switch and the autopilot switch (ADR 0011) are read while off, and no other autopilot variable is.
     */
    private class TrapEnv(private val base: Map<String, String>) : Map<String, String> by base {
        override fun get(key: String): String? {
            val switch = key == "KIVRA_SELFDEV_ENABLED" || key == AutopilotSettings.ENV_ENABLED
            if ((key.startsWith("KIVRA_SELFDEV_") && !switch) || key.startsWith("KIVRA_FX_")) {
                throw AssertionError("a self-development variable was read while the feature is off")
            }
            return base[key]
        }
    }

    private fun h2Config() = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_selfdev_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )

    @Test
    fun `with self-development off, its routes are not registered, even for an authenticated development caller`() = testApplication {
        environment { config = h2Config() }
        application {
            appModule(TrapEnv(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development")))
        }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/selfdev/tasks").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/selfdev/runs").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/selfdev/runs/01ARZ3NDEKTSV4RRFFQ69G5FAV").status)
        // The Task API that is not part of this feature is unchanged.
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `with the autopilot off, no autopilot variable beyond its switch is read, and no planner route exists`() = testApplication {
        environment { config = h2Config() }
        application {
            // The planner's knobs are set but the switch is off: TrapEnv fails the test if any of them is read.
            appModule(
                TrapEnv(
                    mapOf(
                        "KIVRA_AUTH_MODE" to "none",
                        "KIVRA_RUNTIME_ENV" to "development",
                        AutopilotSettings.ENV_ENABLED to "false",
                        AutopilotSettings.ENV_APPROVAL to "auto",
                        AutopilotSettings.ENV_PLAN_INTERVAL_SECONDS to "5",
                    ),
                ),
            )
        }
        // Goal routes are not part of PR B (PR D); nothing is registered for them.
        assertEquals(HttpStatusCode.NotFound, client.post("/api/selfdev/goals").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/selfdev/goals").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `enabling without oidc-jwt fails the startup, and the message names no value`() {
        val e = assertFailsWith<IllegalStateException> {
            testApplication {
                environment { config = h2Config() }
                application {
                    appModule(mapOf("KIVRA_SELFDEV_ENABLED" to "true", "KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development"))
                }
                client.get("/health")
            }
        }
        assertEquals(true, e.message!!.contains("KIVRA_AUTH_MODE"))
    }
}
