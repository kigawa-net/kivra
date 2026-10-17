package net.kigawa.kivra.routes

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the behaviour tests of the Web UI script (`src/test/js/ui-behavior.test.js`) with `node --test`: the real app.js in a
 * stub DOM (kind selection, idempotency keys, double submit, fixed messages, the release dialog, text-only rendering).
 * Node 18 or later must be on the PATH (CI runners have it).
 */
class UiBehaviorTest {
    @Test
    fun `the page script behaves as specified in the stub DOM`() {
        val script = File("src/test/js/ui-behavior.test.js")
        assertTrue(script.isFile, "missing ${script.absolutePath}")
        val process = try {
            ProcessBuilder("node", "--test", script.path)
                .directory(File(".").absoluteFile)
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            error("node (18 or later) is required to run the UI behaviour tests: ${e.message}")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "node --test did not finish:\n$output")
        assertEquals(0, process.exitValue(), "UI behaviour tests failed:\n$output")
    }
}
