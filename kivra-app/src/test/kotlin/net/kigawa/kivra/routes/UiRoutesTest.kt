package net.kigawa.kivra.routes

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kigawa.kivra.appModule

private object UiTestResources

private fun resource(name: String): String =
    UiTestResources::class.java.getResourceAsStream("/ui/$name")?.use { it.readBytes().decodeToString() }
        ?: error("ui/$name is missing from the test classpath")

private val indexHtml by lazy { resource("index.html") }
private val appJs by lazy { resource("app.js") }
private val appCss by lazy { resource("app.css") }

private val EXPECTED_CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

class UiRoutesTest {
    private fun assertSecurityHeaders(response: HttpResponse) {
        assertEquals(EXPECTED_CSP, response.headers["Content-Security-Policy"])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `ui pages are served with the right content types and security headers`() = testApplication {
        application { routing { configureUiRoutes() } }

        for (path in listOf("/ui", "/ui/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals("text/html; charset=utf-8", response.headers["Content-Type"], path)
            assertSecurityHeaders(response)
        }

        val js = client.get("/ui/app.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertEquals("text/javascript; charset=utf-8", js.headers["Content-Type"])
        assertSecurityHeaders(js)

        val css = client.get("/ui/app.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertEquals("text/css; charset=utf-8", css.headers["Content-Type"])
        assertSecurityHeaders(css)
    }

    @Test
    fun `served html is the bundled index and references only same-origin assets`() = testApplication {
        application { routing { configureUiRoutes() } }

        assertEquals(indexHtml, client.get("/ui").bodyAsText())
        assertEquals(appJs, client.get("/ui/app.js").bodyAsText())
        assertEquals(appCss, client.get("/ui/app.css").bodyAsText())
    }

    @Test
    fun `no external URLs, CDN references or inline scripts or styles`() {
        for ((name, text) in listOf("index.html" to indexHtml, "app.js" to appJs, "app.css" to appCss)) {
            assertFalse(text.contains("http://"), "$name must not contain http://")
            assertFalse(text.contains("https://"), "$name must not contain https://")
            assertFalse(text.contains("//cdn", ignoreCase = true), "$name must not reference a CDN")
            assertFalse(text.contains("@import"), "$name must not use @import")
        }
        // The only script and stylesheet are same-origin files; there is no inline code for the CSP to block.
        assertEquals(1, Regex("<script\\b").findAll(indexHtml).count())
        assertContains(indexHtml, "<script src=\"/ui/app.js\" defer></script>")
        assertEquals(1, Regex("<link\\b").findAll(indexHtml).count())
        assertContains(indexHtml, "<link rel=\"stylesheet\" href=\"/ui/app.css\">")
        assertFalse(Regex("<script(?![^>]*\\bsrc=)[^>]*>").containsMatchIn(indexHtml), "no inline <script>")
        assertFalse(Regex("<style\\b").containsMatchIn(indexHtml), "no inline <style>")
        assertFalse(Regex("\\sstyle=\"").containsMatchIn(indexHtml), "no inline style attributes")
    }

    @Test
    fun `script does not use DOM sinks that would interpret server data as markup or code`() {
        val forbidden = listOf(
            "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
            "javascript:", "srcdoc", "execCommand",
        )
        for (token in forbidden) {
            assertFalse(appJs.contains(token), "app.js must not use $token")
            assertFalse(indexHtml.contains(token), "index.html must not use $token")
        }
        // No event-handler attributes set by string, e.g. setAttribute('onclick', ...).
        assertFalse(Regex("setAttribute\\(\\s*['\"]on", RegexOption.IGNORE_CASE).containsMatchIn(appJs))
        // Only fixed attribute names are set by the helper.
        assertFalse(Regex("setAttribute\\(\\s*['\"](?!href['\"]|type['\"])").containsMatchIn(appJs))
        // fetch targets are same-origin paths only.
        val fetchTargets = Regex("apiFetch\\(\\s*'([^']*)'").findAll(appJs).map { it.groupValues[1] }.toList()
        assertTrue(fetchTargets.isNotEmpty())
        assertTrue(fetchTargets.all { it.startsWith("/api/") || it == "/health" }, "fetch targets: $fetchTargets")
        // Authorization is added by oauth2-proxy, never by the page.
        assertFalse(appJs.contains("Authorization"))
    }

    @Test
    fun `page shows the simulation banner and the executed false marker`() {
        assertContains(indexHtml, "このサーバーの Run は、すべてシミュレーションです")
        assertContains(indexHtml, "executed: false")
        assertContains(indexHtml, "SIMULATED")
        // Never claim that a Run ran.
        assertFalse(indexHtml.contains("実行中"))
        assertFalse(appJs.contains("実行中"))
    }

    @Test
    fun `the site root redirects to the ui with a fixed relative location`() = testApplication {
        application { routing { configureUiRoutes() } }
        val noFollow = createClient { followRedirects = false }

        val response = noFollow.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/ui", response.headers["Location"])
        assertEquals("no-store", response.headers["Cache-Control"])
        // A query string must not change where it goes.
        assertEquals("/ui", noFollow.get("/?next=https://evil.example/").headers["Location"])
    }

    @Test
    fun `the site root is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test
    fun `title input is limited to 200 characters`() {
        assertContains(indexHtml, "maxlength=\"200\"")
    }

    @Test
    fun `ui is not registered without KIVRA_AUTH_MODE`() = testApplication {
        environment { config = h2Config() }
        application { appModule(emptyMap()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/ui").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ui/app.js").status)
    }

    @Test
    fun `ui is registered when the task api is enabled in development`() = testApplication {
        environment { config = h2Config() }
        application {
            appModule(mapOf("KIVRA_AUTH_MODE" to "none", "KIVRA_RUNTIME_ENV" to "development"))
        }

        val response = client.get("/ui")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/html; charset=utf-8", response.headers["Content-Type"])
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
