package net.kigawa.kivra.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Minimal web UI (`/ui`): static files from the classpath (`resources/ui/`). No build step, no CDN.
 *
 * The pages hold no secrets. The API they call (`/api/...`) is protected by the Task API's authentication. This is
 * registered only when `KIVRA_AUTH_MODE` is set (see [net.kigawa.kivra.appModule]); otherwise `/ui` is a 404.
 *
 * Scripts and styles come from separate files, so the CSP needs no hashes and no inline code.
 */
internal const val UI_CONTENT_SECURITY_POLICY =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

private val HTML_TYPE = ContentType.parse("text/html; charset=utf-8")
private val JS_TYPE = ContentType.parse("text/javascript; charset=utf-8")
private val CSS_TYPE = ContentType.parse("text/css; charset=utf-8")

private object UiResources

/** Loads every file at registration, so a missing resource fails at startup instead of on the first request. */
internal fun Route.configureUiRoutes() {
    val index = loadUiResource("index.html")
    val appJs = loadUiResource("app.js")
    val appCss = loadUiResource("app.css")
    // The site root has no page of its own. Send people to the UI (a fixed relative path, so there is no open redirect).
    get("/") {
        call.response.header("Cache-Control", "no-store")
        call.respondRedirect("/ui", permanent = false)
    }
    get("/ui") { call.respondUi(index, HTML_TYPE) }
    get("/ui/") { call.respondUi(index, HTML_TYPE) }
    get("/ui/app.js") { call.respondUi(appJs, JS_TYPE) }
    get("/ui/app.css") { call.respondUi(appCss, CSS_TYPE) }
}

private fun loadUiResource(name: String): ByteArray {
    val stream = UiResources::class.java.getResourceAsStream("/ui/$name")
        ?: error("UI resource is missing from the classpath: ui/$name")
    return stream.use { it.readBytes() }
}

private suspend fun ApplicationCall.respondUi(bytes: ByteArray, contentType: ContentType) {
    response.header("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    respondBytes(bytes, contentType)
}
