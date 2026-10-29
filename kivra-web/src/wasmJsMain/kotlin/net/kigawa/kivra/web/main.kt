package net.kigawa.kivra.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import net.kigawa.kivra.ui.KivraApp

/**
 * Wasm entry point for the Kivra Web app.
 * This is the main function that runs in the browser.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(canvasElementId = "ComposeTarget") {
        KivraApp()
    }
}
