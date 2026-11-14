package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.kigawa.kivra.appModule

private object UiTestResources

private fun composeResource(name: String): String =
    UiTestResources::class.java.getResourceAsStream("/ui/compose/$name")?.use { it.readBytes().decodeToString() }
        ?: error("ui/compose/$name is missing from the test classpath (copyComposeWebDist runs before processResources)")

private val EXPECTED_COMPOSE_CSP =
    "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self'; connect-src 'self'; " +
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
        // Wasm needs 'wasm-unsafe-eval' to compile (not 'unsafe-eval': JS eval and inline scripts stay blocked).
        assertEquals(listOf("'self'", "'wasm-unsafe-eval'"), csp["script-src"])
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
        application { appModule(mapOf("KIVRA_SELFDEV_ENABLED" to "false")) }

        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test
    fun `ui is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(mapOf("KIVRA_SELFDEV_ENABLED" to "false")) }

        assertEquals(HttpStatusCode.NotFound, client.get("/ui").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/compose").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/app.js").status)
    }

    @Test
    fun `ui is registered when the task api is enabled in development`() = testApplication {
        environment { config = h2Config() }
        application {
            appModule(mapOf("KIVRA_SELFDEV_ENABLED" to "false", "KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development"))
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

    /** The composeResources files on the test classpath, as request paths under /ui/compose/ (copyComposeWebDist copies them). */
    private fun composeResourcePaths(): List<String> {
        val root = UiTestResources::class.java.classLoader.getResource("ui/compose/composeResources")
            ?: error("ui/compose/composeResources is missing from the test classpath (copyComposeWebDist runs before processResources)")
        val base = File(root.toURI())
        return base.walkTopDown().filter { it.isFile }
            .map { "composeResources/" + it.relativeTo(base).invariantSeparatorsPath }
            .toList().sorted()
    }

    @Test
    fun `the bundled japanese font is served from its composeResources path as a font with the security headers`() = testApplication {
        application { routing { configureUiRoutes() } }
        val fonts = composeResourcePaths().filter { it.endsWith(".ttf") }
        assertTrue(fonts.isNotEmpty(), "no .ttf under composeResources: the Japanese font was not copied")

        val expectedBytes = fonts.associateWith { name ->
            UiTestResources::class.java.getResourceAsStream("/ui/compose/$name")!!.use { it.readBytes() }
        }
        // Both forms: under /ui/compose/ and the one a page at /ui/compose (no trailing slash) resolves to.
        for (name in fonts) {
            for (url in listOf("/ui/compose/$name", "/ui/$name")) {
                val response = client.get(url)
                assertEquals(HttpStatusCode.OK, response.status, url)
                assertEquals("font/ttf", response.headers["Content-Type"], url)
                assertComposeHeaders(response)
                assertTrue(expectedBytes.getValue(name).contentEquals(response.bodyAsBytes()), "$url: served bytes differ")
            }
        }
    }

    @Test
    fun `the font license is served as plain text next to the font`() = testApplication {
        application { routing { configureUiRoutes() } }
        val license = composeResourcePaths().single { it.endsWith("/files/licenses/NotoSansJP-OFL.txt") }

        val response = client.get("/ui/compose/$license")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/plain; charset=utf-8", response.headers["Content-Type"])
        assertTrue(response.bodyAsText().contains("SIL OPEN FONT LICENSE"))
    }

    @Test
    fun `composeResources paths are served only for known types and never escape the directory`() {
        assertEquals("font/ttf", composeResourceContentType(listOf("font", "noto_sans_jp_regular.ttf"))?.toString())
        assertEquals("font/woff2", composeResourceContentType(listOf("a.woff2"))?.toString())
        assertNull(composeResourceContentType(emptyList()))
        assertNull(composeResourceContentType(listOf("..", "kivra-web.js.ttf")))
        assertNull(composeResourceContentType(listOf("font", "..", "x.ttf")))
        assertNull(composeResourceContentType(listOf("font", ".", "x.ttf")))
        assertNull(composeResourceContentType(listOf("font", "", "x.ttf")))
        assertNull(composeResourceContentType(listOf("font", "x\\..\\y.ttf")))
        assertNull(composeResourceContentType(listOf("font", "x.html")))
        assertNull(composeResourceContentType(listOf("font")))
    }

    @Test
    fun `composeResources requests that do not name a served file answer 404`() = testApplication {
        application { routing { configureUiRoutes() } }

        assertEquals(HttpStatusCode.NotFound, client.get("/ui/compose/composeResources/%2E%2E/%2E%2E/kivra-web.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/compose/composeResources/font").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/compose/composeResources/no-such-file.ttf").status)
    }

    private fun h2Config() = MapApplicationConfig(
        "kivra.database.jdbcUrl" to "jdbc:h2:mem:kivra_ui_${System.nanoTime()};MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "kivra.database.user" to "sa",
        "kivra.database.password" to "",
        "kivra.lease.ttlSeconds" to "30",
        "kivra.lease.graceSeconds" to "5",
    )
}
