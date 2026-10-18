package net.kigawa.kivra.mcp

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kigawa.kivra.appModule

/**
 * The production wiring with MCP off (the default): no `/mcp` and no metadata route, and no MCP variable beyond the switch is read.
 * Any read of another MCP variable, or of the agent role, fails the test (see [TrapEnv]).
 */
class McpAppWiringTest {
    private class TrapEnv(private val base: Map<String, String>) : Map<String, String> by base {
        override fun get(key: String): String? {
            if ((key.startsWith("KIVRA_MCP_") || key == McpSettings.ENV_AGENT_ROLE) && key != McpSettings.ENV_ENABLED) {
                throw AssertionError("an MCP variable was read while the endpoint is off")
            }
            return base[key]
        }
    }

    private fun h2Config() = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_mcp_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )

    @Test
    fun `with the MCP endpoint off, no MCP route exists and no MCP variable beyond the switch is read`() = testApplication {
        environment { config = h2Config() }
        application {
            appModule(
                TrapEnv(
                    mapOf(
                        "KIVRA_AUTH_MODE" to "none",
                        "KIVRA_RUNTIME_ENV" to "development",
                        McpSettings.ENV_ENABLED to "false",
                        McpSettings.ENV_AUDIENCE to "https://kivra-dev.kigawa.net/mcp",
                        McpSettings.ENV_ALLOWED_HOSTS to "kivra-dev.kigawa.net",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.NotFound, client.post("/mcp") {
            header(HttpHeaders.Host, "kivra-dev.kigawa.net")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/oauth-protected-resource").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}
