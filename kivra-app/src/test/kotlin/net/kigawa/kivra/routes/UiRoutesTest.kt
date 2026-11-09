package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import net.kigawa.kivra.appModule

private object UiTestResources

private fun composeResource(name: String): String =
    UiTestResources::class.java.getResourceAsStream("/ui/compose/$name")?.use { it.readBytes().decodeToString() }
        ?: error("ui/compose/$name is missing from the test classpath (copyComposeWebDist runs before processResources)")

private val EXPECTED_COMPOSE_CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; " +
        "worker-src 'self'; child-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

/** Parses a CSP header into directive name to source list. */
private fun parseCsp(csp: String): Map<String, List<String>> =
    csp.split(';').map { it.trim() }.filter { it.isNotEmpty() }.associate { directive ->
        val parts = directive.split(Regex("\\s+"))
        parts[0] to parts.drop(1)
    }

class UiRoutesTest {
    private fun assertComposeHeaders(response: HttpResponse) {
        assertEquals(EXPECTED_COMPOSE_CSP, response.headers["Content-Security-Policy"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `the compose ui is served at ui compose with its content types and security headers`() = testApplication {
        application { routing { configureUiRoutes() } }
        val indexHtml = composeResource("index.html")

        for (path in listOf("/ui/compose", "/ui/compose/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals("text/html; charset=utf-8", response.headers["Content-Type"], path)
            assertComposeHeaders(response)
            assertEquals(indexHtml, response.bodyAsText(), path)
        }

        val js = client.get("/ui/compose/kivra-web.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertEquals("text/javascript; charset=utf-8", js.headers["Content-Type"])
        assertComposeHeaders(js)
        assertEquals(composeResource("kivra-web.js"), js.bodyAsText())
    }

    @Test
    fun `ui and ui slash redirect to the compose ui with a fixed relative location`() = testApplication {
        application { routing { configureUiRoutes() } }
        val noFollow = createClient { followRedirects = false }

        for (path in listOf("/ui", "/ui/")) {
            val response = noFollow.get(path)
            assertEquals(HttpStatusCode.Found, response.status, path)
            assertEquals("/ui/compose", response.headers["Location"], path)
            assertEquals("no-store", response.headers["Cache-Control"], path)
        }
    }

    @Test
    fun `the legacy vanilla ui assets are gone`() = testApplication {
        application { routing { configureUiRoutes() } }

        assertEquals(HttpStatusCode.NotFound, client.get("/ui/app.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/app.css").status)
    }

    @Test
    fun `the compose csp grants only same-origin scripts, styles and images, with no inline code or third parties`() {
        val csp = parseCsp(COMPOSE_UI_CSP)
        // The directives the UI depends on, exactly. Anything else stays denied by default-src 'none'.
        assertEquals(listOf("'none'"), csp["default-src"])
        assertEquals(listOf("'self'"), csp["script-src"])
        assertEquals(listOf("'self'"), csp["style-src"])
        assertEquals(listOf("'self'"), csp["img-src"])
        assertEquals(listOf("'self'"), csp["connect-src"])
        // No directive may allow inline code, eval, hashes or nonces, wildcards, schemes, or third-party hosts.
        val forbidden = listOf("'unsafe-inline'", "'unsafe-eval'", "'unsafe-hashes'", "'strict-dynamic'", "*", "data:", "blob:")
        for ((directive, sources) in csp) {
            for (source in sources) {
                assertFalse(source.startsWith("'sha") || source.startsWith("'nonce-"), "$directive: $source")
                assertFalse(source in forbidden, "$directive must not allow $source")
                assertFalse(source.contains("://") || source.endsWith(":"), "$directive must not allow $source")
            }
        }
    }

    @Test
    fun `the site root redirects to the compose ui with a fixed relative location`() = testApplication {
        application { routing { configureUiRoutes() } }
        val noFollow = createClient { followRedirects = false }

        val response = noFollow.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/ui/compose", response.headers["Location"])
        assertEquals("no-store", response.headers["Cache-Control"])
        // A query string must not change where it goes.
        assertEquals("/ui/compose", noFollow.get("/?next=https://evil.example/").headers["Location"])
    }

    @Test
    fun `the site root is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test
    fun `ui is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/ui").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/compose").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/app.js").status)
    }

    @Test
    fun `ui is registered when the task api is enabled in development`() = testApplication {
        environment { config = h2Config() }
        application {
            appModule(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development"))
        }
        val noFollow = createClient { followRedirects = false }

        val redirect = noFollow.get("/ui")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("/ui/compose", redirect.headers["Location"])

        val page = client.get("/ui/compose")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("text/html; charset=utf-8", page.headers["Content-Type"])
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    private fun h2Config() = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_ui_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )
}
