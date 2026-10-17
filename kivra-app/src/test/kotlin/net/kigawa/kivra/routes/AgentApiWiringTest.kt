package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.appModule

/**
 * The production wiring of the agent API (PR 4a). Off by default: no `/api/agent` route and no agent variable is read. On: the routes
 * are registered, and a missing or wrong setting fails the startup without naming a value.
 */
class AgentApiWiringTest {
    /** Fails the test when an agent variable other than the switch is read while the feature is off. */
    private class TrapEnv(private val base: Map<String, String>) : Map<String, String> by base {
        override fun get(key: String): String? {
            if (key.startsWith("KIVRA_AGENT_") && key != "KIVRA_AGENT_API_ENABLED") {
                throw AssertionError("an agent variable was read while the agent API is off")
            }
            return base[key]
        }
    }

    private fun h2Config() = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_agent_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )

    private val oidc = mapOf(
        "KIVRA_AUTH_MODE" to "oidc-jwt",
        "KIVRA_OIDC_ISSUER" to "https://user.kigawa.net/realms/kigawa-net",
        "KIVRA_OIDC_AUDIENCE" to "kivra-dev",
        "KIVRA_OIDC_REQUIRED_ROLE" to "admin-panel:admin",
    )

    private val agentOn = oidc + mapOf(
        "KIVRA_AGENT_API_ENABLED" to "true",
        "KIVRA_AGENT_AZP" to "kivra-agent",
        "KIVRA_AGENT_REQUIRED_ROLE" to "kivra-agent:agent",
    )

    @Test
    fun `with the agent API off (the default), no agent route exists`() = testApplication {
        environment { config = h2Config() }
        application { appModule(TrapEnv(oidc)) }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/agent/v1/claim").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/agent/v1/attempts/01ARZ3NDEKTSV4RRFFQ69G5FAV/status").status)
        // The rest of the API is unchanged.
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `with the agent API explicitly false, no agent route exists either`() = testApplication {
        environment { config = h2Config() }
        application { appModule(TrapEnv(agentOn + ("KIVRA_AGENT_API_ENABLED" to "false"))) }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/agent/v1/claim").status)
    }

    @Test
    fun `enabled, the claim route exists and refuses a request with no credential`() = testApplication {
        environment { config = h2Config() }
        application { appModule(agentOn) }
        // No token is sent, so no JWKS is fetched. The route is registered and answers 401.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/agent/v1/claim").status)
    }

    @Test
    fun `enabled with a missing setting fails the startup, and the message names the variable and no value`() {
        for (missing in listOf("KIVRA_AGENT_AZP", "KIVRA_AGENT_REQUIRED_ROLE", "KIVRA_OIDC_ISSUER", "KIVRA_OIDC_AUDIENCE")) {
            val env = agentOn - missing
            val e = assertFailsWith<Exception>(missing) {
                testApplication {
                    environment { config = h2Config() }
                    application { appModule(env) }
                    client.post("/health")
                }
            }
            assertTrue(e.message!!.contains(missing), missing)
            assertFalse(e.message!!.contains("kivra-agent"), missing)
        }
    }

    @Test
    fun `enabled without oidc-jwt fails the startup`() {
        val e = assertFailsWith<Exception> {
            testApplication {
                environment { config = h2Config() }
                application { appModule(agentOn + ("KIVRA_AUTH_MODE" to "none") + ("KIVRA_RUNTIME_ENV" to "development")) }
                client.post("/health")
            }
        }
        assertTrue(e.message!!.contains("KIVRA_AUTH_MODE"))
        assertFalse(e.message!!.contains("none"))
    }
}
