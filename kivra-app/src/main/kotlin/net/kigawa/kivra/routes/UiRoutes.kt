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
 * Web UI: the Compose Wasm UI at `/ui/compose`, served from the classpath (`resources/ui/compose/`, copied there by the
 * `copyComposeWebDist` task). `/ui` and `/ui/` redirect to it; the legacy vanilla UI was removed.
 *
 * The pages hold no secrets. The API they call (`/api/...`) is protected by the Task API's authentication. This is
 * registered only when `KIVRA_AUTH_MODE` is set (see [net.kigawa.kivra.appModule]); otherwise `/ui` is a 404.
 */
/** CSP for Compose Wasm UI: same-origin only; script-src also needs 'wasm-unsafe-eval' to compile the wasm module. */
internal const val COMPOSE_UI_CSP =
    "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self'; connect-src 'self'; " +
        "worker-src 'self'; child-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

private val HTML_TYPE = ContentType.parse("text/html; charset=utf-8")
private val JS_TYPE = ContentType.parse("text/javascript; charset=utf-8")
private val WASM_TYPE = ContentType.parse("application/wasm")
private val CSS_TYPE = ContentType.parse("text/css; charset=utf-8")

private object UiResources

/**
 * Discover Compose Wasm resources dynamically.
 * Wasm filenames change on each build, so we can't hardcode them.
 */
private data class ComposeResource(val path: String, val bytes: ByteArray)

private fun discoverComposeResources(): List<ComposeResource> {
    val results = mutableListOf<ComposeResource>()
    val base = "/ui/compose"
    
    // Try to list resources using classpath scanning
    // We look for known patterns: index.html, *.js, *.wasm
    val patterns = listOf(
        "$base/index.html" to HTML_TYPE,
        "$base/kivra-web.js" to JS_TYPE,
    )
    
    for ((path, contentType) in patterns) {
        // loadUiResourceOrNull adds the "/ui/" prefix itself, so the classpath name is relative to it.
        loadUiResourceOrNull(path.removePrefix("/ui/"))?.let { bytes ->
            results.add(ComposeResource(path, bytes))
        }
    }
    
    // Discover wasm files by trying common patterns
    // Since we can't list directory contents on classpath, we use a fallback approach:
    // serve any .wasm request by checking if the resource exists
    // The wasm files are loaded lazily on first request
    
    return results
}

/** Lazy cache for wasm files discovered on first request */
private val wasmCache = mutableMapOf<String, ByteArray?>()

private fun loadWasmFile(filename: String): ByteArray? {
    return wasmCache.getOrPut(filename) {
        loadUiResourceOrNull("compose/$filename")
    }
}

/**
 * Compose resources are looked up at registration. A missing `/ui/compose` page is not registered (so it is a 404), but `/ui` and `/ui/`
 * still redirect to it.
 */
internal fun Route.configureUiRoutes() {
    // Discover Compose Wasm resources dynamically
    val composeResources = discoverComposeResources()
    val composeIndex = composeResources.find { it.path == "/ui/compose/index.html" }
    val composeJs = composeResources.find { it.path == "/ui/compose/kivra-web.js" }

    // The site root has no page of its own. Send people to the UI (a fixed relative path, so there is no open redirect).
    get("/") {
        call.response.header("Cache-Control", "no-store")
        call.respondRedirect("/ui/compose", permanent = false)
    }
    // The legacy vanilla UI is gone. Old bookmarks and links land on the Compose UI. The URL fragment (#/...) is kept by the browser.
    get("/ui") {
        call.response.header("Cache-Control", "no-store")
        call.respondRedirect("/ui/compose", permanent = false)
    }
    get("/ui/") {
        call.response.header("Cache-Control", "no-store")
        call.respondRedirect("/ui/compose", permanent = false)
    }

    // Compose Wasm UI at /ui/compose
    composeIndex?.let { res ->
        get("/ui/compose") { call.respondCompose(res.bytes, HTML_TYPE) }
        get("/ui/compose/") { call.respondCompose(res.bytes, HTML_TYPE) }
    }
    composeJs?.let { res ->
        get("/ui/compose/kivra-web.js") { call.respondCompose(res.bytes, JS_TYPE) }
    }
    
    // Serve any .wasm file under /ui/compose/ (lazy loading)
    get("/ui/compose/{wasmFile}") {
        val wasmFile = call.pathParam("wasmFile") ?: return@get
        if (wasmFile.endsWith(".wasm")) {
            val bytes = loadWasmFile(wasmFile)
            if (bytes != null) {
                call.respondCompose(bytes, WASM_TYPE)
            } else {
                call.respondBytes(byteArrayOf(), WASM_TYPE, status = HttpStatusCode.NotFound)
            }
        } else if (wasmFile == "kivra-web.css") {
            // index.html は CSP(style-src 'self')のためインライン style を持てず、この CSS を読む。
            val bytes = loadUiResourceOrNull("compose/kivra-web.css")
            if (bytes != null) {
                call.respondCompose(bytes, CSS_TYPE)
            } else {
                call.respondBytes(byteArrayOf(), CSS_TYPE, status = HttpStatusCode.NotFound)
            }
        } else {
            call.respondBytes(byteArrayOf(), ContentType.Application.OctetStream, status = HttpStatusCode.NotFound)
        }
    }
    
    // Serve composeResources (Skiko fonts, etc.)
    get("/ui/compose/composeResources/{file}") {
        val file = call.pathParam("file") ?: return@get
        val bytes = loadUiResourceOrNull("compose/composeResources/$file")
        if (bytes != null) {
            call.respondCompose(bytes, ContentType.Application.OctetStream)
        } else {
            call.respondBytes(byteArrayOf(), ContentType.Application.OctetStream, status = HttpStatusCode.NotFound)
        }
    }
}

private fun loadUiResourceOrNull(name: String): ByteArray? {
    val stream = UiResources::class.java.getResourceAsStream("/ui/$name")
        ?: return null
    return stream.use { it.readBytes() }
}

private suspend fun ApplicationCall.respondCompose(bytes: ByteArray, contentType: ContentType) {
    response.header("Content-Security-Policy", COMPOSE_UI_CSP)
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    respondBytes(bytes, contentType)
}
