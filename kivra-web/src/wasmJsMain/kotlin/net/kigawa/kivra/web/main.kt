package net.kigawa.kivra.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import net.kigawa.kivra.ui.KivraApp

/**
 * Wasm entry point for the Kivra Web app.
 * This is the main function that runs in the browser.
 *
 * index.html provides `<div id="ComposeTarget">`. CanvasBasedWindow expects a `<canvas>` with that id:
 * with the div it throws a WebAssembly exception during startup (no message), and it also injects an
 * inline `<style>` that the CSP (style-src 'self') blocks. ComposeViewport mounts its canvas inside the
 * div and injects no style, so the app uses it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "ComposeTarget") {
        KivraApp()
    }
}
