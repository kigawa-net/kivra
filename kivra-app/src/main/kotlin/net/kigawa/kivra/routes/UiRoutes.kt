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

/** CSP for Compose Wasm UI: allows wasm loading from self. */
internal const val COMPOSE_UI_CSP =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; " +
        "worker-src 'self'; child-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

private val HTML_TYPE = ContentType.parse("text/html; charset=utf-8")
private val JS_TYPE = ContentType.parse("text/javascript; charset=utf-8")
private val CSS_TYPE = ContentType.parse("text/css; charset=utf-8")
private val WASM_TYPE = ContentType.parse("application/wasm")

private object UiResources

/** Loads every file at registration, so a missing resource fails at startup instead of on the first request. */
internal fun Route.configureUiRoutes() {
    val index = loadUiResource("index.html")
    val appJs = loadUiResource("app.js")
    val appCss = loadUiResource("app.css")
    
    // Load Compose Wasm resources (optional, may not be present in dev)
    val composeIndex = loadUiResourceOrNull("compose/index.html")
    val composeJs = loadUiResourceOrNull("compose/kivra-web.js")
    val composeWasm1 = loadUiResourceOrNull("compose/037f170986f544477491.wasm")
    val composeWasm2 = loadUiResourceOrNull("compose/835857ff79d9577028d7.wasm")
    
    // The site root has no page of its own. Send people to the UI (a fixed relative path, so there is no open redirect).
    get("/") {
        call.response.header("Cache-Control", "no-store")
        call.respondRedirect("/ui", permanent = false)
    }
    get("/ui") { call.respondUi(index, HTML_TYPE) }
    get("/ui/") { call.respondUi(index, HTML_TYPE) }
    get("/ui/app.js") { call.respondUi(appJs, JS_TYPE) }
    get("/ui/app.css") { call.respondUi(appCss, CSS_TYPE) }
    
    // Compose Wasm UI at /ui/compose
    if (composeIndex != null) {
        get("/ui/compose") { call.respondCompose(composeIndex, HTML_TYPE) }
        get("/ui/compose/") { call.respondCompose(composeIndex, HTML_TYPE) }
    }
    if (composeJs != null) {
        get("/ui/compose/kivra-web.js") { call.respondCompose(composeJs, JS_TYPE) }
    }
    if (composeWasm1 != null) {
        get("/ui/compose/037f170986f544477491.wasm") { call.respondCompose(composeWasm1, WASM_TYPE) }
    }
    if (composeWasm2 != null) {
        get("/ui/compose/835857ff79d9577028d7.wasm") { call.respondCompose(composeWasm2, WASM_TYPE) }
    }
}

private fun loadUiResource(name: String): ByteArray {
    val stream = UiResources::class.java.getResourceAsStream("/ui/$name")
        ?: error("UI resource is missing from the classpath: ui/$name")
    return stream.use { it.readBytes() }
}

private fun loadUiResourceOrNull(name: String): ByteArray? {
    val stream = UiResources::class.java.getResourceAsStream("/ui/$name")
        ?: return null
    return stream.use { it.readBytes() }
}

private suspend fun ApplicationCall.respondUi(bytes: ByteArray, contentType: ContentType) {
    response.header("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    respondBytes(bytes, contentType)
}

private suspend fun ApplicationCall.respondCompose(bytes: ByteArray, contentType: ContentType) {
    response.header("Content-Security-Policy", COMPOSE_UI_CSP)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    respondBytes(bytes, contentType)
}
